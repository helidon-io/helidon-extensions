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

import java.util.LinkedList;
import java.util.List;

import static io.helidon.extensions.brotli.Utils.convertBitDepthsToSymbols;

class HuffmanTree {
    static final int NUM_COMMAND_SYMBOLS = 704;

    private static final int INITIAL_REPEATED_CODE_LENGTH = 8;
    private static final int REPEAT_PREVIOUS_CODE_LENGTH = 16;
    private static final int REPEAT_ZERO_CODE_LENGTH = 17;
    private static final int CODE_LENGTH_CODES = 18;

    private final List<HuffmanNode> nodes;

    HuffmanTree(int size) {
        nodes = new LinkedList<>();
        for (int i = 0; i < size; i++) {
            nodes.add(new HuffmanNode());
        }
    }

    static void buildAndStoreHuffmanTree(Buffer storage, int[] histogram, int total, Buffer depths, Buffer bits) {
        int count = 0;
        int[] symbols = new int[4];
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

        buildHuffmanTree(length, histogram, depths);
        convertBitDepthsToSymbols(depths, length, bits, 0);
        storeHuffmanTree(count, length, depths, storage, symbols);
    }

    void createHuffmanTree(int[] data, int dataIx, int length, int treeLimit, Buffer depth, int depthIx) {
        int n;
        int j;
        int i;
        int k;
        for (int countLimit = 1; ; countLimit *= 2) {
            n = 0;
            for (i = length - 1; i >= 0; i--) {
                if (data[dataIx + i] != 0) {
                    int count = Math.max(data[dataIx + i], countLimit);
                    this.initNode(n++, count, i);
                }
            }
            if (n == 1) {
                depth.set(depthIx + this.node(0).indexRightOrValue(), 1);
                break;
            }
            this.sortNodes(n);
            this.node(n, new Sentinel());
            this.node(n + 1, new Sentinel());
            i = 0;
            j = n + 1;
            for (k = n - 1; k != 0; k--) {
                int left;
                int right;
                if (this.node(i).totalCount() <= this.node(j).totalCount()) {
                    left = i;
                    ++i;
                } else {
                    left = j;
                    ++j;
                }
                if (this.node(i).totalCount() <= this.node(j).totalCount()) {
                    right = i;
                    ++i;
                } else {
                    right = j;
                    ++j;
                }
                int jEnd = 2 * n - k;
                this.node(jEnd).totalCount(this.node(left).totalCount() + this.node(right).totalCount());
                this.node(jEnd).indexLeft(left);
                this.node(jEnd).indexRightOrValue(right);
                this.node(jEnd + 1, new Sentinel());
            }
            if (this.setDepth((2 * n - 1), depth, treeLimit, depthIx)) {
                break;
            }
        }
    }

