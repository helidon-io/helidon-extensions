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

import io.helidon.common.Api;

/**
 * Detached Oracle SQL object, or a named SQL array nested inside an object.
 * Attribute values support scalar JDBC values, byte arrays, nested {@code AqAdt}
 * values, {@link AqXml}, {@link AqBlob}, and {@link AqClob}.
 * SQL NULL is represented by {@link NullValue#INSTANCE}.
 * Mutable binary and temporal values are defensively copied.
 */
@Api.Incubating
public final class AqAdt {
    private final String typeName;
    private final List<Object> attributes;
    private final Kind kind;

    private AqAdt(String typeName, List<?> attributes, Kind kind) {
        this.typeName = Objects.requireNonNull(typeName, "SQL type name");
        if (typeName.isBlank()) {
            throw new IllegalArgumentException("SQL type name must not be blank");
        }
        this.attributes = Objects.requireNonNull(attributes, "SQL attributes")
                .stream().map(AqValues::sqlValue).toList();
        this.kind = kind;
    }

    /**
     * Create a detached SQL object with ordered attributes.
     * The list and its mutable binary and temporal values are copied.
     * Use {@link NullValue#INSTANCE} for SQL NULL attributes.
     *
     * @param typeName nonblank qualified SQL object type name
     * @param attributes non-null ordered attributes containing no null entries
     * @return detached SQL object with kind {@link Kind#STRUCT}
     * @throws NullPointerException if the name, list, or an attribute is null
     * @throws IllegalArgumentException if the name is blank or an attribute has an unsupported type
     */
    public static AqAdt create(String typeName, List<?> attributes) {
        return new AqAdt(typeName, attributes, Kind.STRUCT);
    }

    /**
     * Create a detached named SQL array for use inside an SQL object.
     * The list and its mutable binary and temporal values are copied.
     * Use {@link NullValue#INSTANCE} for SQL NULL elements.
     *
     * @param typeName nonblank qualified SQL array type name
     * @param elements non-null ordered elements containing no null entries
     * @return detached SQL array with kind {@link Kind#ARRAY}
     * @throws NullPointerException if the name, list, or an element is null
     * @throws IllegalArgumentException if the name is blank or an element has an unsupported type
     */
    public static AqAdt createArray(String typeName, List<?> elements) {
        return new AqAdt(typeName, elements, Kind.ARRAY);
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
    @Api.Incubating
    public enum Kind {
        /** SQL object with ordered attributes. */
        STRUCT,
        /** Named SQL array, usually nested in an object. */
        ARRAY
    }

    /**
     * Representation of a SQL NULL attribute.
     */
    @Api.Incubating
    public enum NullValue {
        /** SQL NULL. */
        INSTANCE
    }
}
