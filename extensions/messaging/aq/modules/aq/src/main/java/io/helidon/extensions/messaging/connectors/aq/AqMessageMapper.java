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

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.NClob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.TextMessage;

import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeader;
import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessagingException;

import oracle.jdbc.OracleArray;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleData;
import oracle.jdbc.OracleDataFactory;
import oracle.jms.AQjmsAgent;
import oracle.jms.AQjmsSession;
import oracle.jms.AdtMessage;
import oracle.sql.ORAData;
import oracle.xdb.XMLType;

final class AqMessageMapper {
    private AqMessageMapper() {
    }

    static AqMessage<?> fromNative(javax.jms.Message message, AqRuntimeConfig config) {
        try {
            Object body = readBody(message, config);
            if (config.payloadConverter().isPresent()) {
                body = Objects.requireNonNull(config.payloadConverter().orElseThrow().fromPayload(body),
                                              "AQ incoming payload converter result");
            }
            byte[] serialized = message instanceof ObjectMessage && config.payloadConverter().isEmpty()
                    ? AqValues.serialize((Serializable) body) : null;
            return AqMessage.incoming(body, serialized, readProperties(message), message, true);
        } catch (JMSException | SQLException e) {
            throw new AqMappingException("Cannot snapshot incoming AQ message", e);
        }
    }

    static AqMessage<?> metadataOnly(javax.jms.Message message) {
        try {
            return AqMessage.incoming(null, null, readProperties(message), message, false);
        } catch (JMSException | RuntimeException _) {
            return AqMessage.unavailable();
        }
    }

