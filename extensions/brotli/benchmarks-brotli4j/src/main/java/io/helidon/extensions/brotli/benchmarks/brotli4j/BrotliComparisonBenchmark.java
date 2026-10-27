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

package io.helidon.extensions.brotli.benchmarks.brotli4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import io.helidon.extensions.brotli.BrotliInputStream;
import io.helidon.extensions.brotli.BrotliOutputStream;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.Decoder;
import com.aayushatharva.brotli4j.decoder.DecoderJNI;
import com.aayushatharva.brotli4j.encoder.Encoder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Entity codec comparison with native Brotli4j at quality zero and window eighteen.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+UseG1GC"})
public class BrotliComparisonBenchmark {
    private static final int BUFFER_SIZE = 64_000;

    /**
     * Create a benchmark instance.
     */
    public BrotliComparisonBenchmark() {
    }

    /**
     * Encode and close a fresh Java stream, retaining the encoded entity.
     *
     * @param payload immutable fixture
     * @return encoded bytes consumed by JMH
     * @throws IOException if encoding fails
     */
    @Benchmark
    public byte[] javaEncode(Payload payload) throws IOException {
        return encodeJava(payload.data);
    }

    /**
     * Encode and close a fresh native stream, retaining the encoded entity.
     *
     * @param payload immutable fixture
     * @return encoded bytes consumed by JMH
     * @throws IOException if encoding fails
     */
    @Benchmark
    public byte[] nativeEncode(Payload payload) throws IOException {
        return encodeNative(payload.data);
    }

    /**
     * Decode the common native-produced stream with a fresh Java decoder.
     *
     * @param payload immutable fixture
     * @return decoded bytes consumed by JMH
     * @throws IOException if decoding fails
     */
    @Benchmark
    public byte[] javaDecode(Payload payload) throws IOException {
        return decodeJava(payload.nativeEncoded);
    }

    /**
     * Decode the common native-produced stream with a fresh native decoder.
     *
     * @param payload immutable fixture
     * @return decoded bytes consumed by JMH
     * @throws IOException if decoding fails
     */
    @Benchmark
    public byte[] nativeDecode(Payload payload) throws IOException {
        if (!"OK".equals(payload.nativeOutputNativeStreamStatus)) {
            throw new IOException("Native stream fixture validation failed: " + payload.nativeOutputNativeStreamStatus);
        }
        return decodeNative(payload.nativeEncoded);
    }

    /**
     * Decode the common stream with Brotli4j's one-shot array API, including native handle cleanup.
     * This API differs from the streaming Java decoder and is reported separately.
     *
     * @param payload immutable fixture
     * @return decoded bytes consumed by JMH
     * @throws IOException if decoding fails or returns an unexpected size
     */
    @Benchmark
    public byte[] nativeDecodeDirect(Payload payload) throws IOException {
        return decodeNativeDirect(payload.nativeEncoded, payload.data.length);
    }

