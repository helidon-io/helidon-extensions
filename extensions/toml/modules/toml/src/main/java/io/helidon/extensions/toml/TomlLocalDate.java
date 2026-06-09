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

import java.time.LocalDate;
import java.util.Objects;

import io.helidon.common.Api;

/**
 * TOML local date value.
 */
@Api.Incubating
public final class TomlLocalDate extends TomlScalar<LocalDate> {
    private final LocalDate value;

    private TomlLocalDate(LocalDate value) {
        this.value = Objects.requireNonNull(value);
    }

    /**
     * Create a TOML local date value.
     *
     * @param value local date value
     * @return TOML local date value
     */
    public static TomlLocalDate create(LocalDate value) {
        return new TomlLocalDate(value);
    }

    @Override
    public LocalDate value() {
        return value;
    }

    @Override
    public String text() {
        return value.toString();
    }
}
