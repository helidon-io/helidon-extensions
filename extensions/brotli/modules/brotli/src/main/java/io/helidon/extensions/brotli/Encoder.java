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

package io.helidon.extensions.brotli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;

import static io.helidon.extensions.brotli.Context.LOG2_TABLE;
import static io.helidon.extensions.brotli.Encoder.BrotliEncoderCompressState.EMIT_COMMANDS;
import static io.helidon.extensions.brotli.Encoder.BrotliEncoderCompressState.EMIT_REMAINDER;
import static io.helidon.extensions.brotli.Encoder.BrotliEncoderCompressState.FOR;
import static io.helidon.extensions.brotli.Encoder.BrotliEncoderCompressState.NEXT_BLOCK;
import static io.helidon.extensions.brotli.Encoder.BrotliEncoderCompressState.TRAWL;

class Encoder {
    /**
     * First quality level for high speed process with low compression ratio.
     */
    private static final int QUALITY_LEVEL_0 = 0;
    private static final int BROTLI_WINDOW_GAP = 16;
    private static final long MAX_DISTANCE = 262128;
    private static final int HASH_MUL_32 = 0x1E35A7BD;
    private static final int FIRST_BLOCK_SIZE = 98304;

    private final BitBuffer output;
    private final BitBuffer storage;
    private final OutputStreamManager outputManager;
    private final int quality;
    private final int window;

    private Buffer cmdDepth;
    private Buffer cmdBits;
    private BitBuffer cmdCode;
    private Buffer smallTable;
    private ByteInput inputBuffer;
    private Buffer table;
    private Buffer largeTable;
    private int lastBytes;
    private int lastBytesBits;
    private int[] cmdHisto;
    private int[] histogram;
    private HuffmanTree literalTree;
    private HuffmanTree commandTree;
    private Buffer commandDepth;
    private Buffer commandBits;
    private int input;
    private int ip;
    private int ipEnd;
    private int baseIp;
    private int nextEmit;
    private int metaBlockStart;
    private int blockSize;
    private int totalBlockSize;
    private int lenStorageIx;
    private Buffer litDepth;
    private Buffer litBits;
    private int ipLimit;
    private int nextHash;
    private int nextIp;
    private int skip;
    private int candidate;
    private int lastDistance;
    private int inputSize;
    private int literalRatio;
    private BrotliEncoderCompressState compressState;
    private boolean isInitialized;
    private boolean eof;
    private boolean started;

    Encoder(BrotliOutputStreamConfig builder) {
        output = new BitBuffer(builder.cacheInputSize());
        quality = builder.quality();
        window = builder.window();
        outputManager = new OutputStreamManager(builder);
        storage = new BitBuffer(0);
        isInitialized = false;
        eof = false;
    }

    /**
     * Compress the data contained in internal buffer.
     *
     * @param input input data
     */
    void compress(ByteInput input) {
        if (input == null) {
            return;
        }
        inputBuffer = input;
        inputBuffer.position(0);
        checkInitialization();
        if (quality == QUALITY_LEVEL_0) {
            brotliCompressStreamFast(eof);
            started = true;
        }
    }

    void close(ByteInput input) throws IOException {
        if (eof) {
            return;
        }
        eof = true;
        compress(input);
        outputManager.close();
    }

    private static int hashBytesAtOffset(long v, int offset, int shift) {
        long h = ((v >> (8 * offset)) << 24) * HASH_MUL_32;
        return (int) (h >>> shift);
    }

    /**
     * Check that state is properly initialized.
     */
    private void checkInitialization() {
        if (!isInitialized) {
            encodeWindowBit();
            isInitialized = true;
        }
        if (cmdDepth == null && inputBuffer.remaining() != 0) {
            cmdDepth = new Buffer(128);
            cmdBits = new Buffer(128);
            cmdCode = new BitBuffer(512);
            smallTable = new Buffer(1024);
            largeTable = new Buffer();
            cmdHisto = new int[128];
            histogram = new int[256];
            litDepth = new Buffer(256);
            litBits = new Buffer(256);
            literalTree = new HuffmanTree(513);
            initCommandPrefixCodes();
        }
    }

    private void initCommandPrefixCodes() {
        cmdDepth.writeArray(Context.DEFAULT_COMMAND_DEPTHS, 0, Context.DEFAULT_COMMAND_DEPTHS.length, 0);
        cmdBits.writeArray(Context.DEFAULT_COMMAND_BITS, 0, Context.DEFAULT_COMMAND_BITS.length, 0);
        cmdCode.writeArray(Context.DEFAULT_COMMAND_CODE, 0, Context.DEFAULT_COMMAND_CODE.length, 0);
        cmdCode.bitIx(Context.DEFAULT_COMMAND_CODE_BITS);
    }

