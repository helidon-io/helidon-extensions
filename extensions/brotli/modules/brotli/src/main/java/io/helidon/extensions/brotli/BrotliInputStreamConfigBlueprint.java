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

import java.io.InputStream;
import java.util.List;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Configuration of a Brotli decompression stream.
 */
@Prototype.Blueprint
interface BrotliInputStreamConfigBlueprint extends Prototype.Factory<BrotliInputStream> {
    /**
     * Stream containing Brotli-compressed data. Closing the decoder closes this stream.
     *
     * @return compressed input
     */
    InputStream inputStream();

    /**
     * Size of the buffer used for byte-by-byte reads, in bytes.
     *
     * @return strictly positive buffer size
     */
    @Option.DefaultInt(256)
    int cacheBufferSize();

    /**
     * Optional shared dictionary chunks, in order. At most 15 chunks may be attached.
     * The stream copies the chunk arrays when it is created.
     * References may span chunks but must stay within their concatenated contents;
     * references that continue into the decoded output are not supported.
     *
     * @return dictionary chunks
     */
    @Option.Singular("dictionaryChunk")
    List<byte[]> dictionaryChunks();
}
