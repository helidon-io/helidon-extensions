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

    private final byte[] byteBuffer = new byte[CAPACITY];

    private long accumulator;
    private int bitCount;
    private int bitOffset;
    private int byteOffset;
    private int byteLimit;
    private boolean endOfStreamReached;
    private InputStream input;

    BitReader(InputStream input) {
        this.input = input;
    }

    /**
     * Number of input bits already available, without another underlying read.
     */
    int bufferedBits() {
        return bitCount + ((byteLimit - byteOffset) << 3);
    }

    void checkHealth(boolean endOfStream) {
        if (endOfStream && bufferedBits() != 0) {
            throw new BrotliException("Unused bytes after end");
        }
    }

    /**
     * Peeks at buffered bits only. A prefix-code lookup must verify its selected
     * code length before consuming the zero-extended result.
     */
    int peekBits() {
        fillFromBuffer();
        return (int) accumulator;
    }

    /**
     * Obtains only the input needed for the requested number of bits.
     */
    void ensureBits(int count) {
        fillFromBuffer();
        while (bitCount < count) {
            if (!readInput()) {
                throw new BrotliException("Unexpected end of input");
            }
            fillFromBuffer();
        }
    }

    int readBits(int count) {
        ensureBits(count);
        int value = (int) (accumulator & ((1L << count) - 1));
        incrementBitOffset(count);
        return value;
    }

    void jumpToByteBoundary() {
        int padding = (-bitOffset) & 7;
        if (padding != 0) {
            int paddingBits = readBits(padding);
            if (paddingBits != 0) {
                throw new BrotliException("Corrupted padding bits");
            }
        }
    }

    int copyRawBytes(byte[] data, int offset, int length) {
        if ((bitOffset & 7) != 0) {
            throw new BrotliException("Unaligned copyBytes");
        }
        int copied = 0;
        while (bitCount >= 8 && length != 0) {
            data[offset++] = (byte) accumulator;
            incrementBitOffset(8);
            copied++;
            length--;
        }
        if (length != 0) {
            if (byteOffset == byteLimit) {
                // Return available plaintext before a read that might wait for its producer.
                if (copied != 0) {
                    return copied;
                }
                if (!readInput()) {
                    throw new BrotliException("Unexpected end of input");
                }
            }
            int count = Math.min(length, byteLimit - byteOffset);
            System.arraycopy(byteBuffer, byteOffset, data, offset, count);
            byteOffset += count;
            copied += count;
        }
        return copied;
    }

    void incrementBitOffset(int value) {
        accumulator >>>= value;
        bitCount -= value;
        bitOffset = (bitOffset + value) & 7;
    }

    void close() throws IOException {
        if (input != null) {
            input.close();
            input = null;
        }
    }

    private void fillFromBuffer() {
        while (bitCount <= 56 && byteOffset < byteLimit) {
            accumulator |= (long) (byteBuffer[byteOffset++] & 0xFF) << bitCount;
            bitCount += 8;
        }
    }

    private boolean readInput() {
        if (endOfStreamReached) {
            return false;
        }
        try {
            int count = input.read(byteBuffer, 0, byteBuffer.length);
            if (count == 0) {
                int value = input.read();
                if (value != -1) {
                    byteBuffer[0] = (byte) value;
                    count = 1;
                } else {
                    count = -1;
                }
            }
            byteOffset = 0;
            byteLimit = Math.max(count, 0);
            endOfStreamReached = count == -1;
            return count > 0;
        } catch (IOException e) {
            throw new BrotliException("Failed to read input", e);
        }
    }
}
