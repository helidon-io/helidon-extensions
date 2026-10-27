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

class Buffer {
    private final int maxBitIx;

    private int[] array;
    private int bitIx;
    private int available;

    Buffer(int capacity) {
        this.array = new int[capacity];
        this.bitIx = 0;
        this.maxBitIx = capacity * 8;
        this.available = capacity;
    }

    Buffer(int[] init) {
        this(init.length);
        this.array = init;
    }

    Buffer() {
        this(0);
    }

    int bitIx() {
        return bitIx;
    }

    int byteIx() {
        return bitIx / 8;
    }

    int maxBitIx() {
        return maxBitIx;
    }

    int capacity() {
        return array.length;
    }

    int availableSpace() {
        return this.available;
    }

    void decreaseAvailable(int value) {
        available -= value;
    }

    void available(int value) {
        available = value;
    }

    int[] toArray() {
        return array;
    }

    int get(int position) {
        return array[position];
    }

    void set(int position, int value) {
        array[position] = value;
    }

    byte[] toByteArray() {
        byte[] result = new byte[array.length];
        for (int i = 0; i < array.length; i++) {
            result[i] = (byte) array[i];
        }
        return result;
    }

    void writeBuffer(Buffer buffer, int start, int length) {
        int[] source = buffer.toArray();
        for (int i = start; i < (start + length); i++) {
            if (i < buffer.capacity()) {
                writeByte(source[i]);
            }
        }
    }

    void writeBuffer(Buffer buffer, int start, int length, int destOffset) {
        writeArray(buffer.toArray(), start, length, destOffset);
    }

    void writeArray(int[] source, int sourceOffSet, int length, int destOffset) {
        //System.arraycopy(source, sourceOffSet, array, destOffset, length);
        for (int i = sourceOffSet; i < (sourceOffSet + length); i++) {
            array[destOffset++] = source[i];
        }
    }

    void bitIx(int bitIx) {
        this.bitIx = bitIx;
    }

    void increaseBitIx(int value) {
        bitIx += value;
    }

    void applyMask(int position, int mask) {
        array[position] &= mask;
    }

    long get64Bits(int index) {
        int position = 0;
        long result = 0;
        for (int i = index; i < index + 8; i++) {
            if (i < array.length) {
                result |= ((long) array[i] << position);
            }
            position += 8;
        }
        return result;
    }

    int get32Bits(int index) {
        int position = 0;
        int result = 0;
        for (int i = index; i < index + 4; i++) {
            if (i < array.length) {
                result |= (array[i] << position);
            }
            position += 8;
        }
        return result;
    }

    int get16Bits(int index) {
        int position = 0;
        int result = 0;
        for (int i = index; i < index + 2; i++) {
            if (i < array.length) {
                result |= (array[i] << position);
            }
            position += 8;
        }
        return result;
    }

    void reverse(int start, int end) {
        --end;
        while (start < end) {
            int tmp = this.get(start);
            this.set(start, this.get(end));
            this.set(end, tmp);
            ++start;
            --end;
        }
    }

    boolean isMatch(int ip, int candidate) {
        if ((ip + 4) < this.capacity() && (candidate + 4) < this.capacity()) {
            return this.get32Bits(ip) == this.get32Bits(candidate)
                    && this.get(ip + 4) == this.get(candidate + 4);
        } else {
            return this.get32Bits(ip) == this.get32Bits(candidate);
        }
    }

    void clear() {
        array = new int[array.length];
        bitIx = 0;
    }

    void writeByte(int b) {
        writeBit(8, b);
    }

    void writeBit(int nBit, long bits) {
        write(nBit, bits);
    }

    void writeBit(int nBit, int bits) {
        write(nBit, bits);
    }

    void writeBit(boolean bit) {
        write(1, bit ? 1 : 0);
    }

    private void write(int nBit, long bits) {
        int offset = bitIx >> 3;
        int bitsReservedInFirstByte = bitIx & 7;
        bits <<= bitsReservedInFirstByte;
        array[offset++] |= (int) (bits & 0xFF);
        for (int bitsLeftToWrite = nBit + bitsReservedInFirstByte;
             bitsLeftToWrite >= 9;
             bitsLeftToWrite -= 8) {
            bits >>= 8;
            array[offset++] = (int) (bits & 0xFF);
        }
        if (offset < array.length) {
            array[offset] = 0;
        }
        bitIx += nBit;
    }
}
