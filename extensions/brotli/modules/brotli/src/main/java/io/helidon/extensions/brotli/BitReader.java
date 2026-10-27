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

/**
 * Bit reading helpers.
 */
final class BitReader {
    private static final int CAPACITY = 4096;
    private static final int SLACK = 64;
    private static final int BUFFER_SIZE = CAPACITY + SLACK;
    private static final int SAFEGUARD = 36;
    private static final int WATERLINE = CAPACITY - SAFEGUARD;
    private static final int HALF_BITNESS = 32;
    private static final int HALF_SIZE = 4;
    private static final int HALVES_CAPACITY = CAPACITY / HALF_SIZE;
    private static final int HALF_BUFFER_SIZE = BUFFER_SIZE / HALF_SIZE;
    private static final int HALF_WATERLINE = WATERLINE / HALF_SIZE;
    private static final int LOG_HALF_SIZE = 2;

    private final byte[] byteBuffer;
    private final int[] intBuffer;

    private long accumulator64;
    private int halfOffset;
    private int tailBytes;
    private boolean endOfStreamReached;
    private int bitOffset;
    private InputStream input;

    BitReader(InputStream input) {
        this.input = input;
        this.byteBuffer = new byte[BUFFER_SIZE];
        accumulator64 = 0;
        intBuffer = new int[HALF_BUFFER_SIZE];
        bitOffset = 64;
        halfOffset = HALVES_CAPACITY;
        endOfStreamReached = false;
        prepare();
    }

    /**
     * Fills up the input buffer.
     *
     * <p> No-op if there are at least 36 bytes present after current position.
     *
     * <p> After encountering the end of the input stream, 64 additional zero bytes are copied to the
     * buffer.
     */
    void readMoreInput() {
        if (halfOffset > HALF_WATERLINE) {
            if (endOfStreamReached) {
                if (halfAvailable() >= -2) {
                    return;
                }
                throw new BrotliException("No more input");
            }
            int readOffset = halfOffset << LOG_HALF_SIZE;
            int bytesInBuffer = CAPACITY - readOffset;
            // Move unused bytes to the head of the buffer.
            System.arraycopy(byteBuffer, readOffset, byteBuffer, 0, CAPACITY - readOffset);
            halfOffset = 0;
            while (bytesInBuffer < CAPACITY) {
                int spaceLeft = CAPACITY - bytesInBuffer;
                int len = readInput(input, byteBuffer, bytesInBuffer, spaceLeft);
                // EOF is -1 in Java, but 0 in C#.
                if (len <= 0) {
                    endOfStreamReached = true;
                    tailBytes = bytesInBuffer;
                    bytesInBuffer += HALF_SIZE - 1;
                    break;
                }
                bytesInBuffer += len;
            }
            bytesToNibbles(bytesInBuffer);
        }
    }

    void checkHealth(boolean endOfStream) {
        if (!endOfStreamReached) {
            return;
        }
        int byteOffset = (halfOffset << LOG_HALF_SIZE) + ((bitOffset + 7) >> 3) - 8;
        if (byteOffset > tailBytes) {
            throw new BrotliException("Read after end");
        }
        if ((endOfStream) && (byteOffset != tailBytes)) {
            throw new BrotliException("Unused bytes after end");
        }
    }

    void fillBitWindow() {
        if (bitOffset >= HALF_BITNESS) {
            accumulator64 = ((long) intBuffer[halfOffset++] << HALF_BITNESS)
                    | (accumulator64 >>> HALF_BITNESS);
            bitOffset -= HALF_BITNESS;
        }
    }

    int peekBits() {
        return (int) (accumulator64 >>> bitOffset);
    }

    /**
     * Fetches bits from accumulator.
     * WARNING: accumulator MUST contain at least the specified amount of bits,
     * otherwise BitReader will become broken.
     */
    int readBits(int n) {
        int val = peekBits() & ((1 << n) - 1);
        bitOffset += n;
        return val;
    }

    void reload() {
        if (bitOffset == 64) {
            prepare();
        }
    }

    void jumpToByteBoundary() {
        int padding = (64 - bitOffset) & 7;
        if (padding != 0) {
            int paddingBits = readBits(padding);
            if (paddingBits != 0) {
                throw new BrotliException("Corrupted padding bits");
            }
        }
    }

    void copyRawBytes(byte[] data, int offset, int length) {
        if ((bitOffset & 7) != 0) {
            throw new BrotliException("Unaligned copyBytes");
        }

        // Drain accumulator.
        while ((bitOffset != 64) && (length != 0)) {
            data[offset++] = (byte) peekBits();
            bitOffset += 8;
            length--;
        }
        if (length == 0) {
            return;
        }

        // Get data from shadow buffer with "sizeof(int)" granularity.
        int copyNibbles = Math.min(halfAvailable(), length >> LOG_HALF_SIZE);
        if (copyNibbles > 0) {
            int readOffset = halfOffset << LOG_HALF_SIZE;
            int delta = copyNibbles << LOG_HALF_SIZE;
            System.arraycopy(byteBuffer, readOffset, data, offset, delta);
            offset += delta;
            length -= delta;
            halfOffset += copyNibbles;
        }
        if (length == 0) {
            return;
        }

        // Read tail bytes.
        if (halfAvailable() > 0) {
            // length = 1..3
            fillBitWindow();
            while (length != 0) {
                data[offset++] = (byte) peekBits();
                bitOffset += 8;
                length--;
            }
            checkHealth(false);
            return;
        }

        // Now it is possible to copy bytes directly.
        while (length > 0) {
            int len = readInput(input, data, offset, length);
            if (len == -1) {
                throw new BrotliException("Unexpected end of input");
            }
            offset += len;
            length -= len;
        }
    }

    void incrementBitOffset(int value) {
        this.bitOffset += value;
    }

    int bitOffset() {
        return this.bitOffset;
    }

    void close() throws IOException {
        if (input != null) {
            input.close();
            input = null;
        }
    }

    private void prepare() {
        readMoreInput();
        checkHealth(false);
        fillBitWindow();
        fillBitWindow();
    }

    private int halfAvailable() {
        int limit = HALVES_CAPACITY;
        if (endOfStreamReached) {
            limit = (tailBytes + (HALF_SIZE - 1)) >> LOG_HALF_SIZE;
        }
        return limit - halfOffset;
    }

    /**
     * Translates bytes to halves (int/short).
     */
    private void bytesToNibbles(int byteLen) {
        int halfLen = byteLen >> LOG_HALF_SIZE;
        for (int i = 0; i < halfLen; ++i) {
            intBuffer[i] = ((byteBuffer[i * 4] & 0xFF))
                    | ((byteBuffer[(i * 4) + 1] & 0xFF) << 8)
                    | ((byteBuffer[(i * 4) + 2] & 0xFF) << 16)
                    | ((byteBuffer[(i * 4) + 3] & 0xFF) << 24);
        }
    }

    private int readInput(InputStream src, byte[] dst, int offset, int length) {
        try {
            return src.read(dst, offset, length);
        } catch (IOException e) {
            throw new BrotliException("Failed to read input", e);
        }
    }
}
