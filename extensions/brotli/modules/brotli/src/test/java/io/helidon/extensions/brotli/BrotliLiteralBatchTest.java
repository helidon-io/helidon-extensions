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
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import io.helidon.common.Size;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

class BrotliLiteralBatchTest {
    private static final List<Schedule> SCHEDULES = List.of(
            new Schedule(1, 7),
            new Schedule(2, 8192),
            new Schedule(17, 1),
            new Schedule(17, 8192),
            new Schedule(Integer.MAX_VALUE, 7),
            new Schedule(Integer.MAX_VALUE, 8192));

    @Test
    void decodesLongLiteralRunsAcrossRingGrowthAndWrap() throws IOException {
        // WBITS=16 and 70,000 literal bytes cross the 65,536-byte history window.
        for (Alphabet alphabet : Alphabet.values()) {
            assertDecoded(literalStream(alphabet, false));
        }
    }

    @Test
    void switchesLiteralTreesAfterSeveralLiterals() throws IOException {
        assertDecoded(literalStream(Alphabet.SINGLETON, true));
    }

    @Test
    void returnsRequestedLiteralsBeforeReadingWithheldInput() throws IOException {
        Fixture fixture = literalStream(Alphabet.PAIR, false);
        // Release only enough compressed input for the header and 512 one-bit literals.
        // The producer remains open; reading beyond the released prefix fails immediately.
        int prefix = (fixture.payloadBitOffset() + 512 + 7) / 8;
        for (int sourceChunk : new int[] {1, Integer.MAX_VALUE}) {
            var source = new ReleasedPrefixInputStream(fixture.encoded(), prefix, sourceChunk);
            var config = BrotliInputStreamConfig.builder().cacheBufferSize(Size.create(1)).build();
            try (var input = new BrotliInputStream(source, config)) {
                assertThat("source chunk " + sourceChunk,
                           input.readNBytes(385), is(Arrays.copyOf(fixture.expected(), 385)));
                source.releaseAll();
                assertThat(input.readAllBytes(), is(Arrays.copyOfRange(fixture.expected(), 385, fixture.expected().length)));
                assertThat(input.read(), is(-1));
            }
        }
    }

    private static void assertDecoded(Fixture fixture) throws IOException {
        for (Schedule schedule : SCHEDULES) {
            String description = fixture.name() + ", " + schedule;
            var config = BrotliInputStreamConfig.builder().cacheBufferSize(Size.create(3)).verifyEndOfInput(true).build();
            var destination = new ByteArrayOutputStream();
            try (var input = new BrotliInputStream(new ChunkedInputStream(fixture.encoded(), schedule.sourceChunk()), config)) {
                byte[] buffer = new byte[schedule.outputChunk() + 4];
                byte[] canaries = new byte[buffer.length];
                Arrays.fill(canaries, (byte) 0xDA);
                for (;;) {
                    Arrays.fill(buffer, (byte) 0xDA);
                    int count = input.read(buffer, 2, schedule.outputChunk());
                    assertThat(description + " first mismatching prefix byte",
                               Arrays.mismatch(buffer, 0, 2, canaries, 0, 2), is(-1));
                    int written = Math.max(0, count);
                    assertThat(description + " first mismatching byte after returned output",
                               Arrays.mismatch(buffer, 2 + written, buffer.length,
                                               canaries, 2 + written, canaries.length), is(-1));
                    if (count == -1) {
                        break;
                    }
                    assertThat(description + " makes progress", count, greaterThan(0));
                    destination.write(buffer, 2, count);
                }
                assertThat(description + " remains at EOF", input.read(), is(-1));
            }
            assertThat(description, destination.toByteArray(), is(fixture.expected()));
        }
    }