    /**
     * Encode window bits into brotli stream.
     *
     */
    private void encodeWindowBit() {
        if (window == 16) {
            lastBytes = 0;
            lastBytesBits = 1;
        } else if (window == 17) {
            lastBytes = 1;
            lastBytesBits = 7;
        } else if (window > 17) {
            lastBytes = ((window - 17) << 1) | 0x01;
            lastBytesBits = 4;
        } else {
            lastBytes = ((window - 8) << 4) | 0x01;
            lastBytesBits = 7;
        }
    }

    /**
     * Choose which hash table will be used for compression.
     *
     * @param inputSize    Input data size.
     */
    private void getHashTable(int inputSize) {
        int maxTableSize = 32768;
        int size = hashTableSize(maxTableSize, inputSize);
        if (quality == QUALITY_LEVEL_0) {
            if ((size & 0xAAAAA) == 0) {
                size <<= 1;
            }
        }
        if (size <= smallTable.capacity()) {
            table = smallTable;
        } else {
            if (size > largeTable.capacity()) {
                largeTable = new Buffer(size);
            }
            table = largeTable;
        }
        table.clear();
    }

    private int hashTableSize(int maxTableSize, int inputSize) {
        int size = 256;
        while (size < maxTableSize && size < inputSize) {
            size <<= 1;
        }
        return size;
    }

    /**
     * Write a meta-block header into Brotli stream.
     *
     * @param length                Meta-block length.
     * @param isUncompressed        Is the data compressed into this block.
     */
    private void storeMetaBlockHeader(int length, boolean isUncompressed) {
        int nibbles = 6;
        storage.writeBit(1, 0);
        if (length <= 65536) {
            nibbles = 4;
        } else if (length <= 1048576) {
            nibbles = 5;
        }
        storage.writeBit(2, nibbles - 4);
        storage.writeBit(nibbles * 4, length - 1);
        storage.writeBit(isUncompressed);
    }

    /**
     * Builds a literal prefix code into "depths" and "bits" based on the statistics
     * of the "input" string and stores it into the bit stream.
     *
     * @param input                 Source data to build prefix code from.
     * @param index                 Index into input buffer.
     * @param inputSize             Input size.
     * @param depths                Table containing depths from Huffman tree (prefix code length).
     * @param bits                  Table containing bits of prefix codes.
     * @return Returns the literal ratio for the given input.
     */
    private int buildAndStoreLiteralPrefixCode(ByteInput input, int index, int inputSize, Buffer depths, Buffer bits) {
        Arrays.fill(histogram, 0);
        int histogramTotal;
        int i;

        if (inputSize < 32768) {
            for (i = 0; i < inputSize; ++i) {
                ++histogram[input.get(index + i)];
            }
            histogramTotal = inputSize;
            for (i = 0; i < 256; ++i) {
                int adjust = 2 * Math.min(histogram[i], 11);
                histogram[i] += adjust;
                histogramTotal += adjust;
            }
        } else {
            int kSampleRate = 29;
            for (i = 0; i < inputSize; i += kSampleRate) {
                ++histogram[input.get(index + i)];
            }
            histogramTotal = (inputSize + kSampleRate - 1) / kSampleRate;
            for (i = 0; i < 256; ++i) {
                int adjust = 1 + 2 * Math.min(histogram[i], 11);
                histogram[i] += adjust;
                histogramTotal += adjust;
            }
        }
        literalTree.buildAndStoreHuffmanTree(storage, histogram, histogramTotal, depths, bits);
        int literalRatio = 0;
        for (i = 0; i < 256; ++i) {
            if (histogram[i] != 0) {
                literalRatio += histogram[i] * depths.get(i);
            }
        }
        return (literalRatio * 125) / histogramTotal;
    }