    void brotliStoreHuffmanTree(Buffer depths, int alphabetSize, int index, Buffer storage) {
        int[] huffmanTreeHistogram = new int[CODE_LENGTH_CODES];
        Buffer huffmanTreeExtraBits = new Buffer(NUM_COMMAND_SYMBOLS);
        Buffer huffmanTree = new Buffer(NUM_COMMAND_SYMBOLS);
        Buffer codeLengthBitDepth = new Buffer(CODE_LENGTH_CODES);
        Buffer codeLengthBitDepthSymbols = new Buffer(CODE_LENGTH_CODES);
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
                case REPEAT_PREVIOUS_CODE_LENGTH:
                    storage.writeBit(2, huffmanTreeExtraBits.get(i));
                    break;
                case REPEAT_ZERO_CODE_LENGTH:
                    storage.writeBit(3, huffmanTreeExtraBits.get(i));
                    break;
                default:
                    break;
            }
        }

    }

    private static void storeHuffmanTree(int count, int length, Buffer depths, Buffer storage, int[] symbols) {
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

    private static void buildHuffmanTree(int length, int[] histogram, Buffer depths) {
        int maxTreeSize = 2 * length + 1;
        HuffmanTree tree = new HuffmanTree(maxTreeSize);

        for (int countLimit = 1; ; countLimit *= 2) {
            int nodeIndex = 0;
            for (int l = length; l != 0;) {
                --l;
                if (histogram[l] != 0) {
                    tree.node(nodeIndex).initNode(Math.max(histogram[l], countLimit), l);
                    ++nodeIndex;
                }
            }

            int n = nodeIndex;
            int i = 0;
            int j = n + 1;
            tree.sortNodes(n);
            tree.node(nodeIndex++, new Sentinel());
            tree.node(nodeIndex++, new Sentinel());
            for (int k = n - 1; k > 0; --k) {
                int left;
                int right;
                if (tree.node(i).totalCount() <= tree.node(j).totalCount()) {
                    left = i;
                    ++i;
                } else {
                    left = j;
                    ++j;
                }
                if (tree.node(i).totalCount() <= tree.node(j).totalCount()) {
                    right = i;
                    ++i;
                } else {
                    right = j;
                    ++j;
                }
                tree.node(nodeIndex - 1).totalCount(tree.node(left).totalCount() + tree.node(right).totalCount());
                tree.node(nodeIndex - 1).indexLeft(left);
                tree.node(nodeIndex - 1).indexRightOrValue(right);
                tree.node(nodeIndex++, new Sentinel());
            }

            if (tree.setDepth(2 * n - 1, depths, 14, 0)) {
                break;
            }
        }
    }

    private static void swap(int[] array, int j, int i) {
        int temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }

    private void storeHuffmanTreeOfHuffmanTreeToBitMask(int numCodes, Buffer codeLengthBitdepth, Buffer storage) {
        int[] kStorageOrder = {1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        int[] kHuffmanBitLengthHuffmanCodeSymbols = {0, 7, 3, 2, 1, 15};
        int[] kHuffmanBitLengthHuffmanCodeBitLengths = {2, 4, 3, 2, 2, 4};
        int skipSome = 0;
        int codesToStore = CODE_LENGTH_CODES;

        if (numCodes > 1) {
            for (; codesToStore > 0; --codesToStore) {
                if (codeLengthBitdepth.get(kStorageOrder[codesToStore - 1]) != 0) {
                    break;
                }
            }
        }
        if (codeLengthBitdepth.get(kStorageOrder[0]) == 0
                && codeLengthBitdepth.get(kStorageOrder[1]) == 0) {
            skipSome = 2;
            if (codeLengthBitdepth.get(kStorageOrder[2]) == 0) {
                skipSome = 3;
            }
        }
        storage.writeBit(2, skipSome);
        for (int i = skipSome; i < codesToStore; ++i) {
            int l = codeLengthBitdepth.get(kStorageOrder[i]);
            storage.writeBit(kHuffmanBitLengthHuffmanCodeBitLengths[l], kHuffmanBitLengthHuffmanCodeSymbols[l]);
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

    private void initNode(int index, long size, int right) {
        nodes.get(index).initNode(size, right);
    }

    private HuffmanNode node(int index) {
        return nodes.get(index);
    }

    private void node(int index, HuffmanNode node) {
        nodes.set(index, node);
    }

    private void sortNodes(int n) {
        if (n < 13) {
            for (int i = 1; i < n; ++i) {
                HuffmanNode node = nodes.get(i);
                int k = i;
                int j = i - 1;
                while (compareNodes(node, nodes.get(j))) {
                    nodes.set(k, nodes.get(j));
                    k = j;
                    if (j-- == 0) {
                        break;
                    }
                }
                nodes.set(k, node);
            }
        } else {
            int g = n < 57 ? 2 : 0;
            for (; g < 6; ++g) {
                int gap = Context.BROTLI_SHELL_GAPS[g];
                for (int i = gap; i < n; ++i) {
                    int j = i;
                    HuffmanNode node = nodes.get(i);
                    for (; j >= gap && compareNodes(node, nodes.get(j - gap)); j -= gap) {
                        nodes.set(j, nodes.get(j - gap));
                    }
                    nodes.set(j, node);
                }
            }
        }
    }

    private boolean setDepth(int p, Buffer depth, int maxDepth, int index) {
        int[] stack = new int[16];
        int level = 0;
        stack[0] = -1;

        while (true) {
            if (nodes.get(p).indexLeft() >= 0) {
                level++;
                if (level > maxDepth) {
                    return false;
                }
                stack[level] = nodes.get(p).indexRightOrValue();
                p = nodes.get(p).indexLeft();
                continue;
            } else {
                depth.set(index + nodes.get(p).indexRightOrValue(), level);
            }
            while (level >= 0 && stack[level] == -1) {
                level--;
            }
            if (level < 0) {
                return true;
            }
            p = stack[level];
            stack[level] = -1;
        }
    }

    private boolean compareNodes(HuffmanNode first, HuffmanNode second) {
        if (first.totalCount() != second.totalCount()) {
            return first.totalCount() < second.totalCount();
        }
        return first.indexRightOrValue() > second.indexRightOrValue();
    }
}
