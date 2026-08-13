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

import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.MessageEOFException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

final class JmsMessageMapper {
    private JmsMessageMapper() {
    }

    static JmsMessage<?> fromJmsMessage(jakarta.jms.Message message, boolean allowObjectMessages) {
        try {
            Object body = readBody(message, allowObjectMessages);
            Map<String, Object> properties = readProperties(message);
            return JmsMessageImpl.incoming(body, properties, message);
        } catch (JMSException e) {
            throw new MessagingException("Cannot snapshot incoming JMS message", e);
        }
    }

    static jakarta.jms.Message toJmsMessage(Session session,
                                            Message<?> message,
                                            boolean allowObjectMessages) throws JMSException {
        jakarta.jms.Message result = createBodyMessage(session, message.entity(), allowObjectMessages);
        if (message instanceof JmsMessage<?> jmsMessage) {
            for (Map.Entry<String, Object> entry : jmsMessage.jmsProperties().entrySet()) {
                result.setObjectProperty(JmsMessageImpl.requirePropertyName(entry.getKey()), entry.getValue());
            }
            if (jmsMessage.correlationId().isPresent()) {
                result.setJMSCorrelationID(jmsMessage.correlationId().orElseThrow());
            }
            if (jmsMessage.type().isPresent()) {
                result.setJMSType(jmsMessage.type().orElseThrow());
            }
        } else {
            copyPortableHeaders(result, message);
        }
        return result;
    }

    static void copyPortableHeaders(jakarta.jms.Message target, Message<?> source) {
        source.headers().forEach((name, value) -> setStringProperty(target, name, value));
    }

    private static Object readBody(jakarta.jms.Message message,
                                   boolean allowObjectMessages) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            return textMessage.getText();
        }
        if (message instanceof BytesMessage bytesMessage) {
            long bodyLength = bytesMessage.getBodyLength();
            if (bodyLength > Integer.MAX_VALUE) {
                throw new MessagingException("JMS bytes message is too large to retain: " + bodyLength);
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
                String name = String.valueOf(names.nextElement());
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
            MapMessage result = session.createMapMessage();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new MessagingException("JMS map-message keys must be strings");
                }
                result.setObject(key, snapshotBodyValue(entry.getValue()));
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
                result.put(name, JmsMessageImpl.snapshotProperty(value));
            }
        }
        return Map.copyOf(result);
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

    private static void setStringProperty(jakarta.jms.Message message, String name, String value) {
        String actualName = JmsMessageImpl.requirePropertyName(name);
        try {
            message.setStringProperty(actualName, value);
        } catch (JMSException | RuntimeException e) {
            throw new MessagingException("Cannot set JMS property " + actualName, e);
        }
    }
}
