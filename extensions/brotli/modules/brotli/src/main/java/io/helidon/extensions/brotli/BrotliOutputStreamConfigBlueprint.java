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

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Configuration of a Brotli compression stream.
 */
@Prototype.Blueprint
@Prototype.Configured
interface BrotliOutputStreamConfigBlueprint {
    /**
     * Compression quality. Only quality {@code 0} is currently implemented.
     * Unsupported values fail when a stream is constructed.
     *
     * @return compression quality
     */
    @Option.Configured
    @Option.DefaultInt(0)
    int quality();

    /**
     * Base-two logarithm of the sliding window size.
     *
     * @return window size exponent, between 10 and 24
     */
    @Option.Configured
    @Option.DefaultInt(18)
    int window();

    /**
     * Size of the uncompressed input cache, in bytes.
     *
     * @return input cache size, at least 4
     */
    @Option.Configured
    @Option.DefaultInt(64_000)
    int cacheInputSize();
}