    /**
     * Write the insert length code into the brotli stream.
     *
     * @param insertLen         Insert length value.
     * @param depth             Length of the prefix code.
     * @param bits              Actual prefix code bits.
     * @param histo             Histogram of command.
     */
    private void emitInsertLen(int insertLen, Buffer depth, Buffer bits, int[] histo) {
        if (insertLen < 6) {
            int code = insertLen + 40;
            storage.writeBit(depth.get(code), bits.get(code));
            ++histo[code];
        } else if (insertLen < 130) {
            int tail = insertLen - 2;
            int nBits = log2FloorNonZero(tail) - 1;
            int prefix = tail >> nBits;
            int insCode = (nBits << 1) + prefix + 42;
            storage.writeBit(depth.get(insCode), bits.get(insCode));
            storage.writeBit(nBits, (tail - (prefix << nBits)));
            ++histo[insCode];
        } else if (insertLen < 2114) {
            int tail = insertLen - 66;
            int nBits = log2FloorNonZero(tail);
            int code = nBits + 50;
            storage.writeBit(depth.get(code), bits.get(code));
            storage.writeBit(nBits, (tail - (1 << nBits)));
            ++histo[code];
        } else {
            storage.writeBit(depth.get(61), bits.get(61));
            storage.writeBit(12, (insertLen - 2114));
            ++histo[61];
        }
    }

    /**
     * Defines if encoder should emit uncompressed data.
     *
     * @param metaBlockStart    Starting point of the meta-block.
     * @param nextEmit          Pointer to the next byte not covered by a copy.
     * @param insertLen         Insert length value.
     * @param literalRatio     Literal ratio for current block.
     * @return true if the data should be uncompressed, false otherwise.
     */
    private boolean shouldUseUncompressedMode(int metaBlockStart, int nextEmit, int insertLen, int literalRatio) {
        int compressed = nextEmit - metaBlockStart;
        if ((compressed * 50) > insertLen) {
            return false;
        } else {
            return literalRatio > 980;
        }
    }

    /**
     * Emit an uncompressed meta-block into the brotli stream.
     *
     * @param begin      Pointer to the beginning of the block.
     * @param end        Pointer to the end of the block.
     * @param storageIx  Pointer to the starting bit of the block.
     */
    private void emitUncompressedMetaBlock(int begin, int end, int storageIx) {
        int len = end - begin;
        rewindBitPosition(storageIx);
        storeMetaBlockHeader(len, true);
        int storageBit = (storage.bitIx() + 7) & ~7;
        storage.writeBuffer(inputBuffer, begin, len, storageBit >> 3);
        storageBit += len << 3;
        storage.bitIx(storageBit);
        storage.set(storageBit >> 3, 0);
    }

    /**
     * Moves the storage pointer to the new destination.
     *
     * @param storageIx    Pointer destination.
     */
    private void rewindBitPosition(int storageIx) {
        int bitPos = storageIx & 7;
        int mask = (1 << bitPos) - 1;
        storage.applyMask(storageIx >> 3, mask);
        storage.bitIx(storageIx);
    }

    /**
     * Write the long insert length code into the brotli stream.
     *
     * @param insertLen         Insert length value.
     * @param depth             Length of the prefix code.
     * @param bits              Actual prefix code bits.
     * @param histo             Histogram of command.
     */
    private void emitLongInsertLen(int insertLen, Buffer depth, Buffer bits, int[] histo) {
        if (insertLen < 22594) {
            storage.writeBit(depth.get(62), bits.get(62));
            storage.writeBit(14, (insertLen - 6210));
            ++histo[62];
        } else {
            storage.writeBit(depth.get(63), bits.get(63));
            storage.writeBit(24, (insertLen - 22594));
            ++histo[63];
        }
    }

    /**
     * Emit literal bytes into the brotli stream.
     *
     * @param startIx       Offset into input data buffer.
     * @param input             Input data buffer.
     * @param len               Length to be emitted.
     * @param depths            Table containing length of the prefix code.
     * @param bits              Table containing prefix code bits.
     */
    private void emitLiterals(int startIx, ByteInput input, int len, Buffer depths, Buffer bits) {
        byte[] array = input.array();
        for (int j = startIx; j < (startIx + len); j++) {
            int lit = array[j] & 0xFF;
            storage.writeBit(depths.get(lit), bits.get(lit));
        }
    }

    /**
     * Write distance prefix code into brotli stream.
     *
     * @param distance          Distance value.
     * @param depth             Length of distance prefix code.
     * @param bits              Prefix code value.
     * @param histo             Command histogram.
     */
    private void emitDistance(int distance, Buffer depth, Buffer bits, int[] histo) {
        int d = distance + 3;
        int nBits = log2FloorNonZero(d) - 1;
        int prefix = (d >> nBits) & 1;
        int offset = (2 + prefix) << nBits;
        int distCode = 2 * (nBits - 1) + prefix + 80;
        storage.writeBit(depth.get(distCode), bits.get(distCode));
        storage.writeBit(nBits, (d - offset));
        ++histo[distCode];
    }