    /**
     * Check all four fixtures and print compressed sizes, ratios and common-input hashes outside JMH.
     *
     * @param args unused
     * @throws IOException if a codec fails
     * @throws NoSuchAlgorithmException if SHA-256 is unavailable
     */
    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        System.out.println("size,kind,inputBytes,javaBytes,nativeBytes,javaRatio,nativeRatio,"
                                   + "inputSha256,compressedSha256,javaOutputNativeStream,nativeOutputNativeStream");
        var digest = MessageDigest.getInstance("SHA-256");
        for (String size : new String[] {"SHORT", "LONG"}) {
            for (String kind : new String[] {"TEXT", "RANDOM"}) {
                var payload = new Payload();
                payload.configure(size, kind);
                payload.prepare();
                System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%.8f,%.8f,%s,%s,%s,%s%n",
                                  size, kind, payload.data.length,
                                  payload.javaEncodedLength, payload.nativeEncoded.length,
                                  (double) payload.javaEncodedLength / payload.data.length,
                                  (double) payload.nativeEncoded.length / payload.data.length,
                                  HexFormat.of().formatHex(digest.digest(payload.data)),
                                  HexFormat.of().formatHex(digest.digest(payload.nativeEncoded)),
                                  csv(payload.javaOutputNativeStreamStatus),
                                  csv(payload.nativeOutputNativeStreamStatus));
            }
        }
    }

    private static byte[] encodeJava(byte[] data) throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var output = BrotliOutputStream.builder()
                .outputStream(destination)
                .quality(0)
                .window(18)
                .cacheInputSize(BUFFER_SIZE)
                .build()) {
            output.write(data);
        }
        return destination.toByteArray();
    }

    private static byte[] encodeNative(byte[] data) throws IOException {
        var destination = new ByteArrayOutputStream();
        var parameters = Encoder.Parameters.create(0, 18, Encoder.Mode.GENERIC);
        try (var output = new com.aayushatharva.brotli4j.encoder.BrotliOutputStream(destination,
                                                                                parameters,
                                                                                BUFFER_SIZE)) {
            output.write(data);
        }
        return destination.toByteArray();
    }

    private static byte[] decodeJava(byte[] encoded) throws IOException {
        try (var input = BrotliInputStream.builder().inputStream(new ByteArrayInputStream(encoded)).build()) {
            return input.readAllBytes();
        }
    }

    private static byte[] decodeNative(byte[] encoded) throws IOException {
        try (var input = new com.aayushatharva.brotli4j.decoder.BrotliInputStream(new ByteArrayInputStream(encoded),
                                                                             BUFFER_SIZE)) {
            return input.readAllBytes();
        }
    }

    private static byte[] decodeNativeDirect(byte[] encoded, int expectedLength) throws IOException {
        // The public array API destroys its per-call native decoder in its own finally block.
        var result = Decoder.decompress(encoded);
        if (result.getResultStatus() != DecoderJNI.Status.DONE) {
            throw new IOException("Native direct decode status: " + result.getResultStatus());
        }
        byte[] decoded = result.getDecompressedData();
        if (decoded == null || decoded.length != expectedLength) {
            throw new IOException("Native direct decode returned an unexpected output size");
        }
        return decoded;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * Trial-built, read-only input shared by workers; streams and mutable codec state are per invocation.
     */
    @State(Scope.Benchmark)
    public static class Payload {
        @Param({"SHORT", "LONG"})
        private String size;

        @Param({"TEXT", "RANDOM"})
        private String kind;

        private byte[] data;
        private byte[] nativeEncoded;
        private int javaEncodedLength;
        private String javaOutputNativeStreamStatus;
        private String nativeOutputNativeStreamStatus;

        /**
         * Create a fixture state.
         */
        public Payload() {
        }

        /**
         * Select a fixture for the standalone interoperability check.
         *
         * @param size short or long
         * @param kind text or random
         */
        public void configure(String size, String kind) {
            this.size = size;
            this.kind = kind;
        }

        /**
         * Load JNI once, create the common input and cross-check both encoders against both decoders.
         *
         * @throws IOException if a codec fails
         */
        @Setup(Level.Trial)
        public void prepare() throws IOException {
            Brotli4jLoader.ensureAvailability();
            int length = switch (size) {
                case "SHORT" -> 256;
                case "LONG" -> 1_048_576;
                default -> throw new IllegalArgumentException("Unknown size: " + size);
            };
            data = switch (kind) {
                case "TEXT" -> text(length);
                case "RANDOM" -> random(length);
                default -> throw new IllegalArgumentException("Unknown kind: " + kind);
            };
            byte[] javaEncoded = encodeJava(data);
            nativeEncoded = encodeNative(data);
            javaEncodedLength = javaEncoded.length;
            verify("Java/Java", decodeJava(javaEncoded));
            verify("Java/native direct", decodeNativeDirect(javaEncoded, data.length));
            verify("native/Java", decodeJava(nativeEncoded));
            verify("native/native direct", decodeNativeDirect(nativeEncoded, data.length));
            javaOutputNativeStreamStatus = streamStatus(javaEncoded);
            nativeOutputNativeStreamStatus = streamStatus(nativeEncoded);
        }

        private String streamStatus(byte[] encoded) {
            try {
                return Arrays.equals(data, decodeNative(encoded)) ? "OK" : "OUTPUT_MISMATCH";
            } catch (IOException failure) {
                return failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
        }

        private void verify(String path, byte[] decoded) {
            if (!Arrays.equals(data, decoded)) {
                throw new IllegalStateException(path + " interoperability failed for " + size + '/' + kind);
            }
        }

        private static byte[] text(int length) {
            var text = new StringBuilder(length + 128);
            for (int index = 0; text.length() < length; index++) {
                text.append("{\"id\":").append(index)
                        .append(",\"method\":\"GET\",\"path\":\"/catalog/items\",\"status\":200,")
                        .append("\"message\":\"Hello Helidon\"}\n");
            }
            return Arrays.copyOf(text.toString().getBytes(StandardCharsets.UTF_8), length);
        }

        private static byte[] random(int length) {
            byte[] result = new byte[length];
            new SplittableRandom(7932).nextBytes(result);
            return result;
        }
    }
}
