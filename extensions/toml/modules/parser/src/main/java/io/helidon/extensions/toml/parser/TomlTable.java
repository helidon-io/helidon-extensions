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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * TOML table value.
 */
public final class TomlTable implements TomlValue {
    private final Map<String, TomlValue> values;

    TomlTable(Map<String, TomlValue> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Table values.
     *
     * @return immutable table values
     */
    public Map<String, TomlValue> values() {
        return values;
    }

    /**
     * Get a value by its key within this table.
     *
     * @param key key within this table
     * @return value, if present
     */
    public Optional<TomlValue> get(String key) {
        return Optional.ofNullable(values.get(key));
    }
}
