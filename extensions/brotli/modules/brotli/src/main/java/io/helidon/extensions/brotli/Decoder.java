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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static io.helidon.extensions.brotli.Context.BLOCK_LENGTH_N_BITS;
import static io.helidon.extensions.brotli.Context.BLOCK_LENGTH_OFFSET;
import static io.helidon.extensions.brotli.Context.CODE_LENGTH_CODE_ORDER;
import static io.helidon.extensions.brotli.Context.COPY_LENGTH_N_BITS;
import static io.helidon.extensions.brotli.Context.DISTANCE_SHORT_CODE_INDEX_OFFSET;
import static io.helidon.extensions.brotli.Context.DISTANCE_SHORT_CODE_VALUE_OFFSET;
import static io.helidon.extensions.brotli.Context.FIXED_TABLE;
import static io.helidon.extensions.brotli.Context.INSERT_LENGTH_N_BITS;
import static io.helidon.extensions.brotli.Decoder.RunningState.BLOCK_START;
import static io.helidon.extensions.brotli.Decoder.RunningState.CLOSED;
import static io.helidon.extensions.brotli.Decoder.RunningState.COMPRESSED_BLOCK_START;
import static io.helidon.extensions.brotli.Decoder.RunningState.COPY_FROM_COMPOUND_DICTIONARY;
import static io.helidon.extensions.brotli.Decoder.RunningState.COPY_LOOP;
import static io.helidon.extensions.brotli.Decoder.RunningState.COPY_UNCOMPRESSED;
import static io.helidon.extensions.brotli.Decoder.RunningState.FINISHED;
import static io.helidon.extensions.brotli.Decoder.RunningState.INITIALIZED;
import static io.helidon.extensions.brotli.Decoder.RunningState.INIT_WRITE;
import static io.helidon.extensions.brotli.Decoder.RunningState.INSERT_LOOP;
import static io.helidon.extensions.brotli.Decoder.RunningState.MAIN_LOOP;
import static io.helidon.extensions.brotli.Decoder.RunningState.READ_METADATA;
import static io.helidon.extensions.brotli.Decoder.RunningState.USE_DICTIONARY;
import static io.helidon.extensions.brotli.Decoder.RunningState.WRITE;

/**
 * API for Brotli decompression.
 */
final class Decoder {
    private static final int MIN_LARGE_WINDOW_BITS = 10;
    private static final int MAX_LARGE_WINDOW_BITS = 30;
    private static final int DEFAULT_CODE_LENGTH = 8;
    private static final int CODE_LENGTH_REPEAT_CODE = 16;
    private static final int NUM_LITERAL_CODES = 256;
    private static final int NUM_COMMAND_CODES = 704;
    private static final int NUM_BLOCK_LENGTH_CODES = 26;
    private static final int LITERAL_CONTEXT_BITS = 6;
    private static final int DISTANCE_CONTEXT_BITS = 2;
    private static final int CD_BLOCK_MAP_BITS = 8;
    private static final int HUFFMAN_TABLE_BITS = 8;
    private static final int HUFFMAN_TABLE_SIZE_26 = 396;
    private static final int HUFFMAN_TABLE_SIZE_258 = 632;
    private static final int CODE_LENGTH_CODES = 18;
    private static final int NUM_DISTANCE_SHORT_CODES = 16;
    private static final int MAX_TRANSFORMED_WORD_LENGTH = 37;
    private static final int MAX_DISTANCE_BITS = 24;
    private static final int MAX_LARGE_WINDOW_DISTANCE_BITS = 62;
    private static final int MAX_LENGTH = 15;
    // A block switch plus command and two 24-bit lengths needs at most 117 bits.
    private static final int MAX_ATOMIC_INPUT_BITS = 128;

    /**
     * Safe distance limit.
     * Limit ((1 << 31) - 4) allows safe distance calculation without overflows,
     * given the distance alphabet size is limited to corresponding size.
     */
    private static final int MAX_ALLOWED_DISTANCE = 0x7FFFFFFC;
    private static final short[] CMD_LOOKUP = new short[NUM_COMMAND_CODES * 4];

    static {
        short[] insertLengthOffsets = new short[24];
        short[] copyLengthOffsets = new short[24];
        copyLengthOffsets[0] = 2;
        for (int i = 0; i < 23; ++i) {
            insertLengthOffsets[i + 1] = (short) (insertLengthOffsets[i] + (1 << INSERT_LENGTH_N_BITS[i]));
            copyLengthOffsets[i + 1] = (short) (copyLengthOffsets[i] + (1 << COPY_LENGTH_N_BITS[i]));
        }

        for (int cmdCode = 0; cmdCode < NUM_COMMAND_CODES; ++cmdCode) {
            int rangeIdx = cmdCode >>> 6;
            int distanceContextOffset = -4;
            if (rangeIdx >= 2) {
                rangeIdx -= 2;
                distanceContextOffset = 0;
            }
            int insertCode = (((0x29850 >>> (rangeIdx * 2)) & 0x3) << 3) | ((cmdCode >>> 3) & 7);
            int copyCode = (((0x26244 >>> (rangeIdx * 2)) & 0x3) << 3) | (cmdCode & 7);
            short copyLengthOffset = copyLengthOffsets[copyCode];
            int distanceContext = distanceContextOffset + (copyLengthOffset > 4 ? 3 : copyLengthOffset - 2);
            int index = cmdCode * 4;
            CMD_LOOKUP[index] = (short) (INSERT_LENGTH_N_BITS[insertCode] | (COPY_LENGTH_N_BITS[copyCode] << 8));
            CMD_LOOKUP[index + 1] = insertLengthOffsets[insertCode];
            CMD_LOOKUP[index + 2] = copyLengthOffsets[copyCode];
            CMD_LOOKUP[index + 3] = (short) distanceContext;
        }
    }

    private final BitReader bitReader;
    private final byte[][] cdChunks;
    private final int[] cdChunkOffsets;
    private final int cdTotalSize;
    private final byte[] distExtraBits;
    private final int[] rings;
    private final int[] blockTrees;
    private final int[] distOffset;
    private final int maxWindowBits;
    private final boolean verifyEndOfInput;

