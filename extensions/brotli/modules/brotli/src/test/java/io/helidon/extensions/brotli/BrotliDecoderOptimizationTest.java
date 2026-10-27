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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import io.helidon.common.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrotliDecoderOptimizationTest {
    // RFC 7932 sections 3.4 and 5: all simple prefix shapes with a four-literal command.
    // Every frozen stream and expected output was independently verified by Google Brotli 1.2.1.
    private static final List<Fixture> SIMPLE_TREES = List.of(
            new Fixture("simple-0", "3000000044508010c0", "41414141"),
            new Fixture("simple-1", "300000005490508010800e", "41424142"),
            new Fixture("simple-2", "30000000a456505680108036", "5a41595a"),
            new Fixture("simple-3", "30000000b456509690002180c901", "5a415942"),
            new Fixture("simple-4", "30000000b4565096d00021007d03", "5a415942"),
            new Fixture("simple-5", "30000000c47f8010c0", "ffffffff"),
            new Fixture("simple-6", "3000000004408010c0", "00000000"));
    // RFC 7932 section 9.2: fourteen metablocks change alphabet shapes, including zero and high-bit literals.
    private static final Fixture CHANGING_TREES = new Fixture("changing alphabets",
            "300000004450801000060000800a120a1002500c000000a91594152004a06100000068ada02c2101"
                    + "420093180000005a2b284b688010803e0c000000f11f200480010000200002840030000000044080"
                    + "100006000080f80f1002c0000000d05a415942038400f46100000068ada02c210142009318000000"
                    + "522b282b400840c30000005041420142008a010000208202840006",
            "41414141414241425a41595a5a4159425a415942ffffffff0000000000000000ffffffff5a415942"
                    + "5a4159425a41595a4142414241414141");
    // RFC 7932 section 6: two literal block types alternate every byte, sharing a singleton literal tree.
    private static final Fixture BLOCK_SWITCHES = new Fixture("alternating literal blocks",
            "300020a200000011142004000c", "41414141");
    // Sections 4 and 9.2: symbol 18 resolves differently for NDIRECT = 0, 1 and 2, with NPOSTFIX = 0.
    // Google Brotli 1.2.1 independently verified these streams and their plaintext.
    private static final Fixture DEFAULT_DISTANCES = new Fixture("default distance alphabet",
            "700010414243444546474878000000222884094906",
            "414243444546474844454647484445464748444546474844");
    private static final Fixture DIRECT_ONE = new Fixture("one direct distance code",
            "700010414243444546474878000002222884098906",
            "414243444546474845464748454647484546474845464748");
    private static final Fixture DIRECT_TWO = new Fixture("two direct distance codes",
            "700010414243444546474878000004222884098906",
            "414243444546474846474846474846474846474846474846");
    private static final List<Fixture> CHANGING_DISTANCES = List.of(
            new Fixture("default, direct one, default, direct two",
                    "70001041424344454647487800000022288409497000104142434445464748780000022228840989"
                            + "7000104142434445464748780000002228840949700010414243444546474878000004222884098906",
                    DEFAULT_DISTANCES.expected() + DIRECT_ONE.expected()
                            + DEFAULT_DISTANCES.expected() + DIRECT_TWO.expected()),
            new Fixture("direct two, default, direct one, default",
                    "70001041424344454647487800000422288409897000104142434445464748780000002228840949"
                            + "7000104142434445464748780000022228840989700010414243444546474878000000222884094906",
                    DIRECT_TWO.expected() + DEFAULT_DISTANCES.expected()
                            + DIRECT_ONE.expected() + DEFAULT_DISTANCES.expected()));
    private static final Fixture REPEATED_DISTANCE_HEADERS = new Fixture("64 changing distance headers",
            ("7000104142434445464748780000002228840949"
                    + "7000104142434445464748780000022228840989"
                    + "7000104142434445464748780000002228840949"
                    + "7000104142434445464748780000042228840989").repeat(16) + "06",
            (DEFAULT_DISTANCES.expected() + DIRECT_ONE.expected()
                    + DEFAULT_DISTANCES.expected() + DIRECT_TWO.expected()).repeat(16));
    // Sections 3.2, 3.5, 7 and 9.2: complex code lengths cross the eight-bit table root,
    // and four literal context trees replace a single tree between metablocks.
    // Google Brotli 1.2.1 independently verified every encoded stream and expected output.
    private static final List<Fixture> COMPLEX_AND_CONTEXT_TREES = List.of(
            new Fixture("complex lengths three",
                    "700000005055445555cccccccc8143004039dd07", "0001020304050607"),
            new Fixture("complex lengths one through nine",
                    "900000005055445555482c6a1e9901500068f7bedfdfbfff03", "00010203040506070809"),
            new Fixture("four literal context trees",
                    "3000000053930300000050555555adaaaaaafaffffff27200224600220400860", "10203000"),
            new Fixture("one, four, one literal trees",
                    "700000005055445555cccccccc8143004039dd3100000053930300000050555555adaaaaaafaffffff"
                            + "27200224600220400800090000005545545585c4a2e6911900058076effbfdfdfb3f",
                    "00010203040506071020300000010203040506070809"));
    // Sections 4, 5 and 9.2: literal history is repeated by distance-one and distance-two copies.
    private static final List<Fixture> OVERLAPPING_COPIES = List.of(
            new Fixture("1 overlapping copies",
                    "2000104141413800000022280c09c8",
                    "41".repeat(11)),
            new Fixture("20 overlapping copies",
                    "2000104141413800000022280c09080e000080080a4302820300002082c29080e000000088a03024"
                            + "203800000022280c09080e000080080a4302820300002082c29080e000000088a030242038000000"
                            + "22280c09080e000080080a4302820300002082c29080e000000088a03024203800000022280c0908"
                            + "0e000080080a4302820300002082c29080e000000088a03024203800000022280c09080e00008008"
                            + "0a4302820300002082c29080e000000088a030242003",
                    "41".repeat(163)),
            new Fixture("sixteen repeated bytes",
                    "20001041414178000000222884094803", "41".repeat(19)),
            new Fixture("sixteen repeated pairs",
                    "10001041427800000022288409c803", "4142".repeat(9)));
    private static final List<ReadSchedule> SCHEDULES = List.of(
            new ReadSchedule(1, 1, 1),
            new ReadSchedule(1, 2, 3),
            new ReadSchedule(2, 7, 17),
            new ReadSchedule(17, 8192, 256),
            new ReadSchedule(Integer.MAX_VALUE, 8192, 256));

    @Test
    @DisplayName("RFC 7932 section 3.4: one through four simple prefix symbols decode with partial reads")
    void decodesEverySimplePrefixShape() throws IOException {
        for (Fixture fixture : SIMPLE_TREES) {
            assertDecoded(fixture);
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 3.4 and 9.2: changing metablock alphabets do not retain prior symbols")
    void decodesChangingAlphabetsAcrossMetablocks() throws IOException {
        assertDecoded(CHANGING_TREES);
    }

    @Test
    @DisplayName("RFC 7932 section 6: literal block switches preserve their type and length prefix trees")
    void decodesAlternatingLiteralBlocks() throws IOException {
        assertDecoded(BLOCK_SWITCHES);
    }

    @Test
    @DisplayName("RFC 7932 sections 4 and 9.2: distance alphabets change across metablocks and independent streams")
    void decodesChangingDistanceAlphabets() throws IOException {
        for (Fixture fixture : List.of(DEFAULT_DISTANCES, DIRECT_ONE, DEFAULT_DISTANCES,
                                       DIRECT_TWO, DEFAULT_DISTANCES)) {
            assertDecoded(fixture);
        }
        for (Fixture fixture : CHANGING_DISTANCES) {
            assertDecoded(fixture);
        }
        assertDecoded(REPEATED_DISTANCE_HEADERS);
    }

    @Test
    @DisplayName("RFC 7932 sections 3.5, 7 and 9.2: complex prefixes and context maps change across metablocks")
    void decodesComplexPrefixesAndChangingContextTrees() throws IOException {
        for (Fixture fixture : COMPLEX_AND_CONTEXT_TREES) {
            assertDecoded(fixture);
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 4, 5 and 9.2: overlapping copies preserve history across small metablocks")
    void decodesOverlappingCopiesWhileHistoryGrows() throws IOException {
        for (Fixture fixture : OVERLAPPING_COPIES) {
            assertDecoded(fixture);
        }
    }

    @Test
    @DisplayName("RFC 7932 sections 3.4 and 9.2: incomplete simple prefix streams are rejected at every byte boundary")
    void rejectsTruncatedSimplePrefixStreams() throws IOException {
        for (Fixture fixture : SIMPLE_TREES) {
            byte[] encoded = HexFormat.of().parseHex(fixture.encoded());
            for (int length = 0; length < encoded.length; length++) {
                for (int sourceChunk : new int[] {1, Integer.MAX_VALUE}) {
                    String description = fixture.name() + ", prefix " + length + ", source chunk " + sourceChunk;
                    var inputConfig = BrotliInputStreamConfig.builder().verifyEndOfInput(true).build();
                    try (var input = new BrotliInputStream(new ChunkedInputStream(Arrays.copyOf(encoded, length), sourceChunk),
                                                           inputConfig)) {
                        assertThrows(IOException.class, input::readAllBytes, description);
                    }
                }
            }
        }
    }

    private static void assertDecoded(Fixture fixture) throws IOException {
        byte[] encoded = HexFormat.of().parseHex(fixture.encoded());
        byte[] expected = HexFormat.of().parseHex(fixture.expected());
        for (ReadSchedule schedule : SCHEDULES) {
            String description = fixture.name() + ", " + schedule;
            var destination = new ByteArrayOutputStream();
            var inputConfig = BrotliInputStreamConfig.builder()
                    .cacheBufferSize(Size.create(schedule.cacheSize()))
                    .verifyEndOfInput(true)
                    .build();
            try (var input = new BrotliInputStream(new ChunkedInputStream(encoded, schedule.sourceChunk()), inputConfig)) {
                if (schedule.readChunk() == 1) {
                    for (int value; (value = input.read()) != -1;) {
                        destination.write(value);
                    }
                } else {
                    byte[] buffer = new byte[schedule.readChunk() + 4];
                    Arrays.fill(buffer, (byte) 0xDA);
                    for (int count; (count = input.read(buffer, 2, schedule.readChunk())) != -1;) {
                        assertThat(description + " makes progress", count, greaterThan(0));
                        destination.write(buffer, 2, count);
                        assertThat(description + " preserves prefix", buffer[1], is((byte) 0xDA));
                        assertThat(description + " preserves suffix", buffer[buffer.length - 2], is((byte) 0xDA));
                    }
                }
            }
            assertThat(description, destination.toByteArray(), is(expected));
        }
    }

    private record Fixture(String name, String encoded, String expected) {
    }

    private record ReadSchedule(int sourceChunk, int readChunk, int cacheSize) {
    }

    private static final class ChunkedInputStream extends ByteArrayInputStream {
        private final int chunkSize;

        private ChunkedInputStream(byte[] encoded, int chunkSize) {
            super(encoded);
            this.chunkSize = chunkSize;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            return super.read(destination, offset, Math.min(length, chunkSize));
        }
    }
}
