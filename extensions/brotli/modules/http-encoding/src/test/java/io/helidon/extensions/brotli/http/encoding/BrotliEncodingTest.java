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
import io.helidon.extensions.brotli.BrotliInputStreamConfig;
import io.helidon.extensions.brotli.BrotliOutputStream;
import io.helidon.extensions.brotli.BrotliOutputStreamConfig;
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
        assertThat(encoding.prototype().inputStreamConfig().maxWindowBits(), is(24));
        assertThat(encoding.prototype().outputStreamConfig().window(), is(18));
        assertThat(BrotliEncoding.builder().build().name(), is("br"));
    }

    @Test
    void builderUsesProgrammaticName() {
        var encoding = BrotliEncoding.builder().name("custom-brotli").build();

        assertThat(encoding.name(), is("custom-brotli"));
        assertThat(encoding.type(), is("br"));
        assertThat(encoding.ids(), is(Set.of("br")));
    }

    @Test
    void builderUsesComponentConfiguration() throws IOException {
        var config = Config.just(ConfigSources.create(Map.of("name", "configured-brotli",
                                                            "input-stream.max-window-bits", "10",
                                                            "input-stream.cache-buffer-size", "17",
                                                            "output-stream.window", "10",
                                                            "output-stream.cache-input-size", "32")));
        var encoding = BrotliEncoding.builder().config(config).build();
        byte[] entity = "Brotli with a small window".getBytes(StandardCharsets.UTF_8);
        var compressed = new ByteArrayOutputStream();
        try (var output = encoding.encoder().apply(compressed)) {
            output.write(entity);
        }

        assertThat(encoding.name(), is("configured-brotli"));
        assertThat(encoding.prototype().inputStreamConfig().maxWindowBits(), is(10));
        assertThat(encoding.prototype().inputStreamConfig().cacheBufferSize(), is(17));
        assertThat(encoding.prototype().outputStreamConfig().window(), is(10));
        assertThat(encoding.prototype().outputStreamConfig().cacheInputSize(), is(32));
        try (var input = encoding.decoder().apply(new ByteArrayInputStream(compressed.toByteArray()))) {
            assertThat(input.readAllBytes(), is(entity));
        }
        try (var input = encoding.decoder().apply(new ByteArrayInputStream(LARGE_WINDOW_HEADER))) {
            IOException failure = assertThrows(IOException.class, input::read);
            assertThat(failure.getCause().getMessage(), is("Brotli window exceeds configured maximum"));
        }
    }

    @Test
    void defaultDecoderAcceptsMaximumWindow() throws IOException {
        byte[] entity = "Brotli with a maximum window".getBytes(StandardCharsets.UTF_8);
        var compressed = new ByteArrayOutputStream();
        try (var output = new BrotliOutputStream(compressed, BrotliOutputStreamConfig.builder().window(24).build())) {
            output.write(entity);
        }

        try (var input = BrotliEncoding.create().decoder().apply(new ByteArrayInputStream(compressed.toByteArray()))) {
            assertThat(input.readAllBytes(), is(entity));
        }
    }

    @Test
    void defaultDecoderUsesStandardStaticDictionary() throws IOException {
        // RFC 7932 sections 8 and 13: standard br uses the built-in dictionary.
        byte[] encoded = HexFormat.of().parseHex("620000004450081210");
        try (var input = BrotliEncoding.create().decoder().apply(new ByteArrayInputStream(encoded))) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.US_ASCII), is("time"));
        }
    }

    @Test
    void attachedDictionariesFailWhenBuilt() {
        var inputConfig = BrotliInputStreamConfig.builder()
                .addDictionaryChunk("abcdefgh".getBytes(StandardCharsets.US_ASCII))
                .build();
        var config = BrotliEncodingConfig.builder().inputStreamConfig(inputConfig).buildPrototype();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                                                       () -> BrotliEncoding.builder().inputStreamConfig(inputConfig).build());
        assertThat(failure.getMessage(), is("HTTP br content encoding does not support attached dictionaries"));
        assertThrows(IllegalArgumentException.class, () -> BrotliEncoding.create(config));
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
        var config = Config.just(ConfigSources.create(Map.of("name", "ignored-name")));
        var encoding = provider.create(config, "configured-brotli");

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
                                                            "content-encodings.0.input-stream.max-window-bits", "22")));
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
        var inputConfig = BrotliInputStreamConfig.builder().maxWindowBits(22).build();
        var encoding = BrotliEncoding.builder().inputStreamConfig(inputConfig).build();
        try (var input = encoding.decoder().apply(new ByteArrayInputStream(LARGE_WINDOW_HEADER))) {
            IOException failure = assertThrows(IOException.class, input::read);
            assertThat(failure.getCause().getMessage(), is("Brotli window exceeds configured maximum"));
        }
    }

    @Test
    void invalidWindowLimitsFailWhenBuilt() {
        for (int maxWindowBits : new int[] {9, 25}) {
            var config = Config.just(ConfigSources.create(Map.of("input-stream.max-window-bits",
                                                                Integer.toString(maxWindowBits))));
            var inputConfig = BrotliInputStreamConfig.builder().maxWindowBits(maxWindowBits).build();
            assertThrows(IllegalArgumentException.class,
                         () -> BrotliEncoding.builder().inputStreamConfig(inputConfig).build(),
                         "programmatic maximum window " + maxWindowBits);
            assertThrows(IllegalArgumentException.class,
                         () -> BrotliEncoding.builder().config(config).build(),
                         "configured maximum window " + maxWindowBits);
        }
    }

    @Test
    void factoriesUseEncodingConfiguration() {
        var inputConfig = BrotliInputStreamConfig.builder().maxWindowBits(10).build();
        var config = BrotliEncodingConfig.builder().name("prototype-brotli").inputStreamConfig(inputConfig).buildPrototype();
        var encoding = BrotliEncoding.create(config);
        var customized = BrotliEncoding.create(builder -> builder.name("customized-brotli")
                .inputStreamConfig(BrotliInputStreamConfig.builder().maxWindowBits(22).build()));

        assertThat(encoding.name(), is("prototype-brotli"));
        assertThat(encoding.prototype().inputStreamConfig().maxWindowBits(), is(10));
        assertThat(customized.name(), is("customized-brotli"));
        assertThat(customized.prototype().inputStreamConfig().maxWindowBits(), is(22));
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
        var inputConfig = BrotliInputStreamConfig.builder().verifyEndOfInput(false).build();
        var encoding = BrotliEncoding.builder().inputStreamConfig(inputConfig).build();
        try (var input = encoding.decoder().apply(source)) {
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
