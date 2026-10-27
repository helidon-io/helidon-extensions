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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Attached raw-prefix dictionary address mapping from RFC 9841 section 3.2, using the command and block syntax of
 * RFC 7932 sections 4, 5, and 9.2. These references stay within the concatenated dictionary contents; the complete
 * RFC 9841 format, including references that continue into decoded output, is not supported.
 */
class BrotliDictionaryTest {
    private static final byte[] DICTIONARY = "abcdefgh".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("RFC 9841 section 3.2 address mapping: copy a chunk prefix")
    void copiesChunkPrefix() throws IOException {
        assertThat(decode(dictionaryReference(4, 8), List.of(DICTIONARY), 256, 8192),
                   is("abcd".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("RFC 9841 section 3.2 address mapping: copy from a nonzero chunk offset")
    void copiesChunkSuffix() throws IOException {
        assertThat(decode(dictionaryReference(4, 4), List.of(DICTIONARY), 256, 8192),
                   is("efgh".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("RFC 9841 section 3.2 address mapping: copy one complete chunk")
    void copiesWholeChunk() throws IOException {
        assertThat(decode(dictionaryReference(8, 8), List.of(DICTIONARY), 256, 8192), is(DICTIONARY));
    }

    @Test
    @DisplayName("RFC 9841 section 3.2 address mapping: chunk boundaries do not change copied bytes")
    void copiesAcrossChunksIncludingEmptyChunks() throws IOException {
        List<byte[]> chunks = List.of(new byte[0],
                                      new byte[] {'a'},
                                      new byte[0],
                                      new byte[] {'b', 'c'},
                                      new byte[0],
                                      new byte[] {'d', 'e', 'f'},
                                      new byte[] {'g', 'h'},
                                      new byte[0]);
        assertThat(decode(dictionaryReference(8, 8), chunks, 256, 8192), is(DICTIONARY));
        assertThat(decode(dictionaryReference(4, 4), chunks, 256, 8192),
                   is("efgh".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("RFC 9841 section 3.2 address mapping: small reads preserve a pending copy")
    void resumesCopyWithSmallReads() throws IOException {
        List<byte[]> chunks = List.of(new byte[] {'a', 'b', 'c'}, new byte[0], new byte[] {'d', 'e', 'f', 'g', 'h'});
        for (int readSize : new int[] {1, 2, 3}) {
            assertThat("read size " + readSize,
                       decode(dictionaryReference(8, 8), chunks, 1, readSize), is(DICTIONARY));
        }
    }

    @Test
    @DisplayName("RFC 9841 section 3.2 and RFC 7932 section 4: copy across the window boundary")
    void resumesCopyAcrossRingBoundary() throws IOException {
        byte[] prefix = new byte[65_534];
        for (int i = 0; i < prefix.length; i++) {
            prefix[i] = (byte) i;
        }
        byte[] expected = Arrays.copyOf(prefix, prefix.length + DICTIONARY.length);
        System.arraycopy(DICTIONARY, 0, expected, prefix.length, DICTIONARY.length);
        // RFC 7932 section 4: the maximum backward distance is (1 << WBITS) - 16.
        byte[] encoded = dictionaryReference(prefix, 8, 65_520 + 8);
        for (int readSize : new int[] {1, 257}) {
            byte[] actual = decode(encoded, List.of(DICTIONARY), 1, readSize);
            assertThat("decoded length, read size " + readSize, actual.length, is(expected.length));
            assertThat("first differing byte index, read size " + readSize, Arrays.mismatch(actual, expected), is(-1));
        }
    }

    @Test
    @DisplayName("RFC 7932 section 8 and RFC 9841 section 3.2: dictionary copies follow history growth")
    void copiesAttachedDictionaryAfterStaticTransformAtGrowthBoundary() throws IOException {
        byte[] prefix = new byte[1023];
        Arrays.fill(prefix, (byte) 'A');
        byte[] expected = Arrays.copyOf(prefix, prefix.length + 8);
        System.arraycopy("timedefg".getBytes(StandardCharsets.US_ASCII), 0, expected, prefix.length, 8);
        assertThat(decode(dictionaryCopiesAcrossGrowth(prefix), List.of(DICTIONARY), 1, 1), is(expected));
    }

    @Test
    @DisplayName("RFC 7932 section 9.2: attached dictionary copy cannot exceed the declared metablock")
    void rejectsAttachedDictionaryCopyBeyondMetablockBeforeReturningBytes() throws IOException {
        byte[] encoded = dictionaryReference(new byte[0], 4, 8, 1);
        var inputConfig = BrotliInputStreamConfig.builder().dictionaryChunks(List.of(DICTIONARY)).build();
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded), inputConfig)) {
            assertThrows(IOException.class, () -> input.read(new byte[1]));
        }
    }

    private static byte[] decode(byte[] encoded, List<byte[]> chunks, int cacheSize, int readSize) throws IOException {
        var destination = new ByteArrayOutputStream();
        var inputConfig = BrotliInputStreamConfig.builder()
                .dictionaryChunks(chunks)
                .cacheBufferSize(cacheSize)
                .build();
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded), inputConfig)) {
            if (readSize == 1) {
                for (int value; (value = input.read()) != -1;) {
                    destination.write(value);
                }
            } else {
                byte[] buffer = new byte[readSize + 4];
                for (int count; (count = input.read(buffer, 2, readSize)) != -1;) {
                    assertThat("a nonempty read must make progress", count, greaterThan(0));
                    destination.write(buffer, 2, count);
                }
            }
        }
        return destination.toByteArray();
    }

    private static byte[] dictionaryReference(int length, int distance) {
        return dictionaryReference(new byte[0], length, distance);
    }

    private static byte[] dictionaryReference(byte[] prefix, int length, int distance) {
        return dictionaryReference(prefix, length, distance, length);
    }

    private static byte[] dictionaryReference(byte[] prefix, int length, int distance, int declaredLength) {
        var bits = new BitWriter();
        bits.write(1, 0); // RFC 7932 section 9.1: WBITS=16.
        if (prefix.length != 0) {
            // Section 9.2: a nonfinal uncompressed block, with byte-aligned data.
            bits.write(1, 0); // ISLAST=0.
            bits.write(2, 0); // MNIBBLES=4.
            bits.write(16, prefix.length - 1);
            bits.write(1, 1); // ISUNCOMPRESSED=1.
            bits.writeBytes(prefix);
        }
        // Section 9.2: final compressed block; ISUNCOMPRESSED is absent when ISLAST=1.
        bits.write(1, 1); // ISLAST=1.
        bits.write(1, 0); // ISLASTEMPTY=0.
        bits.write(2, 0); // MNIBBLES=4.
        bits.write(16, declaredLength - 1);
        // One block type per alphabet, NPOSTFIX=NDIRECT=0, context mode 0, one tree per context map.
        bits.write(13, 0);
        // Section 3.4: singleton trees consume no symbol bits. The literal tree is unused.
        bits.writeSingleton(8, 'A');
        // Section 5: command 130 is insert 0/copy 4; command 134 is insert 0/copy 8.
        bits.writeSingleton(10, switch (length) {
            case 4 -> 130;
            case 8 -> 134;
            default -> throw new IllegalArgumentException("Unsupported fixture copy length: " + length);
        });
        writeDistance(bits, distance, 1);
        return bits.toByteArray();
    }

    private static byte[] dictionaryCopiesAcrossGrowth(byte[] prefix) {
        var bits = new BitWriter();
        bits.write(1, 0); // RFC 7932 section 9.1: WBITS=16.
        bits.write(1, 0); // Nonfinal uncompressed prefix.
        bits.write(2, 0);
        bits.write(16, prefix.length - 1);
        bits.write(1, 1);
        bits.writeBytes(prefix);

        bits.write(1, 0); // Nonfinal compressed block with two four-byte dictionary commands.
        bits.write(2, 0);
        bits.write(16, 7);
        bits.write(1, 0);
        bits.write(13, 0);
        bits.writeSingleton(8, 'A');
        bits.writeSingleton(10, 130);
        writeDistance(bits, prefix.length + DICTIONARY.length + 1, 2);

        // Metadata keeps enough valid input buffered while the first transformed word is pending output.
        bits.write(1, 0);
        bits.write(2, 3);
        bits.write(1, 0);
        bits.write(2, 1);
        bits.write(8, 31);
        bits.writeBytes(new byte[32]);
        bits.write(1, 1); // Final empty block.
        bits.write(1, 1);
        return bits.toByteArray();
    }

    private static void writeDistance(BitWriter bits, int distance, int repetitions) {
        // Section 4: an explicit distance with NPOSTFIX=NDIRECT=0; alphabet size is 64.
        for (int code = 16; code < 64; code++) {
            int extraBits = 1 + ((code - 16) >>> 1);
            long base = ((2L + ((code - 16) & 1)) << extraBits) - 3;
            if (distance >= base && distance - base < (1L << extraBits)) {
                bits.writeSingleton(6, code);
                for (int i = 0; i < repetitions; i++) {
                    bits.write(extraBits, (int) (distance - base));
                }
                return;
            }
        }
        throw new IllegalArgumentException("Unsupported fixture distance: " + distance);
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream destination = new ByteArrayOutputStream();
        private int pending;
        private int count;

        private void write(int length, int value) {
            // RFC 7932 section 2: bits are packed least significant bit first.
            for (int i = 0; i < length; i++) {
                pending |= ((value >>> i) & 1) << count++;
                if (count == 8) {
                    destination.write(pending);
                    pending = 0;
                    count = 0;
                }
            }
        }

        private void writeSingleton(int symbolBits, int symbol) {
            write(2, 1); // Simple prefix code.
            write(2, 0); // NSYM=1.
            write(symbolBits, symbol);
        }

        private void writeBytes(byte[] bytes) {
            align();
            destination.writeBytes(bytes);
        }

        private void align() {
            if (count != 0) {
                destination.write(pending);
                pending = 0;
                count = 0;
            }
        }

        private byte[] toByteArray() {
            align();
            return destination.toByteArray();
        }
    }
}
