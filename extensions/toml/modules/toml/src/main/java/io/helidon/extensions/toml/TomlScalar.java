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
 * TOML scalar value.
 *
 * @param <T> Java type of the scalar value
 */
@Api.Incubating
public abstract sealed class TomlScalar<T> implements TomlValue
        permits TomlBoolean,
                TomlFloat,
                TomlInteger,
                TomlLocalDate,
                TomlLocalDateTime,
                TomlLocalTime,
                TomlOffsetDateTime,
                TomlString {

    TomlScalar() {
    }

    /**
     * Scalar value. The value is one of {@link String}, {@link Long}, {@link Double}, {@link Boolean},
     * {@link java.time.OffsetDateTime}, {@link java.time.LocalDateTime}, {@link java.time.LocalDate}, or
     * {@link java.time.LocalTime}.
     *
     * @return scalar value
     */
    public abstract T value();

    /**
     * Scalar text.
     *
     * @return scalar text
     */
    public abstract String text();

    @Override
    public final String toString() {
        return text();
    }

    @Override
    public final boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        TomlScalar<?> that = (TomlScalar<?>) obj;
        return Objects.equals(value(), that.value());
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), value());
    }
}
