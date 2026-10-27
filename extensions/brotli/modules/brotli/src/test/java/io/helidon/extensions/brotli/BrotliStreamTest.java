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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrotliStreamTest {
    private static final byte[] DATA = "my data".getBytes(StandardCharsets.UTF_8);
    // Frozen Brotli bytes from the upstream stream tests, independent of this encoder.
    private static final byte[] COMPRESSED_DATA = HexFormat.of().parseHex("0303806d79206461746103");
    private static final String INTEROP_TEXT = """
            Helidon Brotli interoperability fixture.
            The quick brown fox jumps over the lazy dog.
            Compression and decompression must preserve every byte.
            """.repeat(2);
    // Produced with Google's brotli CLI at quality 11. No native test dependency is required.
    private static final byte[] INTEROP_DATA = HexFormat.of().parseHex(
            "a1d808006e9c1c6bb7e3562a2051a9732a1ba16ac494db69f617ca3afa1eb6bbc1017b719c0740810fbcf9803caa63ce66dc94"
                    + "afa39bc644cb2837d82c662c6697cdc3767d49f14370584c19b131d12f0047efff81c62d5e34b54f85f268282c2d"
                    + "13a3e821d7290a03");
    // Google brotli CLI, quality 11 and a 64 KiB window, forcing copy resumption across ring-buffer flushes.
    private static final byte[] LONG_INTEROP_DATA = HexFormat.of().parseHex(
            "f4ff1f801b27c7daedb8950a4854ea9cca46a81a31e5769afd85b28ebe87ed6e70c05e1ce70150e0036f3ea88dea98b31937e5"
                    + "ebe8a631d132ca0d368b198bd965f3b05d5f52fc101c1653466c4cf40bc0d1fb7fa0718b174ded53a13c1a0a4bcbc428"
                    + "7a2cf701d7290a83feff037e0980382c00d0bd0fd0ff7fc02f0110870500baf701faff0ff82500e2b00040f73e207e55"
                    + "f14b00c46101809e2800");

    @Test
    void writesKnownBrotliStream() throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var stream = BrotliOutputStream.builder().outputStream(destination).build()) {
            stream.write(DATA);
        }
        assertThat(destination.toByteArray(), is(COMPRESSED_DATA));
    }

    @Test
    void decodesIndependentQualityElevenStream() throws IOException {
        try (var stream = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(INTEROP_DATA)).build()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8), is(INTEROP_TEXT));
        }
    }

    @Test
    void decodesIndependentLongCopyStream() throws IOException {
        try (var stream = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(LONG_INTEROP_DATA)).build()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8), is(INTEROP_TEXT.repeat(2000)));
        }
    }

    @Test
    void resumesIndependentLongCopyWithOneByteReads() throws IOException {
        byte[] expected = INTEROP_TEXT.repeat(2000).getBytes(StandardCharsets.UTF_8);
        byte[] actual = new byte[expected.length];
        try (var stream = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(LONG_INTEROP_DATA)).build()) {
            for (int i = 0; i < actual.length; i++) {
                int count = stream.read(actual, i, 1);
                if (count != 1) {
                    assertThat("decoded byte index " + i, count, is(1));
                }
            }
            assertThat(stream.read(), is(-1));
        }
        assertThat(actual, is(expected));
    }

    @Test
    void mixedReadsReturnRemainingBufferedBytesAtEndOfStream() throws IOException {
        try (var stream = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(COMPRESSED_DATA)).build()) {
            assertThat(stream.read(), is((int) DATA[0]));
            byte[] remaining = new byte[32];
            assertThat(stream.read(remaining), is(DATA.length - 1));
            assertThat(new String(remaining, 0, DATA.length - 1, StandardCharsets.UTF_8), is("y data"));
            assertThat(stream.read(), is(-1));
            assertThat(stream.read(remaining), is(-1));
            assertThat(stream.read(remaining, 0, 0), is(0));
        }
    }

    @Test
    void roundTripsAcrossInputCacheBoundaries() throws IOException {
        for (int size : new int[] {0, 1, 7, 16, 32, 1024, 64_000, 64_001, 128_123}) {
            byte[] data = new byte[size];
            new Random(size).nextBytes(data);
            var destination = new ByteArrayOutputStream();
            try (var stream = BrotliOutputStream.builder().outputStream(destination).build()) {
                stream.write(data);
            }
            try (var stream = BrotliInputStream.builder()
                    .inputStream(new ByteArrayInputStream(destination.toByteArray()))
                    .build()) {
                assertThat("payload size " + size, stream.readAllBytes(), is(data));
            }
        }
    }

    @Test
    void flushesAndContinuesWriting() throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var stream = BrotliOutputStream.builder().outputStream(destination).build()) {
            stream.write(DATA, 0, 4);
            stream.flush();
            stream.flush();
            stream.write(DATA, 4, 3);
        }
        try (var stream = BrotliInputStream.builder()
                .inputStream(new ByteArrayInputStream(destination.toByteArray()))
                .build()) {
            assertThat(stream.readAllBytes(), is(DATA));
        }
    }

    @Test
    void flushingBeforeAnyInputDoesNotFinishTheStream() throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var stream = BrotliOutputStream.builder().outputStream(destination).build()) {
            stream.flush();
            stream.flush();
            stream.write(DATA);
        }
        assertThat(destination.toByteArray(), is(COMPRESSED_DATA));
    }

    @Test
    void truncatedStreamIsRejected() throws IOException {
        byte[] truncated = Arrays.copyOf(COMPRESSED_DATA, COMPRESSED_DATA.length - 2);
        try (var input = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(truncated)).build()) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    @Test
    void compressesRepetitivePayloadAcrossCacheBoundaries() throws IOException {
        byte[] data = INTEROP_TEXT.repeat(2000).getBytes(StandardCharsets.UTF_8);
        var destination = new ByteArrayOutputStream();
        try (var stream = BrotliOutputStream.builder().outputStream(destination).build()) {
            stream.write(data);
        }
        assertThat(destination.size(), lessThan(data.length));
        try (var stream = BrotliInputStream.builder()
                .inputStream(new ByteArrayInputStream(destination.toByteArray()))
                .build()) {
            assertThat(stream.readAllBytes(), is(data));
        }
    }

    @Test
    void propagatesOutputFailureAndClosesDestination() throws IOException {
        var destination = new FailingOutputStream();
        var stream = BrotliOutputStream.builder().outputStream(destination).build();
        stream.write(DATA);
        IOException failure = assertThrows(IOException.class, stream::close);
        assertThat(failure.getMessage(), is("destination failed"));
        assertThat(destination.closed, is(true));
    }

    @Test
    void rejectsUnsupportedCompressionQuality() {
        assertThrows(IllegalArgumentException.class,
                     () -> BrotliOutputStream.builder().outputStream(new ByteArrayOutputStream()).quality(1).build());
    }

    @Test
    void validatesInputAndOutputRanges() throws IOException {
        try (var stream = BrotliOutputStream.builder().outputStream(new ByteArrayOutputStream()).build()) {
            assertThrows(IndexOutOfBoundsException.class, () -> stream.write(DATA, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.write(DATA, 0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.write(DATA, Integer.MAX_VALUE, 1));
        }
        try (var stream = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(COMPRESSED_DATA)).build()) {
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], 0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], Integer.MAX_VALUE, 1));
        }
    }

    @Test
    void closingIsIdempotentAndPreventsFurtherIo() throws IOException {
        var destination = new ByteArrayOutputStream();
        var output = BrotliOutputStream.builder().outputStream(destination).build();
        output.write(DATA);
        output.close();
        byte[] compressed = destination.toByteArray();
        output.close();
        assertThat(destination.toByteArray(), is(compressed));
        assertThrows(IOException.class, () -> output.write(1));
        assertThrows(IOException.class, output::flush);

        var input = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(compressed)).build();
        input.close();
        input.close();
        assertThrows(IOException.class, input::read);
    }

    @Test
    void malformedInputFailsDuringRead() throws IOException {
        try (var input = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(new byte[] {1})).build()) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        private boolean closed;

        @Override
        public void write(int value) throws IOException {
            throw new IOException("destination failed");
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