    private RunningState runningState;
    private RunningState nextRunningState;
    private byte[] ringBuffer;
    private byte[] contextModes;
    private byte[] contextMap;
    private byte[] distContextMap;
    private int[] literalTreeGroup;
    private int[] commandTreeGroup;
    private int[] distanceTreeGroup;
    private int metaBlockLength;
    private int inputEnd;
    private int literalBlockLength;
    private int numLiteralBlockTypes;
    private int commandBlockLength;
    private int numCommandBlockTypes;
    private int distanceBlockLength;
    private int numDistanceBlockTypes;
    private int pos;
    private int maxDistance;
    private int distRbIdx;
    private int trivialLiteralContext;
    private int literalTreeIdx;
    private int commandTreeIdx;
    private int insertLength;
    private int contextMapSlice;
    private int distContextMapSlice;
    private int contextLookupOffset1;
    private int contextLookupOffset2;
    private int distanceCode;
    private int numDirectDistanceCodes;
    private int distancePostfixBits;
    private int distance;
    private int copyLength;
    private int maxBackwardDistance;
    private int maxRingBufferSize;
    private int ringBufferSize;
    private int expectedTotalSize;
    private int outputUsed;
    private int ringBufferBytesWritten;
    private int ringBufferBytesReady;
    private int cdBrIndex;
    private int cdBrOffset;
    private int cdBrLength;
    private int cdBrCopied;
    private int cdBlockBits;
    private byte[] cdBlockMap;
    private byte[] output;
    private int outputOffset;
    private int outputLength;
    private boolean isUncompressed;
    private boolean isMetadata;
    private boolean isLargeWindow;
    private int fence;
    private int ringBufferMask;
    private int j;

    Decoder(InputStream input, int[] cdChunkOffsets, byte[][] cdChunks, int cdTotalSize,
            int maxWindowBits, boolean verifyEndOfInput) {
        int maxDistanceAlphabetLimit = calculateDistanceAlphabetLimit(3, 15 << 3);
        this.ringBuffer = new byte[0];
        this.rings = new int[10];
        this.rings[0] = 16;
        this.rings[1] = 15;
        this.rings[2] = 11;
        this.rings[3] = 4;
        this.cdBlockBits = -1;
        this.cdChunkOffsets = cdChunkOffsets;
        this.cdChunks = cdChunks;
        this.cdTotalSize = cdTotalSize;
        this.maxWindowBits = maxWindowBits;
        this.verifyEndOfInput = verifyEndOfInput;
        this.bitReader = new BitReader(input);
        this.blockTrees = new int[7 + 3 * (HUFFMAN_TABLE_SIZE_258 + HUFFMAN_TABLE_SIZE_26)];
        this.blockTrees[0] = 7;
        this.distRbIdx = 3;
        this.distExtraBits = new byte[maxDistanceAlphabetLimit];
        this.distOffset = new int[maxDistanceAlphabetLimit];
        runningState = INITIALIZED;
    }

    /**
     * Builds Huffman lookup table assuming code lengths are in symbol order.
     *
     * @return number of slots used by resulting Huffman table
     */
    static int buildHuffmanTable(int[] tableGroup, int tableIdx, int rootBits, int[] codeLengths, int codeLengthsSize) {
        int tableOffset = tableGroup[tableIdx];
        int key;
        int[] sorted = new int[codeLengthsSize];
        int[] count = new int[MAX_LENGTH + 1];
        int[] offset = new int[MAX_LENGTH + 1];
        int symbol;

        for (symbol = 0; symbol < codeLengthsSize; symbol++) {
            count[codeLengths[symbol]]++;
        }
        offset[1] = 0;
        for (int len = 1; len < MAX_LENGTH; len++) {
            offset[len + 1] = offset[len] + count[len];
        }
        for (symbol = 0; symbol < codeLengthsSize; symbol++) {
            if (codeLengths[symbol] != 0) {
                sorted[offset[codeLengths[symbol]]++] = symbol;
            }
        }

        int tableBits = rootBits;
        int tableSize = 1 << tableBits;
        int totalSize = tableSize;
        if (offset[MAX_LENGTH] == 1) {
            for (key = 0; key < totalSize; key++) {
                tableGroup[tableOffset + key] = sorted[0];
            }
            return totalSize;
        }
        key = 0;
        symbol = 0;
        for (int len = 1, step = 2; len <= rootBits; len++, step <<= 1) {
            for (; count[len] > 0; count[len]--) {
                replicateValue(tableGroup, tableOffset + key, step, tableSize,
                        len << 16 | sorted[symbol++]);
                key = getNextKey(key, len);
            }
        }

        int mask = totalSize - 1;
        int low = -1;
        int currentOffset = tableOffset;
        for (int len = rootBits + 1, step = 2; len <= MAX_LENGTH; len++, step <<= 1) {
            for (; count[len] > 0; count[len]--) {
                if ((key & mask) != low) {
                    currentOffset += tableSize;
                    tableBits = nextTableBitSize(count, len, rootBits);
                    tableSize = 1 << tableBits;
                    totalSize += tableSize;
                    low = key & mask;
                    tableGroup[tableOffset + low] = (tableBits + rootBits) << 16 | (currentOffset - tableOffset - low);
                }
                replicateValue(tableGroup, currentOffset + (key >> rootBits), step, tableSize,
                        (len - rootBits) << 16 | sorted[symbol++]);
                key = getNextKey(key, len);
            }
        }
        return totalSize;
    }

    void close() throws IOException {
        if (runningState == CLOSED) {
            return;
        }
        runningState = CLOSED;
        bitReader.close();
    }

