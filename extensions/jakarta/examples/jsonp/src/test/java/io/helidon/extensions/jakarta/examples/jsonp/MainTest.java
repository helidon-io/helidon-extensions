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

package io.helidon.extensions.jakarta.examples.jsonp;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.extensions.jakarta.http.media.jsonp.JsonpSupport;

import jakarta.json.Json;
import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class MainTest {
    @Test
    void clientServerRoundTrip() {
        var request = Json.createObjectBuilder().add("message", "Příliš žluťoučký kůň").build();
        assertThat(Main.roundTrip(JsonpSupport.builder().build(), request), is(request));
    }

    @Test
    void customizedFactoriesServeClientAndServer() {
        var readerFactory = new CountingReaderFactory();
        var writerFactory = new CountingWriterFactory();
        var support = JsonpSupport.builder()
                .readerFactory(readerFactory)
                .writerFactory(writerFactory)
                .build();
        var request = Json.createObjectBuilder().add("custom", "factories").build();

        assertThat(Main.roundTrip(support, request), is(request));
        assertThat("request and response use the customized reader", readerFactory.readers.get(), is(2));
        assertThat("request and response use the customized writer", writerFactory.writers.get(), is(2));
    }

    private static class CountingReaderFactory implements JsonReaderFactory {
        private final JsonReaderFactory delegate = Json.createReaderFactory(Map.of());
        private final AtomicInteger readers = new AtomicInteger();

        @Override
        public JsonReader createReader(Reader reader) {
            readers.incrementAndGet();
            return delegate.createReader(reader);
        }

        @Override
        public JsonReader createReader(InputStream stream) {
            readers.incrementAndGet();
            return delegate.createReader(stream);
        }

        @Override
        public JsonReader createReader(InputStream stream, Charset charset) {
            readers.incrementAndGet();
            return delegate.createReader(stream, charset);
        }

        @Override
        public Map<String, ?> getConfigInUse() {
            return delegate.getConfigInUse();
        }
    }

    private static class CountingWriterFactory implements JsonWriterFactory {
        private final JsonWriterFactory delegate = Json.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true));
        private final AtomicInteger writers = new AtomicInteger();

        @Override
        public JsonWriter createWriter(Writer writer) {
            writers.incrementAndGet();
            return delegate.createWriter(writer);
        }

        @Override
        public JsonWriter createWriter(OutputStream stream) {
            writers.incrementAndGet();
            return delegate.createWriter(stream);
        }

        @Override
        public JsonWriter createWriter(OutputStream stream, Charset charset) {
            writers.incrementAndGet();
            return delegate.createWriter(stream, charset);
        }

        @Override
        public Map<String, ?> getConfigInUse() {
            return delegate.getConfigInUse();
        }
    }
}
