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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

class BrotliEncodingTest {
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
    void providerIsConfigurable() {
        var config = Config.just(ConfigSources.create(Map.of("content-encodings.0.type", "br")));
        var context = ContentEncodingContext.create(config);

        assertThat(context.contentEncodingSupported("br"), is(true));
        assertThat(context.prototype().contentEncodings().getFirst().type(), is("br"));
        assertThat(context.prototype().contentEncodings().getFirst().name(), is("0"));
    }
}
