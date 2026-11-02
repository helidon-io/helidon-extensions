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

package io.helidon.extensions.jackson.benchmarks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.Headers;
import io.helidon.http.WritableHeaders;
import io.helidon.http.media.EntityReader;
import io.helidon.http.media.EntityWriter;
import io.helidon.http.media.MediaSupport;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * In-memory JSON entity reading and both outbound HTTP writer paths.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class JacksonBenchmark {
    private static final GenericType<Payload> TYPE = GenericType.create(Payload.class);

    /**
     * Media provider selected independently for each trial.
     */
    @Param({"jackson", "jackson-2", "jackson-3"})
    private String provider;

    /**
     * Number of characters in the JSON string property.
     */
    @Param({"128", "4096"})
    private int size;

    private EntityReader<Payload> reader;
    private EntityWriter<Payload> writer;
    private Headers requestHeaders;
    private Payload payload;
    private byte[] encoded;

    /**
     * Select the provider and verify complete reader/writer round trips outside timed operations.
     */
    @Setup
    public void setup() {
        MediaSupport support = switch (provider) {
            case "jackson" -> io.helidon.http.media.jackson.JacksonSupport.create();
            case "jackson-2" -> io.helidon.extensions.jackson.jackson2.http.media.JacksonSupport.create();
            case "jackson-3" -> io.helidon.extensions.jackson.jackson3.http.media.JacksonSupport.create();
            default -> throw new IllegalArgumentException(provider);
        };
        var headers = WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON);
        reader = support.reader(TYPE, headers).supplier().get();
        writer = support.writer(TYPE, headers).supplier().get();
        requestHeaders = WritableHeaders.create();
        payload = new Payload();
        payload.setMessage("x".repeat(size));
        encoded = write();
        if (!read().getMessage().equals(payload.getMessage())) {
            throw new IllegalStateException("Roundtrip failed for " + provider);
        }
        if (!writer.supportsInstanceWriter()) {
            throw new IllegalStateException("Instance writers are not supported by " + provider);
        }
        var instance = writer.instanceWriter(TYPE, payload,
                                             WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON));
        var serverInstance = writer.instanceWriter(TYPE, payload, requestHeaders,
                                                   WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON));
        if (!instance.alwaysInMemory() || !serverInstance.alwaysInMemory()
                || !Arrays.equals(encoded, instance.instanceBytes())
                || !Arrays.equals(encoded, serverInstance.instanceBytes())
                || !Arrays.equals(encoded, writeInstance())
                || !Arrays.equals(encoded, writeServerInstance())) {
            throw new IllegalStateException("Instance-writer fixture failed for " + provider);
        }
    }

    /**
     * Read a complete entity through a fresh input stream and headers.
     *
     * @return deserialized payload consumed by JMH
     */
    @Benchmark
    public Payload read() {
        return reader.read(TYPE, new ByteArrayInputStream(encoded),
                           WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON));
    }

    /**
     * Write directly to a fresh output stream and extract the complete byte array.
     *
     * @return serialized entity bytes consumed by JMH
     */
    @Benchmark
    public byte[] write() {
        var output = new ByteArrayOutputStream();
        writer.write(TYPE, payload, output, WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON));
        return output.toByteArray();
    }

    /**
     * Serialize a client entity through a fresh instance writer and retrieve its complete bytes.
     * This is the in-memory path used by HTTP clients when the writer supports it.
     *
     * @return serialized entity bytes consumed by JMH
     */
    @Benchmark
    public byte[] writeInstance() {
        return writer.instanceWriter(TYPE, payload,
                                     WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON))
                .instanceBytes();
    }

    /**
     * Serialize a server response through a fresh instance writer and response headers.
     * Request headers represent the existing request and are prepared outside timed operations.
     *
     * @return serialized entity bytes consumed by JMH
     */
    @Benchmark
    public byte[] writeServerInstance() {
        return writer.instanceWriter(TYPE, payload, requestHeaders,
                                     WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON))
                .instanceBytes();
    }

    /**
     * Mutable JavaBean used as the same JSON shape for all providers.
     */
    public static class Payload {
        private String message;

        /**
         * String property carried by the entity.
         *
         * @return message
         */
        public String getMessage() {
            return message;
        }

        /**
         * Set the string property carried by the entity.
         *
         * @param message message
         */
        public void setMessage(String message) {
            this.message = message;
        }
    }
}
