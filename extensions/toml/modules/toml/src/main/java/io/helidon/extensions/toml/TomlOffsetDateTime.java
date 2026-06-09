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

import java.time.OffsetDateTime;
import java.util.Objects;

import io.helidon.common.Api;

/**
 * TOML offset date-time value.
 */
@Api.Incubating
public final class TomlOffsetDateTime extends TomlScalar<OffsetDateTime> {
    private final OffsetDateTime value;

    private TomlOffsetDateTime(OffsetDateTime value) {
        this.value = Objects.requireNonNull(value);
        if (value.getOffset().getTotalSeconds() % 60 != 0) {
            throw new IllegalArgumentException("TOML offset date-time requires offset minute precision");
        }
    }

    /**
     * Create a TOML offset date-time value.
     *
     * @param value offset date-time value
     * @return TOML offset date-time value
     */
    public static TomlOffsetDateTime create(OffsetDateTime value) {
        return new TomlOffsetDateTime(value);
    }

    @Override
    public OffsetDateTime value() {
        return value;
    }

    @Override
    public String text() {
        return TomlDateTimes.format(value);
    }
}