    /**
     * Actual decompress implementation.
     */
    void decompress(byte[] destBuffer, int destOffset, int destLen) {
        this.output = destBuffer;
        this.outputOffset = destOffset;
        this.outputLength = destLen;
        this.outputUsed = 0;
        if (runningState == CLOSED) {
            throw new IllegalStateException("Can't decompress after close");
        }
        if (runningState == INITIALIZED) {
            int windowBits = decodeWindowBits();
            if (windowBits == -1) {
                throw new BrotliException("Invalid 'windowBits' code");
            }
            if (windowBits > maxWindowBits) {
                throw new BrotliException("Brotli window exceeds configured maximum");
            }
            maxRingBufferSize = 1 << windowBits;
            maxBackwardDistance = maxRingBufferSize - 16;
            runningState = BLOCK_START;
        }
        updateFence();
        ringBufferMask = ringBufferSize - 1;
        while (runningState != FINISHED) {
            switch (runningState) {
                case BLOCK_START -> {
                    blockStart();
                    continue;
                }
                case COMPRESSED_BLOCK_START -> {
                    readMetablockHuffmanCodesAndContextMaps();
                    runningState = MAIN_LOOP;
                    continue;
                }
                case MAIN_LOOP -> {
                    if (metaBlockLength <= 0) {
                        runningState = BLOCK_START;
                        continue;
                    }
                    if (writeBeforeInput()) {
                        continue;
                    }
                    mainLoop();
                    continue;
                }
                case INSERT_LOOP -> {
                    insertLoop();
                    if (runningState != COPY_LOOP) {
                        continue;
                    }
                    continue;
                }
                case COPY_LOOP -> {
                    copyLoop();
                    continue;
                }
                case USE_DICTIONARY -> {
                    doUseDictionary(fence);
                    continue;
                }
                case COPY_FROM_COMPOUND_DICTIONARY -> {
                    copyFromCompoundDictionary(fence);
                    if (pos >= fence) {
                        nextRunningState = COPY_FROM_COMPOUND_DICTIONARY;
                        runningState = INIT_WRITE;
                        continue;
                    }
                    runningState = MAIN_LOOP;
                    continue;
                }
                case READ_METADATA -> {
                    readMetadata();
                    continue;
                }
                case COPY_UNCOMPRESSED -> {
                    copyUncompressedData();
                    continue;
                }
                case INIT_WRITE -> {
                    ringBufferBytesReady = Math.min(pos, ringBufferSize);
                    runningState = WRITE;
                    continue;
                }
                case WRITE -> {
                    if (writeRingBuffer() == 0) {
                        return;
                    }
                    write();
                    continue;
                }
                default -> throw new BrotliException("Unexpected state " + runningState);
            }
        }
        if (metaBlockLength < 0) {
            throw new BrotliException("Invalid metablock length");
        }
        bitReader.jumpToByteBoundary();
        bitReader.checkEndOfInput(verifyEndOfInput);
    }

    int outputUsed() {
        return this.outputUsed;
    }

    /**
     * Returns reverse(reverse(key, len) + 1, len).
     *
     * <p> reverse(key, len) is the bit-wise reversal of the len least significant bits of key.
     */
    private static int getNextKey(int key, int len) {
        int step = 1 << (len - 1);
        while ((key & step) != 0) {
            step >>= 1;
        }
        return (key & (step - 1)) + step;
    }

    /**
     * Stores {@code item} in {@code table[0], table[step], table[2 * step] .., table[end]}.
     *
     * <p> Assumes that end is an integer multiple of step.
     */
    private static void replicateValue(int[] table, int offset, int step, int end, int item) {
        do {
            end -= step;
            table[offset + end] = item;
        } while (end > 0);
    }

    /**
     * Determine the width of the next second-level Huffman lookup table.
     *
     * @param count histogram of bit lengths for the remaining symbols,
     * @param len code length of the next processed symbol.
     * @return table width of the next 2nd level table.
     */
    private static int nextTableBitSize(int[] count, int len, int rootBits) {
        int left = 1 << (len - rootBits);
        while (len < MAX_LENGTH) {
            left -= count[len];
            if (left <= 0) {
                break;
            }
            len++;
            left <<= 1;
        }
        return len - rootBits;
    }

    private int calculateDistanceAlphabetSize(int postFix, int direct, int maxDistBits) {
        return NUM_DISTANCE_SHORT_CODES + direct + 2 * (maxDistBits << postFix);
    }

    private int calculateDistanceAlphabetLimit(int postFix, int direct) {
        if (Decoder.MAX_ALLOWED_DISTANCE < direct + (2 << postFix)) {
            throw new IllegalArgumentException("maxDistance is too small");
        }
        int offset = ((Decoder.MAX_ALLOWED_DISTANCE - direct) >> postFix) + 4;
        int distBits = log2floor(offset) - 1;
        int group = ((distBits - 1) << 1) | ((offset >> distBits) & 1);
        return ((group - 1) << postFix) + (1 << postFix) + direct + NUM_DISTANCE_SHORT_CODES;
    }

    /**
     * Reads brotli stream header and parses "window bits".
     *
     * @return -1 if header is invalid
     */
    private int decodeWindowBits() {
        boolean largeWindowEnabled = isLargeWindow;
        isLargeWindow = false;

        if (bitReader.readBits(1) == 0) {
            return 16;
        }
        int n = bitReader.readBits(3);
        if (n != 0) {
            return 17 + n;
        }
        n = bitReader.readBits(3);
        if (n != 0) {
            if (n == 1) {
                if (!largeWindowEnabled) {
                    return -1;
                }
                isLargeWindow = true;
                if (bitReader.readBits(1) == 1) {
                    return -1;
                }
                n = bitReader.readBits(6);
                if (n < MIN_LARGE_WINDOW_BITS || n > MAX_LARGE_WINDOW_BITS) {
                    return -1;
                }
                return n;
            } else {
                return 8 + n;
            }
        }
        return 17;
    }

    /**
     * Decodes a number in the range [0..255], by reading 1 - 11 bits.
     */
    private int decodeVarLenUnsignedByte() {
        if (bitReader.readBits(1) != 0) {
            int n = bitReader.readBits(3);
            if (n == 0) {
                return 1;
            } else {
                return bitReader.readBits(n) + (1 << n);
            }
        }
        return 0;
    }

    private void decodeMetaBlockLength() {
        inputEnd = bitReader.readBits(1);
        metaBlockLength = 0;
        isUncompressed = false;
        isMetadata = false;
        if ((inputEnd != 0) && bitReader.readBits(1) != 0) {
            return;
        }
        int sizeNibbles = bitReader.readBits(2) + 4;
        if (sizeNibbles == 7) {
            isMetadata = true;
            if (bitReader.readBits(1) != 0) {
                throw new BrotliException("Corrupted reserved bit");
            }
            int sizeBytes = bitReader.readBits(2);
            if (sizeBytes == 0) {
                return;
            }
            for (int i = 0; i < sizeBytes; i++) {
                int bits = bitReader.readBits(8);
                if (bits == 0 && i + 1 == sizeBytes && sizeBytes > 1) {
                    throw new BrotliException("Exuberant nibble");
                }
                metaBlockLength |= bits << (i * 8);
            }
        } else {
            for (int i = 0; i < sizeNibbles; i++) {
                int bits = bitReader.readBits(4);
                if (bits == 0 && i + 1 == sizeNibbles && sizeNibbles > 4) {
                    throw new BrotliException("Exuberant nibble");
                }
                metaBlockLength |= bits << (i * 4);
            }
        }
        metaBlockLength++;
        if (!isMetadata && inputEnd == 0) {
            isUncompressed = bitReader.readBits(1) != 0;
        }
    }