    static Fixture literalStream(Alphabet alphabet, boolean switches) {
        // RFC 7932 sections 2, 3.4, 3.5, 5, 6, 7 and 9.2. A single insert command
        // fills the final metablock, so its copy and distance are never consumed.
        int length = switches ? 1024 : 70_000;
        var bits = new BitWriter();
        bits.write(1, 0); // WBITS=16.
        bits.write(1, 1); // ISLAST.
        bits.write(1, 0); // Not empty.
        int nibbles = switches ? 4 : 5;
        bits.write(2, nibbles - 4);
        bits.write(4 * nibbles, length - 1);
        if (switches) {
            bits.write(4, 1); // Two literal block types: varlen unsigned value 1.
            bits.singleton(2, 1); // Block type 1 means the next type, modulo two.
            bits.singleton(5, 1); // Block length symbol 1 means 5 + two extra bits.
            bits.write(2, 3); // Initial literal block length 8.
            bits.write(2, 0); // One command block type and one distance block type.
            bits.write(6, 0); // NPOSTFIX=NDIRECT=0.
            bits.write(4, 0); // Context mode 0 for both literal block types.
            bits.write(4, 1); // Two literal trees.
            bits.write(1, 0); // No context-map zero run encoding.
            bits.write(2, 1); // Simple context-map prefix code.
            bits.write(2, 1); // Two symbols.
            bits.write(1, 0);
            bits.write(1, 1);
            for (int i = 0; i < 128; i++) {
                bits.write(1, i / 64); // Identity map: type 0 -> tree 0; type 1 -> tree 1.
            }
            bits.write(1, 0); // No inverse move-to-front transform.
            bits.write(1, 0); // One distance tree.
            bits.singleton(8, 'A');
            bits.singleton(8, 'B');
        } else {
            bits.write(13, 0); // One type per alphabet, zero distance params, mode 0, one tree per map.
            switch (alphabet) {
                case SINGLETON -> bits.singleton(8, 'A');
                case PAIR -> {
                    bits.write(2, 1);
                    bits.write(2, 1);
                    bits.write(8, 'A');
                    bits.write(8, 'B');
                }
                case COMPLEX -> {
                    // A complete complex tree: symbols 0..15 have lengths 1,2,...,15,15.
                    // Its code-length alphabet uses all symbols 0..15 with length 4.
                    bits.write(2, 0);
                    for (int symbol : new int[] {1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15}) {
                        bits.write(2, symbol < 16 ? 1 : 0); // Fixed prefix for length 4 or 0.
                    }
                    for (int symbol = 0; symbol < 16; symbol++) {
                        bits.write(4, Integer.reverse(Math.min(symbol + 1, 15)) >>> 28);
                    }
                }
            }
        }
        // Section 5: command 472 is insert code 19 (578 + 9 extra bits), copy code 0;
        // command 504 is insert code 23 (22594 + 24 extra bits), copy code 0.
        bits.singleton(10, switches ? 472 : 504);
        bits.singleton(6, 0); // Unused distance tree.
        bits.write(switches ? 9 : 24, length - (switches ? 578 : 22594));
        int payloadBitOffset = bits.bitCount();
        byte[] expected = new byte[length];
        for (int i = 0; i < length; i++) {
            if (switches) {
                if (i != 0 && i % 8 == 0) {
                    bits.write(2, 3); // Next block has eight literals; type and length trees are singletons.
                }
                expected[i] = (byte) ('A' + (i / 8) % 2);
            } else {
                switch (alphabet) {
                    case SINGLETON -> expected[i] = 'A';
                    case PAIR -> {
                        expected[i] = (byte) ('A' + (i & 1));
                        bits.write(1, i & 1);
                    }
                    case COMPLEX -> {
                        int symbol = i % 16;
                        expected[i] = (byte) symbol;
                        int depth = Math.min(symbol + 1, 15);
                        // Canonical codes are 0,10,110,...,111111111111110,111111111111111.
                        bits.write(depth, symbol == 15 ? 32767 : (1 << symbol) - 1);
                    }
                }
            }
        }
        return new Fixture(switches ? "eight-literal block switches" : alphabet.name(),
                           bits.toByteArray(), expected, payloadBitOffset);
    }

    enum Alphabet {
        SINGLETON, PAIR, COMPLEX
    }

    record Fixture(String name, byte[] encoded, byte[] expected, int payloadBitOffset) {
    }

    private record Schedule(int sourceChunk, int outputChunk) {
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream destination = new ByteArrayOutputStream();
        private int pending;
        private int count;

        private void write(int length, int value) {
            for (int i = 0; i < length; i++) {
                pending |= ((value >>> i) & 1) << count++;
                if (count == 8) {
                    destination.write(pending);
                    pending = 0;
                    count = 0;
                }
            }
        }

        private void singleton(int width, int symbol) {
            write(2, 1);
            write(2, 0);
            write(width, symbol);
        }

        private int bitCount() {
            return destination.size() * 8 + count;
        }

        private byte[] toByteArray() {
            if (count != 0) {
                destination.write(pending);
            }
            return destination.toByteArray();
        }
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

    private static final class ReleasedPrefixInputStream extends InputStream {
        private final byte[] encoded;
        private final int chunkSize;
        private int released;
        private int position;

        private ReleasedPrefixInputStream(byte[] encoded, int released, int chunkSize) {
            this.encoded = encoded;
            this.released = released;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position == encoded.length) {
                return -1;
            }
            if (position == released) {
                throw new IOException("Decoder requested input beyond the released literal prefix");
            }
            int count = Math.min(length, Math.min(chunkSize, released - position));
            System.arraycopy(encoded, position, destination, offset, count);
            position += count;
            return count;
        }

        private void releaseAll() {
            released = encoded.length;
        }
    }
}
