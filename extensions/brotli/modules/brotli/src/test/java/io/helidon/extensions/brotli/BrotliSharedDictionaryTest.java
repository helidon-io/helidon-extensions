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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Independent streams exercising static dictionary addressing and word transformations from RFC 7932 section 8
 * and Appendices A and B.
 */
class BrotliSharedDictionaryTest {
    // Handcrafted singleton-code streams, independently decoded by Google Brotli 1.2.1.
    // Appendix A: length 4 indices 0/1 are time/down; length 5 index 772 is años;
    // length 6 indices 1793/632 are как/中国. Appendix B: transforms 0/1/9/44 are identity/space/first-case/all-case.
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("time, identity", "620000004450081210", "time"),
            new Fixture("down, identity", "620000004450081250", "down"),
            new Fixture("time, first-case", "6200000044500812260101", "Time"),
            new Fixture("time, all-case", "62000000445008122a010c", "TIME"),
            new Fixture("time, space suffix", "82000000445008122001", "time "),
            new Fixture("как, identity", "a2000000445010126141", "как"),
            new Fixture("как, all-case", "a2000000445010126cc119", "КАК"),
            new Fixture("中国, identity", "a2000000445010121e1f", "中国"),
            new Fixture("años, identity", "8200000044500c121f02", "años"));
    private static final int[] READ_SIZES = {1, 2, 8192};
    private static final int[] CACHE_SIZES = {1, 3, 256};

    @Test
    @DisplayName("RFC 7932 section 8 and Appendices A/B: independent streams preserve static words and transforms")
    void decodesStaticWordsAndTransformsSequentially() throws IOException {
        for (Fixture fixture : FIXTURES) {
            for (int schedule = 0; schedule < READ_SIZES.length; schedule++) {
                assertDecoded(fixture, CACHE_SIZES[schedule], READ_SIZES[schedule]);
            }
        }
    }

    @Test
    @DisplayName("RFC 7932 section 8 and Appendices A/B: concurrent streams preserve static words and transforms")
    void decodesStaticWordsAndTransformsConcurrently() throws Exception {
        int workers = 6;
        var start = new CyclicBarrier(workers);
        var tasks = new ArrayList<Future<?>>();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                int firstFixture = worker;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int round = 0; round < 2; round++) {
                        int schedule = (firstFixture + round) % READ_SIZES.length;
                        for (int i = 0; i < FIXTURES.size(); i++) {
                            Fixture fixture = FIXTURES.get((firstFixture + i) % FIXTURES.size());
                            assertDecoded(fixture, CACHE_SIZES[schedule], READ_SIZES[schedule]);
                        }
                    }
                    return null;
                }));
            }
            assertAll(tasks.stream().map(task -> (Executable) task::get));
        }
    }

    @Test
    @DisplayName("RFC 7932 section 9.2: static dictionary copy cannot exceed the declared metablock")
    void rejectsStaticDictionaryCopyBeyondMetablockBeforeReturningBytes() throws IOException {
        // The first fixture decodes "time"; its metablock length is changed from four bytes to one.
        byte[] encoded = HexFormat.of().parseHex("020000004450081210");
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded))) {
            assertThrows(IOException.class, () -> input.read(new byte[1]));
        }
    }

    private static void assertDecoded(Fixture fixture, int cacheSize, int readSize) throws IOException {
        String description = fixture.name() + ", cache size " + cacheSize + ", read size " + readSize;
        var destination = new ByteArrayOutputStream();
        byte[] encoded = HexFormat.of().parseHex(fixture.encoded());
        var inputConfig = BrotliInputStreamConfig.builder().cacheBufferSize(cacheSize).build();
        try (var input = new BrotliInputStream(new ByteArrayInputStream(encoded), inputConfig)) {
            if (readSize == 1) {
                for (int value; (value = input.read()) != -1;) {
                    destination.write(value);
                }
            } else {
                byte[] buffer = new byte[readSize];
                for (int count; (count = input.read(buffer)) != -1;) {
                    assertThat(description + " makes progress", count, greaterThan(0));
                    destination.write(buffer, 0, count);
                }
            }
        } catch (IOException failure) {
            throw new IOException(description, failure);
        }
        assertThat(description, destination.toByteArray(), is(fixture.expected().getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(String name, String encoded, String expected) {
    }
}