    static javax.jms.Message toNative(Session session, Message<?> message, AqRuntimeConfig config) {
        int deliveryDelay = deliveryDelay(message);
        Message<?> original = message instanceof DeadLetterMessage<?> deadLetter ? deadLetter.originalMessage() : message;
        AqMessage<?> aq = original instanceof AqMessage<?> candidate ? candidate : null;
        Object entity = aq == null ? original.entity()
                : aq.bodyAvailable() ? aq.entityForMapping(config.allowObjectMessages()) : null;
        if (entity != null && config.payloadConverter().isPresent()) {
            entity = Objects.requireNonNull(config.payloadConverter().orElseThrow().toPayload(entity),
                                            "AQ outgoing payload converter result");
        }
        javax.jms.Message result = null;
        try {
            result = aq != null && aq.objectBody() && config.payloadConverter().isEmpty()
                    ? createObject(session, entity, config) : createBody(session, entity, config);
            if (deliveryDelay > 0) {
                result.setIntProperty("JMS_OracleDelay", deliveryDelay);
            }
            if (aq != null) {
                if (aq.correlationId().isPresent()) {
                    result.setJMSCorrelationID(aq.correlationId().orElseThrow());
                }
                if (aq.type().isPresent()) {
                    requireJmsHeaders(result);
                    result.setJMSType(aq.type().orElseThrow());
                }
                if (aq.replyTo().isPresent()) {
                    requireJmsHeaders(result);
                    String destination = aq.replyTo().orElseThrow();
                    result.setJMSReplyTo(aq.replyToType() == AqDestinationType.TOPIC
                                                 ? session.createTopic(destination) : session.createQueue(destination));
                }
                if (aq.replyToAgent().isPresent()) {
                    requireJmsHeaders(result);
                    AqRecipient agent = aq.replyToAgent().orElseThrow();
                    result.setJMSReplyTo(new AQjmsAgent(agent.name().orElse(null),
                                                       agent.address().orElse(null),
                                                       agent.protocol()));
                }
            }
            Map<String, Object> properties = portableProperties(message);
            if (aq != null) {
                aq.properties().forEach((name, value) -> {
                    if (Objects.equals(aq.headers().last(name), message.headers().last(name))) {
                        properties.put(name, value);
                    }
                });
            }
            if (!properties.isEmpty()) {
                requireJmsHeaders(result);
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    setProperty(result, entry.getKey(), entry.getValue());
                }
            }
            return result;
        } catch (JMSException | SQLException | RuntimeException e) {
            if (result != null) {
                try {
                    releaseNative(result);
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            if (e instanceof MessagingException messagingException) {
                throw messagingException;
            }
            if (e instanceof JMSException || e instanceof SQLException) {
                throw new AqMappingException(e);
            }
            throw new MessagingException("Cannot map outgoing AQ message", e);
        }
    }

    static Object payloadFactory(AqRuntimeConfig config) {
        return switch (config.payloadType()) {
            case JMS -> null;
            case XML -> XMLType.getORADataFactory();
            case ADT -> (OracleDataFactory) (value, _) -> {
                try {
                    Object detached = detach(value, config.maxBodyBytes());
                    if (!(detached instanceof AqAdt adt) || adt.kind() != AqAdt.Kind.STRUCT) {
                        throw new SQLException("AQ ADT payload must be an Oracle SQL object");
                    }
                    return new AdtData(adt);
                } catch (SQLException | RuntimeException failure) {
                    // Let dequeue finish so the connector can apply the configured message failure policy.
                    return new FailedAdtData(failure);
                }
            };
        };
    }

    static void releaseNative(javax.jms.Message message) {
        if (!(message instanceof AdtMessage adt)) {
            return;
        }
        try {
            Object payload = adt.getAdtPayload();
            if (payload instanceof XMLType xml) {
                xml.close();
            } else if (payload instanceof AdtData data) {
                data.release();
            }
        } catch (JMSException | SQLException e) {
            throw new MessagingException("Cannot release AQ payload resources", e);
        }
    }

    private static int deliveryDelay(Message<?> message) {
        if (message instanceof DeadLetterMessage<?>) {
            return 0;
        }
        var value = message.localMetadata().value(AqMessage.DELIVERY_DELAY_METADATA);
        if (value.isEmpty()) {
            return 0;
        }
        if (!(value.orElseThrow() instanceof MessageHeaderValue.IntegerValue integer)) {
            throw new MessagingException("AQ delivery delay metadata must be an integer number of seconds");
        }
        try {
            int seconds = integer.value().intValueExact();
            if (seconds < 0) {
                throw new MessagingException("AQ delivery delay metadata must not be negative");
            }
            return seconds;
        } catch (ArithmeticException e) {
            throw new MessagingException("AQ delivery delay metadata must not exceed " + Integer.MAX_VALUE + " seconds", e);
        }
    }

    private static Object readBody(javax.jms.Message message, AqRuntimeConfig config) throws JMSException, SQLException {
        if (message instanceof AdtMessage adt) {
            Object payload = adt.getAdtPayload();
            if (payload instanceof XMLType xml) {
                try {
                    return AqXml.builder().text(xml.getStringVal()).build();
                } finally {
                    xml.close();
                }
            }
            if (payload instanceof AdtData data) {
                return data.payload;
            }
            if (payload instanceof FailedAdtData data) {
                throw new AqMappingException("Cannot snapshot incoming AQ ADT payload", data.failure);
            }
            throw new MessagingException("Unsupported AQ ADT factory result: "
                                                 + (payload == null ? "null" : payload.getClass().getName()));
        }
        if (message instanceof TextMessage text) {
            return Objects.requireNonNull(text.getText(), "AQ text body");
        }
        if (message instanceof BytesMessage bytes) {
            long length = bytes.getBodyLength();
            requireLength(length, config.maxBodyBytes());
            byte[] result = new byte[(int) length];
            byte[] chunk = new byte[Math.min(8192, result.length)];
            int offset = 0;
            bytes.reset();
            while (offset < result.length) {
                int requested = Math.min(chunk.length, result.length - offset);
                int read = bytes.readBytes(chunk, requested);
                if (read <= 0 || read > requested) {
                    throw new MessagingException("AQ bytes payload does not match its declared length");
                }
                System.arraycopy(chunk, 0, result, offset, read);
                offset += read;
            }
            return result;
        }
        if (message instanceof MapMessage map) {
            Map<String, Object> values = new LinkedHashMap<>();
            Enumeration<?> names = map.getMapNames();
            while (names.hasMoreElements()) {
                Object key = names.nextElement();
                if (!(key instanceof String name) || name.isEmpty()) {
                    throw new MessagingException("AQ map payload has an invalid key");
                }
                values.put(name, AqValues.mapValue(map.getObject(name)));
            }
            return Collections.unmodifiableMap(values);
        }
        if (message instanceof ObjectMessage object) {
            requireObjectMessages(config);
            return Objects.requireNonNull(object.getObject(), "AQ object body");
        }
        throw new MessagingException("Unsupported AQ message type: " + message.getClass().getName());
    }

    private static javax.jms.Message createBody(Session session, Object entity, AqRuntimeConfig config)
            throws JMSException, SQLException {
        if (entity == null) {
            if (config.payloadType() != AqPayloadType.JMS) {
                throw new MessagingException("An unavailable AQ body cannot be sent to a typed ADT or XML queue");
            }
            return session.createMessage();
        }
        if (config.payloadType() == AqPayloadType.ADT) {
            if (!(entity instanceof AqAdt adt) || adt.kind() != AqAdt.Kind.STRUCT) {
                throw new MessagingException("AQ ADT payloads require an AqAdt SQL object");
            }
            AdtData data = new AdtData(adt);
            try {
                return aqSession(session).createORAMessage(data);
            } catch (JMSException | RuntimeException | Error failure) {
                data.releaseAfterFailure(failure);
                throw failure;
            }
        }
        if (config.payloadType() == AqPayloadType.XML) {
            String text = entity instanceof AqXml xml ? xml.text() : entity instanceof String string ? string : null;
            if (text == null) {
                throw new MessagingException("AQ XML payloads require AqXml or String");
            }
            AQjmsSession aq = aqSession(session);
            XMLType xml = XMLType.createXML(aq.getDBConnection(), text);
            try {
                return aq.createORAMessage((ORAData) xml);
            } catch (JMSException | RuntimeException e) {
                xml.close();
                throw e;
            }
        }
        if (entity instanceof String text) {
            return session.createTextMessage(text);
        }
        if (entity instanceof byte[] bytes) {
            BytesMessage result = session.createBytesMessage();
            result.writeBytes(bytes.clone());
            return result;
        }
        if (entity instanceof ByteBuffer buffer) {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            BytesMessage result = session.createBytesMessage();
            result.writeBytes(bytes);
            return result;
        }
        if (entity instanceof Map<?, ?> map) {
            MapMessage result = session.createMapMessage();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name) || name.isEmpty()) {
                    throw new MessagingException("AQ map payload keys must be non-empty strings");
                }
                result.setObject(name, AqValues.mapValue(entry.getValue()));
            }
            return result;
        }
        if (entity instanceof Serializable serializable) {
            return createObject(session, serializable, config);
        }
        throw new MessagingException("Unsupported AQ payload: " + entity.getClass().getName());
    }

    private static javax.jms.Message createObject(Session session, Object entity, AqRuntimeConfig config) throws JMSException {
        requireObjectMessages(config);
        if (config.payloadType() != AqPayloadType.JMS || !(entity instanceof Serializable serializable)) {
            throw new MessagingException("AQ object payloads require a JMS-compatible destination and Serializable entity");
        }
        return session.createObjectMessage((Serializable) AqValues.deserialize(AqValues.serialize(serializable)));
    }

    private static Map<String, Object> readProperties(javax.jms.Message message) throws JMSException {
        Map<String, Object> result = new LinkedHashMap<>();
        Enumeration<?> names = message.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = String.valueOf(names.nextElement());
            if (name.startsWith("JMS") && !name.equals("JMSXGroupID") && !name.equals("JMSXGroupSeq")) {
                continue;
            }
            Object value = message.getObjectProperty(name);
            if (value != null) {
                result.put(name, AqValues.property(name, value));
            }
        }
        return result;
    }

    private static Map<String, Object> portableProperties(Message<?> message) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (MessageHeader header : message.headers()) {
            String name = AqValues.propertyName(header.name());
            if (result.containsKey(name)) {
                throw new MessagingException("AQ properties do not support duplicate header: " + name);
            }
            result.put(name, portableProperty(name, header.value()));
        }
        return result;
    }

    private static Object portableProperty(String name, MessageHeaderValue value) {
        Object result;
        if (value instanceof MessageHeaderValue.TextValue text) {
            if (name.equals("JMSXGroupSeq")) {
                try {
                    result = Integer.parseInt(text.value());
                } catch (NumberFormatException e) {
                    throw new MessagingException("Invalid AQ grouping property: " + name, e);
                }
            } else {
                result = text.value();
            }
        } else if (value instanceof MessageHeaderValue.BooleanValue bool) {
            result = bool.value();
        } else if (value instanceof MessageHeaderValue.IntegerValue number) {
            try {
                if (name.equals("JMSXGroupSeq")) {
                    result = number.value().intValueExact();
                } else {
                    result = number.value().longValueExact();
                }
            } catch (ArithmeticException e) {
                throw new MessagingException("AQ integer property exceeds its supported range: " + name, e);
            }
        } else if (value instanceof MessageHeaderValue.Float32Value number) {
            result = number.value();
        } else if (value instanceof MessageHeaderValue.Float64Value number) {
            result = number.value();
        } else {
            throw new MessagingException("Unsupported AQ portable header value: " + name);
        }
        return AqValues.property(name, result);
    }

    private static void setProperty(javax.jms.Message message, String name, Object value) throws JMSException {
        if (name.equals("JMSXGroupID")) {
            message.setStringProperty(name, (String) value);
        } else if (name.equals("JMSXGroupSeq")) {
            message.setIntProperty(name, (Integer) value);
        } else {
            message.setObjectProperty(name, value);
        }
    }

    private static Object detach(Object value, int maxBodyBytes) throws SQLException {
        if (value == null) {
            return AqAdt.NullValue.INSTANCE;
        }
        if (value instanceof Struct struct) {
            AqAdtConfig.Builder builder = AqAdt.builder().typeName(struct.getSQLTypeName());
            detachAttributes(builder, struct.getAttributes(), maxBodyBytes);
            return builder.build();
        }
        if (value instanceof OracleArray array) {
            try (SqlResource _ = array::free) {
                AqAdtConfig.Builder builder = AqAdt.builder().typeName(array.getSQLTypeName()).kind(AqAdt.Kind.ARRAY);
                detachAttributes(builder, array.getArray(), maxBodyBytes);
                return builder.build();
            }
        }
        if (value instanceof Blob blob) {
            try (SqlResource _ = blob::free) {
                long length = blob.length();
                requireLength(length, maxBodyBytes);
                return AqBlob.builder().bytes(blob.getBytes(1, (int) length)).build();
            }
        }
        if (value instanceof Clob clob) {
            try (SqlResource _ = clob::free) {
                long length = clob.length();
                requireLength(length, maxBodyBytes);
                return AqClob.builder().text(clob.getSubString(1, (int) length)).national(clob instanceof NClob).build();
            }
        }
        if (value instanceof SQLXML xml) {
            try (SqlResource _ = xml::free) {
                return AqXml.builder().text(xml.getString()).build();
            }
        }
        return AqValues.sqlValue(value);
    }

    private static void detachAttributes(AqAdtConfig.Builder builder, Object values, int maxBodyBytes) throws SQLException {
        Exception failure = null;
        for (int i = 0; i < java.lang.reflect.Array.getLength(values); i++) {
            try {
                builder.addAttribute(detach(java.lang.reflect.Array.get(values, i), maxBodyBytes));
            } catch (SQLException | RuntimeException e) {
                // Finish detaching siblings so their JDBC resources are released as well.
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure instanceof SQLException sql) {
            throw sql;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
    }

    private static Object attach(Object value, Connection connection, List<AutoCloseable> resources) throws SQLException {
        if (value instanceof AqAdt.NullValue) {
            return null;
        }
        if (value instanceof AqAdt adt) {
            Object[] attributes = new Object[adt.attributes().size()];
            List<Object> detached = adt.attributes();
            for (int i = 0; i < attributes.length; i++) {
                attributes[i] = attach(detached.get(i), connection, resources);
            }
            return switch (adt.kind()) {
                case STRUCT -> connection.createStruct(adt.typeName(), attributes);
                case ARRAY -> {
                    Array array = connection.unwrap(OracleConnection.class).createOracleArray(adt.typeName(), attributes);
                    resources.add(array::free);
                    yield array;
                }
            };
        }
        if (value instanceof AqXml xml) {
            XMLType nativeXml = XMLType.createXML(connection, xml.text());
            resources.add(nativeXml::close);
            return nativeXml;
        }
        if (value instanceof AqBlob blob) {
            Blob nativeBlob = connection.createBlob();
            resources.add(nativeBlob::free);
            nativeBlob.setBytes(1, blob.bytes());
            return nativeBlob;
        }
        if (value instanceof AqClob clob) {
            Clob nativeClob = clob.national() ? connection.createNClob() : connection.createClob();
            resources.add(nativeClob::free);
            nativeClob.setString(1, clob.text());
            return nativeClob;
        }
        return AqValues.sqlValue(value);
    }

    private static AQjmsSession aqSession(Session session) {
        if (session instanceof AQjmsSession aq) {
            return aq;
        }
        throw new MessagingException("Native AQ payloads require an Oracle AQ session");
    }

    private static void requireJmsHeaders(javax.jms.Message message) {
        if (message instanceof AdtMessage) {
            throw new MessagingException("Native AQ ADT/XML payloads do not support JMS type, reply-to, or user properties");
        }
    }

    private static void requireObjectMessages(AqRuntimeConfig config) {
        if (!config.allowObjectMessages()) {
            throw new MessagingException("AQ ObjectMessage is disabled; enable allow-object-messages only for trusted data");
        }
    }

    private static void requireLength(long length, int maxBodyBytes) {
        if (length < 0 || length > maxBodyBytes) {
            throw new MessagingException("AQ payload length " + length + " exceeds max-body-bytes " + maxBodyBytes);
        }
    }

    private interface SqlResource extends AutoCloseable {
        @Override
        void close() throws SQLException;
    }

    private static final class FailedAdtData implements OracleData {
        private final Exception failure;

        private FailedAdtData(Exception failure) {
            this.failure = failure;
        }

        @Override
        public Object toJDBCObject(Connection connection) throws SQLException {
            throw new SQLException("Cannot map an unavailable AQ ADT payload", failure);
        }
    }

    private static final class AdtData implements OracleData {
        private final AqAdt payload;
        private final List<AutoCloseable> resources = new ArrayList<>();

        private AdtData(AqAdt payload) {
            this.payload = payload;
        }

        @Override
        public Object toJDBCObject(Connection connection) throws SQLException {
            try {
                return attach(payload, connection, resources);
            } catch (SQLException | RuntimeException | Error failure) {
                releaseAfterFailure(failure);
                throw failure;
            }
        }

        private void releaseAfterFailure(Throwable failure) {
            try {
                release();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }

        private void release() throws SQLException {
            SQLException failure = null;
            for (AutoCloseable resource : resources.reversed()) {
                try {
                    resource.close();
                } catch (Exception e) {
                    if (failure == null) {
                        failure = new SQLException("Cannot release AQ ADT resources", e);
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            resources.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
