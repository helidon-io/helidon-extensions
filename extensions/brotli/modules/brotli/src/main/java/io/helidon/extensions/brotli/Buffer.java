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

import java.util.Arrays;

/**
 * Integer-valued Huffman and hash tables.
 */
final class Buffer {
    private final int[] array;

    private int bitIx;

    Buffer(int capacity) {
        array = new int[capacity];
    }

    Buffer() {
        this(0);
    }

    int byteIx() {
        return bitIx >>> 3;
    }

    int capacity() {
        return array.length;
    }

    int get(int position) {
        return array[position];
    }

    void set(int position, int value) {
        array[position] = value;
    }

    void writeBuffer(Buffer source, int offset, int length, int destination) {
        System.arraycopy(source.array, offset, array, destination, length);
    }

    void writeArray(int[] source, int offset, int length, int destination) {
        System.arraycopy(source, offset, array, destination, length);
    }

    void reverse(int start, int end) {
        for (int left = start, right = end - 1; left < right; left++, right--) {
            int value = array[left];
            array[left] = array[right];
            array[right] = value;
        }
    }

    void clear() {
        Arrays.fill(array, 0);
        bitIx = 0;
    }

    void writeByte(int value) {
        array[byteIx()] = value & 0xFF;
        bitIx += Byte.SIZE;
    }
}
