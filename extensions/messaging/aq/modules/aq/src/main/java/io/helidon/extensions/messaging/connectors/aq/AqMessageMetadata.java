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

import java.util.Optional;

import javax.jms.JMSException;

import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessageMetadata;
import io.helidon.messaging.MessagingException;

import oracle.jms.AdtMessage;

final class AqMessageMetadata {
    private static final Outgoing NO_OPTIONS = new Outgoing(0, Optional.empty(), Optional.empty());

    private AqMessageMetadata() {
    }

    static Outgoing outgoing(Message<?> message, AqPayloadType payloadType) {
        if (message instanceof DeadLetterMessage<?>) {
            return NO_OPTIONS;
        }
        MessageMetadata metadata = message.localMetadata();
        int delay = deliveryDelay(metadata);
        Optional<String> applicationId = textOption(metadata, AqMessage.APPLICATION_ID_METADATA);
        Optional<String> exceptionQueue = textOption(metadata, AqMessage.EXCEPTION_QUEUE_METADATA);
        if (applicationId.isPresent() && payloadType != AqPayloadType.JMS) {
            throw new MessagingException("AQ application ID is only supported for JMS payloads");
        }
        return new Outgoing(delay, applicationId, exceptionQueue);
    }

    static MessageMetadata snapshot(javax.jms.Message message) throws JMSException {
        MessageMetadata.Builder result = MessageMetadata.builder();
        if (!(message instanceof AdtMessage)) {
            textSnapshot(result, message, "JMSXAppID", AqMessage.RECEIVED_APPLICATION_ID_METADATA);
            textSnapshot(result, message, "JMSXUserID", AqMessage.RECEIVED_USER_ID_METADATA);
        }
        textSnapshot(result, message, "JMS_OracleExcpQ", AqMessage.RECEIVED_EXCEPTION_QUEUE_METADATA);
        textSnapshot(result, message, "JMS_OracleOriginalMessageID", AqMessage.RECEIVED_ORIGINAL_MESSAGE_ID_METADATA);
        integerSnapshot(result, message, "JMSXState", AqMessage.RECEIVED_STATE_METADATA);
        integerSnapshot(result, message, "JMSXRcvTimestamp", AqMessage.RECEIVED_TIMESTAMP_METADATA);
        integerSnapshot(result, message, "JMS_OracleDelay", AqMessage.RECEIVED_DELIVERY_DELAY_METADATA);
        return result.build();
    }

    private static int deliveryDelay(MessageMetadata metadata) {
        var value = metadata.value(AqMessage.DELIVERY_DELAY_METADATA);
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

    private static Optional<String> textOption(MessageMetadata metadata, String name) {
        var value = metadata.value(name);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (!(value.orElseThrow() instanceof MessageHeaderValue.TextValue text) || text.value().isBlank()) {
            throw new MessagingException("AQ metadata " + name + " must be non-blank text");
        }
        return Optional.of(text.value());
    }

    private static void textSnapshot(MessageMetadata.Builder target, javax.jms.Message message,
                                     String property, String name) throws JMSException {
        Object value = message.getObjectProperty(property);
        if (value == null) {
            return;
        }
        if (!(value instanceof String text)) {
            throw new MessagingException("AQ provider property " + property + " must be text");
        }
        target.set(name, text);
    }

    private static void integerSnapshot(MessageMetadata.Builder target, javax.jms.Message message,
                                        String property, String name) throws JMSException {
        Object value = message.getObjectProperty(property);
        if (value == null) {
            return;
        }
        // Oracle returns Long for JMS delay and Integer for ADT delay.
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new MessagingException("AQ provider property " + property + " must be an integer");
        }
        target.set(name, MessageHeaderValue.IntegerValue.create(((Number) value).longValue()));
    }

    record Outgoing(int deliveryDelay, Optional<String> applicationId, Optional<String> exceptionQueue) {
        void applyTo(javax.jms.Message message) throws JMSException {
            if (deliveryDelay > 0) {
                message.setIntProperty("JMS_OracleDelay", deliveryDelay);
            }
            if (applicationId.isPresent()) {
                message.setStringProperty("JMSXAppID", applicationId.orElseThrow());
            }
            if (exceptionQueue.isPresent()) {
                message.setStringProperty("JMS_OracleExcpQ", exceptionQueue.orElseThrow());
            }
        }
    }
}
