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

package io.helidon.extensions.toml;

import java.util.Objects;

import io.helidon.common.Api;

/**
 * TOML string value.
 */
@Api.Incubating
public final class TomlString extends TomlScalar<String> {
    private final String value;

    private TomlString(String value) {
        this.value = Objects.requireNonNull(value);
    }

    /**
     * Create a TOML string value.
     *
     * @param value string value
     * @return TOML string value
     */
    public static TomlString create(String value) {
        return new TomlString(value);
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public String text() {
        return value;
    }
}
