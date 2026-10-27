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
package io.helidon.extensions.jackson.benchmark;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
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

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class JacksonBenchmark {
    private static final GenericType<Payload> TYPE = GenericType.create(Payload.class);

    @Param({"jackson", "jackson-2", "jackson-3"})
    public String provider;

    @Param({"128", "4096"})
    public int size;

    private EntityReader<Payload> reader;
    private EntityWriter<Payload> writer;
    private Payload payload;
    private byte[] encoded;

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
        payload = new Payload();
        payload.setMessage("x".repeat(size));
        encoded = write();
        if (!read().getMessage().equals(payload.getMessage())) {
            throw new IllegalStateException("Roundtrip failed for " + provider);
        }
    }

    @Benchmark
    public Payload read() {
        return reader.read(TYPE, new ByteArrayInputStream(encoded),
                           WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON));
    }

    @Benchmark
    public byte[] write() {
        var output = new ByteArrayOutputStream();
        writer.write(TYPE, payload, output, WritableHeaders.create().contentType(MediaTypes.APPLICATION_JSON));
        return output.toByteArray();
    }

    public static class Payload {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
