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

/**
 * TOML float value represented as an IEEE 754 binary64 value.
 */
public final class TomlFloat extends TomlScalar<Double> {
    private final double value;

    private TomlFloat(double value) {
        this.value = value;
    }

    /**
     * Create a TOML float value.
     *
     * @param value float value
     * @return TOML float value
     */
    public static TomlFloat create(double value) {
        return new TomlFloat(value);
    }

    @Override
    public Double value() {
        return value;
    }

    @Override
    public String text() {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-inf";
        }
        return Double.toString(value);
    }
}
