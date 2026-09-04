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

package io.helidon.extensions.messaging.connectors.jms;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.helidon.messaging.HeaderValue;
import io.helidon.messaging.MessageHeaders;
import io.helidon.messaging.MessagingException;

import jakarta.jms.JMSException;

final class JmsMessageImpl<T> implements JmsMessage<T> {
    static final String JMSX_GROUP_ID = "JMSXGroupID";
    static final String JMSX_GROUP_SEQ = "JMSXGroupSeq";

    private final T entity;
    private final byte[] serializedEntity;
    private final String serializedEntityType;
    private final boolean bodyAvailable;
    private final Map<String, Object> properties;
    private final MessageHeaders headers;
    private final Optional<String> messageId;
    private final Optional<String> correlationId;
    private final Optional<String> type;
    private final OptionalLong timestamp;
    private final OptionalLong expiration;
    private final OptionalLong deliveryTime;
    private final OptionalInt priority;
    private final Optional<Boolean> redelivered;

    private JmsMessageImpl(T entity,
                           Map<String, Object> properties,
                           Optional<String> messageId,
                           Optional<String> correlationId,
                           Optional<String> type,
                           OptionalLong timestamp,
                           OptionalLong expiration,
                           OptionalLong deliveryTime,
                           OptionalInt priority,
                           Optional<Boolean> redelivered,
                           boolean bodyAvailable,
                           boolean snapshotSerializable) {
        T actualEntity = Objects.requireNonNull(entity, "entity");
        if (snapshotSerializable
                && !(actualEntity instanceof byte[])
                && !(actualEntity instanceof Map<?, ?>)
                && !(actualEntity instanceof java.util.List<?>)
                && !(actualEntity instanceof String)) {
            if (!(actualEntity instanceof Serializable serializable)) {
                throw new IllegalArgumentException("Unsupported JMS message body type: "
                                                           + actualEntity.getClass().getName());
            }
            this.serializedEntityType = actualEntity.getClass().getName();
            this.serializedEntity = serializeBody(serializable);
            validateSerializedBody(serializedEntity, serializedEntityType);
            this.entity = null;
        } else {
            this.entity = snapshotBody(actualEntity, snapshotSerializable);
            this.serializedEntity = null;
            this.serializedEntityType = null;
        }
        this.bodyAvailable = bodyAvailable;
        this.properties = snapshotProperties(properties);
        this.headers = portableHeaders(this.properties);
        this.messageId = messageId;
        this.correlationId = correlationId;
        this.type = type;
        this.timestamp = timestamp;
        this.expiration = expiration;
        this.deliveryTime = deliveryTime;
        this.priority = priority;
        this.redelivered = redelivered;
    }

    static <T> JmsMessage<T> outgoing(T entity,
                                      String correlationId,
                                      String type,
                                      Map<String, Object> properties) {
        return new JmsMessageImpl<>(entity,
                                    properties,
                                    Optional.empty(),
                                    Optional.ofNullable(correlationId),
                                    Optional.ofNullable(type),
                                    OptionalLong.empty(),
                                    OptionalLong.empty(),
                                    OptionalLong.empty(),
                                    OptionalInt.empty(),
                                    Optional.empty(),
                                    true,
                                    false);
    }

    static <T> JmsMessage<T> incoming(T entity,
                                      Map<String, Object> properties,
                                      jakarta.jms.Message message,
                                      boolean snapshotSerializable) throws JMSException {
        return new JmsMessageImpl<>(entity,
                                    properties,
                                    Optional.ofNullable(message.getJMSMessageID()),
                                    Optional.ofNullable(message.getJMSCorrelationID()),
                                    Optional.ofNullable(message.getJMSType()),
                                    OptionalLong.of(message.getJMSTimestamp()),
                                    OptionalLong.of(message.getJMSExpiration()),
                                    OptionalLong.of(message.getJMSDeliveryTime()),
                                    OptionalInt.of(message.getJMSPriority()),
                                    Optional.of(message.getJMSRedelivered()),
                                    true,
                                    snapshotSerializable && message instanceof jakarta.jms.ObjectMessage);
    }

    static JmsMessage<Object> metadataOnly(Map<String, Object> properties,
                                           jakarta.jms.Message message) throws JMSException {
        return new JmsMessageImpl<>(UnavailableBody.INSTANCE,
                                    properties,
                                    Optional.ofNullable(message.getJMSMessageID()),
                                    Optional.ofNullable(message.getJMSCorrelationID()),
                                    Optional.ofNullable(message.getJMSType()),
                                    OptionalLong.of(message.getJMSTimestamp()),
                                    OptionalLong.of(message.getJMSExpiration()),
                                    OptionalLong.of(message.getJMSDeliveryTime()),
                                    OptionalInt.of(message.getJMSPriority()),
                                    Optional.of(message.getJMSRedelivered()),
                                    false,
                                    false);
    }

