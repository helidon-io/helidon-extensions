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

/**
 * Huffman bit-depth and symbol conversion helpers.
 */
final class Utils {
    private static final int MAX_HUFFMAN_BITS = 16;

    private Utils() {
    }

    static void convertBitDepthsToSymbols(Buffer depth, int len, Buffer bits, int index) {
        int[] blCount = new int[MAX_HUFFMAN_BITS];
        int[] nextCode = new int[MAX_HUFFMAN_BITS];
        int i;
        int code = 0;
        for (i = index; i < (index + len); ++i) {
            ++blCount[depth.get(i)];
        }
        blCount[0] = 0;
        nextCode[0] = 0;
        for (i = 1; i < MAX_HUFFMAN_BITS; ++i) {
            code = (code + blCount[(i) - 1]) << 1;
            nextCode[i] = code;
        }
        for (i = index; i < (index + len); ++i) {
            if (depth.get(i) != 0) {
                bits.set(i, reverseBits(depth.get(i), nextCode[depth.get(i)]++));
            }
        }
    }

    private static int reverseBits(int numBits, int bits) {
        int result = Context.LUT[bits & 0x0F];
        for (int i = 4; i < numBits; i += 4) {
            result <<= 4;
            bits = (bits >> 4);
            result |= Context.LUT[bits & 0x0F];
        }
        result >>= ((-numBits) & 0x03);
        return result;
    }
}
