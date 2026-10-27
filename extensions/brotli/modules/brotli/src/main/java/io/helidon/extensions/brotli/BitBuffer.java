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

/* Copyright 2010 Google Inc. All Rights Reserved.

   Distributed under MIT license.
   See file LICENSE for detail or copy at https://opensource.org/licenses/MIT
*/
package io.helidon.extensions.brotli;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Growable byte storage for least-significant-bit-first compressed output.
 */
final class BitBuffer {
    private static final byte[] EMPTY = new byte[0];
    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final int capacity;

    private byte[] array = EMPTY;
    private int bitIx;

    BitBuffer(int capacity) {
        this.capacity = capacity;
    }

    int bitIx() {
        return bitIx;
    }

    void bitIx(int position) {
        bitIx = position;
    }

    int byteIx() {
        return bitIx >>> 3;
    }

    int availableSpace() {
        return capacity - byteIx();
    }

    byte[] array() {
        return array;
    }

    int get(int index) {
        return index < array.length ? array[index] & 0xFF : 0;
    }

    int get16Bits(int index) {
        return get(index) | (get(index + 1) << 8);
    }

    void set(int index, int value) {
        ensureCapacity(index + 1);
        array[index] = (byte) value;
    }

    void applyMask(int index, int mask) {
        array[index] &= (byte) mask;
    }

    void writeArray(int[] source, int offset, int length, int destination) {
        ensureCapacity(destination + length + 1);
        for (int i = 0; i < length; i++) {
            array[destination + i] = (byte) source[offset + i];
        }
    }

    void writeBuffer(BitBuffer source, int offset, int length) {
        writeBytes(source.array, offset, length);
    }

    void writeBuffer(ByteInput source, int offset, int length, int destination) {
        ensureCapacity(destination + length + 1);
        System.arraycopy(source.array(), offset, array, destination, length);
    }

    void writeByte(int value) {
        writeBit(Byte.SIZE, value);
    }

    void writeBit(boolean value) {
        writeBit(1, value ? 1 : 0);
    }

    void writeBit(int count, long value) {
        if (count == 0) {
            return;
        }
        if (count > 56) {
            writeBit(56, value);
            writeBit(count - 56, value >>> 56);
            return;
        }
        int index = bitIx >>> 3;
        int offset = bitIx & 7;
        ensureCapacity(index + Long.BYTES + 1);
        long mask = (1L << count) - 1;
        long bits = (array[index] & 0xFFL) | ((value & mask) << offset);
        LONG_VIEW.set(array, index, bits);
        bitIx += count;
        array[(bitIx + 7) >>> 3] = 0;
    }

    void clear() {
        Arrays.fill(array, 0, Math.min(array.length, ((bitIx + 7) >>> 3) + 1), (byte) 0);
        bitIx = 0;
    }

    private void writeBytes(byte[] source, int offset, int length) {
        if ((bitIx & 7) == 0) {
            int index = byteIx();
            ensureCapacity(index + length + 1);
            System.arraycopy(source, offset, array, index, length);
            bitIx += length << 3;
            array[byteIx()] = 0;
        } else {
            for (int i = 0; i < length; i++) {
                writeByte(source[offset + i]);
            }
        }
    }

    private void ensureCapacity(int required) {
        if (required > array.length) {
            array = Arrays.copyOf(array, Math.max(required, Math.max(32, array.length << 1)));
        }
    }
}
