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

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Configuration of Brotli HTTP content encoding.
 */
@Prototype.Blueprint
@Prototype.Configured(value = "br", root = false)
interface BrotliEncodingConfigBlueprint extends Prototype.Factory<BrotliEncoding> {
    /**
     * Name of this encoding instance. The content coding identifier remains {@code br}.
     *
     * @return encoding instance name
     */
    @Option.Configured
    @Option.Default("br")
    String name();

    /**
     * Largest Brotli window exponent accepted from the compressed stream header.
     * Lower values limit decoder ring-buffer memory but reject streams that declare a larger window.
     *
     * @return maximum window exponent, from 10 through 24
     */
    @Option.Configured
    @Option.DefaultInt(24)
    int maxWindowBits();
}
