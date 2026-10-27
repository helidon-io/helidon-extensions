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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

class BrotliEncoderBufferTest {
    // Independently Google-decoded fixture already covered by the RFC conformance tests.
    private static final byte[] FLUSHED_DATA = HexFormat.of().parseHex(
            "8325000080aaaaaaea9f0fa72b11f3e1c4ff77d5bfe7deea9e515ddec67057bc585858d8d858d5cb8ceae0c3871f83c1601"
                    + "c67b2aa0683c1603018cca1ce88549446e1b0d04eb2f70477f194e1c9f9071a03");

    @Test
    @DisplayName("Huffman ties preserve the established encoded bytes")
    void preservesHuffmanTieOrder() throws Exception {
        byte[] data = new byte[16_384];
        Arrays.fill(data, (byte) 'A');
        for (int i = 0; i < data.length; i += 7) {
            data[i] = (byte) (i * 29);
        }
        byte[] encoded = encode(data, 64_000, false);
        assertThat(encoded.length, is(804));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
                   is("119fe527424dcbc4b08889dbf55b24f49b40ec8e6ebf82e3fc444e31c2124d09"));
        assertThat(decode(encoded), is(data));
    }

    @Test
    void scalarAndBulkWritesAgreeAtCacheBoundaries() throws IOException {
        for (int cache : new int[] {4, 5, 7, 76, 77, 64_000}) {
            for (int length : new int[] {cache - 1, cache, cache + 1, 2 * cache + 3}) {
                byte[] data = alphabet(length);
                byte[] scalar = encode(data, cache, true);
                byte[] bulk = encode(data, cache, false);
                String description = "cache " + cache + ", payload length " + length;
                assertThat(description, bulk, is(scalar));
                assertThat(description, decode(bulk), is(data));
            }
        }
    }

    @Test
    void mixedOffsetWritesPreserveUnsignedBytes() throws IOException {
        byte[] data = alphabet(1025);
        byte[] padded = new byte[data.length + 9];
        Arrays.fill(padded, (byte) 0xA5);
        System.arraycopy(data, 0, padded, 4, data.length);
        for (int cache : new int[] {77, 64_000}) {
            var destination = new ByteArrayOutputStream();
            try (var output = BrotliOutputStream.builder()
                    .outputStream(destination)
                    .cacheInputSize(cache)
                    .build()) {
                int offset = 0;
                while (offset < data.length) {
                    output.write(padded[offset + 4] | 0x1000);
                    offset++;
                    int count = Math.min(31, data.length - offset);
                    output.write(padded, offset + 4, count);
                    output.write(padded, 4, 0);
                    offset += count;
                }
            }
            assertThat("cache " + cache, destination.toByteArray(), is(encode(data, cache, false)));
            assertThat("cache " + cache, decode(destination.toByteArray()), is(data));
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 9.2 and 9.3: offset writes and repeated flushes preserve pending bits")
    void preservesFrozenFlushAndFinalBits() throws IOException {
        byte[] pattern = "abracadabra\0".getBytes(StandardCharsets.US_ASCII);
        byte[] padded = new byte[84];
        for (int i = 0; i < 76; i++) {
            padded[i + 4] = pattern[i % pattern.length];
        }
        var destination = new ByteArrayOutputStream();
        try (var output = BrotliOutputStream.builder().outputStream(destination).build()) {
            output.flush();
            output.write(padded, 4, 31);
            output.write(padded, 35, 31);
            for (int i = 66; i < 80; i++) {
                output.write(padded[i]);
            }
            output.flush();
            output.flush();
        }
        assertThat(destination.toByteArray(), is(FLUSHED_DATA));
        assertThat(decode(destination.toByteArray()), is(Arrays.copyOfRange(padded, 4, 80)));
    }

    @Test
    void physicalGrowthDoesNotChangeLogicalFlushBoundary() throws IOException {
        byte[] data = alphabet(78);
        var destination = new ByteArrayOutputStream();
        try (var output = BrotliOutputStream.builder()
                .outputStream(destination)
                .cacheInputSize(77)
                .build()) {
            output.write(data, 0, 32);
            assertThat(destination.size(), is(0));
            output.write(data, 32, 45);
            assertThat(destination.size(), is(0));
            output.write(data, 0, 0);
            assertThat(destination.size(), is(0));
            output.write(data, 77, 1);
            assertThat(destination.size(), greaterThan(0));
        }
        assertThat(decode(destination.toByteArray()), is(data));
    }

    @Test
    @DisplayName("RFC 7932 section 9.3: empty close emits a complete final empty stream")
    void emptyClosePreservesFrozenStream() throws IOException {
        for (int cache : new int[] {4, 77, 64_000}) {
            var destination = new ByteArrayOutputStream();
            try (var output = BrotliOutputStream.builder()
                    .outputStream(destination)
                    .cacheInputSize(cache)
                    .build()) {
                output.write(new byte[0]);
                output.flush();
            }
            assertThat("cache " + cache, destination.toByteArray(), is(new byte[] {0x33}));
            assertThat("cache " + cache, decode(destination.toByteArray()), is(new byte[0]));
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 3.2 and 9.2: each flushed block has an independent prefix-code alphabet")
    void reusesWorkspacesAcrossChangingAlphabetFlushes() throws IOException {
        byte[][] sections = {
            new byte[1],
            alphabet(4097),
            "abracadabra".repeat(100).getBytes(StandardCharsets.US_ASCII),
            new byte[76],
            alphabet(257)
        };
        var expected = new ByteArrayOutputStream();
        var bulkDestination = new ByteArrayOutputStream();
        var scalarDestination = new ByteArrayOutputStream();
        try (var bulk = BrotliOutputStream.builder().outputStream(bulkDestination).build();
             var scalar = BrotliOutputStream.builder().outputStream(scalarDestination).build()) {
            for (byte[] section : sections) {
                expected.write(section);
                bulk.write(section);
                for (byte value : section) {
                    scalar.write(value);
                }
                bulk.flush();
                scalar.flush();
                bulk.flush();
                scalar.flush();
            }
        }
        assertThat(bulkDestination.toByteArray(), is(scalarDestination.toByteArray()));
        assertThat(decode(bulkDestination.toByteArray()), is(expected.toByteArray()));
    }

    @Test
    @DisplayName("RFC 7932 sections 3.2 and 9.2: fragment reuse preserves changing multi-block code trees")
    void reusesWorkspacesAcrossLargeFragments() throws IOException {
        byte[] data = new byte[300_003];
        Arrays.fill(data, 0, 98_304, (byte) 0x80);
        for (int i = 98_304; i < 163_840; i++) {
            data[i] = (byte) i;
        }
        byte[] pattern = "the quick brown fox".getBytes(StandardCharsets.US_ASCII);
        for (int i = 163_840; i < 230_000; i++) {
            data[i] = pattern[i % pattern.length];
        }
        for (int i = 230_000; i < data.length; i++) {
            data[i] = (byte) (i % 7);
        }
        for (int cache : new int[] {64_000, 128_123, 300_003}) {
            byte[] bulk = encode(data, cache, false);
            assertThat("cache " + cache, bulk, is(encode(data, cache, true)));
            assertThat("cache " + cache, decode(bulk), is(data));
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 3.4 and 9.2: each simple literal tree replaces the prior block alphabet")
    void replacesStaleSymbolsBeforeBuildingSimpleLiteralTree() throws IOException {
        byte[] data = new byte[128_123];
        Arrays.fill(data, 0, 98_304, (byte) 'A');
        for (int i = 98_304; i < data.length; i++) {
            data[i] = (byte) ((i & 1) == 0 ? 'B' : 'C');
        }
        assertThat("first differing plaintext byte",
                   Arrays.mismatch(data, decode(encode(data, data.length, false))), is(-1));
    }

    @Test
    @DisplayName("RFC 7932 sections 3.5 and 9.2: each complex literal tree replaces the prior block alphabet")
    void replacesStaleSymbolsBeforeBuildingComplexLiteralTree() throws IOException {
        byte[] pattern = "abracadabra\0".getBytes(StandardCharsets.US_ASCII);
        for (int length : new int[] {128_123, 300_003}) {
            byte[] data = alphabet(length);
            for (int i = 98_304; i < data.length; i++) {
                data[i] = pattern[(i - 98_304) % pattern.length];
            }
            assertThat("payload length " + length + ", first differing plaintext byte",
                       Arrays.mismatch(data, decode(encode(data, 128_123, false))), is(-1));
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 2 and 5: copy lengths preserve repeated prefixes and the first differing literal")
    void preservesRepeatedPrefixesAndFollowingLiterals() throws IOException {
        for (int repeated : new int[] {5, 6, 12, 13, 14, 20, 21, 22, 261, 4101}) {
            int sourceLength = Math.max(128, repeated + 1);
            int copyStart = sourceLength + 31;
            for (boolean finalCopy : new boolean[] {false, true}) {
                byte[] data = new byte[copyStart + repeated + (finalCopy ? 0 : 65)];
                new Random(7932).nextBytes(data);
                System.arraycopy(data, 0, data, copyStart, repeated);
                if (!finalCopy) {
                    data[copyStart + repeated] = (byte) (data[repeated] ^ 0xFF);
                }
                byte[] encoded = encode(data, 64_000, false);
                String description = "repeated prefix " + repeated + ", final copy " + finalCopy;
                assertThat(description + ", first differing encoded byte",
                           Arrays.mismatch(encoded, encode(data, 64_000, true)), is(-1));
                assertThat(description + ", first differing plaintext byte",
                           Arrays.mismatch(data, decode(encoded)), is(-1));
            }
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 2 and 5: overlapping copies preserve high-bit bytes through the final input byte")
    void preservesOverlappingPatternsAndShortInputTails() throws IOException {
        for (int period : new int[] {1, 2, 3, 5, 7, 8, 16}) {
            for (int length : new int[] {0, 1, 4, 5, 7, 8, 9, 15, 16, 17, 127, 4097}) {
                byte[] data = new byte[length];
                for (int i = 0; i < data.length; i++) {
                    data[i] = (byte) (0x80 + i % period);
                }
                for (int cache : new int[] {77, 64_000}) {
                    byte[] encoded = encode(data, cache, false);
                    String description = "period " + period + ", length " + length + ", cache " + cache;
                    assertThat(description + ", first differing encoded byte",
                               Arrays.mismatch(encoded, encode(data, cache, true)), is(-1));
                    assertThat(description + ", first differing plaintext byte",
                               Arrays.mismatch(data, decode(encoded)), is(-1));
                }
            }
        }
    }

    private static byte[] alphabet(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    private static byte[] encode(byte[] data, int cache, boolean scalar) throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var output = BrotliOutputStream.builder()
                .outputStream(destination)
                .cacheInputSize(cache)
                .build()) {
            if (scalar) {
                for (byte value : data) {
                    output.write(value);
                }
            } else {
                output.write(data);
            }
        }
        return destination.toByteArray();
    }

    private static byte[] decode(byte[] encoded) throws IOException {
        try (var input = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(encoded)).build()) {
            return input.readAllBytes();
        }
    }
}