    /**
     * Decodes the next Huffman code from bit-stream.
     */
    private int readSymbol(int[] tableGroup, int tableIdx) {
        return readPrefixSymbol(tableGroup, tableGroup[tableIdx], HUFFMAN_TABLE_BITS);
    }

    private int readPrefixSymbol(int[] table, int tableOffset, int rootBits) {
        int rootMask = (1 << rootBits) - 1;
        while (true) {
            int value = bitReader.peekBits();
            int available = bitReader.bufferedBits();
            int offset = tableOffset + (value & rootMask);
            int bits = table[offset] >> 16;
            int symbol = table[offset] & 0xFFFF;
            if (bits > rootBits && available >= rootBits) {
                offset += symbol + ((value & ((1 << bits) - 1)) >>> rootBits);
                bits = (table[offset] >> 16) + rootBits;
                symbol = table[offset] & 0xFFFF;
            }
            if (bits <= available) {
                bitReader.incrementBitOffset(bits);
                return symbol;
            }
            // Zero-extension is sufficient only after the selected prefix is complete.
            // One additional bit may select a shorter code than the speculative entry.
            bitReader.ensureBits(available + 1);
        }
    }

    private int readBlockLength(int[] tableGroup, int tableIdx) {
        int code = readSymbol(tableGroup, tableIdx);
        int n = BLOCK_LENGTH_N_BITS[code];
        return BLOCK_LENGTH_OFFSET[code] + bitReader.readBits(n);
    }

    private void moveToFront(int[] v, int index) {
        int value = v[index];
        for (; index > 0; index--) {
            v[index] = v[index - 1];
        }
        v[0] = value;
    }

    private void inverseMoveToFrontTransform(byte[] v, int vLen) {
        int[] mtf = new int[256];
        for (int i = 0; i < 256; i++) {
            mtf[i] = i;
        }
        for (int i = 0; i < vLen; i++) {
            int index = v[i] & 0xFF;
            v[i] = (byte) mtf[index];
            if (index != 0) {
                moveToFront(mtf, index);
            }
        }
    }

    private void readHuffmanCodeLengths(int[] codeLengthCodeLengths, int numSymbols, int[] codeLengths) {
        int symbol = 0;
        int prevCodeLen = DEFAULT_CODE_LENGTH;
        int repeat = 0;
        int repeatCodeLen = 0;
        int space = 32768;
        int[] table = new int[32 + 1];
        int tableIdx = table.length - 1;
        buildHuffmanTable(table, tableIdx, 5, codeLengthCodeLengths, CODE_LENGTH_CODES);

        while (symbol < numSymbols && space > 0) {
            int codeLen = readPrefixSymbol(table, 0, 5);
            if (codeLen < CODE_LENGTH_REPEAT_CODE) {
                repeat = 0;
                codeLengths[symbol++] = codeLen;
                if (codeLen != 0) {
                    prevCodeLen = codeLen;
                    space -= 32768 >> codeLen;
                }
            } else {
                int extraBits = codeLen - 14;
                int newLen = 0;
                if (codeLen == CODE_LENGTH_REPEAT_CODE) {
                    newLen = prevCodeLen;
                }
                if (repeatCodeLen != newLen) {
                    repeat = 0;
                    repeatCodeLen = newLen;
                }
                int oldRepeat = repeat;
                if (repeat > 0) {
                    repeat -= 2;
                    repeat <<= extraBits;
                }
                repeat += bitReader.readBits(extraBits) + 3;
                int repeatDelta = repeat - oldRepeat;
                if (symbol + repeatDelta > numSymbols) {
                    throw new BrotliException("symbol + repeatDelta > numSymbols");
                }
                for (int i = 0; i < repeatDelta; i++) {
                    codeLengths[symbol++] = repeatCodeLen;
                }
                if (repeatCodeLen != 0) {
                    space -= repeatDelta << (15 - repeatCodeLen);
                }
            }
        }
        if (space != 0) {
            throw new BrotliException("Unused space");
        }
        Arrays.fill(codeLengths, symbol, numSymbols, 0);
    }

    private void checkDupes(int[] symbols, int length) {
        for (int i = 0; i < length - 1; ++i) {
            for (int j = i + 1; j < length; ++j) {
                if (symbols[i] == symbols[j]) {
                    throw new BrotliException("Duplicate simple Huffman code symbol");
                }
            }
        }
    }

    /**
     * Reads up to 4 symbols directly and applies predefined histograms.
     */
    private int readSimpleHuffmanCode(int alphabetSizeMax, int alphabetSizeLimit, int[] tableGroup, int tableIdx) {
        int[] codeLengths = new int[alphabetSizeLimit];
        int[] symbols = new int[4];
        int maxBits = 1 + log2floor(alphabetSizeMax - 1);
        int numSymbols = bitReader.readBits(2) + 1;
        int histogramId = numSymbols;

        for (int i = 0; i < numSymbols; i++) {
            int symbol = bitReader.readBits(maxBits);
            if (symbol >= alphabetSizeLimit) {
                throw new BrotliException("Can't readHuffmanCode");
            }
            symbols[i] = symbol;
        }
        checkDupes(symbols, numSymbols);
        if (numSymbols == 4) {
            histogramId += bitReader.readBits(1);
        }

        switch (histogramId) {
            case 1 -> codeLengths[symbols[0]] = 1;
            case 2 -> {
                codeLengths[symbols[0]] = 1;
                codeLengths[symbols[1]] = 1;
            }
            case 3 -> {
                codeLengths[symbols[0]] = 1;
                codeLengths[symbols[1]] = 2;
                codeLengths[symbols[2]] = 2;
            }
            case 4 -> {
                codeLengths[symbols[0]] = 2;
                codeLengths[symbols[1]] = 2;
                codeLengths[symbols[2]] = 2;
                codeLengths[symbols[3]] = 2;
            }
            case 5 -> {
                codeLengths[symbols[0]] = 1;
                codeLengths[symbols[1]] = 2;
                codeLengths[symbols[2]] = 3;
                codeLengths[symbols[3]] = 3;
            }
            default -> {
            }
        }
        return buildHuffmanTable(tableGroup, tableIdx, HUFFMAN_TABLE_BITS, codeLengths, alphabetSizeLimit);
    }

