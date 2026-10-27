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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrotliStreamingTest {
    private static final byte[] DATA = "my data".getBytes(StandardCharsets.US_ASCII);
    // Independent fixture used by BrotliStreamTest: an uncompressed block and a final empty block.
    private static final byte[] COMPLETE_STREAM = HexFormat.of().parseHex("0303806d79206461746103");
    // RFC 7932 section 9.2 fixture from BrotliConformanceTest, accepted by Google Brotli 1.2.1.
    // Literal C finishes at bit 98; the following metadata header and final block arrive separately.
    private static final byte[] COMPRESSED_WITH_METADATA = HexFormat.of().parseHex("000000007490d010d140248059000003");

    @Test
    void readsCompleteSmallStreamWithoutWaitingForSourceEof() throws IOException {
        for (int chunkSize : new int[] {1, 2, Integer.MAX_VALUE}) {
            var source = new ReleasedPrefixInputStream(COMPLETE_STREAM, COMPLETE_STREAM.length, chunkSize);
            try (var input = BrotliInputStream.builder().inputStream(source).build()) {
                assertThat("source chunk size " + chunkSize, input.readNBytes(DATA.length), is(DATA));
                assertThat(input.read(), is(-1));
            }
        }
    }

    @Test
    void returnsUncompressedBlockBeforeReadingNextHeader() throws IOException {
        byte[] encoded = uncompressedStream(DATA);
        var source = new ReleasedPrefixInputStream(encoded, encoded.length - 1, Integer.MAX_VALUE);
        try (var input = BrotliInputStream.builder().inputStream(source).build()) {
            byte[] destination = new byte[64];
            int count = input.read(destination);
            assertThat(count, is(DATA.length));
            assertThat(Arrays.copyOf(destination, count), is(DATA));

            source.releaseThrough(encoded.length);
            assertThat(input.read(), is(-1));
        }
    }

    @Test
    void returnsBufferedBytesBeforeRequestingMoreInput() throws IOException {
        byte[] encoded = uncompressedStream(DATA);
        var source = new ReleasedPrefixInputStream(encoded, encoded.length - 1, Integer.MAX_VALUE);
        try (var input = BrotliInputStream.builder().inputStream(source).build()) {
            assertThat(input.read(), is((int) DATA[0]));

            byte[] destination = new byte[64];
            int count = input.read(destination, 2, destination.length - 2);
            assertThat(count, is(DATA.length - 1));
            assertThat(Arrays.copyOfRange(destination, 2, 2 + count), is(Arrays.copyOfRange(DATA, 1, DATA.length)));

            source.releaseThrough(encoded.length);
            assertThat(input.read(), is(-1));
        }
    }

    @Test
    void returnsCompressedLiteralBeforeReadingFollowingMetadata() throws IOException {
        var source = new ReleasedPrefixInputStream(COMPRESSED_WITH_METADATA, 13, 1);
        try (var input = BrotliInputStream.builder().inputStream(source).build()) {
            byte[] destination = new byte[64];
            assertThat(input.read(destination), is(1));
            assertThat(destination[0], is((byte) 'C'));

            source.releaseThrough(COMPRESSED_WITH_METADATA.length);
            assertThat(input.read(), is(-1));
        }
    }

    @Test
    void returnsRequestedBytesBeforeUncompressedBlockIsComplete() throws IOException {
        byte[] encoded = uncompressedStream(DATA);
        int headerLength = encoded.length - DATA.length - 1;
        var source = new ReleasedPrefixInputStream(encoded, headerLength + 3, Integer.MAX_VALUE);
        try (var input = BrotliInputStream.builder().inputStream(source).cacheBufferSize(1).build()) {
            assertThat(input.readNBytes(3), is(Arrays.copyOf(DATA, 3)));

            source.releaseThrough(encoded.length);
            assertThat(input.readNBytes(DATA.length - 3), is(Arrays.copyOfRange(DATA, 3, DATA.length)));
            assertThat(input.read(), is(-1));
        }
    }

    @Test
    void resumesAfterEachReleasedChunkAcrossRingBufferWrap() throws IOException {
        byte[] expected = new byte[66_000];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        byte[] encoded = uncompressedStream(expected);
        int headerLength = encoded.length - expected.length - 1;
        var source = new ReleasedPrefixInputStream(encoded, headerLength, 7);
        try (var input = BrotliInputStream.builder().inputStream(source).build()) {
            for (int offset = 0; offset < expected.length;) {
                int length = Math.min(257, expected.length - offset);
                source.releaseThrough(headerLength + offset + length);
                assertThat("decoded offset " + offset,
                           input.readNBytes(length), is(Arrays.copyOfRange(expected, offset, offset + length)));
                offset += length;
            }
            source.releaseThrough(encoded.length);
            assertThat(input.read(), is(-1));
        }
    }

    @Test
    void rejectsTruncationAfterReturningAvailableBytes() throws IOException {
        byte[] encoded = uncompressedStream(DATA);
        int headerLength = encoded.length - DATA.length - 1;
        var source = new ReleasedPrefixInputStream(encoded, headerLength + 3, Integer.MAX_VALUE);
        try (var input = BrotliInputStream.builder().inputStream(source).build()) {
            assertThat(input.readNBytes(3), is(Arrays.copyOf(DATA, 3)));

            source.finish();
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    @Test
    void checksFinalPaddingAfterReturningAvailableBytes() throws IOException {
        byte[] encoded = uncompressedStream(DATA);
        encoded[encoded.length - 1] |= (byte) 0x80;
        var source = new ReleasedPrefixInputStream(encoded, encoded.length - 1, Integer.MAX_VALUE);
        try (var input = BrotliInputStream.builder().inputStream(source).build()) {
            assertThat(input.readNBytes(DATA.length), is(DATA));

            source.releaseThrough(encoded.length);
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    private static byte[] uncompressedStream(byte[] data) {
        // RFC 7932 sections 9.1 and 9.2: WBITS=16, nonfinal uncompressed MLEN=data.length,
        // zero alignment bits, raw payload, then ISLAST=1/ISLASTEMPTY=1 and zero terminal fill.
        int nibbles = data.length <= 65_536 ? 4 : 5;
        int headerLength = (5 + 4 * nibbles + 7) / 8;
        long header = ((long) (data.length - 1) << 4) | ((long) (nibbles - 4) << 2) | (1L << (4 + 4 * nibbles));
        byte[] encoded = new byte[headerLength + data.length + 1];
        for (int i = 0; i < headerLength; i++) {
            encoded[i] = (byte) (header >>> (8 * i));
        }
        System.arraycopy(data, 0, encoded, headerLength, data.length);
        encoded[encoded.length - 1] = 3;
        return encoded;
    }

    /**
     * A producer that releases a prefix and remains open. An unwanted read fails immediately
     * instead of blocking forever, so assertions test progress without timing assumptions.
     * {@code available()} deliberately supplies no hint about bytes that a read can obtain.
     */
    private static final class ReleasedPrefixInputStream extends InputStream {
        private final byte[] data;
        private final int chunkSize;

        private int released;
        private int position;
        private boolean finished;

        private ReleasedPrefixInputStream(byte[] data, int released, int chunkSize) {
            this.data = data;
            this.released = released;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() throws IOException {
            if (position == released) {
                return exhaustedPrefix();
            }
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position == released) {
                return exhaustedPrefix();
            }
            int count = Math.min(length, Math.min(chunkSize, released - position));
            System.arraycopy(data, position, destination, offset, count);
            position += count;
            return count;
        }

        @Override
        public int available() {
            return 0;
        }

        void releaseThrough(int limit) {
            released = limit;
        }

        void finish() {
            finished = true;
        }

        private int exhaustedPrefix() throws IOException {
            if (finished) {
                return -1;
            }
            throw new IOException("Decoder requested more input before consuming the released payload");
        }
    }
}
