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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Byte-oriented encoder input with independent write size and compression cursor.
 */
final class ByteInput {
    private static final byte[] EMPTY = new byte[0];
    private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final int limit;

    private byte[] array = EMPTY;
    private int size;
    private int position;

    ByteInput(int limit) {
        this.limit = limit;
    }

    int size() {
        return size;
    }

    int limit() {
        return limit;
    }

    int position() {
        return position;
    }

    void position(int position) {
        this.position = position;
    }

    int remaining() {
        return size - position;
    }

    void advance(int length) {
        position += length;
    }

    byte[] array() {
        return array;
    }

    int get(int index) {
        return index < size ? array[index] & 0xFF : 0;
    }

    long get64Bits(int index) {
        if (index + Long.BYTES <= size) {
            return (long) LONG_VIEW.get(array, index);
        }
        long result = 0;
        for (int i = 0; i < Long.BYTES && index + i < size; i++) {
            result |= (long) (array[index + i] & 0xFF) << (i << 3);
        }
        return result;
    }

    int get32Bits(int index) {
        if (index + Integer.BYTES <= size) {
            return (int) INT_VIEW.get(array, index);
        }
        int result = 0;
        for (int i = 0; i < Integer.BYTES && index + i < size; i++) {
            result |= (array[index + i] & 0xFF) << (i << 3);
        }
        return result;
    }

    boolean isMatch(int index, int candidate) {
        return get32Bits(index) == get32Bits(candidate)
                && (index + 4 >= limit || candidate + 4 >= limit || get(index + 4) == get(candidate + 4));
    }

    void writeByte(int value) {
        ensureCapacity(size + 1);
        array[size++] = (byte) value;
    }

    void write(byte[] source, int offset, int length) {
        ensureCapacity(size + length);
        System.arraycopy(source, offset, array, size, length);
        size += length;
    }

    void clear() {
        Arrays.fill(array, 0, size, (byte) 0);
        size = 0;
        position = 0;
    }

    private void ensureCapacity(int required) {
        if (required > array.length) {
            int capacity = Math.min(limit, Math.max(required, Math.max(32, array.length << 1)));
            array = Arrays.copyOf(array, capacity);
        }
    }
}
