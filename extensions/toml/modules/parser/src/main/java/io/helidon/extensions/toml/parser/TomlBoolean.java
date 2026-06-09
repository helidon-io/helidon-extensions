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
 * TOML boolean value.
 */
@Api.Incubating
public final class TomlBoolean extends TomlScalar<Boolean> {
    /**
     * TOML {@code false}.
     */
    public static final TomlBoolean FALSE = new TomlBoolean(false);

    /**
     * TOML {@code true}.
     */
    public static final TomlBoolean TRUE = new TomlBoolean(true);

    private final boolean value;

    private TomlBoolean(boolean value) {
        this.value = value;
    }

    /**
     * Create a TOML boolean value.
     *
     * @param value boolean value
     * @return TOML boolean value
     */
    public static TomlBoolean create(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public Boolean value() {
        return value;
    }

    @Override
    public String text() {
        return Boolean.toString(value);
    }
}
