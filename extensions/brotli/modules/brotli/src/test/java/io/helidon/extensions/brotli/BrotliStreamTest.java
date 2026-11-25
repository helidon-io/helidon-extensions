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
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.sameInstance;
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
    void constructorsRejectNullArguments() {
        var compressed = new ByteArrayInputStream(COMPRESSED_DATA);
        var destination = new ByteArrayOutputStream();
        assertThrows(NullPointerException.class, () -> new BrotliInputStream(null));
        assertThrows(NullPointerException.class, () -> new BrotliInputStream(compressed, null));
        assertThrows(NullPointerException.class, () -> new BrotliOutputStream(null));
        assertThrows(NullPointerException.class, () -> new BrotliOutputStream(destination, null));
    }

    @Test
    void defaultConfigurationsCanBeReusedWithDifferentStreams() throws IOException {
        var outputConfig = BrotliOutputStream.DEFAULT_CONFIG;
        var inputConfig = BrotliInputStream.DEFAULT_CONFIG;
        for (int i = 0; i < 2; i++) {
            var destination = new ByteArrayOutputStream();
            try (var output = new BrotliOutputStream(destination, outputConfig)) {
                output.write(DATA);
            }
            assertThat(destination.toByteArray(), is(COMPRESSED_DATA));
            try (var input = new BrotliInputStream(new ByteArrayInputStream(destination.toByteArray()), inputConfig)) {
                assertThat(input.readAllBytes(), is(DATA));
            }
        }
    }

    @Test
    void writesKnownBrotliStream() throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var stream = new BrotliOutputStream(destination)) {
            stream.write(DATA);
        }
        assertThat(destination.toByteArray(), is(COMPRESSED_DATA));
    }

    @Test
    void googleDecoderReadsHelidonStreams() throws IOException {
        byte[] incompressible = new byte[70_000];
        new Random(7932).nextBytes(incompressible);
        byte[] text = INTEROP_TEXT.repeat(512).getBytes(StandardCharsets.UTF_8);
        for (byte[] data : new byte[][] {new byte[0], DATA, text, incompressible}) {
            var encoded = new ByteArrayOutputStream();
            try (var output = new BrotliOutputStream(encoded)) {
                int split = data.length / 2;
                output.write(data, 0, split);
                output.flush();
                output.write(data, split, data.length - split);
            }
            try (var google = new org.brotli.dec.BrotliInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
                assertThat("first mismatching byte for input length " + data.length,
                           Arrays.mismatch(data, google.readAllBytes()), is(-1));
            }
        }
    }

    @Test
    void decodesIndependentQualityElevenStream() throws IOException {
        try (var stream = new BrotliInputStream(new ByteArrayInputStream(INTEROP_DATA))) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8), is(INTEROP_TEXT));
        }
    }

    @Test
    void decodesIndependentLongCopyStream() throws IOException {
        try (var stream = new BrotliInputStream(new ByteArrayInputStream(LONG_INTEROP_DATA))) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8), is(INTEROP_TEXT.repeat(2000)));
        }
    }

    @Test
    void resumesIndependentLongCopyWithOneByteReads() throws IOException {
        byte[] expected = INTEROP_TEXT.repeat(2000).getBytes(StandardCharsets.UTF_8);
        byte[] actual = new byte[expected.length];
        try (var stream = new BrotliInputStream(new ByteArrayInputStream(LONG_INTEROP_DATA))) {
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
        try (var stream = new BrotliInputStream(new ByteArrayInputStream(COMPRESSED_DATA))) {
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
            try (var stream = new BrotliOutputStream(destination)) {
                stream.write(data);
            }
            try (var stream = new BrotliInputStream(new ByteArrayInputStream(destination.toByteArray()))) {
                assertThat("payload size " + size, stream.readAllBytes(), is(data));
            }
        }
    }

    @Test
    void flushesAndContinuesWriting() throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var stream = new BrotliOutputStream(destination)) {
            stream.write(DATA, 0, 4);
            stream.flush();
            stream.flush();
            stream.write(DATA, 4, 3);
        }
        try (var stream = new BrotliInputStream(new ByteArrayInputStream(destination.toByteArray()))) {
            assertThat(stream.readAllBytes(), is(DATA));
        }
    }

    @Test
    void flushingBeforeAnyInputDoesNotFinishTheStream() throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var stream = new BrotliOutputStream(destination)) {
            stream.flush();
            stream.flush();
            stream.write(DATA);
        }
        assertThat(destination.toByteArray(), is(COMPRESSED_DATA));
    }

    @Test
    void truncatedStreamIsRejected() throws IOException {
        byte[] truncated = Arrays.copyOf(COMPRESSED_DATA, COMPRESSED_DATA.length - 2);
        try (var input = new BrotliInputStream(new ByteArrayInputStream(truncated))) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    @Test
    void compressesRepetitivePayloadAcrossCacheBoundaries() throws IOException {
        byte[] data = INTEROP_TEXT.repeat(2000).getBytes(StandardCharsets.UTF_8);
        var destination = new ByteArrayOutputStream();
        try (var stream = new BrotliOutputStream(destination)) {
            stream.write(data);
        }
        assertThat(destination.size(), lessThan(data.length));
        try (var stream = new BrotliInputStream(new ByteArrayInputStream(destination.toByteArray()))) {
            assertThat(stream.readAllBytes(), is(data));
        }
    }

    @Test
    void propagatesSourceTimeout() throws IOException {
        var timeout = new SocketTimeoutException("source timed out");
        var source = new InputStream() {
            @Override
            public int read() throws IOException {
                throw timeout;
            }
        };
        try (var input = new BrotliInputStream(source)) {
            assertThat(assertThrows(SocketTimeoutException.class, input::read), sameInstance(timeout));
        }
    }

    @Test
    void preservesReadFailureAcrossResourceCleanup() {
        var timeout = new SocketTimeoutException("source timed out");
        var source = new InputStream() {
            private boolean closed;

            @Override
            public int read() throws IOException {
                throw timeout;
            }

            @Override
            public void close() throws IOException {
                closed = true;
                throw timeout;
            }
        };
        IOException failure = assertThrows(IOException.class, () -> {
            try (var input = new BrotliInputStream(source)) {
                input.read();
            }
        });
        assertThat(failure, sameInstance(timeout));
        assertThat(failure.getSuppressed().length, is(0));
        assertThat(source.closed, is(true));
    }

    @Test
    void preservesFlushFailureAcrossResourceCleanup() {
        for (boolean buffered : new boolean[] {false, true}) {
            for (boolean distinctCloseFailure : new boolean[] {false, true}) {
                var flushFailure = new IOException("destination failed");
                var closeFailure = distinctCloseFailure ? new IOException("close failed") : flushFailure;
                var destination = new FailingOutputStream(flushFailure, closeFailure);
                IOException failure = assertThrows(IOException.class, () -> {
                    try (var output = new BrotliOutputStream(destination)) {
                        if (buffered) {
                            output.write(DATA);
                        }
                        output.flush();
                    }
                });
                assertThat(failure, sameInstance(flushFailure));
                Throwable[] expectedSuppressed = distinctCloseFailure ? new Throwable[] {closeFailure} : new Throwable[0];
                assertThat(failure.getSuppressed(), is(expectedSuppressed));
                assertThat(destination.closed, is(true));
            }
        }
    }

    @Test
    void preservesSourceFailureAfterHeaderParsingStarts() throws IOException {
        var timeout = new SocketTimeoutException("source timed out");
        var source = new FilterInputStream(new ByteArrayInputStream(INTEROP_DATA)) {
            private int reads;

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (++reads == 2) {
                    throw timeout;
                }
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        try (var input = new BrotliInputStream(source)) {
            assertThat(assertThrows(IOException.class, input::read), sameInstance(timeout));
            assertThat(assertThrows(IOException.class, () -> input.read(new byte[8])), sameInstance(timeout));
            assertThat(assertThrows(IOException.class, input::read), sameInstance(timeout));
            assertThat(source.reads, is(2));
        }
    }

    @Test
    void preservesInterruptionDuringFirstTransfer() throws IOException {
        var timeout = new SocketTimeoutException("partial transfer");
        var source = new FilterInputStream(new ByteArrayInputStream(INTEROP_DATA)) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (timeout.bytesTransferred == 0) {
                    timeout.bytesTransferred = super.read(bytes, offset, 1);
                    throw timeout;
                }
                return super.read(bytes, offset, length);
            }
        };
        try (var input = new BrotliInputStream(source)) {
            assertThat(assertThrows(IOException.class, input::read), sameInstance(timeout));
            assertThat(assertThrows(IOException.class, input::read), sameInstance(timeout));
        }
    }

    @Test
    void reportsCloseFailureAfterSuccessfulReadRetry() throws IOException {
        var failure = new IOException("source failed");
        var source = new FilterInputStream(new ByteArrayInputStream(COMPRESSED_DATA)) {
            private boolean failNextRead = true;

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (failNextRead) {
                    failNextRead = false;
                    throw failure;
                }
                return super.read(bytes, offset, length);
            }

            @Override
            public void close() throws IOException {
                throw failure;
            }
        };
        var input = new BrotliInputStream(source);
        assertThat(assertThrows(IOException.class, input::read), sameInstance(failure));
        assertThat(input.readAllBytes(), is(DATA));
        assertThat(assertThrows(IOException.class, input::close), sameInstance(failure));
    }

    @Test
    void stopsEncodingAfterPartialDestinationWrite() throws IOException {
        var failure = new IOException("partial write");
        var accepted = new ByteArrayOutputStream();
        var destination = new OutputStream() {
            private int closes;

            @Override
            public void write(int value) throws IOException {
                if (accepted.size() == 1) {
                    // Fail once and accept later writes so an accidental replay is observable.
                    accepted.write(value);
                    throw failure;
                }
                accepted.write(value);
            }

            @Override
            public void close() {
                closes++;
            }
        };
        var output = new BrotliOutputStream(destination);
        output.write(DATA);
        assertThat(assertThrows(IOException.class, output::flush), sameInstance(failure));
        int prefixSize = accepted.size();
        assertThrows(IOException.class, () -> output.write(1));
        assertThrows(IOException.class, () -> output.write(DATA));
        assertThrows(IOException.class, output::flush);
        output.close();
        output.close();
        assertThat(accepted.size(), is(prefixSize));
        assertThat(destination.closes, is(1));
    }

    @Test
    void stopsEncodingAfterUncheckedPartialWrite() {
        for (boolean sharedCloseFailure : new boolean[] {false, true}) {
            var failure = new IllegalStateException("partial write");
            var destination = new OutputStream() {
                private int bytesAccepted;
                private int closes;

                @Override
                public void write(int value) {
                    if (++bytesAccepted == 2) {
                        throw failure;
                    }
                }

                @Override
                public void close() {
                    closes++;
                    if (sharedCloseFailure) {
                        throw failure;
                    }
                }
            };
            var thrown = assertThrows(IllegalStateException.class, () -> {
                try (var stream = new BrotliOutputStream(destination)) {
                    stream.write(DATA);
                    stream.flush();
                }
            });
            assertThat(thrown, sameInstance(failure));
            assertThat("accepted bytes after cleanup", destination.bytesAccepted, is(2));
            assertThat("destination close attempts", destination.closes, is(1));
        }
    }

    @Test
    void preservesCompressedWriteFailureWhenOuterStreamFlushesOnClose() {
        var failure = new IOException("destination write failed");
        var destination = new FailingOutputStream(failure, null);
        IOException reported = assertThrows(IOException.class, () -> {
            try (var output = new DataOutputStream(new BrotliOutputStream(destination))) {
                output.write(DATA);
                output.flush();
            }
        });
        assertThat(reported, sameInstance(failure));
        assertThat(destination.writeAttempts, is(1));
        assertThat(destination.closed, is(true));
    }

    @Test
    void retriesDestinationFlushAfterCompressedBytesWereWritten() throws IOException {
        var failure = new IOException("flush failed");
        var accepted = new ByteArrayOutputStream();
        var destination = new FilterOutputStream(accepted) {
            private boolean failNextFlush = true;

            @Override
            public void flush() throws IOException {
                if (failNextFlush) {
                    failNextFlush = false;
                    throw failure;
                }
                super.flush();
            }
        };
        try (var output = new BrotliOutputStream(destination)) {
            output.write(DATA);
            assertThat(assertThrows(IOException.class, output::flush), sameInstance(failure));
            output.flush();
        }
        try (var decoder = new BrotliInputStream(new ByteArrayInputStream(accepted.toByteArray()))) {
            assertThat(decoder.readAllBytes(), is(DATA));
        }
    }

    @Test
    void reportsFinalWriteFailureAfterContinuedOutput() throws IOException {
        for (boolean retryFlush : new boolean[] {false, true}) {
            var failure = new IOException("destination failed");
            var destination = new OutputStream() {
                private boolean failNextFlush = true;

                @Override
                public void flush() throws IOException {
                    if (failNextFlush) {
                        failNextFlush = false;
                        throw failure;
                    }
                }

                @Override
                public void write(int value) throws IOException {
                    throw failure;
                }
            };
            var output = new BrotliOutputStream(destination);
            assertThat(assertThrows(IOException.class, output::flush), sameInstance(failure));
            if (retryFlush) {
                output.flush();
            } else {
                output.write(DATA);
            }
            assertThat(assertThrows(IOException.class, output::close), sameInstance(failure));
        }
    }

    @Test
    void propagatesOutputFailureAndClosesDestination() throws IOException {
        var destination = new FailingOutputStream();
        var stream = new BrotliOutputStream(destination);
        stream.write(DATA);
        IOException failure = assertThrows(IOException.class, stream::close);
        assertThat(failure.getMessage(), is("destination failed"));
        assertThat(destination.closed, is(true));
    }

    @Test
    void preservesSharedWriteAndCloseFailure() throws IOException {
        var failure = new IOException("destination failed");
        var stream = new BrotliOutputStream(new FailingOutputStream(failure, failure));
        stream.write(DATA);
        assertThat(assertThrows(IOException.class, stream::close), sameInstance(failure));
        assertThat(failure.getSuppressed().length, is(0));
    }

    @Test
    void preservesSharedUncheckedIoFailureOnClose() throws IOException {
        var cause = new IOException("destination failed");
        var failure = new UncheckedIOException(cause);
        var destination = new OutputStream() {
            private int closeAttempts;

            @Override
            public void write(int value) {
                throw failure;
            }

            @Override
            public void close() {
                closeAttempts++;
                throw failure;
            }
        };
        var stream = new BrotliOutputStream(destination);
        stream.write(1);

        assertThat(assertThrows(IOException.class, stream::close), sameInstance(cause));
        assertThat("suppressed failures on the original I/O error", cause.getSuppressed().length, is(0));
        stream.close();
        assertThat("destination close attempts", destination.closeAttempts, is(1));
    }

    @Test
    void suppressesDistinctCloseFailureOnWriteFailure() throws IOException {
        var writeFailure = new IOException("write failed");
        var closeFailure = new IOException("close failed");
        var stream = new BrotliOutputStream(new FailingOutputStream(writeFailure, closeFailure));
        stream.write(DATA);
        assertThat(assertThrows(IOException.class, stream::close), sameInstance(writeFailure));
        assertThat(writeFailure.getSuppressed(), is(new Throwable[] {closeFailure}));
    }

    @Test
    void closesDestinationAfterUncheckedWriteFailure() throws IOException {
        for (boolean sharedFailure : new boolean[] {false, true}) {
            var writeFailure = new IllegalStateException("write failed");
            var closeFailure = new IOException("close failed");
            var destination = new OutputStream() {
                private int closeAttempts;

                @Override
                public void write(int value) {
                    throw writeFailure;
                }

                @Override
                public void close() throws IOException {
                    closeAttempts++;
                    if (sharedFailure) {
                        throw writeFailure;
                    }
                    throw closeFailure;
                }
            };
            var stream = new BrotliOutputStream(destination);
            stream.write(1);

            assertThat(assertThrows(IllegalStateException.class, stream::close), sameInstance(writeFailure));
            assertThat("destination cleanup after failed final write", destination.closeAttempts, is(1));
            Throwable[] expectedSuppressed = sharedFailure ? new Throwable[0] : new Throwable[] {closeFailure};
            assertThat(writeFailure.getSuppressed(), is(expectedSuppressed));

            stream.close();
            assertThat("destination cleanup after a repeated close", destination.closeAttempts, is(1));
        }
    }

    @Test
    void rejectsUnsupportedCompressionQuality() {
        var outputConfig = BrotliOutputStreamConfig.builder().quality(1).build();
        assertThrows(IllegalArgumentException.class,
                     () -> new BrotliOutputStream(new ByteArrayOutputStream(), outputConfig));
    }

    @Test
    void validatesInputAndOutputRanges() throws IOException {
        try (var stream = new BrotliOutputStream(new ByteArrayOutputStream())) {
            assertThrows(IndexOutOfBoundsException.class, () -> stream.write(DATA, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.write(DATA, 0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.write(DATA, Integer.MAX_VALUE, 1));
        }
        try (var stream = new BrotliInputStream(new ByteArrayInputStream(COMPRESSED_DATA))) {
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], 0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], Integer.MAX_VALUE, 1));
        }
    }

    @Test
    void closingIsIdempotentAndPreventsFurtherIo() throws IOException {
        var destination = new ByteArrayOutputStream();
        var output = new BrotliOutputStream(destination);
        output.write(DATA);
        output.close();
        byte[] compressed = destination.toByteArray();
        output.close();
        assertThat(destination.toByteArray(), is(compressed));
        assertThrows(IOException.class, () -> output.write(1));
        assertThrows(IOException.class, output::flush);

        var input = new BrotliInputStream(new ByteArrayInputStream(compressed));
        input.close();
        input.close();
        assertThrows(IOException.class, input::read);
    }

    @Test
    void malformedInputFailsDuringRead() throws IOException {
        try (var input = new BrotliInputStream(new ByteArrayInputStream(new byte[] {1}))) {
            assertThrows(IOException.class, input::readAllBytes);
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        private final IOException writeFailure;
        private final IOException closeFailure;
        private int writeAttempts;
        private boolean closed;

        private FailingOutputStream() {
            this(new IOException("destination failed"), null);
        }

        private FailingOutputStream(IOException writeFailure, IOException closeFailure) {
            this.writeFailure = writeFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void write(int value) throws IOException {
            writeAttempts++;
            throw writeFailure;
        }

        @Override
        public void flush() throws IOException {
            throw writeFailure;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
