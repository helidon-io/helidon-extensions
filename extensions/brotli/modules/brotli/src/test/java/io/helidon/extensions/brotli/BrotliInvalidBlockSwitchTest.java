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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrotliInvalidBlockSwitchTest {
    // https://github.com/nodejs/node/blob/v26.12.1/test/parallel/test-zlib-invalid-input.js
    // declares one block type and attempts an invalid switch (CVE-2026-111497).
    private static final byte[] INVALID_BLOCK_SWITCH = HexFormat.of().parseHex(
            "200010414141fcffff07002228f00b60f7f41f000000");

    @Test
    void rejectsInvalidBlockSwitchWithBufferedAndIncrementalInput() throws IOException {
        for (boolean oneByteAtATime : new boolean[] {false, true}) {
            InputStream source = new ByteArrayInputStream(INVALID_BLOCK_SWITCH);
            if (oneByteAtATime) {
                source = new FilterInputStream(source) {
                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        return super.read(bytes, offset, Math.min(length, 1));
                    }
                };
            }
            try (var stream = BrotliInputStream.builder().inputStream(source).build()) {
                IOException failure = assertThrows(IOException.class,
                                                   () -> stream.transferTo(OutputStream.nullOutputStream()),
                                                   "one byte at a time: " + oneByteAtATime);
                assertThat("one byte at a time: " + oneByteAtATime, failure.getCause(), instanceOf(BrotliException.class));
                assertThat("one byte at a time: " + oneByteAtATime,
                           failure.getCause().getMessage(), is("Invalid metablock length"));
            }
        }
    }
}