    private int readComplexHuffmanCode(int alphabetSizeLimit, int skip, int[] tableGroup, int tableIdx) {
        int[] codeLengths = new int[alphabetSizeLimit];
        int[] codeLengthCodeLengths = new int[CODE_LENGTH_CODES];
        int space = 32;
        int numCodes = 0;
        for (int i = skip; i < CODE_LENGTH_CODES && space > 0; i++) {
            int codeLenIdx = CODE_LENGTH_CODE_ORDER[i];
            int v = readPrefixSymbol(FIXED_TABLE, 0, 4);
            codeLengthCodeLengths[codeLenIdx] = v;
            if (v != 0) {
                space -= (32 >> v);
                numCodes++;
            }
        }
        if (space != 0 && numCodes != 1) {
            throw new BrotliException("Corrupted Huffman code histogram");
        }
        readHuffmanCodeLengths(codeLengthCodeLengths, alphabetSizeLimit, codeLengths);
        return buildHuffmanTable(tableGroup, tableIdx, HUFFMAN_TABLE_BITS, codeLengths, alphabetSizeLimit);
    }

    /**
     * Decodes Huffman table from bit-stream.
     *
     * @return number of slots used by resulting Huffman table
     */
    private int readHuffmanCode(int alphabetSizeMax, int alphabetSizeLimit, int[] tableGroup, int tableIdx) {
        int simpleCodeOrSkip = bitReader.readBits(2);
        if (simpleCodeOrSkip == 1) {
            return readSimpleHuffmanCode(alphabetSizeMax, alphabetSizeLimit, tableGroup, tableIdx);
        } else {
            return readComplexHuffmanCode(alphabetSizeLimit, simpleCodeOrSkip, tableGroup, tableIdx);
        }
    }

    private int decodeContextMap(int contextMapSize, byte[] contextMap) {
        int numTrees = decodeVarLenUnsignedByte() + 1;

        if (numTrees == 1) {
            Arrays.fill(contextMap, 0, contextMapSize, (byte) 0);
            return numTrees;
        }
        int useRleForZeros = bitReader.readBits(1);
        int maxRunLengthPrefix = 0;
        if (useRleForZeros != 0) {
            maxRunLengthPrefix = bitReader.readBits(4) + 1;
        }
        int alphabetSize = numTrees + maxRunLengthPrefix;
        int tableSize = Context.MAX_HUFFMAN_TABLE_SIZE[(alphabetSize + 31) >> 5];
        int[] table = new int[tableSize + 1];
        int tableIdx = table.length - 1;
        readHuffmanCode(alphabetSize, alphabetSize, table, tableIdx);
        for (int i = 0; i < contextMapSize;) {
            int code = readSymbol(table, tableIdx);
            if (code == 0) {
                contextMap[i] = 0;
                i++;
            } else if (code <= maxRunLengthPrefix) {
                int reps = (1 << code) + bitReader.readBits(code);
                while (reps != 0) {
                    if (i >= contextMapSize) {
                        throw new BrotliException("Corrupted context map");
                    }
                    contextMap[i] = 0;
                    i++;
                    reps--;
                }
            } else {
                contextMap[i] = (byte) (code - maxRunLengthPrefix);
                i++;
            }
        }
        if (bitReader.readBits(1) == 1) {
            inverseMoveToFrontTransform(contextMap, contextMapSize);
        }
        return numTrees;
    }

    private int decodeBlockTypeAndLength(int treeType, int numBlockTypes) {
        final int[] ringBuffers = rings;
        final int offset = 4 + treeType * 2;
        int blockType = readSymbol(blockTrees, 2 * treeType);
        int result = readBlockLength(blockTrees, 2 * treeType + 1);

        if (blockType == 1) {
            blockType = ringBuffers[offset + 1] + 1;
        } else if (blockType == 0) {
            blockType = ringBuffers[offset];
        } else {
            blockType -= 2;
        }
        if (blockType >= numBlockTypes) {
            blockType -= numBlockTypes;
        }
        ringBuffers[offset] = ringBuffers[offset + 1];
        ringBuffers[offset + 1] = blockType;
        return result;
    }

    private void decodeLiteralBlockSwitch() {
        literalBlockLength = decodeBlockTypeAndLength(0, numLiteralBlockTypes);
        int literalBlockType = rings[5];
        contextMapSlice = literalBlockType << LITERAL_CONTEXT_BITS;
        literalTreeIdx = contextMap[contextMapSlice] & 0xFF;
        int contextMode = contextModes[literalBlockType];
        contextLookupOffset1 = contextMode << 9;
        contextLookupOffset2 = contextLookupOffset1 + 256;
    }

    private void decodeCommandBlockSwitch() {
        commandBlockLength = decodeBlockTypeAndLength(1, numCommandBlockTypes);
        commandTreeIdx = rings[7];
    }

    private void decodeDistanceBlockSwitch() {
        distanceBlockLength = decodeBlockTypeAndLength(2, numDistanceBlockTypes);
        distContextMapSlice = rings[9] << DISTANCE_CONTEXT_BITS;
    }

    private void reallocateRingBuffer() {
        int newSize = maxRingBufferSize;
        if (newSize > expectedTotalSize) {
            int minimalNewSize = expectedTotalSize;
            while ((newSize >> 1) > minimalNewSize) {
                newSize >>= 1;
            }
            if ((inputEnd == 0) && newSize < 16384 && maxRingBufferSize >= 16384) {
                newSize = 16384;
            }
        }
        if (newSize <= ringBufferSize) {
            return;
        }
        int ringBufferSizeWithSlack = newSize + MAX_TRANSFORMED_WORD_LENGTH;
        byte[] newBuffer = new byte[ringBufferSizeWithSlack];
        if (ringBuffer.length != 0) {
            System.arraycopy(ringBuffer, 0, newBuffer, 0, ringBufferSize);
        }
        ringBuffer = newBuffer;
        ringBufferSize = newSize;
    }

    private void readNextMetablockHeader() {
        if (inputEnd != 0) {
            nextRunningState = FINISHED;
            runningState = INIT_WRITE;
            return;
        }
        literalTreeGroup = new int[0];
        commandTreeGroup = new int[0];
        distanceTreeGroup = new int[0];
        decodeMetaBlockLength();
        if ((metaBlockLength == 0) && !isMetadata) {
            return;
        }
        if (isUncompressed || isMetadata) {
            bitReader.jumpToByteBoundary();
            runningState = isMetadata ? READ_METADATA : COPY_UNCOMPRESSED;
        } else {
            runningState = COMPRESSED_BLOCK_START;
        }

        if (isMetadata) {
            return;
        }
        expectedTotalSize += metaBlockLength;
        if (expectedTotalSize > 1 << 30) {
            expectedTotalSize = 1 << 30;
        }
        if (ringBufferSize < maxRingBufferSize) {
            reallocateRingBuffer();
        }
    }

