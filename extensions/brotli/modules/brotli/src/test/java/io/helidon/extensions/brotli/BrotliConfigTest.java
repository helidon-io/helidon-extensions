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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import io.helidon.common.Size;
import io.helidon.config.Config;
import io.helidon.config.ConfigMappingException;
import io.helidon.config.ConfigSources;
import io.helidon.config.spi.ConfigNode;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrotliConfigTest {
    private static final byte[] DATA = "my data".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMPRESSED_DATA = HexFormat.of().parseHex("0303806d79206461746103");

    @Test
    void emptyConfigurationPreservesDefaults() throws IOException {
        var destination = new ByteArrayOutputStream();
        var outputConfig = BrotliOutputStreamConfig.builder().config(Config.empty()).build();
        assertThat(outputConfig.quality(), is(0));
        assertThat(outputConfig.window(), is(18));
        assertThat(outputConfig.cacheInputSize(), is(Size.create(64_000)));
        try (var output = new BrotliOutputStream(destination, outputConfig)) {
            output.write(DATA);
        }
        assertThat(destination.toByteArray(), is(COMPRESSED_DATA));
        var inputConfig = BrotliInputStreamConfig.builder().config(Config.empty()).build();
        assertThat(inputConfig.cacheBufferSize(), is(Size.create(256)));
        assertThat(inputConfig.maxWindowBits(), is(24));
        assertThat(inputConfig.verifyEndOfInput(), is(false));
        try (var input = new BrotliInputStream(new ByteArrayInputStream(COMPRESSED_DATA), inputConfig)) {
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
        var outputConfig = BrotliOutputStreamConfig.builder().config(config.get("encoder")).build();
        assertThat(outputConfig.quality(), is(0));
        assertThat(outputConfig.window(), is(10));
        assertThat(outputConfig.cacheInputSize(), is(Size.create(17)));
        try (var output = new BrotliOutputStream(destination, outputConfig)) {
            output.write(expected);
        }
        var restored = new ByteArrayOutputStream();
        var inputConfig = BrotliInputStreamConfig.builder().config(config.get("decoder")).build();
        assertThat(inputConfig.cacheBufferSize(), is(Size.create(3)));
        assertThat(inputConfig.maxWindowBits(), is(10));
        assertThat(inputConfig.verifyEndOfInput(), is(true));
        try (var input = new BrotliInputStream(new ByteArrayInputStream(destination.toByteArray()), inputConfig)) {
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
    void configuredCacheSizesAcceptUnits() throws IOException {
        var config = Config.just(ConfigSources.create(Map.of("encoder.cache-input-size", "4 KiB",
                                                            "decoder.cache-buffer-size", "2 KiB")));
        byte[] expected = "Configured cache sizes with units. ".repeat(400).getBytes(StandardCharsets.UTF_8);
        var destination = new ByteArrayOutputStream();
        var outputConfig = BrotliOutputStreamConfig.builder().config(config.get("encoder")).build();
        assertThat(outputConfig.cacheInputSize(), is(Size.create(4096)));
        try (var output = new BrotliOutputStream(destination, outputConfig)) {
            output.write(expected);
        }
        var inputConfig = BrotliInputStreamConfig.builder().config(config.get("decoder")).build();
        assertThat(inputConfig.cacheBufferSize(), is(Size.create(2048)));
        try (var input = new BrotliInputStream(new ByteArrayInputStream(destination.toByteArray()), inputConfig)) {
            assertThat(input.readAllBytes(), is(expected));
        }
    }

    @Test
    void scalarConfigurationPreservesProgrammaticDictionaryChunks() throws IOException {
        byte[] dictionary = "abcdefgh".getBytes(StandardCharsets.US_ASCII);
        // Frozen raw-prefix reference from the RFC 9841 section 3.2 cases in BrotliDictionaryTest.
        byte[] encoded = HexFormat.of().parseHex("e200000044501812d2");
        var config = Config.just(ConfigSources.create(Map.of("cache-buffer-size", "1")));
        var inputConfig = BrotliInputStreamConfig.builder()
                .dictionaryChunks(List.of(dictionary))
                .config(config)
                .build();
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded), inputConfig)) {
            var destination = new ByteArrayOutputStream();
            for (int value; (value = input.read()) != -1;) {
                destination.write(value);
            }
            assertThat(destination.toByteArray(), is(dictionary));
        }
    }

    @Test
    void configuredDictionaryChunksPreserveOrderAndBinaryBytes() throws IOException {
        var config = Config.just(ConfigSources.create(Map.of("decoder.dictionary-chunks.0", "AAEC",
                                                            "decoder.dictionary-chunks.1", "/wBhYmM=",
                                                            "decoder.cache-buffer-size", "1")));
        var inputConfig = BrotliInputStreamConfig.builder().config(config.get("decoder")).build();
        // Frozen eight-byte raw-prefix reference from BrotliDictionaryTest.
        byte[] encoded = HexFormat.of().parseHex("e200000044501812d2");
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded), inputConfig)) {
            assertThat(input.readAllBytes(), is(HexFormat.of().parseHex("000102ff00616263")));
        }
    }

    @Test
    void configuredDictionaryChunksReplaceProgrammaticChunks() throws IOException {
        var config = Config.just(ConfigSources.create(Map.of("dictionary-chunks.0", "YWJj",
                                                            "dictionary-chunks.1", "ZGVmZ2g=")));
        var inputConfig = BrotliInputStreamConfig.builder()
                .addDictionaryChunk("discarded".getBytes(StandardCharsets.US_ASCII))
                .config(config)
                .build();
        assertThat(inputConfig.dictionaryChunks().size(), is(2));
        byte[] encoded = HexFormat.of().parseHex("e200000044501812d2");
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded), inputConfig)) {
            assertThat(input.readAllBytes(), is("abcdefgh".getBytes(StandardCharsets.US_ASCII)));
        }
    }

    @Test
    void emptyConfiguredDictionaryClearsProgrammaticChunks() throws IOException {
        var node = ConfigNode.ObjectNode.builder()
                .addList("dictionary-chunks", ConfigNode.ListNode.builder().build())
                .build();
        var config = Config.just(ConfigSources.create(node));
        var inputConfig = BrotliInputStreamConfig.builder()
                .addDictionaryChunk("discarded".getBytes(StandardCharsets.US_ASCII))
                .config(config)
                .build();
        assertThat(inputConfig.dictionaryChunks().isEmpty(), is(true));
        byte[] encoded = HexFormat.of().parseHex("620000004450081210");
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded), inputConfig)) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.US_ASCII), is("time"));
        }
    }

    @Test
    void rejectsMalformedConfiguredDictionaryChunk() {
        var config = Config.just(ConfigSources.create(Map.of("dictionary-chunks.0", "YWJj",
                                                            "dictionary-chunks.1", "not-base64!")));
        ConfigMappingException failure = assertThrows(ConfigMappingException.class,
                                                      () -> BrotliInputStreamConfig.builder().config(config).build());
        assertThat(failure.getMessage(), containsString("dictionary-chunks.1"));
        assertThat(failure.getCause(), instanceOf(IllegalArgumentException.class));
    }

    @Test
    void rejectsExcessiveConfiguredDictionaryChunks() {
        Map<String, String> properties = new HashMap<>();
        for (int i = 0; i < 16; i++) {
            properties.put("dictionary-chunks." + i, "AA==");
        }
        var config = Config.just(ConfigSources.create(properties));
        var inputConfig = BrotliInputStreamConfig.builder().config(config).build();
        assertThrows(IllegalArgumentException.class,
                     () -> new BrotliInputStream(new ByteArrayInputStream(COMPRESSED_DATA), inputConfig));
    }

    @Test
    void rejectsUnsupportedConfiguredQuality() {
        var config = Config.just(ConfigSources.create(Map.of("quality", "1")));
        var outputConfig = BrotliOutputStreamConfig.builder().config(config).build();
        assertThrows(IllegalArgumentException.class,
                     () -> new BrotliOutputStream(new ByteArrayOutputStream(), outputConfig));
    }

    @Test
    void rejectsConfiguredWindowOutsideSupportedRange() {
        for (int window : new int[] {9, 25}) {
            var config = Config.just(ConfigSources.create(Map.of("window", Integer.toString(window))));
            var outputConfig = BrotliOutputStreamConfig.builder().config(config).build();
            assertThrows(IllegalArgumentException.class,
                         () -> new BrotliOutputStream(new ByteArrayOutputStream(), outputConfig),
                         "window " + window);
        }
    }

    @Test
    void rejectsConfiguredInputCacheOutsideSupportedRange() {
        for (String size : List.of("-1", "0", "3", "2147483648", "9223372036854775808")) {
            var config = Config.just(ConfigSources.create(Map.of("cache-input-size", size)));
            var outputConfig = BrotliOutputStreamConfig.builder().config(config).build();
            assertThrows(IllegalArgumentException.class,
                         () -> new BrotliOutputStream(new ByteArrayOutputStream(), outputConfig),
                         "input cache size " + size);
        }
    }

    @Test
    void rejectsConfiguredReadCacheOutsideSupportedRange() {
        for (String size : List.of("-1", "0", "2147483648", "9223372036854775808")) {
            var config = Config.just(ConfigSources.create(Map.of("cache-buffer-size", size)));
            var inputConfig = BrotliInputStreamConfig.builder().config(config).build();
            assertThrows(IllegalArgumentException.class,
                         () -> new BrotliInputStream(new ByteArrayInputStream(COMPRESSED_DATA), inputConfig),
                         "read cache size " + size);
        }
    }

    @Test
    void rejectsConfiguredMaximumWindowOutsideSupportedRange() {
        for (int maxWindowBits : new int[] {9, 25}) {
            var config = Config.just(ConfigSources.create(Map.of("max-window-bits", Integer.toString(maxWindowBits))));
            var inputConfig = BrotliInputStreamConfig.builder().config(config).build();
            assertThrows(IllegalArgumentException.class,
                         () -> new BrotliInputStream(new ByteArrayInputStream(COMPRESSED_DATA), inputConfig),
                         "maximum window " + maxWindowBits);
        }
    }

    @Test
    void rejectsDeclaredWindowAboveConfiguredMaximumBeforeReadingBody() throws IOException {
        // WBITS=24 and a final metablock claiming 16 MiB, with no compressed body.
        byte[] header = HexFormat.of().parseHex("9fffffff");
        var inputConfig = BrotliInputStreamConfig.builder().maxWindowBits(22).build();
        try (var input = new BrotliInputStream(new ByteArrayInputStream(header), inputConfig)) {
            IOException failure = assertThrows(IOException.class, input::read);
            assertThat(failure.getCause(), instanceOf(BrotliException.class));
            assertThat(failure.getCause().getMessage(), is("Brotli window exceeds configured maximum"));
        }
    }
}
