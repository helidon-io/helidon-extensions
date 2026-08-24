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

package io.helidon.extensions.messaging.connectors.pulsar;

import java.nio.ByteBuffer;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.Objects;

import io.helidon.messaging.ConnectorConfig;
import io.helidon.messaging.MessagingException;

import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericRecord;

/**
 * Built-in Pulsar payload schema.
 */
public enum PulsarSchemaType {
    /**
     * Broker-schema discovery, using {@link GenericRecord} for incoming messages and encoded {@code byte[]} for
     * outgoing messages.
     */
    AUTO,
    /** UTF-8 {@link String} payloads. */
    STRING,
    /** Binary {@code byte[]} payloads. */
    BYTES,
    /** {@link ByteBuffer} payloads. */
    BYTEBUFFER,
    /** {@link Boolean} payloads. */
    BOOLEAN,
    /** Signed 8-bit {@link Byte} payloads. */
    INT8,
    /** Signed 16-bit {@link Short} payloads. */
    INT16,
    /** Signed 32-bit {@link Integer} payloads. */
    INT32,
    /** Signed 64-bit {@link Long} payloads. */
    INT64,
    /** 32-bit {@link Float} payloads. */
    FLOAT,
    /** 64-bit {@link Double} payloads. */
    DOUBLE,
    /** {@link Date} payloads. */
    DATE,
    /** {@link Time} payloads. */
    TIME,
    /** {@link Timestamp} payloads. */
    TIMESTAMP,
    /** {@link Instant} payloads. */
    INSTANT,
    /** {@link LocalDate} payloads. */
    LOCAL_DATE,
    /** {@link LocalTime} payloads. */
    LOCAL_TIME,
    /** {@link LocalDateTime} payloads. */
    LOCAL_DATE_TIME;

    @SuppressWarnings("unchecked")
    Schema<Object> schema(ConnectorConfig.Direction direction) {
        Objects.requireNonNull(direction);
        Schema<?> schema = switch (this) {
        case AUTO -> direction == ConnectorConfig.Direction.INCOMING
                ? Schema.AUTO_CONSUME()
                : Schema.AUTO_PRODUCE_BYTES();
        case STRING -> Schema.STRING;
        case BYTES -> Schema.BYTES;
        case BYTEBUFFER -> Schema.BYTEBUFFER;
        case BOOLEAN -> Schema.BOOL;
        case INT8 -> Schema.INT8;
        case INT16 -> Schema.INT16;
        case INT32 -> Schema.INT32;
        case INT64 -> Schema.INT64;
        case FLOAT -> Schema.FLOAT;
        case DOUBLE -> Schema.DOUBLE;
        case DATE -> Schema.DATE;
        case TIME -> Schema.TIME;
        case TIMESTAMP -> Schema.TIMESTAMP;
        case INSTANT -> Schema.INSTANT;
        case LOCAL_DATE -> Schema.LOCAL_DATE;
        case LOCAL_TIME -> Schema.LOCAL_TIME;
        case LOCAL_DATE_TIME -> Schema.LOCAL_DATE_TIME;
        };
        return (Schema<Object>) schema;
    }

    Object snapshot(Object value, ConnectorConfig.Direction direction) {
        Objects.requireNonNull(direction);
        if (value == null) {
            return null;
        }
        Class<?> payloadType = payloadType(direction);
        if (!payloadType.isInstance(value)) {
            throw new MessagingException("Pulsar " + this + " schema"
                                                 + (this == AUTO ? " for " + direction + " channels" : "")
                                                 + " requires a " + payloadType.getTypeName() + " payload, not "
                                                 + value.getClass().getName());
        }
        return PulsarMessageImpl.snapshotEntity(value);
    }

    private Class<?> payloadType(ConnectorConfig.Direction direction) {
        return switch (this) {
        case AUTO -> direction == ConnectorConfig.Direction.INCOMING ? GenericRecord.class : byte[].class;
        case STRING -> String.class;
        case BYTES -> byte[].class;
        case BYTEBUFFER -> ByteBuffer.class;
        case BOOLEAN -> Boolean.class;
        case INT8 -> Byte.class;
        case INT16 -> Short.class;
        case INT32 -> Integer.class;
        case INT64 -> Long.class;
        case FLOAT -> Float.class;
        case DOUBLE -> Double.class;
        case DATE -> Date.class;
        case TIME -> Time.class;
        case TIMESTAMP -> Timestamp.class;
        case INSTANT -> Instant.class;
        case LOCAL_DATE -> LocalDate.class;
        case LOCAL_TIME -> LocalTime.class;
        case LOCAL_DATE_TIME -> LocalDateTime.class;
        };
    }
}