    private int readMetablockPartition(int treeType, int numBlockTypes) {
        int offset = blockTrees[2 * treeType];
        if (numBlockTypes <= 1) {
            blockTrees[2 * treeType + 1] = offset;
            blockTrees[2 * treeType + 2] = offset;
            return 1 << 28;
        }

        int blockTypeAlphabetSize = numBlockTypes + 2;
        offset += readHuffmanCode(blockTypeAlphabetSize, blockTypeAlphabetSize, blockTrees, 2 * treeType);
        blockTrees[2 * treeType + 1] = offset;

        int blockLengthAlphabetSize = NUM_BLOCK_LENGTH_CODES;
        offset += readHuffmanCode(blockLengthAlphabetSize, blockLengthAlphabetSize, blockTrees, 2 * treeType + 1);
        blockTrees[2 * treeType + 2] = offset;

        return readBlockLength(blockTrees, 2 * treeType + 1);
    }

    private void calculateDistanceLut(int alphabetSizeLimit) {
        int postfix = 1 << distancePostfixBits;
        int bits = 1;
        int half = 0;
        int i = NUM_DISTANCE_SHORT_CODES;

        /* Fill direct codes. */
        for (int j = 0; j < numDirectDistanceCodes; ++j) {
            distExtraBits[i] = 0;
            distOffset[i] = j + 1;
            ++i;
        }

        /* Fill regular distance codes. */
        while (i < alphabetSizeLimit) {
            int base = numDirectDistanceCodes + ((((2 + half) << bits) - 4) << distancePostfixBits) + 1;
            /* Always fill the complete group. */
            for (int j = 0; j < postfix; ++j) {
                distExtraBits[i] = (byte) bits;
                distOffset[i] = base + j;
                ++i;
            }
            bits = bits + half;
            half = half ^ 1;
        }
    }

    private void readMetablockHuffmanCodesAndContextMaps() {
        numLiteralBlockTypes = decodeVarLenUnsignedByte() + 1;
        literalBlockLength = readMetablockPartition(0, numLiteralBlockTypes);
        numCommandBlockTypes = decodeVarLenUnsignedByte() + 1;
        commandBlockLength = readMetablockPartition(1, numCommandBlockTypes);
        numDistanceBlockTypes = decodeVarLenUnsignedByte() + 1;
        distanceBlockLength = readMetablockPartition(2, numDistanceBlockTypes);
        distancePostfixBits = bitReader.readBits(2);
        numDirectDistanceCodes = bitReader.readBits(4) << distancePostfixBits;
        contextModes = new byte[numLiteralBlockTypes];
        for (int i = 0; i < numLiteralBlockTypes;) {
            int limit = Math.min(i + 96, numLiteralBlockTypes);
            for (; i < limit; ++i) {
                contextModes[i] = (byte) bitReader.readBits(2);
            }
        }

        contextMap = new byte[numLiteralBlockTypes << LITERAL_CONTEXT_BITS];
        int numLiteralTrees = decodeContextMap(numLiteralBlockTypes << LITERAL_CONTEXT_BITS, contextMap);
        trivialLiteralContext = 1;
        for (int j = 0; j < numLiteralBlockTypes << LITERAL_CONTEXT_BITS; j++) {
            if (contextMap[j] != j >> LITERAL_CONTEXT_BITS) {
                trivialLiteralContext = 0;
                break;
            }
        }

        distContextMap = new byte[numDistanceBlockTypes << DISTANCE_CONTEXT_BITS];
        int numDistTrees = decodeContextMap(numDistanceBlockTypes << DISTANCE_CONTEXT_BITS, distContextMap);

        literalTreeGroup = decodeHuffmanTreeGroup(NUM_LITERAL_CODES, NUM_LITERAL_CODES,
            numLiteralTrees);
        commandTreeGroup = decodeHuffmanTreeGroup(NUM_COMMAND_CODES, NUM_COMMAND_CODES,
            numCommandBlockTypes);
        int distanceAlphabetSizeMax = calculateDistanceAlphabetSize(
                distancePostfixBits,
                numDirectDistanceCodes,
                MAX_DISTANCE_BITS);
        int distanceAlphabetSizeLimit = distanceAlphabetSizeMax;
        if (isLargeWindow) {
            distanceAlphabetSizeMax = calculateDistanceAlphabetSize(distancePostfixBits,
                    numDirectDistanceCodes,
                    MAX_LARGE_WINDOW_DISTANCE_BITS);
            distanceAlphabetSizeLimit = calculateDistanceAlphabetLimit(distancePostfixBits, numDirectDistanceCodes);
        }
        distanceTreeGroup = decodeHuffmanTreeGroup(distanceAlphabetSizeMax, distanceAlphabetSizeLimit,
            numDistTrees);
        calculateDistanceLut(distanceAlphabetSizeLimit);

        contextMapSlice = 0;
        distContextMapSlice = 0;
        contextLookupOffset1 = contextModes[0] * 512;
        contextLookupOffset2 = contextLookupOffset1 + 256;
        literalTreeIdx = 0;
        commandTreeIdx = 0;

        rings[4] = 1;
        rings[5] = 0;
        rings[6] = 1;
        rings[7] = 0;
        rings[8] = 1;
        rings[9] = 0;
    }

    private void copyUncompressedData() {
        if (metaBlockLength <= 0) {
            runningState = BLOCK_START;
            return;
        }
        int chunkLength = Math.min(fence - pos, metaBlockLength);
        chunkLength = bitReader.copyRawBytes(ringBuffer, pos, chunkLength);
        metaBlockLength -= chunkLength;
        pos += chunkLength;
        nextRunningState = metaBlockLength == 0 ? BLOCK_START : COPY_UNCOMPRESSED;
        runningState = INIT_WRITE;
    }

    private int writeRingBuffer() {
        int toWrite = Math.min(outputLength - outputUsed,
            ringBufferBytesReady - ringBufferBytesWritten);
        if (toWrite != 0) {
            System.arraycopy(ringBuffer, ringBufferBytesWritten, output,
                outputOffset + outputUsed, toWrite);
            outputUsed += toWrite;
            ringBufferBytesWritten += toWrite;
        }
        if (outputUsed == 0) {
            return 1;
        } else {
            return 0;
        }
    }