    private void emitCopyLenLastDistance(int copyLen, Buffer depth, Buffer bits, int[] histo) {
        if (copyLen < 12) {
            storage.writeBit(depth.get(copyLen - 4), bits.get(copyLen - 4));
            ++histo[copyLen - 4];
        } else if (copyLen < 72) {
            int tail = copyLen - 8;
            int nBits = log2FloorNonZero(tail) - 1;
            int prefix = tail >> nBits;
            int code = (nBits << 1) + prefix + 4;
            storage.writeBit(depth.get(code), bits.get(code));
            storage.writeBit(nBits, (tail - (prefix << nBits)));
            ++histo[code];
        } else if (copyLen < 136) {
            int tail = copyLen - 8;
            int code = (tail >> 5) + 30;
            storage.writeBit(depth.get(code), bits.get(code));
            storage.writeBit(5, (tail & 31));
            storage.writeBit(depth.get(64), bits.get(64));
            ++histo[code];
            ++histo[64];
        } else if (copyLen < 2120) {
            int tail = copyLen - 72;
            int nBits = log2FloorNonZero(tail);
            int code = nBits + 28;
            storage.writeBit(depth.get(code), bits.get(code));
            storage.writeBit(nBits, (tail - (1 << nBits)));
            storage.writeBit(depth.get(64), bits.get(64));
            ++histo[code];
            ++histo[64];
        } else {
            storage.writeBit(depth.get(39), bits.get(39));
            storage.writeBit(24, (copyLen - 2120));
            storage.writeBit(depth.get(64), bits.get(64));
            ++histo[39];
            ++histo[64];
        }
    }

    private void emitCopyLen(int copyLen, Buffer depth, Buffer bits, int[] histo) {
        if (copyLen < 10) {
            storage.writeBit(depth.get(copyLen + 14), bits.get(copyLen + 14));
            ++histo[copyLen + 14];
        } else if (copyLen < 134) {
            int tail = copyLen - 6;
            int nbits = log2FloorNonZero(tail) - 1;
            int prefix = tail >> nbits;
            int code = (nbits << 1) + prefix + 20;
            storage.writeBit(depth.get(code), bits.get(code));
            storage.writeBit(nbits, (tail - (prefix << nbits)));
            ++histo[code];
        } else if (copyLen < 2118) {
            int tail = copyLen - 70;
            int nBits = log2FloorNonZero(tail);
            int code = nBits + 28;
            storage.writeBit(depth.get(code), bits.get(code));
            storage.writeBit(nBits, (tail - (1 << nBits)));
            ++histo[code];
        } else {
            storage.writeBit(depth.get(39), bits.get(39));
            storage.writeBit(24, (copyLen - 2118));
            ++histo[39];
        }
    }

    private boolean shouldMergeBlock(ByteInput data, int index, int length, Buffer depths) {
        int[] histo = histogram;
        Arrays.fill(histo, 0);
        int kSampleRate = 43;
        int i;
        for (i = 0; i < length; i += kSampleRate) {
            ++histo[data.get(index + i)];
        }
        int total = (length + kSampleRate - 1) / kSampleRate;
        double r = (fastLog2(total) + 0.5) * (double) total + 200;
        for (i = 0; i < 256; ++i) {
            r -= (double) histo[i] * (depths.get(i) + fastLog2(histo[i]));
        }
        return r >= 0.0;
    }

    private void updateBits(int bits, int pos, BitBuffer array) {
        int nBits = 20;
        while (nBits > 0) {
            int bytePos = pos >> 3;
            int unchangedBitsIx = pos & 7;
            int changedBitsIx = Math.min(nBits, 8 - unchangedBitsIx);
            int totalBits = unchangedBitsIx + changedBitsIx;
            int mask = (-(1 << totalBits)) | ((1 << unchangedBitsIx) - 1);
            int unchangedBits = array.get(bytePos) & mask;
            int changedBits = bits & ((1 << changedBitsIx) - 1);
            array.set(bytePos, (byte) ((changedBits << unchangedBitsIx) | unchangedBits));
            nBits -= changedBitsIx;
            bits >>= changedBitsIx;
            pos += changedBitsIx;
        }
    }

