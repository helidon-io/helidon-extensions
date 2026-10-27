/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/* Copyright 2015 Google Inc. All Rights Reserved.

   Distributed under MIT license.
   See file LICENSE for detail or copy at https://opensource.org/licenses/MIT
*/
package io.helidon.extensions.brotli;

import java.util.Arrays;

import static io.helidon.extensions.brotli.Utils.convertBitDepthsToSymbols;

class HuffmanTree {
    static final int NUM_COMMAND_SYMBOLS = 704;

    private static final int INITIAL_REPEATED_CODE_LENGTH = 8;
    private static final int REPEAT_PREVIOUS_CODE_LENGTH = 16;
    private static final int REPEAT_ZERO_CODE_LENGTH = 17;
    private static final int CODE_LENGTH_CODES = 18;

    private static final int[] STORAGE_ORDER = {1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    private static final int[] BIT_LENGTH_SYMBOLS = {0, 7, 3, 2, 1, 15};
    private static final int[] BIT_LENGTH_DEPTHS = {2, 4, 3, 2, 2, 4};
    private static final long[] EMPTY_COUNTS = new long[0];
    private static final int[] EMPTY_CHILDREN = new int[0];

    private final int maximumSize;
    private final int[] symbols = new int[4];

    private long[] counts = EMPTY_COUNTS;
    private int[] leftChildren = EMPTY_CHILDREN;
    private int[] rightChildren = EMPTY_CHILDREN;
    private int[] stack;
    private HuffmanWorkspace workspace;

    HuffmanTree(int size) {
        maximumSize = size;
    }

    void buildAndStoreHuffmanTree(BitBuffer storage, int[] histogram, int total, Buffer depths, Buffer bits) {
        int count = 0;
        Arrays.fill(symbols, 0);
        int length = 0;

        while (total != 0) {
            if (histogram[length] != 0) {
                if (count < 4) {
                    symbols[count] = length;
                }
                ++count;
                total -= histogram[length];
            }
            ++length;
        }
        if (count <= 1) {
            storage.writeBit(4, 1);
            storage.writeBit(8, symbols[0]);
            depths.set(symbols[0], 0);
            bits.set(symbols[0], 0);
            return;
        }

        // A new metablock alphabet must not inherit bit depths from the previous prefix code.
        for (int i = 0; i < length; i++) {
            depths.set(i, 0);
        }
        buildHuffmanTree(length, histogram, depths);
        convertBitDepthsToSymbols(depths, length, bits, 0);
        storeHuffmanTree(count, length, depths, storage, symbols);
    }

    void createHuffmanTree(int[] data, int dataIx, int length, int treeLimit, Buffer depth, int depthIx) {
        ensureCapacity(2 * length + 1);
        for (int countLimit = 1; ; countLimit *= 2) {
            int n = 0;
            for (int i = length - 1; i >= 0; i--) {
                if (data[dataIx + i] != 0) {
                    setNode(n++, Math.max(data[dataIx + i], countLimit), -1, i);
                }
            }
            if (n == 1) {
                depth.set(depthIx + rightChildren[0], 1);
                break;
            }
            sortNodes(n);
            setNode(n, Integer.MAX_VALUE, -1, -1);
            setNode(n + 1, Integer.MAX_VALUE, -1, -1);
            int i = 0;
            int j = n + 1;
            for (int k = n - 1; k != 0; k--) {
                int left;
                int right;
                if (counts[i] <= counts[j]) {
                    left = i++;
                } else {
                    left = j++;
                }
                if (counts[i] <= counts[j]) {
                    right = i++;
                } else {
                    right = j++;
                }
                int jEnd = 2 * n - k;
                setNode(jEnd, counts[left] + counts[right], left, right);
                setNode(jEnd + 1, Integer.MAX_VALUE, -1, -1);
            }
            if (setDepth(2 * n - 1, depth, treeLimit, depthIx)) {
                break;
            }
        }
    }

    void brotliStoreHuffmanTree(Buffer depths, int alphabetSize, int index, BitBuffer storage) {
        if (workspace == null) {
            workspace = new HuffmanWorkspace();
        }
        workspace.clear();
        int[] huffmanTreeHistogram = workspace.histogram;
        Buffer huffmanTreeExtraBits = workspace.extraBits;
        Buffer huffmanTree = workspace.tree;
        Buffer codeLengthBitDepth = workspace.depths;
        Buffer codeLengthBitDepthSymbols = workspace.bits;
        int i;
        int numCodes = 0;
        int code = 0;

        writeHuffmanTree(depths, index, alphabetSize, huffmanTree, huffmanTreeExtraBits);
        for (i = 0; i < huffmanTree.byteIx(); ++i) {
            ++huffmanTreeHistogram[huffmanTree.get(i)];
        }
        for (i = 0; i < CODE_LENGTH_CODES; ++i) {
            if (huffmanTreeHistogram[i] != 0) {
                if (numCodes == 0) {
                    code = i;
                    numCodes = 1;
                } else {
                    numCodes = 2;
                    break;
                }
            }
        }
        createHuffmanTree(huffmanTreeHistogram, 0, CODE_LENGTH_CODES, 5, codeLengthBitDepth, 0);
        convertBitDepthsToSymbols(codeLengthBitDepth, CODE_LENGTH_CODES, codeLengthBitDepthSymbols, 0);
        storeHuffmanTreeOfHuffmanTreeToBitMask(numCodes, codeLengthBitDepth, storage);
        if (numCodes == 1) {
            codeLengthBitDepth.set(code, 0);
        }
        for (i = 0; i < huffmanTree.byteIx(); ++i) {
            int ix = huffmanTree.get(i);
            storage.writeBit(codeLengthBitDepth.get(ix), codeLengthBitDepthSymbols.get(ix));
            switch (ix) {
                case REPEAT_PREVIOUS_CODE_LENGTH -> storage.writeBit(2, huffmanTreeExtraBits.get(i));
                case REPEAT_ZERO_CODE_LENGTH -> storage.writeBit(3, huffmanTreeExtraBits.get(i));
                default -> {
                }
            }
        }

    }

    private static void storeHuffmanTree(int count, int length, Buffer depths, BitBuffer storage, int[] symbols) {
        if (count <= 4) {
            int i;
            storage.writeBit(2, 1);
            storage.writeBit(2, (count - 1));
            for (i = 0; i < count; i++) {
                int j;
                for (j = i + 1; j < count; j++) {
                    if (depths.get(symbols[j]) < depths.get(symbols[i])) {
                        swap(symbols, j, i);
                    }
                }
            }
            if (count == 2) {
                storage.writeBit(8, symbols[0]);
                storage.writeBit(8, symbols[1]);
            } else if (count == 3) {
                storage.writeBit(8, symbols[0]);
                storage.writeBit(8, symbols[1]);
                storage.writeBit(8, symbols[2]);
            } else {
                storage.writeBit(8, symbols[0]);
                storage.writeBit(8, symbols[1]);
                storage.writeBit(8, symbols[2]);
                storage.writeBit(8, symbols[3]);
                storage.writeBit(1, depths.get(symbols[0]) == 1 ? 1 : 0);
            }
        } else {
            int previousValue = 8;
            long longValue = (0x0000FFL << 32) | 0x55555554L;
            storage.writeBit(40, longValue);

            for (int i = 0; i < length;) {
                int value = depths.get(i);
                int reps = 1;
                for (int k = i + 1; k < length && depths.get(k) == value; ++k) {
                    ++reps;
                }
                i += reps;
                if (value == 0) {
                    storage.writeBit(Context.ZERO_REPS_DEPTH[reps], Context.ZERO_REPS_BITS[reps]);
                } else {
                    if (previousValue != value) {
                        storage.writeBit(Context.CODE_LENGTH_DEPTH[value], Context.CODE_LENGTH_BITS[value]);
                        --reps;
                    }
                    if (reps < 3) {
                        while (reps != 0) {
                            reps--;
                            storage.writeBit(Context.CODE_LENGTH_DEPTH[value], Context.CODE_LENGTH_BITS[value]);
                        }
                    } else {
                        reps -= 3;
                        storage.writeBit(Context.NON_ZERO_REPS_DEPTH[reps], Context.NON_ZERO_REPS_BITS[reps]);
                    }
                    previousValue = value;
                }
            }
        }
    }

    private static void swap(int[] array, int j, int i) {
        int temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }

    private void buildHuffmanTree(int length, int[] histogram, Buffer depths) {
        ensureCapacity(2 * length + 1);
        for (int countLimit = 1; ; countLimit *= 2) {
            int nodeIndex = 0;
            for (int symbol = length - 1; symbol >= 0; symbol--) {
                if (histogram[symbol] != 0) {
                    setNode(nodeIndex++, Math.max(histogram[symbol], countLimit), -1, symbol);
                }
            }
            int n = nodeIndex;
            int i = 0;
            int j = n + 1;
            sortNodes(n);
            setNode(nodeIndex++, Integer.MAX_VALUE, -1, -1);
            setNode(nodeIndex++, Integer.MAX_VALUE, -1, -1);
            for (int k = n - 1; k > 0; k--) {
                int left;
                int right;
                if (counts[i] <= counts[j]) {
                    left = i++;
                } else {
                    left = j++;
                }
                if (counts[i] <= counts[j]) {
                    right = i++;
                } else {
                    right = j++;
                }
                setNode(nodeIndex - 1, counts[left] + counts[right], left, right);
                setNode(nodeIndex++, Integer.MAX_VALUE, -1, -1);
            }
            if (setDepth(2 * n - 1, depths, 14, 0)) {
                break;
            }
        }
    }

    private void storeHuffmanTreeOfHuffmanTreeToBitMask(int numCodes, Buffer codeLengthBitdepth, BitBuffer storage) {
        int skipSome = 0;
        int codesToStore = CODE_LENGTH_CODES;

        if (numCodes > 1) {
            for (; codesToStore > 0; --codesToStore) {
                if (codeLengthBitdepth.get(STORAGE_ORDER[codesToStore - 1]) != 0) {
                    break;
                }
            }
        }
        if (codeLengthBitdepth.get(STORAGE_ORDER[0]) == 0
                && codeLengthBitdepth.get(STORAGE_ORDER[1]) == 0) {
            skipSome = 2;
            if (codeLengthBitdepth.get(STORAGE_ORDER[2]) == 0) {
                skipSome = 3;
            }
        }
        storage.writeBit(2, skipSome);
        for (int i = skipSome; i < codesToStore; ++i) {
            int l = codeLengthBitdepth.get(STORAGE_ORDER[i]);
            storage.writeBit(BIT_LENGTH_DEPTHS[l], BIT_LENGTH_SYMBOLS[l]);
        }
    }

    private void writeHuffmanTree(Buffer depth, int index, int length, Buffer tree, Buffer extraBitsData) {
        int previousValue = INITIAL_REPEATED_CODE_LENGTH;
        int i;
        int reps;
        boolean useRleForNonZero = false;
        boolean useRleForZero = false;

        int newLength = length;
        for (i = 0; i < length; ++i) {
            if (depth.get(index + length - i - 1) == 0) {
                --newLength;
            } else {
                break;
            }
        }

        if (length > 50) {
            int totalRepsZero = 0;
            int totalRepsNonZero = 0;
            int countRepsZero = 1;
            int countRepsNonZero = 1;
            for (i = 0; i < length; i += reps) {
                int value = depth.get(index + i);
                reps = 1;
                for (int k = i + 1; k < length && depth.get(index + k) == value; ++k) {
                    ++reps;
                }
                if (reps >= 3 && value == 0) {
                    totalRepsZero += reps;
                    ++countRepsZero;
                }
                if (reps >= 4 && value != 0) {
                    totalRepsNonZero += reps;
                    ++countRepsNonZero;
                }
            }
            useRleForNonZero = totalRepsNonZero > (countRepsNonZero * 2);
            useRleForZero = totalRepsZero > (countRepsZero * 2);
        }

        for (i = 0; i < newLength; i += reps) {
            int value = depth.get(index + i);
            reps = 1;
            if ((value != 0 && useRleForNonZero) || (value == 0 && useRleForZero)) {
                for (int k = i + 1; k < newLength && depth.get(index + k) == value; ++k) {
                    ++reps;
                }
            }
            if (value == 0) {
                writeHuffmanTreeRepetitionsZeros(reps, tree, extraBitsData);
            } else {
                writeHuffmanTreeRepetitions(previousValue, value, reps, tree, extraBitsData);
                previousValue = value;
            }
        }
    }

    private void writeHuffmanTreeRepetitionsZeros(int repetitions, Buffer tree, Buffer extraBitsData) {
        if (repetitions == 11) {
            tree.writeByte(0);
            extraBitsData.writeByte(0);
            --repetitions;
        }
        if (repetitions < 3) {
            for (int i = 0; i < repetitions; ++i) {
                tree.writeByte(0);
                extraBitsData.writeByte(0);
            }
        } else {
            int start = tree.byteIx();
            repetitions -= 3;
            while (true) {
                tree.writeByte(REPEAT_ZERO_CODE_LENGTH);
                extraBitsData.writeByte(repetitions & 0x7);
                repetitions >>= 3;
                if (repetitions == 0) {
                    break;
                }
                --repetitions;
            }
            tree.reverse(start, tree.byteIx());
            extraBitsData.reverse(start, tree.byteIx());
        }
    }

    private void writeHuffmanTreeRepetitions(int previousValue, int value, int repetitions, Buffer tree, Buffer extraBitsData) {
        if (previousValue != value) {
            tree.writeByte(value);
            extraBitsData.writeByte(0);
            --repetitions;
        }
        if (repetitions == 7) {
            tree.writeByte(value);
            extraBitsData.writeByte(0);
            --repetitions;
        }
        if (repetitions < 3) {
            for (int i = 0; i < repetitions; ++i) {
                tree.writeByte(value);
                extraBitsData.writeByte(0);
            }
        } else {
            int start = tree.byteIx();
            repetitions -= 3;
            while (true) {
                tree.writeByte(REPEAT_PREVIOUS_CODE_LENGTH);
                extraBitsData.writeByte(repetitions & 0x3);
                repetitions >>= 2;
                if (repetitions == 0) {
                    break;
                }
                --repetitions;
            }
            tree.reverse(start, tree.byteIx());
            extraBitsData.reverse(start, tree.byteIx());
        }
    }

    private void setNode(int index, long count, int left, int right) {
        counts[index] = count;
        leftChildren[index] = left;
        rightChildren[index] = right;
    }

    private void copyNode(int source, int destination) {
        counts[destination] = counts[source];
        leftChildren[destination] = leftChildren[source];
        rightChildren[destination] = rightChildren[source];
    }

    private void sortNodes(int n) {
        if (n < 13) {
            for (int i = 1; i < n; i++) {
                long count = counts[i];
                int left = leftChildren[i];
                int right = rightChildren[i];
                int destination = i;
                int source = i - 1;
                while (precedes(count, right, source)) {
                    copyNode(source, destination);
                    destination = source;
                    if (source-- == 0) {
                        break;
                    }
                }
                setNode(destination, count, left, right);
            }
        } else {
            int g = n < 57 ? 2 : 0;
            for (; g < Context.BROTLI_SHELL_GAPS.length; g++) {
                int gap = Context.BROTLI_SHELL_GAPS[g];
                for (int i = gap; i < n; i++) {
                    long count = counts[i];
                    int left = leftChildren[i];
                    int right = rightChildren[i];
                    int destination = i;
                    for (; destination >= gap && precedes(count, right, destination - gap); destination -= gap) {
                        copyNode(destination - gap, destination);
                    }
                    setNode(destination, count, left, right);
                }
            }
        }
    }

    private boolean setDepth(int node, Buffer depths, int maxDepth, int offset) {
        if (stack == null) {
            stack = new int[16];
        }
        Arrays.fill(stack, -1);
        int level = 0;
        while (true) {
            if (leftChildren[node] >= 0) {
                level++;
                if (level > maxDepth) {
                    return false;
                }
                stack[level] = rightChildren[node];
                node = leftChildren[node];
                continue;
            }
            depths.set(offset + rightChildren[node], level);
            while (level >= 0 && stack[level] == -1) {
                level--;
            }
            if (level < 0) {
                return true;
            }
            node = stack[level];
            stack[level] = -1;
        }
    }

    private boolean precedes(long count, int value, int index) {
        return count < counts[index] || (count == counts[index] && value > rightChildren[index]);
    }

    private void ensureCapacity(int required) {
        if (required > maximumSize) {
            throw new BrotliException("Huffman tree exceeds its alphabet size");
        }
        if (required > counts.length) {
            int oldSize = counts.length;
            int size = Math.min(maximumSize, Math.max(required, oldSize << 1));
            counts = Arrays.copyOf(counts, size);
            leftChildren = Arrays.copyOf(leftChildren, size);
            rightChildren = Arrays.copyOf(rightChildren, size);
            Arrays.fill(leftChildren, oldSize, size, -1);
        }
    }

    private static final class HuffmanWorkspace {
        private final int[] histogram = new int[CODE_LENGTH_CODES];
        private final Buffer extraBits = new Buffer(NUM_COMMAND_SYMBOLS);
        private final Buffer tree = new Buffer(NUM_COMMAND_SYMBOLS);
        private final Buffer depths = new Buffer(CODE_LENGTH_CODES);
        private final Buffer bits = new Buffer(CODE_LENGTH_CODES);

        private void clear() {
            Arrays.fill(histogram, 0);
            extraBits.clear();
            tree.clear();
            depths.clear();
            bits.clear();
        }
    }
}