    private int[] decodeHuffmanTreeGroup(int alphabetSizeMax, int alphabetSizeLimit, int n) {
        int maxTableSize = Context.MAX_HUFFMAN_TABLE_SIZE[(alphabetSizeLimit + 31) >> 5];
        int[] group = new int[n + n * maxTableSize];
        int next = n;
        for (int i = 0; i < n; ++i) {
            group[i] = next;
            next += readHuffmanCode(alphabetSizeMax, alphabetSizeLimit, group, i);
        }
        return group;
    }

    private void doUseDictionary(int fence) {
        if (distance > MAX_ALLOWED_DISTANCE) {
            throw new BrotliException("Invalid backward reference");
        }
        int address = distance - maxDistance - 1 - cdTotalSize;
        if (address < 0) {
            initializeCompoundDictionaryCopy(-address - 1, copyLength);
            runningState = COPY_FROM_COMPOUND_DICTIONARY;
        } else {
            var dictionary = Dictionary.instance();
            ByteBuffer dictionaryData = dictionary.data();
            int wordLength = copyLength;
            if (wordLength > Dictionary.MAX_DICTIONARY_WORD_LENGTH) {
                throw new BrotliException("Invalid backward reference");
            }
            int shift = dictionary.sizeBits(wordLength);
            if (shift == 0) {
                throw new BrotliException("Invalid backward reference");
            }
            int offset = dictionary.offset(wordLength);
            int mask = (1 << shift) - 1;
            int wordIdx = address & mask;
            int transformIdx = address >>> shift;
            offset += wordIdx * wordLength;
            Transform transform = Transform.rfcTransform();
            if (transformIdx >= transform.numTransforms()) {
                throw new BrotliException("Invalid backward reference");
            }
            int len = transform.transformDictionaryWord(ringBuffer, pos, dictionaryData, offset, wordLength, transformIdx);
            if (len > metaBlockLength) {
                throw new BrotliException("Invalid metablock length");
            }
            pos += len;
            metaBlockLength -= len;
            if (pos >= fence) {
                nextRunningState = MAIN_LOOP;
                runningState = INIT_WRITE;
                return;
            }
            runningState = MAIN_LOOP;
        }
    }

    private void initializeCompoundDictionary() {
        cdBlockMap = new byte[1 << CD_BLOCK_MAP_BITS];
        int blockBits = CD_BLOCK_MAP_BITS;
        while (((cdTotalSize - 1) >>> blockBits) != 0) {
            blockBits++;
        }
        blockBits -= CD_BLOCK_MAP_BITS;
        cdBlockBits = blockBits;
        int cursor = 0;
        int index = 0;
        while (cursor < cdTotalSize) {
            while (cdChunkOffsets[index + 1] < cursor) {
                index++;
            }
            cdBlockMap[cursor >>> blockBits] = (byte) index;
            cursor += 1 << blockBits;
        }
    }

    private void initializeCompoundDictionaryCopy(int address, int length) {
        if (address < 0 || address >= cdTotalSize) {
            throw new BrotliException("Invalid backward reference");
        }
        if (length > cdTotalSize - address) {
            throw new BrotliException("Attached dictionary reference extends beyond the dictionary");
        }
        if (length > metaBlockLength) {
            throw new BrotliException("Invalid metablock length");
        }
        if (cdBlockBits == -1) {
            initializeCompoundDictionary();
        }
        int index = cdBlockMap[address >>> cdBlockBits];
        while (address >= cdChunkOffsets[index + 1]) {
            index++;
        }
        distRbIdx = (distRbIdx + 1) & 0x3;
        rings[distRbIdx] = distance;
        metaBlockLength -= length;
        cdBrIndex = index;
        cdBrOffset = address - cdChunkOffsets[index];
        cdBrLength = length;
        cdBrCopied = 0;
    }

    private void copyFromCompoundDictionary(int fence) {
        while (cdBrLength != cdBrCopied) {
            int space = fence - pos;
            int chunkLength = cdChunkOffsets[cdBrIndex + 1] - cdChunkOffsets[cdBrIndex];
            int remChunkLength = chunkLength - cdBrOffset;
            int length = cdBrLength - cdBrCopied;
            if (length > remChunkLength) {
                length = remChunkLength;
            }
            if (length > space) {
                length = space;
            }
            System.arraycopy(cdChunks[cdBrIndex], cdBrOffset, ringBuffer, pos, length);
            pos += length;
            cdBrOffset += length;
            cdBrCopied += length;
            if (length == remChunkLength) {
                cdBrIndex++;
                cdBrOffset = 0;
            }
            if (pos >= fence) {
                break;
            }
        }
    }

    private void readMetadata() {
        while (metaBlockLength > 0) {
            bitReader.readBits(8);
            metaBlockLength--;
        }
        runningState = BLOCK_START;
    }

    private void write() {
        if (pos >= maxBackwardDistance) {
            maxDistance = maxBackwardDistance;
        }
        if (pos >= ringBufferSize) {
            if (pos > ringBufferSize) {
                System.arraycopy(ringBuffer, ringBufferSize, ringBuffer, 0, pos - ringBufferSize);
            }
            pos &= ringBufferMask;
            ringBufferBytesWritten = 0;
        }
        updateFence();
        runningState = nextRunningState;
    }

    private void updateFence() {
        fence = ringBufferBytesWritten + Math.min(outputLength - outputUsed,
                                                 ringBufferSize - ringBufferBytesWritten);
    }

    private boolean writeBeforeInput() {
        if (pos > ringBufferBytesWritten && bitReader.bufferedBits() < MAX_ATOMIC_INPUT_BITS) {
            nextRunningState = runningState;
            runningState = INIT_WRITE;
            return true;
        }
        return false;
    }

