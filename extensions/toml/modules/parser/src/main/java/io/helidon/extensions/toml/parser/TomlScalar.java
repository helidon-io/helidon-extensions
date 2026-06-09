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

import java.util.Objects;

/**
 * TOML scalar value.
 */
public final class TomlScalar implements TomlValue {
    private final Object value;

    TomlScalar(Object value) {
        this.value = Objects.requireNonNull(value);
    }

    /**
     * Scalar value. The value is one of {@link String}, {@link Long}, {@link Double}, {@link Boolean},
     * {@link java.time.OffsetDateTime}, {@link java.time.LocalDateTime}, {@link java.time.LocalDate}, or
     * {@link java.time.LocalTime}.
     *
     * @return scalar value
     */
    public Object value() {
        return value;
    }

    /**
     * Scalar value as text.
     *
     * @return scalar text
     */
    public String stringValue() {
        return String.valueOf(value);
    }
}
