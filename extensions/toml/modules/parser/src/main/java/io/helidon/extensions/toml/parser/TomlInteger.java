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

package io.helidon.extensions.toml.parser;

import io.helidon.common.Api;

/**
 * TOML integer value represented as a signed 64-bit value.
 */
@Api.Incubating
public final class TomlInteger extends TomlScalar<Long> {
    private final long value;

    private TomlInteger(long value) {
        this.value = value;
    }

    /**
     * Create a TOML integer value.
     *
     * @param value integer value
     * @return TOML integer value
     */
    public static TomlInteger create(long value) {
        return new TomlInteger(value);
    }

    @Override
    public Long value() {
        return value;
    }

    @Override
    public String text() {
        return Long.toString(value);
    }
}
