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

import java.util.List;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Size;

/**
 * Configuration of a Brotli decompression stream.
 */
@Prototype.Blueprint
@Prototype.Configured
@Prototype.CustomMethods(BrotliInputStreamConfigSupport.class)
interface BrotliInputStreamConfigBlueprint {
    /**
     * Size of the buffer used for byte-by-byte reads.
     *
     * @return buffer size, from 1 through {@link Integer#MAX_VALUE} bytes
     */
    @Option.Configured
    @Option.Default("256")
    Size cacheBufferSize();

    /**
     * Largest Brotli window exponent accepted from the compressed stream header.
     * Lower values limit decoder ring-buffer memory but reject streams that declare a larger window.
     *
     * @return maximum window exponent, from 10 through 24
     */
    @Option.Configured
    @Option.DefaultInt(24)
    int maxWindowBits();

    /**
     * Whether to require underlying input EOF when the Brotli stream ends,
     * rejecting trailing bytes. Enable this for a bounded entity stream that must be
     * fully consumed. The default allows progressive reads to finish before the
     * underlying input closes.
     *
     * @return whether the underlying input must end with the Brotli stream
     */
    @Option.Configured
    @Option.DefaultBoolean(false)
    boolean verifyEndOfInput();

    /**
     * Optional shared dictionary chunks, in order. At most 15 chunks may be attached.
     * The stream copies the chunk arrays when it is created.
     * References may span chunks but must stay within their concatenated contents;
     * references that continue into the decoded output are not supported.
     * Configuration supplies an ordered list of Base64 strings under {@code dictionary-chunks}.
     * A configured list replaces previously supplied chunks; an absent key preserves them.
     * Malformed Base64 is rejected when the configuration is applied.
     *
     * @return dictionary chunks
     */
    @Option.Configured
    @Option.Singular
    List<byte[]> dictionaryChunks();
}
