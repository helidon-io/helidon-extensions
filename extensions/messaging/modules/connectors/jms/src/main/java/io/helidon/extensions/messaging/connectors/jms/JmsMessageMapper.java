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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeader;
import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessagingException;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.MessageEOFException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

final class JmsMessageMapper {
    private static final String LEGACY_FAILURE_TYPE_HEADER = "helidon_messaging_dead_letter_failure_type";
    private static final String LEGACY_FAILURE_MESSAGE_HEADER = "helidon_messaging_dead_letter_failure_message";
    private static final Set<String> DEAD_LETTER_HEADERS = Set.of(DeadLetterMessage.SOURCE_CHANNEL_HEADER,
                                                                  DeadLetterMessage.ATTEMPTS_HEADER,
                                                                  LEGACY_FAILURE_TYPE_HEADER,
                                                                  LEGACY_FAILURE_MESSAGE_HEADER);

    private JmsMessageMapper() {
    }

    static JmsMessage<?> fromJmsMessage(jakarta.jms.Message message,
                                        boolean allowObjectMessages,
                                        int maxBodyBytes) {
        try {
            Object body = readBody(message, allowObjectMessages, maxBodyBytes);
            Map<String, Object> properties = readProperties(message);
            return JmsMessageImpl.incoming(body, properties, message, allowObjectMessages);
        } catch (JMSException e) {
            throw new MessagingException("Cannot snapshot incoming JMS message", e);
        }
    }

    static JmsMessage<?> metadataOnly(jakarta.jms.Message message) {
        try {
            Map<String, Object> properties = readProperties(message);
            return JmsMessageImpl.metadataOnly(properties, message);
        } catch (JMSException e) {
            throw new MessagingException("Cannot snapshot rejected incoming JMS message metadata", e);
        }
    }

    static jakarta.jms.Message toJmsMessage(Session session,
                                            Message<?> message,
                                            boolean allowObjectMessages) throws JMSException {
        if (message instanceof DeadLetterMessage<?> deadLetterMessage
                && deadLetterMessage.originalMessage() instanceof JmsMessage<?> originalMessage) {
            return toJmsDeadLetterMessage(session, deadLetterMessage, originalMessage, allowObjectMessages);
        }
        if (message instanceof JmsMessage<?> jmsMessage && !jmsMessage.bodyAvailable()) {
            throw new MessagingException("JMS message body is unavailable");
        }
        Object entity = message instanceof JmsMessageImpl<?> implementation
                ? implementation.entityForMapping(allowObjectMessages)
                : message.entity();
        jakarta.jms.Message result = createBodyMessage(session, entity, allowObjectMessages);
        if (message instanceof JmsMessage<?> jmsMessage) {
            for (Map.Entry<String, Object> entry : jmsMessage.jmsProperties().entrySet()) {
                setTypedProperty(result, entry.getKey(), entry.getValue());
            }
            if (jmsMessage.correlationId().isPresent()) {
                result.setJMSCorrelationID(jmsMessage.correlationId().orElseThrow());
            }
            if (jmsMessage.type().isPresent()) {
                result.setJMSType(jmsMessage.type().orElseThrow());
            }
        } else if (message instanceof DeadLetterMessage<?> deadLetterMessage) {
            portableHeaders(deadLetterMessage)
                    .forEach((name, value) -> setPortableProperty(result, name, portableProperty(name, value)));
            setDeadLetterProperties(result, deadLetterMessage);
        } else {
            copyPortableHeaders(result, message);
        }
        return result;
    }

    private static jakarta.jms.Message toJmsDeadLetterMessage(Session session,
                                                              DeadLetterMessage<?> deadLetterMessage,
                                                              JmsMessage<?> originalMessage,
                                                              boolean allowObjectMessages) throws JMSException {
        Map<String, MessageHeaderValue> wrapperHeaders = portableHeaders(deadLetterMessage);
        Map<String, MessageHeaderValue> originalHeaders = portableHeaders(originalMessage);
        Map<String, Object> nativeProperties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : originalMessage.jmsProperties().entrySet()) {
            String name = entry.getKey();
            if (DEAD_LETTER_HEADERS.contains(name)) {
                continue;
            }
            if (Objects.equals(originalHeaders.get(name), wrapperHeaders.get(name))) {
                nativeProperties.put(name, entry.getValue());
                wrapperHeaders.remove(name);
            }
        }
        Map<String, Object> portableProperties = new LinkedHashMap<>();
        wrapperHeaders.forEach((name, value) -> portableProperties.put(name, portableProperty(name, value)));

