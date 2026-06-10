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

import java.time.LocalTime;
import java.util.Objects;

import io.helidon.common.Api;

/**
 * TOML local time value.
 */
@Api.Incubating
public final class TomlLocalTime extends TomlScalar<LocalTime> {
    private final LocalTime value;

    private TomlLocalTime(LocalTime value) {
        this.value = Objects.requireNonNull(value);
    }

    /**
     * Create a TOML local time value.
     *
     * @param value local time value
     * @return TOML local time value
     */
    public static TomlLocalTime create(LocalTime value) {
        return new TomlLocalTime(value);
    }

    @Override
    public LocalTime value() {
        return value;
    }

    @Override
    public String text() {
        return TomlDateTimes.format(value);
    }
}