    private void copyLoop() {
        int available = fence - pos;
        if (available <= 0) {
            nextRunningState = COPY_LOOP;
            runningState = INIT_WRITE;
            return;
        }
        int src = (pos - distance) & ringBufferMask;
        int dst = pos;
        // A small caller read must not expand the complete copy command before returning.
        int remaining = Math.min(copyLength - j, available);
        int srcEnd = src + remaining;
        int dstEnd = dst + remaining;
        if ((srcEnd < ringBufferMask) && (dstEnd < ringBufferMask)) {
            if (remaining < 12 || (srcEnd > dst && dstEnd > src)) {
                int k = 0;
                for (; k + 4 <= remaining; k += 4) {
                    ringBuffer[dst++] = ringBuffer[src++];
                    ringBuffer[dst++] = ringBuffer[src++];
                    ringBuffer[dst++] = ringBuffer[src++];
                    ringBuffer[dst++] = ringBuffer[src++];
                }
                for (; k < remaining; k++) {
                    ringBuffer[dst++] = ringBuffer[src++];
                }
            } else {
                System.arraycopy(ringBuffer, src, ringBuffer, dst, srcEnd - src);
            }
            j += remaining;
            metaBlockLength -= remaining;
            pos += remaining;
            if (pos >= fence) {
                nextRunningState = j == copyLength ? MAIN_LOOP : COPY_LOOP;
                runningState = INIT_WRITE;
            }
        } else {
            while (j < copyLength) {
                ringBuffer[pos] = ringBuffer[(pos - distance) & ringBufferMask];
                metaBlockLength--;
                pos++;
                j++;
                if (pos >= fence) {
                    nextRunningState = j == copyLength ? MAIN_LOOP : COPY_LOOP;
                    runningState = INIT_WRITE;
                    break;
                }
            }
        }
        if (runningState == COPY_LOOP) {
            runningState = MAIN_LOOP;
        }
    }

    private void insertLoop() {
        if (trivialLiteralContext != 0) {
            while (j < insertLength) {
                if (writeBeforeInput()) {
                    return;
                }
                if (literalBlockLength == 0) {
                    decodeLiteralBlockSwitch();
                }
                literalBlockLength--;
                ringBuffer[pos] = (byte) readSymbol(literalTreeGroup, literalTreeIdx);
                pos++;
                j++;
                if (pos >= fence) {
                    nextRunningState = INSERT_LOOP;
                    runningState = INIT_WRITE;
                    break;
                }
            }
        } else {
            int prevByte1 = ringBuffer[(pos - 1) & ringBufferMask] & 0xFF;
            int prevByte2 = ringBuffer[(pos - 2) & ringBufferMask] & 0xFF;
            while (j < insertLength) {
                if (writeBeforeInput()) {
                    return;
                }
                if (literalBlockLength == 0) {
                    decodeLiteralBlockSwitch();
                }
                int literalContext = Context.LOOKUP[contextLookupOffset1 + prevByte1]
                        | Context.LOOKUP[contextLookupOffset2 + prevByte2];
                int literalTreeIdx = contextMap[contextMapSlice + literalContext] & 0xFF;
                literalBlockLength--;
                prevByte2 = prevByte1;
                prevByte1 = readSymbol(literalTreeGroup, literalTreeIdx);
                ringBuffer[pos] = (byte) prevByte1;
                pos++;
                j++;
                if (pos >= fence) {
                    nextRunningState = INSERT_LOOP;
                    runningState = INIT_WRITE;
                    break;
                }
            }
        }
        if (runningState != INSERT_LOOP) {
            return;
        }
        if (writeBeforeInput()) {
            return;
        }
        metaBlockLength -= insertLength;
        if (metaBlockLength <= 0) {
            runningState = MAIN_LOOP;
            return;
        }
        if (distanceCode < 0) {
            distance = rings[distRbIdx];
        } else {
            if (distanceBlockLength == 0) {
                decodeDistanceBlockSwitch();
            }
            distanceBlockLength--;
            int distTreeIdx = distContextMap[distContextMapSlice + distanceCode] & 0xFF;
            distanceCode = readSymbol(distanceTreeGroup, distTreeIdx);
            if (distanceCode < NUM_DISTANCE_SHORT_CODES) {
                int index = (distRbIdx + DISTANCE_SHORT_CODE_INDEX_OFFSET[distanceCode]) & 0x3;
                distance = rings[index] + DISTANCE_SHORT_CODE_VALUE_OFFSET[distanceCode];
                if (distance <= 0) {
                    throw new BrotliException("Invalid backward distance: " + distance);
                }
            } else {
                int extraBits = distExtraBits[distanceCode];
                int bits = bitReader.readBits(extraBits);
                distance = distOffset[distanceCode] + (bits << distancePostfixBits);
            }
        }
        if (maxDistance != maxBackwardDistance && pos < maxBackwardDistance) {
            maxDistance = pos;
        } else {
            maxDistance = maxBackwardDistance;
        }
        if (distance > maxDistance) {
            runningState = USE_DICTIONARY;
            return;
        }
        if (distanceCode > 0) {
            distRbIdx = (distRbIdx + 1) & 0x3;
            rings[distRbIdx] = distance;
        }
        if (copyLength > metaBlockLength) {
            throw new BrotliException("Invalid backward reference");
        }
        j = 0;
        runningState = COPY_LOOP;
    }

    private void mainLoop() {
        if (commandBlockLength == 0) {
            decodeCommandBlockSwitch();
        }
        commandBlockLength--;
        int cmdCode = readSymbol(commandTreeGroup, commandTreeIdx) << 2;
        short insertAndCopyExtraBits = CMD_LOOKUP[cmdCode];
        int insertLengthOffset = CMD_LOOKUP[cmdCode + 1];
        int copyLengthOffset = CMD_LOOKUP[cmdCode + 2];
        distanceCode = CMD_LOOKUP[cmdCode + 3];
        int insertLengthExtraBits = insertAndCopyExtraBits & 0xFF;
        insertLength = insertLengthOffset + bitReader.readBits(insertLengthExtraBits);
        int copyLengthExtraBits = insertAndCopyExtraBits >> 8;
        copyLength = copyLengthOffset + bitReader.readBits(copyLengthExtraBits);
        j = 0;
        runningState = INSERT_LOOP;
    }

    private void blockStart() {
        if (metaBlockLength < 0) {
            throw new BrotliException("Invalid metablock length");
        }
        if (pos > ringBufferBytesWritten) {
            nextRunningState = BLOCK_START;
            runningState = INIT_WRITE;
            return;
        }
        readNextMetablockHeader();
        updateFence();
        ringBufferMask = ringBufferSize - 1;
    }

    private int log2floor(int i) {
        int result = -1;
        int step = 16;
        while (step > 0) {
            if ((i >>> step) != 0) {
                result += step;
                i = i >>> step;
            }
            step = step >> 1;
        }
        return result + i;
    }

    enum RunningState {
        INITIALIZED,
        BLOCK_START,
        COMPRESSED_BLOCK_START,
        MAIN_LOOP,
        READ_METADATA,
        COPY_UNCOMPRESSED,
        INSERT_LOOP,
        COPY_LOOP,
        USE_DICTIONARY,
        FINISHED,
        CLOSED,
        INIT_WRITE,
        WRITE,
        COPY_FROM_COMPOUND_DICTIONARY
    }
}