        Object entity;
        if (!originalMessage.bodyAvailable()) {
            entity = null;
        } else if (originalMessage instanceof JmsMessageImpl<?> originalImpl) {
            entity = originalImpl.entityForMapping(allowObjectMessages);
        } else {
            entity = originalMessage.entity();
        }
        jakarta.jms.Message result = createBodyMessage(session, entity, allowObjectMessages);
        for (Map.Entry<String, Object> entry : nativeProperties.entrySet()) {
            setTypedProperty(result, entry.getKey(), entry.getValue());
        }
        portableProperties.forEach((name, value) -> setPortableProperty(result, name, value));
        if (originalMessage.correlationId().isPresent()) {
            result.setJMSCorrelationID(originalMessage.correlationId().orElseThrow());
        }
        if (originalMessage.type().isPresent()) {
            result.setJMSType(originalMessage.type().orElseThrow());
        }
        setDeadLetterProperties(result, deadLetterMessage);
        return result;
    }

    private static void setDeadLetterProperties(jakarta.jms.Message message, DeadLetterMessage<?> deadLetterMessage) {
        setPortableProperty(message, DeadLetterMessage.SOURCE_CHANNEL_HEADER, deadLetterMessage.sourceChannel());
        setPortableProperty(message,
                            DeadLetterMessage.ATTEMPTS_HEADER,
                            Integer.toString(deadLetterMessage.attempts()));
    }

    private static Map<String, MessageHeaderValue> portableHeaders(Message<?> message) {
        Map<String, MessageHeaderValue> result = new LinkedHashMap<>();
        for (MessageHeader header : message.headers()) {
            if (DEAD_LETTER_HEADERS.contains(header.name())) {
                continue;
            }
            if (result.putIfAbsent(header.name(), header.value()) != null) {
                throw new MessagingException("JMS application properties do not support duplicate header: "
                                                     + header.name());
            }
        }
        return result;
    }

    static void copyPortableHeaders(jakarta.jms.Message target, Message<?> source) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (MessageHeader header : source.headers()) {
            String name = JmsMessageImpl.requirePropertyName(header.name());
            if (properties.containsKey(name)) {
                throw new MessagingException("JMS application properties do not support duplicate header: " + name);
            }
            properties.put(name, portableProperty(name, header.value()));
        }
        properties.forEach((name, value) -> setPortableProperty(target, name, value));
    }

    private static Object readBody(jakarta.jms.Message message,
                                   boolean allowObjectMessages,
                                   int maxBodyBytes) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            return textMessage.getText();
        }
        if (message instanceof BytesMessage bytesMessage) {
            long bodyLength = bytesMessage.getBodyLength();
            if (bodyLength < 0) {
                throw new MessagingException("JMS bytes message declared a negative body length: " + bodyLength);
            }
            if (bodyLength > maxBodyBytes) {
                throw new MessagingException("JMS bytes message body length " + bodyLength
                                                     + " exceeds max-body-bytes " + maxBodyBytes);
            }
            byte[] body = new byte[(int) bodyLength];
            bytesMessage.reset();
            int offset = 0;
            while (offset < body.length) {
                byte[] chunk = new byte[Math.min(8192, body.length - offset)];
                int read = bytesMessage.readBytes(chunk, chunk.length);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new MessagingException("JMS bytes message made no progress while reading its body");
                }
                if (read > chunk.length) {
                    throw new MessagingException("JMS bytes message returned more data than requested");
                }
                System.arraycopy(chunk, 0, body, offset, read);
                offset += read;
            }
            if (offset != body.length) {
                throw new MessagingException("JMS bytes message ended before its declared body length");
            }
            return body;
        }
        if (message instanceof MapMessage mapMessage) {
            Map<String, Object> body = new LinkedHashMap<>();
            Enumeration<?> names = mapMessage.getMapNames();
            while (names.hasMoreElements()) {
                String name = requireMapName(names.nextElement());
                body.put(name, snapshotBodyValue(mapMessage.getObject(name)));
            }
            return Collections.unmodifiableMap(body);
        }
        if (message instanceof StreamMessage streamMessage) {
            List<Object> body = new ArrayList<>();
            streamMessage.reset();
            while (true) {
                try {
                    body.add(snapshotBodyValue(streamMessage.readObject()));
                } catch (MessageEOFException e) {
                    return Collections.unmodifiableList(body);
                }
            }
        }
        if (message instanceof ObjectMessage objectMessage) {
            if (!allowObjectMessages) {
                throw new MessagingException("JMS ObjectMessage is disabled; set allow-object-messages=true only for "
                                                     + "trusted data");
            }
            return objectMessage.getObject();
        }
        Object body = message.getBody(Object.class);
        if (body == null) {
            return null;
        }
        throw new MessagingException("Unsupported incoming JMS message type: " + message.getClass().getName());
    }

    private static jakarta.jms.Message createBodyMessage(Session session,
                                                         Object entity,
                                                         boolean allowObjectMessages) throws JMSException {
        if (entity == null) {
            return session.createMessage();
        }
        if (entity instanceof String text) {
            return session.createTextMessage(text);
        }
        if (entity instanceof byte[] bytes) {
            BytesMessage result = session.createBytesMessage();
            result.writeBytes(bytes.clone());
            return result;
        }
        if (entity instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = requireMapName(entry.getKey());
                values.put(key, snapshotBodyValue(entry.getValue()));
            }
            MapMessage result = session.createMapMessage();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                result.setObject(entry.getKey(), entry.getValue());
            }
            return result;
        }
        if (entity instanceof List<?> list) {
            StreamMessage result = session.createStreamMessage();
            for (Object value : list) {
                result.writeObject(snapshotBodyValue(value));
            }
            return result;
        }
        if (entity instanceof Serializable serializable) {
            if (!allowObjectMessages) {
                throw new MessagingException("JMS ObjectMessage is disabled; set allow-object-messages=true only for "
                                                     + "trusted data");
            }
            return session.createObjectMessage(serializable);
        }
        throw new MessagingException("Unsupported outgoing JMS payload type: "
                                             + (entity == null ? "null" : entity.getClass().getName()));
    }

    private static Map<String, Object> readProperties(jakarta.jms.Message message) throws JMSException {
        Map<String, Object> result = new LinkedHashMap<>();
        Enumeration<?> names = message.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = String.valueOf(names.nextElement());
            if (!JmsMessageImpl.isApplicationPropertyName(name)) {
                continue;
            }
            Object value = message.getObjectProperty(name);
            if (value != null) {
                result.put(name, JmsMessageImpl.snapshotProperty(name, value));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object snapshotBodyValue(Object value) {
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
        throw new MessagingException("Unsupported JMS map or stream value type: " + value.getClass().getName());
    }

    private static String requireMapName(Object name) {
        try {
            return JmsMessageImpl.requireMapName(name);
        } catch (IllegalArgumentException e) {
            throw new MessagingException(e.getMessage(), e);
        }
    }

    private static void setTypedProperty(jakarta.jms.Message message, String name, Object value) throws JMSException {
        String actualName = JmsMessageImpl.requirePropertyName(name);
        Object actualValue = JmsMessageImpl.snapshotProperty(actualName, value);
        if (JmsMessageImpl.JMSX_GROUP_ID.equals(actualName)) {
            message.setStringProperty(actualName, (String) actualValue);
        } else if (JmsMessageImpl.JMSX_GROUP_SEQ.equals(actualName)) {
            message.setIntProperty(actualName, (Integer) actualValue);
        } else {
            message.setObjectProperty(actualName, actualValue);
        }
    }

    private static Object portableProperty(String name, MessageHeaderValue value) {
        Object result;
        if (value instanceof MessageHeaderValue.TextValue textValue) {
            if (JmsMessageImpl.JMSX_GROUP_SEQ.equals(name)) {
                try {
                    result = Integer.parseInt(textValue.value());
                } catch (NumberFormatException e) {
                    throw new MessagingException("Cannot map messaging header " + name + " to a JMS property", e);
                }
            } else {
                result = textValue.value();
            }
        } else if (value instanceof MessageHeaderValue.BooleanValue booleanValue) {
            result = booleanValue.value();
        } else if (value instanceof MessageHeaderValue.IntegerValue integerValue) {
            try {
                if (JmsMessageImpl.JMSX_GROUP_SEQ.equals(name)) {
                    result = integerValue.value().intValueExact();
                } else {
                    result = integerValue.value().longValueExact();
                }
            } catch (ArithmeticException e) {
                throw new MessagingException("Cannot map messaging header " + name + " to a JMS property", e);
            }
        } else if (value instanceof MessageHeaderValue.Float32Value floatValue) {
            result = floatValue.value();
        } else if (value instanceof MessageHeaderValue.Float64Value doubleValue) {
            result = doubleValue.value();
        } else {
            throw new MessagingException("Unsupported JMS header value for " + name + ": "
                                                 + value.getClass().getSimpleName());
        }
        try {
            return JmsMessageImpl.snapshotProperty(name, result);
        } catch (IllegalArgumentException e) {
            throw new MessagingException("Cannot map messaging header " + name + " to a JMS property", e);
        }
    }

    private static void setPortableProperty(jakarta.jms.Message message, String name, Object value) {
        try {
            if (value instanceof String stringValue) {
                message.setStringProperty(name, stringValue);
            } else if (value instanceof Boolean booleanValue) {
                message.setBooleanProperty(name, booleanValue);
            } else if (value instanceof Integer integerValue) {
                message.setIntProperty(name, integerValue);
            } else if (value instanceof Long longValue) {
                message.setLongProperty(name, longValue);
            } else if (value instanceof Float floatValue) {
                message.setFloatProperty(name, floatValue);
            } else if (value instanceof Double doubleValue) {
                message.setDoubleProperty(name, doubleValue);
            } else {
                throw new MessagingException("Unsupported JMS property value for " + name + ": "
                                                     + value.getClass().getName());
            }
        } catch (JMSException | RuntimeException e) {
            if (e instanceof MessagingException messagingException) {
                throw messagingException;
            }
            throw new MessagingException("Cannot set JMS property " + name, e);
        }
    }
}
