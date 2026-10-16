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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessageHeaders;
import io.helidon.messaging.MessagingException;

final class AqValues {
    private AqValues() {
    }

    static Object sqlValue(Object value) {
        Objects.requireNonNull(value, "SQL attribute; use AqAdt.NullValue.INSTANCE for SQL NULL");
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof Timestamp timestamp) {
            Timestamp copy = new Timestamp(timestamp.getTime());
            copy.setNanos(timestamp.getNanos());
            return copy;
        }
        if (value instanceof Date date) {
            return new Date(date.getTime());
        }
        if (value instanceof Time time) {
            return new Time(time.getTime());
        }
        if (value instanceof AqAdt || value instanceof AqXml || value instanceof AqBlob || value instanceof AqClob
                || value instanceof AqAdt.NullValue
                || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof LocalDate || value instanceof LocalTime
                || value instanceof LocalDateTime || value instanceof OffsetDateTime
                || value instanceof OffsetTime || value instanceof Instant) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported detached SQL attribute: " + value.getClass().getName());
    }

    static Object body(Object value) {
        Objects.requireNonNull(value, "entity");
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());
            copy.put(buffer.duplicate()).flip();
            return copy.asReadOnlyBuffer().order(buffer.order());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String name) || name.isEmpty()) {
                    throw new IllegalArgumentException("AQ map payload keys must be non-empty strings");
                }
                result.put(name, mapValue(item));
            });
            return Collections.unmodifiableMap(result);
        }
        return value;
    }

    static Object mapValue(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported AQ map value: " + value.getClass().getName());
    }

    static String propertyName(String name) {
        Objects.requireNonNull(name, "property name");
        if (name.equals("JMSXGroupID") || name.equals("JMSXGroupSeq")) {
            return name;
        }
        if (name.isEmpty() || name.startsWith("JMS") || !Character.isJavaIdentifierStart(name.codePointAt(0))
                || name.codePoints().anyMatch(c -> !Character.isJavaIdentifierPart(c))
                || switch (name.toUpperCase(Locale.ROOT)) {
                    case "NULL", "TRUE", "FALSE", "NOT", "AND", "OR", "BETWEEN", "LIKE", "IN", "IS", "ESCAPE" -> true;
                    default -> false;
                }) {
            throw new IllegalArgumentException("Invalid AQ application property name: " + name);
        }
        return name;
    }

    static Object property(String name, Object value) {
        propertyName(name);
        Objects.requireNonNull(value, "property value");
        if (name.equals("JMSXGroupID") && !(value instanceof String)
                || name.equals("JMSXGroupSeq") && !(value instanceof Integer)) {
            throw new IllegalArgumentException("Invalid AQ grouping property type: " + name);
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported AQ application property: " + name);
    }

    static Map<String, Object> properties(Map<String, Object> properties) {
        Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((name, value) -> result.put(propertyName(name), property(name, value)));
        return Collections.unmodifiableMap(result);
    }

    static MessageHeaders headers(Map<String, Object> properties) {
        MessageHeaders.Builder result = MessageHeaders.builder();
        properties.forEach((name, value) -> result.add(name, header(value)));
        return result.build();
    }

    static byte[] serialize(Serializable entity) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(buffer)) {
                output.writeObject(entity);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new MessagingException("Cannot snapshot AQ object payload", e);
        }
    }

    static Object deserialize(byte[] bytes) {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return Objects.requireNonNull(input.readObject(), "Deserialized AQ object payload");
        } catch (IOException | ClassNotFoundException e) {
            throw new MessagingException("Cannot restore AQ object payload snapshot", e);
        }
    }

    private static MessageHeaderValue header(Object value) {
        if (value instanceof Boolean bool) {
            return MessageHeaderValue.BooleanValue.create(bool);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return MessageHeaderValue.IntegerValue.create(((Number) value).longValue());
        }
        if (value instanceof Float number) {
            return MessageHeaderValue.Float32Value.create(number);
        }
        if (value instanceof Double number) {
            return MessageHeaderValue.Float64Value.create(number);
        }
        return MessageHeaderValue.TextValue.create((String) value);
    }
}
