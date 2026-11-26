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

/**
 * Pure Java Brotli HTTP content encoding.
 */
module io.helidon.extensions.brotli.http.encoding {
    requires static io.helidon.config.metadata;

    requires transitive io.helidon.extensions.brotli;
    requires io.helidon.http;

    requires transitive io.helidon.common;
    requires transitive io.helidon.builder.api;
    requires transitive io.helidon.config;
    requires transitive io.helidon.http.encoding;

    exports io.helidon.extensions.brotli.http.encoding;

    provides io.helidon.http.encoding.spi.ContentEncodingProvider
            with io.helidon.extensions.brotli.http.encoding.BrotliEncodingProvider;
}
