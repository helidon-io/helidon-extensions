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

package io.helidon.extensions.messaging.connectors.aq;

import java.util.List;
import java.util.Objects;

/**
 * Detached Oracle SQL object, or a named SQL array nested inside an object.
 * Attribute values support scalar JDBC values, byte arrays, nested {@code AqAdt}
 * values, {@link AqXml}, {@link AqBlob}, and {@link AqClob}.
 * SQL NULL is represented by {@link NullValue#INSTANCE}.
 * Mutable binary and temporal values are defensively copied.
 */
public final class AqAdt {
    private final String typeName;
    private final List<Object> attributes;
    private final Kind kind;

    private AqAdt(AqAdtConfig config) {
        Objects.requireNonNull(config);
        this.typeName = Objects.requireNonNull(config.typeName());
        if (typeName.isBlank()) {
            throw new IllegalArgumentException("SQL type name must not be blank");
        }
        this.attributes = config.attributes().stream().map(AqValues::sqlValue).toList();
        this.kind = Objects.requireNonNull(config.kind());
    }

    /**
     * Create a builder.
     *
     * @return builder
     */
    public static AqAdtConfig.Builder builder() {
        return AqAdtConfig.builder();
    }

    static AqAdt create(AqAdtConfig config) {
        return new AqAdt(Objects.requireNonNull(config));
    }

    /**
     * Qualified SQL type name.
     *
     * @return SQL type name
     */
    public String typeName() {
        return typeName;
    }

    /**
     * Detached attribute or element snapshot.
     *
     * @return ordered values
     */
    public List<Object> attributes() {
        return attributes.stream().map(AqValues::sqlValue).toList();
    }

    /**
     * SQL value shape.
     *
     * @return shape
     */
    public Kind kind() {
        return kind;
    }

    /**
     * SQL value shapes.
     */
    public enum Kind {
        /** SQL object with ordered attributes. */
        STRUCT,
        /** Named SQL array, usually nested in an object. */
        ARRAY
    }

    /**
     * Representation of a SQL NULL attribute.
     */
    public enum NullValue {
        /** SQL NULL. */
        INSTANCE
    }
}