    /**
     * Builds a command and distance prefix code (each 64 symbols) into "depth" and
     * "bits" based on "histogram" and stores it into the bit stream.
     *
     * @param histogram              Command histogram.
     * @param depth                  Table containing length of prefix code.
     * @param bits                   Table containing bits of prefix code.
     * @param storage                storage buffer further copied to brotli stream.
     */
    private void buildAndStoreCommandPrefixCode(int[] histogram, Buffer depth, Buffer bits, BitBuffer storage) {
        if (commandTree == null) {
            commandTree = new HuffmanTree(129);
            commandDepth = new Buffer(HuffmanTree.NUM_COMMAND_SYMBOLS);
            commandBits = new Buffer(64);
        }
        commandDepth.clear();
        commandBits.clear();
        HuffmanTree tree = commandTree;
        Buffer cmdDepth = commandDepth;
        Buffer cmdBits = commandBits;

        tree.createHuffmanTree(histogram, 0, 64, 15, depth, 0);
        tree.createHuffmanTree(histogram, 64, 64, 14, depth, 64);
        cmdDepth.writeBuffer(depth, 0, 24, 0);
        cmdDepth.writeBuffer(depth, 40, 8, 24);
        cmdDepth.writeBuffer(depth, 24, 8, 32);
        cmdDepth.writeBuffer(depth, 48, 8, 40);
        cmdDepth.writeBuffer(depth, 32, 8, 48);
        cmdDepth.writeBuffer(depth, 56, 8, 56);
        Utils.convertBitDepthsToSymbols(cmdDepth, 64, cmdBits, 0);
        bits.writeBuffer(cmdBits, 0, 48, 0);
        bits.writeBuffer(cmdBits, 32, 8, 24);
        bits.writeBuffer(cmdBits, 48, 8, 32);
        bits.writeBuffer(cmdBits, 24, 8, 40);
        bits.writeBuffer(cmdBits, 40, 8, 48);
        bits.writeBuffer(cmdBits, 56, 8, 56);
        Utils.convertBitDepthsToSymbols(depth, 64, bits, 64);
        cmdDepth.clear();
        cmdDepth.writeBuffer(depth, 0, 8, 0);
        cmdDepth.writeBuffer(depth, 8, 8, 64);
        cmdDepth.writeBuffer(depth, 16, 8, 128);
        cmdDepth.writeBuffer(depth, 24, 8, 192);
        cmdDepth.writeBuffer(depth, 32, 8, 384);
        for (int i = 0; i < 8; ++i) {
            cmdDepth.set(128 + 8 * i, depth.get(40 + i));
            cmdDepth.set(256 + 8 * i, depth.get(48 + i));
            cmdDepth.set(448 + 8 * i, depth.get(56 + i));
        }
        tree.brotliStoreHuffmanTree(cmdDepth, HuffmanTree.NUM_COMMAND_SYMBOLS, 0, storage);
        tree.brotliStoreHuffmanTree(depth, 64, 64, storage);
    }

    /**
     * Compression for quality 0.
     *
     * @param lastBlock         Is it the last block to be emitted ?
     */
    private void brotliCompressStreamFast(boolean lastBlock) {
        if (inputBuffer.remaining() == 0) {
            storage.clear();
            outputManager.resetLastFlushPosition();
            if (started) {
                if (lastBlock) {
                    // The final empty block follows the pending compressed bits, without byte alignment.
                    storage.writeBit(lastBytesBits, lastBytes);
                    storage.writeBit(2, 3);
                    lastBytes = storage.get16Bits(storage.bitIx() >> 3);
                    lastBytesBits = storage.bitIx() & 7;
                    outputManager.finished(true);
                    while (injectFlushOrPushOutput(output, storage)) {
                        // Drain complete bytes and the zero-filled final partial byte.
                    }
                    outputManager.flushToOutputStream(output);
                }
                return;
            }
            storage.writeBit(8, 51);
            injectFlushOrPushOutput(output, storage);
            outputManager.flushToOutputStream(output);
            return;
        }

        while (true) {
            if (injectFlushOrPushOutput(output, storage)) {
                continue;
            }
            if (inputBuffer.remaining() != 0) {
                int blockSize = Math.min(1 << window, inputBuffer.remaining());
                boolean isLast = inputBuffer.remaining() == blockSize && lastBlock;
                boolean forceFlush = inputBuffer.remaining() == blockSize;
                storage.clear();
                storage.writeByte(lastBytes & 255);
                storage.writeByte(lastBytes >> 8);
                storage.bitIx(lastBytesBits);
                outputManager.resetLastFlushPosition();
                if (forceFlush && blockSize == 0) {
                    outputManager.finished(false);
                    continue;
                }
                getHashTable(blockSize);

                brotliCompressFragmentFast(blockSize, isLast);

                if (forceFlush && !isLast && (storage.bitIx() & 7) != 0) {
                    // RFC 7932 section 11.2: empty metadata aligns the next block without ending the stream.
                    storage.writeBit(6, 6);
                    int padding = (-storage.bitIx()) & 7;
                    if (padding != 0) {
                        storage.writeBit(padding, 0);
                    }
                }

                if (blockSize != 0) {
                    inputBuffer.advance(blockSize);
                }
                lastBytes = storage.get16Bits((storage.bitIx() >> 3));
                lastBytesBits = storage.bitIx() & 7;
                if (isLast) {
                    outputManager.finished(true);
                }
                continue;
            }
            break;
        }
        outputManager.flushToOutputStream(output);
    }

