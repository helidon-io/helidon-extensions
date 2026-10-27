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

package io.helidon.extensions.brotli.http.encoding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.http.HeaderNames;
import io.helidon.http.WritableHeaders;
import io.helidon.http.encoding.ContentEncodingContext;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrotliEncodingTest {
    private static final byte[] LARGE_WINDOW_HEADER = HexFormat.of().parseHex("9fffffff");

    @Test
    void defaultEncodingUsesBr() {
        var encoding = BrotliEncoding.create();

        assertThat(encoding.ids(), is(Set.of("br")));
        assertThat(encoding.type(), is("br"));
        assertThat(encoding.name(), is("br"));
        assertThat(encoding.supportsEncoding(), is(true));
        assertThat(encoding.supportsDecoding(), is(true));
    }

    @Test
    void encoderRemovesUncompressedLength() {
        var headers = WritableHeaders.create();
        headers.set(HeaderNames.CONTENT_LENGTH, "123");

        BrotliEncoding.create().encoder().headers(headers);

        assertThat(headers.get(HeaderNames.CONTENT_ENCODING).get(), is("br"));
        assertThat(headers.contains(HeaderNames.CONTENT_LENGTH), is(false));
    }

    @Test
    void encoderAndDecoderPreservePayload() throws IOException {
        byte[] entity = "Brotli encoding for Helidon HTTP".getBytes(StandardCharsets.UTF_8);
        var encoding = BrotliEncoding.create();
        var compressed = new ByteArrayOutputStream();
        try (var out = encoding.encoder().apply(compressed)) {
            out.write(entity);
        }

        try (var in = encoding.decoder().apply(new ByteArrayInputStream(compressed.toByteArray()))) {
            assertThat(in.readAllBytes(), is(entity));
        }
    }

    @Test
    void providerUsesConfiguredName() {
        var provider = new BrotliEncodingProvider();
        var encoding = provider.create(Config.empty(), "configured-brotli");

        assertThat(provider.configKey(), is("br"));
        assertThat(encoding.name(), is("configured-brotli"));
        assertThat(encoding.type(), is("br"));
    }

    @Test
    void providerIsDiscovered() {
        var context = ContentEncodingContext.create();

        assertThat(context.contentEncodingSupported("br"), is(true));
        assertThat(context.contentDecodingSupported("br"), is(true));
    }

    @Test
    void providerIsConfigurable() throws IOException {
        var config = Config.just(ConfigSources.create(Map.of("content-encodings.0.type", "br",
                                                            "content-encodings.0.max-window-bits", "22")));
        var context = ContentEncodingContext.create(config);

        assertThat(context.contentEncodingSupported("br"), is(true));
        assertThat(context.prototype().contentEncodings().getFirst().type(), is("br"));
        assertThat(context.prototype().contentEncodings().getFirst().name(), is("0"));
        try (var input = context.decoder("br").apply(new ByteArrayInputStream(LARGE_WINDOW_HEADER))) {
            IOException failure = assertThrows(IOException.class, input::read);
            assertThat(failure.getCause().getMessage(), is("Brotli window exceeds configured maximum"));
        }
    }

    @Test
    void programmaticDecoderWindowLimitRejectsLargeHeader() throws IOException {
        var encoding = BrotliEncoding.create(22);
        try (var input = encoding.decoder().apply(new ByteArrayInputStream(LARGE_WINDOW_HEADER))) {
            IOException failure = assertThrows(IOException.class, input::read);
            assertThat(failure.getCause().getMessage(), is("Brotli window exceeds configured maximum"));
        }
        assertThrows(IllegalArgumentException.class, () -> BrotliEncoding.create(9));
    }

    @Test
    void httpDecoderRejectsIncrementalTrailingDataWithoutDraining() throws IOException {
        byte[] encoded = HexFormat.of().parseHex("0303806d792064617461034142434445");
        var source = new ByteArrayInputStream(encoded) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        try (var input = BrotliEncoding.create().decoder().apply(source)) {
            IOException failure = assertThrows(IOException.class, input::readAllBytes);
            assertThat(failure.getCause().getMessage(), is("Unused bytes after end"));
            assertThat(source.available(), is(4));
            assertThrows(IOException.class, input::read);
        }
    }

    @Test
    void httpDecoderReturnsPlaintextBeforeSourceEof() throws IOException {
        byte[] encoded = HexFormat.of().parseHex("0303806d79206461746103");
        var source = new FilterInputStream(new ByteArrayInputStream(encoded)) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (in.available() == 0) {
                    throw new IOException("Decoder waited for source EOF");
                }
                return super.read(bytes, offset, length);
            }
        };
        try (var input = BrotliEncoding.create().decoder().apply(source)) {
            assertThat(input.read(), is((int) 'm'));
        }
    }
}
