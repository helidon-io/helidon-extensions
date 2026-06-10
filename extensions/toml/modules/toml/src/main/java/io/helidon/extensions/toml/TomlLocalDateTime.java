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

import java.time.LocalDateTime;
import java.util.Objects;

import io.helidon.common.Api;

/**
 * TOML local date-time value.
 */
@Api.Incubating
public final class TomlLocalDateTime extends TomlScalar<LocalDateTime> {
    private final LocalDateTime value;

    private TomlLocalDateTime(LocalDateTime value) {
        this.value = Objects.requireNonNull(value);
    }

    /**
     * Create a TOML local date-time value.
     *
     * @param value local date-time value
     * @return TOML local date-time value
     */
    public static TomlLocalDateTime create(LocalDateTime value) {
        return new TomlLocalDateTime(value);
    }

    @Override
    public LocalDateTime value() {
        return value;
    }

    @Override
    public String text() {
        return TomlDateTimes.format(value);
    }
}