    /**
     * Compress the inputBuffer data.
     * If the data compressed if bigger than a brotli stream with uncompressed data,
     * than fallback to uncompressed stream.
     *
     * @param blockSize         Block size to be compressed.
     * @param isLast            true if it is the last block to be compressed.
     */
    private void brotliCompressFragmentFast(int blockSize, boolean isLast) {
        int initialStorageBit = storage.bitIx();
        int initialInputPosition = inputBuffer.position();
        if (blockSize == 0) {
            storage.writeBit(2, 3);
            return;
        }
        brotliCompressFragmentFastImpl(blockSize, isLast);
        if (storage.bitIx() - initialStorageBit > 31 + (blockSize << 3)) {
            emitUncompressedMetaBlock(initialInputPosition, initialInputPosition + blockSize, initialStorageBit);
        }
        if (isLast) {
            storage.writeBit(2, 3);
        }
    }

    /**
     * Actual Brotli compression for quality 0.
     *
     * @param size block size to be compressed.
     * @param isLast true if it is the last block to be compressed.
     */
    private void brotliCompressFragmentFastImpl(int size, boolean isLast) {
        final int shift = 64 - log2FloorNonZero(table.capacity());
        Arrays.fill(cmdHisto, 0);
        input = inputBuffer.position();
        ip = input;
        ipEnd = input;
        baseIp = input;
        nextEmit = input;
        metaBlockStart = input;
        inputSize = size;
        blockSize = Math.min(inputSize, FIRST_BLOCK_SIZE);
        totalBlockSize = blockSize;
        lenStorageIx = storage.bitIx() + 3;
        litDepth.clear();
        litBits.clear();
        ipLimit = 0;
        nextHash = 0;
        nextIp = 0;
        skip = 0;
        candidate = 0;
        lastDistance = 0;
        compressState = EMIT_COMMANDS;

        storeMetaBlockHeader(blockSize, false);
        storage.writeBit(13, 0);
        literalRatio = buildAndStoreLiteralPrefixCode(inputBuffer, input, blockSize, litDepth, litBits);
        for (int i = 0; i + 7 < cmdCode.bitIx(); i += 8) {
            storage.writeBit(8, cmdCode.get(i >> 3));
        }
        storage.writeBit(cmdCode.bitIx() & 7, cmdCode.get(cmdCode.bitIx() >> 3));

        main_loop:
        while (true) {
            switch (compressState) {
                case EMIT_COMMANDS -> {
                    emitCommands(shift);
                    continue;
                }
                case FOR -> {
                    skip = 32;
                    nextIp = ip;
                    compressState = TRAWL;
                    continue;
                }
                case TRAWL -> {
                    trawl(shift);
                    continue;
                }
                case EMIT_REMAINDER -> {
                    emitRemainder();
                    continue;
                }
                case NEXT_BLOCK -> {
                    nextBlock();
                    if (compressState == EMIT_COMMANDS) {
                        continue;
                    }
                    break main_loop;
                }
                default -> throw new BrotliException("Wrong compress state");
            }
        }
        if (!isLast) {
            cmdCode.set(0, 0);
            cmdCode.bitIx(0);
            buildAndStoreCommandPrefixCode(cmdHisto, cmdDepth, cmdBits, cmdCode);
        }
    }

    private void nextBlock() {
        if (inputSize > 0) {
            metaBlockStart = input;
            blockSize = Math.min(inputSize, FIRST_BLOCK_SIZE);
            totalBlockSize = blockSize;
            lenStorageIx = storage.bitIx() + 3;
            storeMetaBlockHeader(blockSize, false);
            storage.writeBit(13, 0);
            literalRatio = buildAndStoreLiteralPrefixCode(inputBuffer, input, blockSize, litDepth, litBits);
            buildAndStoreCommandPrefixCode(cmdHisto, cmdDepth, cmdBits, storage);
            compressState = EMIT_COMMANDS;
        }
    }

