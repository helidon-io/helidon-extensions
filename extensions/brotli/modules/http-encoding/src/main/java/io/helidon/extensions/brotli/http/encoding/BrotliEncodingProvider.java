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

import java.util.Objects;

import io.helidon.common.Weighted;
import io.helidon.config.Config;
import io.helidon.http.encoding.ContentEncoding;
import io.helidon.http.encoding.spi.ContentEncodingProvider;

/**
 * Service provider for Brotli content encoding.
 */
public final class BrotliEncodingProvider implements ContentEncodingProvider, Weighted {
    /**
     * Public constructor required by {@link java.util.ServiceLoader}.
     */
    public BrotliEncodingProvider() {
    }

    @Override
    public String configKey() {
        return "br";
    }

    @Override
    public ContentEncoding create(Config config, String name) {
        Objects.requireNonNull(config, "config");
        int maxWindowBits = config.get("max-window-bits").asInt().orElse(24);
        return new BrotliEncoding(name, maxWindowBits);
    }

    @Override
    public double weight() {
        return Weighted.DEFAULT_WEIGHT;
    }
}
