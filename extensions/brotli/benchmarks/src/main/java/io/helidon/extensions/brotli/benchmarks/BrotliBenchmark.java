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

package io.helidon.extensions.brotli.benchmarks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import io.helidon.extensions.brotli.BrotliInputStream;
import io.helidon.extensions.brotli.BrotliOutputStream;
import io.helidon.extensions.brotli.http.encoding.BrotliEncoding;
import io.helidon.http.WritableHeaders;
import io.helidon.http.encoding.ContentDecoder;
import io.helidon.http.encoding.ContentEncoder;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * End-to-end in-memory codec and HTTP content-encoding operations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class BrotliBenchmark {
    private static final byte[] TINY_DATA = "my data".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TINY_ENCODED = HexFormat.of().parseHex("0303806d79206461746103");
    private static final String INTEROP_TEXT = """
            Helidon Brotli interoperability fixture.
            The quick brown fox jumps over the lazy dog.
            Compression and decompression must preserve every byte.
            """.repeat(2);
    // Google Brotli CLI quality 11, also covered by the codec interoperability unit test.
    private static final byte[] INTEROP_ENCODED = HexFormat.of().parseHex(
            "a1d808006e9c1c6bb7e3562a2051a9732a1ba16ac494db69f617ca3afa1eb6bbc1017b719c0740810fbcf9803caa63ce66dc94"
                    + "afa39bc644cb2837d82c662c6697cdc3767d49f14370584c19b131d12f0047efff81c62d5e34b54f85f268282c2d"
                    + "13a3e821d7290a03");
    // RFC 7932 Appendix A/B references independently decoded by Google Brotli 1.2.1.
    private static final String[] DICTIONARY_HEX = {
        "620000004450081210", "620000004450081250", "6200000044500812260101",
        "62000000445008122a010c", "82000000445008122001", "a2000000445010126141",
        "a2000000445010126cc119", "a2000000445010121e1f", "8200000044500c121f02"
    };
    private static final String[] DICTIONARY_TEXT = {"time", "down", "Time", "TIME", "time ", "как", "КАК", "中国", "años"};

    /**
     * Encode an entity, including the stream and destination lifecycle.
     *
     * @param state payload
     * @return encoded bytes consumed by JMH
     * @throws IOException if encoding fails
     */
    @Benchmark
    public byte[] encode(Payload state) throws IOException {
        return compress(state.data);
    }

    /**
     * Decode a complete entity with bulk reads.
     *
     * @param state payload
     * @return decoded bytes consumed by JMH
     * @throws IOException if decoding fails
     */
    @Benchmark
    public byte[] decode(Payload state) throws IOException {
        return decompress(state.encoded);
    }

    /**
     * Decode through the public single-byte read/cache path.
     *
     * @param state payload
     * @return a checksum of all decoded bytes
     * @throws IOException if decoding fails
     */
    @Benchmark
    public long decodeByteByByte(Payload state) throws IOException {
        long checksum = 0;
        try (var input = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(state.encoded)).build()) {
            for (int value; (value = input.read()) != -1;) {
                checksum += value;
            }
        }
        return checksum;
    }

    /**
     * Apply the HTTP encoder and its header policy without network transport.
     *
     * @param state payload and cached encoding strategy
     * @param blackhole consumes the completed HTTP headers
     * @return encoded entity bytes
     * @throws IOException if encoding fails
     */
    @Benchmark
    public byte[] httpEncode(Payload state, Blackhole blackhole) throws IOException {
        var destination = new ByteArrayOutputStream();
        var headers = WritableHeaders.create();
        state.encoder.headers(headers);
        blackhole.consume(headers);
        try (var output = state.encoder.apply(destination)) {
            output.write(state.data);
        }
        return destination.toByteArray();
    }

    /**
     * Apply the HTTP decoder without network transport.
     *
     * @param state payload and cached encoding strategy
     * @return decoded entity bytes
     * @throws IOException if decoding fails
     */
    @Benchmark
    public byte[] httpDecode(Payload state) throws IOException {
        try (var input = state.decoder.apply(new ByteArrayInputStream(state.encoded))) {
            return input.readAllBytes();
        }
    }

    /**
     * Create and close an input wrapper without reading; this does not initialize the decoder.
     *
     * @param blackhole consumes the stream object
     * @throws IOException if closing fails
     */
    @Benchmark
    public void inputWrapper(Blackhole blackhole) throws IOException {
        try (var input = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(TINY_ENCODED)).build()) {
            blackhole.consume(input);
        }
    }

    /**
     * Create and close an output stream, including its empty final block.
     *
     * @return encoded empty stream
     * @throws IOException if closing fails
     */
    @Benchmark
    public byte[] outputWrapper() throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var output = BrotliOutputStream.builder().outputStream(destination).build()) {
            output.flush();
        }
        return destination.toByteArray();
    }

    /**
     * Create an input stream and read one byte, including lazy decoder initialization.
     *
     * @return the first decoded byte
     * @throws IOException if decoding fails
     */
    @Benchmark
    public int firstRead() throws IOException {
        try (var input = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(TINY_ENCODED)).build()) {
            return input.read();
        }
    }

    /**
     * Decode nine short entities that each require a static dictionary reference.
     * Metrics are normalized per entity, rather than per nine-entity batch.
     *
     * @param state immutable independent dictionary fixtures
     * @param blackhole consumes every decoded result
     * @throws IOException if decoding fails
     */
    @Benchmark
    @OperationsPerInvocation(9)
    public void dictionaryDecode(DictionaryPayload state, Blackhole blackhole) throws IOException {
        for (byte[] encoded : state.encoded) {
            blackhole.consume(decompress(encoded));
        }
    }

    private static byte[] compress(byte[] data) throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var output = BrotliOutputStream.builder().outputStream(destination).build()) {
            output.write(data);
        }
        return destination.toByteArray();
    }

    private static byte[] decompress(byte[] encoded) throws IOException {
        try (var input = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(encoded)).build()) {
            return input.readAllBytes();
        }
    }

    /**
     * Payloads prepared and checked outside timed operations.
     */
    @State(Scope.Thread)
    public static class Payload {
        /**
         * Representative entity shape; sizes cross the default 64,000-byte encoder cache boundary.
         */
        @Param({"TINY", "CACHE_TEXT", "CACHE_RANDOM", "LARGE_TEXT", "INTEROP"})
        private String shape;

        private byte[] data;
        private byte[] encoded;
        private ContentEncoder encoder;
        private ContentDecoder decoder;

        /**
         * Select a shape when running the fixture checks outside JMH.
         *
         * @param value payload shape
         */
        public void shape(String value) {
            shape = value;
        }

        /**
         * Build fixtures and verify complete codec/HTTP roundtrips.
         *
         * @throws IOException if fixture validation fails
         */
        @Setup(Level.Trial)
        public void prepare() throws IOException {
            data = switch (shape) {
                case "TINY" -> TINY_DATA.clone();
                case "CACHE_TEXT" -> text(64_001);
                case "CACHE_RANDOM" -> random(64_001);
                case "LARGE_TEXT" -> text(262_144);
                case "INTEROP" -> INTEROP_TEXT.getBytes(StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("Unknown payload shape: " + shape);
            };
            encoded = shape.equals("INTEROP") ? INTEROP_ENCODED.clone() : compress(data);
            var encoding = BrotliEncoding.create();
            encoder = encoding.encoder();
            decoder = encoding.decoder();
            if (!Arrays.equals(decompress(encoded), data)) {
                throw new IllegalStateException("Codec fixture failed: " + shape);
            }
            try (var input = decoder.apply(new ByteArrayInputStream(encoded))) {
                if (!Arrays.equals(input.readAllBytes(), data)) {
                    throw new IllegalStateException("HTTP decoder fixture failed: " + shape);
                }
            }
            var destination = new ByteArrayOutputStream();
            try (var output = encoder.apply(destination)) {
                output.write(data);
            }
            if (!Arrays.equals(decompress(destination.toByteArray()), data)) {
                throw new IllegalStateException("HTTP encoder fixture failed: " + shape);
            }
        }

        private static byte[] text(int length) {
            byte[] pattern = "Helidon Brotli benchmark entity with repeated text. ".getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[length];
            for (int i = 0; i < length; i++) {
                result[i] = pattern[i % pattern.length];
            }
            return result;
        }

        private static byte[] random(int length) {
            byte[] result = new byte[length];
            new Random(7932).nextBytes(result);
            return result;
        }
    }

    /**
     * Guaranteed static-dictionary references, including case/space transforms and UTF-8 words.
     */
    @State(Scope.Thread)
    public static class DictionaryPayload {
        private byte[][] encoded;

        /**
         * Check every external fixture before measurement.
         *
         * @throws IOException if decoding fails
         */
        @Setup(Level.Trial)
        public void prepare() throws IOException {
            encoded = new byte[DICTIONARY_HEX.length][];
            for (int i = 0; i < encoded.length; i++) {
                encoded[i] = HexFormat.of().parseHex(DICTIONARY_HEX[i]);
                byte[] expected = DICTIONARY_TEXT[i].getBytes(StandardCharsets.UTF_8);
                if (!Arrays.equals(decompress(encoded[i]), expected)) {
                    throw new IllegalStateException("Dictionary fixture failed at index " + i);
                }
            }
        }
    }
}