    private void emitRemainder() {
        input += blockSize;
        inputSize -= blockSize;
        blockSize = Math.min(inputSize, 65536);
        if (inputSize > 0 && totalBlockSize + blockSize <= 1048576
                && shouldMergeBlock(inputBuffer, input, blockSize, litDepth)) {
            totalBlockSize += blockSize;
            updateBits(totalBlockSize - 1, lenStorageIx, storage);
            compressState = EMIT_COMMANDS;
            return;
        }
        if (nextEmit < ipEnd) {
            int insert = ipEnd - nextEmit;
            if (insert < 6210) {
                emitInsertLen(insert, cmdDepth, cmdBits, cmdHisto);
                emitLiterals(nextEmit, inputBuffer, insert, litDepth, litBits);
            } else if (shouldUseUncompressedMode(metaBlockStart, nextEmit, insert, literalRatio)) {
                emitUncompressedMetaBlock(metaBlockStart, ipEnd, lenStorageIx - 3);
            } else {
                emitLongInsertLen(insert, cmdDepth, cmdBits, cmdHisto);
                emitLiterals(nextEmit, inputBuffer, insert, litDepth, litBits);
            }
        }
        nextEmit = ipEnd;
        compressState = NEXT_BLOCK;
    }

    private void trawl(int shift) {
        do {
            int hash = nextHash;
            int bytesBetweenHashLookups = (skip++ >> 5);
            ip = nextIp;
            nextIp = ip + bytesBetweenHashLookups;
            if (nextIp > ipLimit) {
                compressState = EMIT_REMAINDER;
                return;
            }
            nextHash = hashBytesAtOffset(inputBuffer.get64Bits(nextIp), 0, shift);
            candidate = ip - lastDistance;
            if (inputBuffer.isMatch(ip, candidate) && candidate < ip) {
                table.set(hash, ip - baseIp);
                break;
            }
            candidate = baseIp + table.get(hash);
            table.set(hash, ip - baseIp);
        } while (!inputBuffer.isMatch(ip, candidate));
        if (ip - candidate > MAX_DISTANCE) {
            return;
        }
        int base = ip;
        int matched = 5 + findMatchLengthWithLimit(inputBuffer, candidate + 5, ip + 5, ipEnd - ip - 5);
        int distance = base - candidate;
        int insert = (base - nextEmit);
        ip += matched;
        if (insert < 6210) {
            emitInsertLen(insert, cmdDepth, cmdBits, cmdHisto);
        } else if (shouldUseUncompressedMode(metaBlockStart, nextEmit, insert, literalRatio)) {
            emitUncompressedMetaBlock(metaBlockStart, base, lenStorageIx - 3);
            inputSize -= base - input;
            input = base;
            nextEmit = input;
            compressState = NEXT_BLOCK;
            return;
        } else {
            emitLongInsertLen(insert, cmdDepth, cmdBits, cmdHisto);
        }
        emitLiterals(nextEmit, inputBuffer, insert, litDepth, litBits);
        if (distance == lastDistance) {
            storage.writeBit(cmdDepth.get(64), cmdBits.get(64));
            ++cmdHisto[64];
        } else {
            emitDistance(distance, cmdDepth, cmdBits, cmdHisto);
            lastDistance = distance;
        }
        emitCopyLenLastDistance(matched, cmdDepth, cmdBits, cmdHisto);
        nextEmit = ip;
        if (ip >= ipLimit) {
            compressState = EMIT_REMAINDER;
            return;
        }
        long inputBytes64 = inputBuffer.get64Bits(ip - 3);
        int prevHash = hashBytesAtOffset(inputBytes64, 0, shift);
        int curHash = hashBytesAtOffset(inputBytes64, 3, shift);
        calculateHash(shift, inputBytes64, prevHash, curHash);
        while (inputBuffer.isMatch(ip, candidate)) {
            base = ip;
            matched = 5 + findMatchLengthWithLimit(inputBuffer, candidate + 5, ip + 5, (ipEnd - ip) - 5);
            if (ip - candidate > MAX_DISTANCE) {
                break;
            }
            ip += matched;
            lastDistance = (base - candidate);
            emitCopyLen(matched, cmdDepth, cmdBits, cmdHisto);
            emitDistance(lastDistance, cmdDepth, cmdBits, cmdHisto);
            nextEmit = ip;
            if (ip >= ipLimit) {
                compressState = EMIT_REMAINDER;
                return;
            }
            inputBytes64 = inputBuffer.get64Bits(ip - 3);
            prevHash = hashBytesAtOffset(inputBytes64, 0, shift);
            curHash = hashBytesAtOffset(inputBytes64, 3, shift);
            calculateHash(shift, inputBytes64, prevHash, curHash);
        }
        nextHash = hashBytesAtOffset(inputBuffer.get64Bits(++ip), 0, shift);
        compressState = FOR;
    }

