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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import io.helidon.common.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Flush-progress tests using the byte-alignment mechanism described by
 * <a href="https://www.rfc-editor.org/rfc/rfc7932.html#section-11.2">RFC 7932 section 11.2</a>
 * and the metadata format in section 9.2. Output scheduling itself is a stream contract.
 */
class BrotliFlushTest {
    @Test
    @DisplayName("RFC 7932 sections 9.2 and 11.2: flushed compressed data is decodable before close")
    void publishesCompressedPayloadBeforeClose() throws IOException {
        assertFlushedPayloads(18, 64_000, new byte[][] {repetitiveData(76)});
    }

    @Test
    void continuesWritingAfterEachFlushedPayloadAcrossWindows() throws IOException {
        for (int window : new int[] {10, 16, 18, 24}) {
            assertFlushedPayloads(window, 64_000,
                                 new byte[][] {repetitiveData(128), repetitiveData(76), repetitiveData(256)});
        }
    }

    @Test
    void flushesWithSmallInputCaches() throws IOException {
        for (int cacheSize : new int[] {4, 5, 7, 76, 77}) {
            assertFlushedPayloads(18, cacheSize, new byte[][] {repetitiveData(256), repetitiveData(128)});
        }
    }

    @Test
    void flushesAcrossInputCacheBoundaries() throws IOException {
        for (int length : new int[] {63_999, 64_000, 64_001, 128_123}) {
            assertFlushedPayloads(18, 64_000, new byte[][] {repetitiveData(length)});
        }
    }

    @Test
    void flushesIncompressiblePayloadsWithoutEndingStream() throws IOException {
        byte[] first = new byte[4097];
        byte[] second = new byte[8193];
        new Random(4097).nextBytes(first);
        new Random(7932).nextBytes(second);
        assertFlushedPayloads(18, 64_000, new byte[][] {first, second});
    }

    @Test
    void emptyAndRepeatedFlushesDoNotAddEncodedData() throws IOException {
        var destination = new ByteArrayOutputStream();
        var source = new PublishedInputStream();
        try (var input = new BrotliInputStream(source)) {
            try (var output = new BrotliOutputStream(destination)) {
                output.flush();
                output.flush();
                assertThat(destination.size(), is(0));

                byte[] data = "my data".getBytes(StandardCharsets.US_ASCII);
                output.write(data);
                output.flush();
                byte[] flushed = destination.toByteArray();
                output.flush();
                output.flush();
                assertThat(destination.toByteArray(), is(flushed));

                source.publish(flushed);
                assertThat(input.readNBytes(data.length), is(data));
            }
            source.publish(destination.toByteArray());
            source.finish();
            assertThat(input.read(), is(-1));
        }
    }

    private static void assertFlushedPayloads(int window, int cacheSize, byte[][] payloads) throws IOException {
        var destination = new ByteArrayOutputStream();
        var source = new PublishedInputStream();
        var outputConfig = BrotliOutputStreamConfig.builder().window(window).cacheInputSize(Size.create(cacheSize)).build();
        try (var input = new BrotliInputStream(source, BrotliInputStreamConfig.builder().cacheBufferSize(Size.create(17)).build())) {
            try (var output = new BrotliOutputStream(destination, outputConfig)) {
                for (int i = 0; i < payloads.length; i++) {
                    byte[] data = payloads[i];
                    output.write(data);
                    output.flush();
                    source.publish(destination.toByteArray());
                    assertThat("window " + window + ", cache " + cacheSize + ", flush " + i,
                               input.readNBytes(data.length), is(data));
                }
            }
            source.publish(destination.toByteArray());
            source.finish();
            assertThat(input.read(), is(-1));
        }
    }

    private static byte[] repetitiveData(int length) {
        byte[] pattern = "abracadabra\0".getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = pattern[i % pattern.length];
        }
        return data;
    }

    /**
     * Exposes only bytes published by a completed flush. An attempted read of unpublished bytes
     * fails instead of blocking, making producer/consumer progress independent of timing.
     */
    private static final class PublishedInputStream extends InputStream {
        private byte[] published = new byte[0];
        private int position;
        private boolean finished;

        @Override
        public int read() throws IOException {
            if (position == published.length) {
                return exhaustedPublication();
            }
            return published[position++] & 0xFF;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position == published.length) {
                return exhaustedPublication();
            }
            int count = Math.min(length, Math.min(7, published.length - position));
            System.arraycopy(published, position, destination, offset, count);
            position += count;
            return count;
        }

        @Override
        public int available() {
            return 0;
        }

        void publish(byte[] bytes) {
            published = bytes;
        }

        void finish() {
            finished = true;
        }

        private int exhaustedPublication() throws IOException {
            if (finished) {
                return -1;
            }
            throw new IOException("Encoder.flush() did not publish enough bytes to decode its payload");
        }
    }
}
