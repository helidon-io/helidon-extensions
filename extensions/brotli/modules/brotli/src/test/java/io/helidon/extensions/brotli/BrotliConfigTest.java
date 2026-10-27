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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrotliConfigTest {
    private static final byte[] DATA = "my data".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMPRESSED_DATA = HexFormat.of().parseHex("0303806d79206461746103");

    @Test
    void emptyConfigurationPreservesDefaults() throws IOException {
        var destination = new ByteArrayOutputStream();
        try (var output = BrotliOutputStream.builder().outputStream(destination).config(Config.empty()).build()) {
            assertThat(output.prototype().quality(), is(0));
            assertThat(output.prototype().window(), is(18));
            assertThat(output.prototype().cacheInputSize(), is(64_000));
            output.write(DATA);
        }
        assertThat(destination.toByteArray(), is(COMPRESSED_DATA));
        try (var input = BrotliInputStream.builder()
                .inputStream(new ByteArrayInputStream(COMPRESSED_DATA))
                .config(Config.empty())
                .build()) {
            assertThat(input.prototype().cacheBufferSize(), is(256));
            assertThat(input.prototype().maxWindowBits(), is(24));
            assertThat(input.prototype().verifyEndOfInput(), is(false));
            assertThat(input.readAllBytes(), is(DATA));
        }
    }

    @Test
    void configuredComponentNodesRoundTripWithSmallCaches() throws IOException {
        var config = Config.just(ConfigSources.create(Map.of("encoder.quality", "0",
                                                            "encoder.window", "10",
                                                            "encoder.cache-input-size", "17",
                                                            "decoder.cache-buffer-size", "3",
                                                            "decoder.max-window-bits", "10",
                                                            "decoder.verify-end-of-input", "true")));
        byte[] expected = "Configuration-driven Brotli stream. ".repeat(80).getBytes(StandardCharsets.UTF_8);
        var destination = new ByteArrayOutputStream();
        try (var output = BrotliOutputStream.builder()
                .outputStream(destination)
                .config(config.get("encoder"))
                .build()) {
            assertThat(output.prototype().quality(), is(0));
            assertThat(output.prototype().window(), is(10));
            assertThat(output.prototype().cacheInputSize(), is(17));
            output.write(expected);
        }
        var restored = new ByteArrayOutputStream();
        try (var input = BrotliInputStream.builder()
                .inputStream(new ByteArrayInputStream(destination.toByteArray()))
                .config(config.get("decoder"))
                .build()) {
            assertThat(input.prototype().cacheBufferSize(), is(3));
            assertThat(input.prototype().maxWindowBits(), is(10));
            assertThat(input.prototype().verifyEndOfInput(), is(true));
            restored.write(input.read());
            restored.writeBytes(input.readNBytes(7));
            restored.writeBytes(input.readAllBytes());
            assertThat(input.read(), is(-1));
        }
        byte[] actual = restored.toByteArray();
        assertThat("decoded length", actual.length, is(expected.length));
        assertThat("first differing byte index", Arrays.mismatch(actual, expected), is(-1));
    }

    @Test
    void scalarConfigurationPreservesProgrammaticDictionaryChunks() throws IOException {
        byte[] dictionary = "abcdefgh".getBytes(StandardCharsets.US_ASCII);
        // Frozen raw-prefix reference from the RFC 9841 section 3.2 cases in BrotliDictionaryTest.
        byte[] encoded = HexFormat.of().parseHex("e200000044501812d2");
        var config = Config.just(ConfigSources.create(Map.of("cache-buffer-size", "1")));
        try (var input = BrotliInputStream.builder()
                .inputStream(new ByteArrayInputStream(encoded))
                .dictionaryChunks(List.of(dictionary))
                .config(config)
                .build()) {
            var destination = new ByteArrayOutputStream();
            for (int value; (value = input.read()) != -1;) {
                destination.write(value);
            }
            assertThat(destination.toByteArray(), is(dictionary));
        }
    }

    @Test
    void rejectsUnsupportedConfiguredQuality() {
        var config = Config.just(ConfigSources.create(Map.of("quality", "1")));
        assertThrows(IllegalArgumentException.class,
                     () -> BrotliOutputStream.builder().outputStream(new ByteArrayOutputStream()).config(config).build());
    }

    @Test
    void rejectsConfiguredWindowOutsideSupportedRange() {
        for (int window : new int[] {9, 25}) {
            var config = Config.just(ConfigSources.create(Map.of("window", Integer.toString(window))));
            assertThrows(IllegalArgumentException.class,
                         () -> BrotliOutputStream.builder().outputStream(new ByteArrayOutputStream()).config(config).build(),
                         "window " + window);
        }
    }

    @Test
    void rejectsConfiguredInputCacheBelowMinimum() {
        var config = Config.just(ConfigSources.create(Map.of("cache-input-size", "3")));
        assertThrows(IllegalArgumentException.class,
                     () -> BrotliOutputStream.builder().outputStream(new ByteArrayOutputStream()).config(config).build());
    }

    @Test
    void rejectsNonpositiveConfiguredReadCache() {
        var config = Config.just(ConfigSources.create(Map.of("cache-buffer-size", "0")));
        assertThrows(IllegalArgumentException.class,
                     () -> BrotliInputStream.builder()
                             .inputStream(new ByteArrayInputStream(COMPRESSED_DATA))
                             .config(config)
                             .build());
    }

    @Test
    void rejectsConfiguredMaximumWindowOutsideSupportedRange() {
        for (int maxWindowBits : new int[] {9, 25}) {
            var config = Config.just(ConfigSources.create(Map.of("max-window-bits", Integer.toString(maxWindowBits))));
            assertThrows(IllegalArgumentException.class,
                         () -> BrotliInputStream.builder()
                                 .inputStream(new ByteArrayInputStream(COMPRESSED_DATA))
                                 .config(config)
                                 .build(),
                         "maximum window " + maxWindowBits);
        }
    }

    @Test
    void rejectsDeclaredWindowAboveConfiguredMaximumBeforeReadingBody() throws IOException {
        // WBITS=24 and a final metablock claiming 16 MiB, with no compressed body.
        byte[] header = HexFormat.of().parseHex("9fffffff");
        try (var input = BrotliInputStream.builder()
                .inputStream(new ByteArrayInputStream(header))
                .maxWindowBits(22)
                .build()) {
            IOException failure = assertThrows(IOException.class, input::read);
            assertThat(failure.getCause(), instanceOf(BrotliException.class));
            assertThat(failure.getCause().getMessage(), is("Brotli window exceeds configured maximum"));
        }
    }
}