    private void calculateHash(int shift, long inputBytes64, int prevHash, int curHash) {
        table.set(prevHash, ip - baseIp - 3);
        prevHash = hashBytesAtOffset(inputBytes64, 1, shift);
        table.set(prevHash, ip - baseIp - 2);
        prevHash = hashBytesAtOffset(inputBytes64, 2, shift);
        table.set(prevHash, ip - baseIp - 1);
        candidate = baseIp + table.get(curHash);
        table.set(curHash, ip - baseIp);
    }

    private void emitCommands(int shift) {
        System.arraycopy(Context.CMD_HISTO_SEED, 0, cmdHisto, 0, Context.CMD_HISTO_SEED.length);
        lastDistance = -1;
        ip = input;
        ipEnd = input + blockSize;
        if (blockSize >= BROTLI_WINDOW_GAP) {
            int lenLimit = Math.min(blockSize - 5, inputSize - BROTLI_WINDOW_GAP);
            ipLimit = input + lenLimit;
            nextHash = hashBytesAtOffset(inputBuffer.get64Bits(++ip), 0, shift);
            compressState = FOR;
            return;
        }
        compressState = EMIT_REMAINDER;
    }

    private boolean injectFlushOrPushOutput(BitBuffer output, BitBuffer storage) {
        boolean result = outputManager.injectFlushOrPushOutput(output, storage, lastBytes, lastBytesBits);
        lastBytes = outputManager.lastByte();
        lastBytesBits = outputManager.lastBytesBit();
        return result;
    }

    private int findMatchLengthWithLimit(ByteInput buffer, int s1, int s2, int limit) {
        byte[] array = buffer.array();
        if (limit >= Long.BYTES) {
            int mismatch = Arrays.mismatch(array, s1, s1 + limit, array, s2, s2 + limit);
            return mismatch < 0 ? limit : mismatch;
        }
        int matched = 0;
        int s2Limit = s2 + limit;
        while (s2 < s2Limit && array[s1 + matched] == array[s2]) {
            ++s2;
            ++matched;
        }
        return matched;
    }

    private double fastLog2(int i) {
        if (i < 256) {
            return LOG2_TABLE[i];
        }
        return (Math.log(i) / Math.log(2) + 1e-10);
    }

    private int log2FloorNonZero(int n) {
        int result = 0;
        while ((n >>= 1) != 0) {
            result++;
        }
        return result;
    }

    enum BrotliEncoderCompressState {
        FOR,
        TRAWL,
        NEXT_BLOCK,
        EMIT_REMAINDER,
        EMIT_COMMANDS;
    }

    private static class OutputStreamManager {
        private final OutputStream outputStream;

        private int lastFlushPosition;
        private int lastBytesBit;
        private int lastByte;
        private boolean isFinished;

        OutputStreamManager(BrotliOutputStreamConfig builder) {
            outputStream = builder.outputStream();
        }

        void flushToOutputStream(BitBuffer output) {
            if (output.bitIx() == 0) {
                return;
            }
            try {
                outputStream.write(output.array(), 0, output.byteIx());
                outputStream.flush();
                output.clear();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         *  Injects padding bits or pushes compressed data to output.
         *  Returns false if nothing is done.
         */
        boolean injectFlushOrPushOutput(BitBuffer output, BitBuffer storage, int lastBytes, int lastBytesBits) {
            lastByte = lastBytes;
            lastBytesBit = lastBytesBits;
            if (output.availableSpace() == 0) {
                flushToOutputStream(output);
                return true;
            }
            if (lastFlushPosition < storage.byteIx() && output.availableSpace() > 0) {
                int copyLength = Math.min(output.availableSpace(), storage.byteIx() - lastFlushPosition);
                output.writeBuffer(storage, lastFlushPosition, copyLength);
                lastFlushPosition += copyLength;
                return true;
            }

            if (isFinished && lastBytesBits != 0) {
                output.writeByte(lastBytes);
                lastByte = 0;
                lastBytesBit = 0;
                return true;
            }
            return false;
        }

        void close() throws IOException {
            outputStream.close();
        }

        void resetLastFlushPosition() {
            lastFlushPosition = 0;
        }

        int lastByte() {
            return lastByte;
        }

        int lastBytesBit() {
            return lastBytesBit;
        }

        void finished(boolean isFinished) {
            this.isFinished = isFinished;
        }
    }
}