    static JmsMessage<Object> rejected() {
        return new JmsMessageImpl<>(UnavailableBody.INSTANCE,
                                    Map.of(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    OptionalLong.empty(),
                                    OptionalLong.empty(),
                                    OptionalLong.empty(),
                                    OptionalInt.empty(),
                                    Optional.empty(),
                                    false,
                                    false);
    }

    static String requirePropertyName(String name) {
        String actual = Objects.requireNonNull(name);
        if (!isApplicationPropertyName(actual)) {
            throw new IllegalArgumentException("Invalid JMS application property name: " + actual);
        }
        return actual;
    }

    static boolean isApplicationPropertyName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (JMSX_GROUP_ID.equals(name) || JMSX_GROUP_SEQ.equals(name)) {
            return true;
        }
        if (name.startsWith("JMS")) {
            return false;
        }
        int first = name.codePointAt(0);
        if (!Character.isJavaIdentifierStart(first)) {
            return false;
        }
        for (int offset = Character.charCount(first); offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            if (!Character.isJavaIdentifierPart(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return switch (name.toUpperCase(Locale.ROOT)) {
        case "NULL", "TRUE", "FALSE", "NOT", "AND", "OR", "BETWEEN", "LIKE", "IN", "IS", "ESCAPE" -> false;
        default -> true;
        };
    }

    static Object snapshotProperty(String name, Object value) {
        String actualName = requirePropertyName(name);
        Object actual = Objects.requireNonNull(value);
        if (actual instanceof Boolean
                || actual instanceof Byte
                || actual instanceof Short
                || actual instanceof Integer
                || actual instanceof Long
                || actual instanceof Float
                || actual instanceof Double
                || actual instanceof String) {
            if (JMSX_GROUP_ID.equals(actualName) && !(actual instanceof String)) {
                throw new IllegalArgumentException(JMSX_GROUP_ID + " must be a String");
            }
            if (JMSX_GROUP_SEQ.equals(actualName) && !(actual instanceof Integer)) {
                throw new IllegalArgumentException(JMSX_GROUP_SEQ + " must be an Integer");
            }
            return actual;
        }
        throw new IllegalArgumentException("Unsupported JMS property type: " + actual.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T snapshotBody(T entity, boolean snapshotSerializable) {
        if (entity instanceof byte[] bytes) {
            return (T) bytes.clone();
        }
        if (entity instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                String name = requireMapName(key);
                result.put(name, snapshotMapOrStreamValue(value));
            });
            return (T) Collections.unmodifiableMap(result);
        }
        if (entity instanceof java.util.List<?> list) {
            return (T) list.stream().map(JmsMessageImpl::snapshotMapOrStreamValue).toList();
        }
        if (entity instanceof Serializable serializable && snapshotSerializable) {
            return deserializeBody(serializeBody(serializable), entity.getClass().getName());
        }
        if (entity instanceof Serializable) {
            return entity;
        }
        throw new IllegalArgumentException("Unsupported JMS message body type: " + entity.getClass().getName());
    }

    private static byte[] serializeBody(Serializable entity) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(entity);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot defensively copy JMS object-message body of type "
                                                       + entity.getClass().getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserializeBody(byte[] bytes, String entityType) {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (T) input.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot defensively copy JMS object-message body of type "
                                                       + entityType, e);
        }
    }

    private static void validateSerializedBody(byte[] bytes, String entityType) {
        if (deserializeBody(bytes, entityType) == null) {
            throw new IllegalArgumentException("Cannot defensively copy JMS object-message body of type " + entityType);
        }
    }

    static String requireMapName(Object name) {
        if (!(name instanceof String actual) || actual.isEmpty()) {
            throw new IllegalArgumentException("JMS map-message names must be non-empty strings");
        }
        return actual;
    }

    private static Object snapshotMapOrStreamValue(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value == null
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Character
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof String) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported JMS map or stream value type: " + value.getClass().getName());
    }

    private static Map<String, Object> snapshotProperties(Map<String, Object> properties) {
        Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((name, value) -> {
            String actualName = requirePropertyName(name);
            result.put(actualName, snapshotProperty(actualName, value));
        });
        return Collections.unmodifiableMap(result);
    }

    private static MessageHeaders portableHeaders(Map<String, Object> properties) {
        MessageHeaders.Builder result = MessageHeaders.builder();
        properties.forEach((name, value) -> result.add(name, portableHeaderValue(value)));
        return result.build();
    }

    private static HeaderValue portableHeaderValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return HeaderValue.booleanValue(booleanValue);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return HeaderValue.integer(((Number) value).longValue());
        }
        if (value instanceof Float floatValue) {
            return HeaderValue.floatingPoint(floatValue);
        }
        if (value instanceof Double doubleValue) {
            return HeaderValue.floatingPoint(doubleValue);
        }
        if (value instanceof String stringValue) {
            return HeaderValue.text(stringValue);
        }
        throw new IllegalArgumentException("Unsupported JMS property type: " + value.getClass().getName());
    }

    @Override
    public T entity() {
        requireBodyAvailable();
        return serializedEntity == null
                ? snapshotBody(entity, false)
                : deserializeBody(serializedEntity, serializedEntityType);
    }

    T entityForMapping(boolean allowObjectMessages) {
        requireBodyAvailable();
        if (serializedEntity == null) {
            return snapshotBody(entity, allowObjectMessages);
        }
        if (!allowObjectMessages) {
            throw new MessagingException("JMS ObjectMessage is disabled; set allow-object-messages=true only for "
                                                 + "trusted data");
        }
        return deserializeBody(serializedEntity, serializedEntityType);
    }

    @Override
    public boolean bodyAvailable() {
        return bodyAvailable;
    }

    private void requireBodyAvailable() {
        if (!bodyAvailable) {
            throw new MessagingException("JMS message body is unavailable");
        }
    }

    @Override
    public MessageHeaders headers() {
        return headers;
    }

    @Override
    public Optional<String> messageId() {
        return messageId;
    }

    @Override
    public Optional<String> correlationId() {
        return correlationId;
    }

    @Override
    public Optional<String> type() {
        return type;
    }

    @Override
    public OptionalLong timestamp() {
        return timestamp;
    }

    @Override
    public OptionalLong expiration() {
        return expiration;
    }

    @Override
    public OptionalLong deliveryTime() {
        return deliveryTime;
    }

    @Override
    public OptionalInt priority() {
        return priority;
    }

    @Override
    public Optional<Boolean> redelivered() {
        return redelivered;
    }

    @Override
    public Map<String, Object> jmsProperties() {
        return properties;
    }

    private enum UnavailableBody {
        INSTANCE
    }
}
