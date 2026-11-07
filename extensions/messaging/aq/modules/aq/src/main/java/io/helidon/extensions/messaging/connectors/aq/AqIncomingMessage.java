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

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.Topic;

import io.helidon.messaging.MessageHeaders;
import io.helidon.messaging.MessageMetadata;
import io.helidon.messaging.MessagingException;

import oracle.jms.AQjmsAgent;
import oracle.jms.AQjmsDestination;
import oracle.jms.AQjmsMessage;
import oracle.jms.AdtMessage;

/**
 * Detached received message, including metadata retained after a payload mapping failure.
 */
final class AqIncomingMessage<T> implements AqMessage<T> {
    private final Object entity;
    private final byte[] serializedEntity;
    private final boolean bodyAvailable;
    private final Map<String, Object> properties;
    private final MessageHeaders headers;
    private final MessageMetadata localMetadata;
    private final Optional<String> messageId;
    private final Optional<String> correlationId;
    private final Optional<String> type;
    private final Optional<String> replyTo;
    private final Optional<AqRecipient> replyToAgent;
    private final AqDestinationType replyToType;
    private final OptionalLong timestamp;
    private final OptionalLong expiration;
    private final OptionalInt priority;
    private final OptionalInt deliveryMode;
    private final Optional<Boolean> redelivered;
    private final OptionalInt attempts;
    private final Optional<AqRecipient> sender;

    private AqIncomingMessage() {
        entity = null;
        serializedEntity = null;
        bodyAvailable = false;
        properties = Map.of();
        headers = MessageHeaders.empty();
        localMetadata = MessageMetadata.empty();
        messageId = Optional.empty();
        correlationId = Optional.empty();
        type = Optional.empty();
        replyTo = Optional.empty();
        replyToAgent = Optional.empty();
        replyToType = AqDestinationType.QUEUE;
        timestamp = OptionalLong.empty();
        expiration = OptionalLong.empty();
        priority = OptionalInt.empty();
        deliveryMode = OptionalInt.empty();
        redelivered = Optional.empty();
        attempts = OptionalInt.empty();
        sender = Optional.empty();
    }

    private AqIncomingMessage(Object entity, byte[] serializedEntity, Map<String, Object> properties,
                              Message message, boolean bodyAvailable) throws JMSException {
        Objects.requireNonNull(message);
        this.entity = entity == null || serializedEntity != null ? null : AqValues.body(entity);
        this.serializedEntity = serializedEntity == null ? null : serializedEntity.clone();
        this.bodyAvailable = bodyAvailable;
        this.properties = AqValues.properties(properties);
        this.headers = AqValues.headers(this.properties);
        this.localMetadata = AqMessageMetadata.snapshot(message);
        this.messageId = Optional.ofNullable(message.getJMSMessageID());
        this.correlationId = Optional.ofNullable(message.getJMSCorrelationID());
        this.type = message instanceof AdtMessage ? Optional.empty() : Optional.ofNullable(message.getJMSType());
        Destination destination = message instanceof AdtMessage ? null : message.getJMSReplyTo();
        if (destination instanceof AQjmsDestination aq) {
            // Oracle destinations implement both Queue and Topic; only the matching name accessor returns a value.
            this.replyTo = Optional.of(aq.getCompleteName());
            this.replyToType = aq.getQueueName() == null ? AqDestinationType.TOPIC : AqDestinationType.QUEUE;
        } else {
            this.replyTo = destinationName(destination);
            this.replyToType = destination instanceof Topic ? AqDestinationType.TOPIC : AqDestinationType.QUEUE;
        }
        this.replyToAgent = destination instanceof AQjmsAgent agent ? sender(agent) : Optional.empty();
        this.timestamp = OptionalLong.of(message.getJMSTimestamp());
        this.expiration = OptionalLong.of(message.getJMSExpiration());
        this.priority = OptionalInt.of(message.getJMSPriority());
        this.deliveryMode = OptionalInt.of(message.getJMSDeliveryMode());
        this.redelivered = Optional.of(message.getJMSRedelivered());
        if (message instanceof AQjmsMessage aq) {
            this.attempts = OptionalInt.of(aq.getAttempts());
            this.sender = sender(aq.getSenderID());
        } else {
            this.attempts = OptionalInt.empty();
            this.sender = Optional.empty();
        }
    }

    static AqIncomingMessage<?> incoming(Object entity, byte[] serialized, Map<String, Object> properties,
                                         Message message, boolean bodyAvailable) throws JMSException {
        return new AqIncomingMessage<>(entity, serialized, properties, message, bodyAvailable);
    }

    static AqIncomingMessage<?> unavailable() {
        return new AqIncomingMessage<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public T entity() {
        if (!bodyAvailable) {
            throw new MessagingException("AQ message body is unavailable");
        }
        return (T) (serializedEntity == null ? AqValues.body(entity) : AqValues.deserialize(serializedEntity));
    }

    @Override
    public MessageHeaders headers() {
        return headers;
    }

    @Override
    public MessageMetadata localMetadata() {
        return localMetadata;
    }

    @Override
    public boolean bodyAvailable() {
        return bodyAvailable;
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
    public Optional<String> replyTo() {
        return replyTo;
    }

    @Override
    public Optional<AqRecipient> replyToAgent() {
        return replyToAgent;
    }

    @Override
    public AqDestinationType replyToType() {
        return replyToType;
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
    public OptionalInt priority() {
        return priority;
    }

    @Override
    public OptionalInt deliveryMode() {
        return deliveryMode;
    }

    @Override
    public Optional<Boolean> redelivered() {
        return redelivered;
    }

    @Override
    public OptionalInt attempts() {
        return attempts;
    }

    @Override
    public Optional<AqRecipient> sender() {
        return sender;
    }

    @Override
    public Map<String, Object> properties() {
        return properties;
    }

    @Override
    public List<AqRecipient> recipients() {
        return List.of();
    }

    Object entityForMapping(boolean allowObjectMessages) {
        if (serializedEntity != null && !allowObjectMessages) {
            throw new MessagingException("AQ ObjectMessage is disabled; enable allow-object-messages only for trusted data");
        }
        return entity();
    }

    boolean objectBody() {
        return serializedEntity != null;
    }

    private static Optional<String> destinationName(Destination destination) throws JMSException {
        if (destination instanceof Queue queue) {
            return Optional.of(queue.getQueueName());
        }
        if (destination instanceof Topic topic) {
            return Optional.of(topic.getTopicName());
        }
        if (destination == null || destination instanceof AQjmsAgent) {
            return Optional.empty();
        }
        throw new MessagingException("Unsupported AQ reply-to destination");
    }

    private static Optional<AqRecipient> sender(AQjmsAgent sender) {
        if (sender == null) {
            return Optional.empty();
        }
        try {
            if (sender.getName() == null && sender.getAddress() == null) {
                return Optional.empty();
            }
            AqRecipient.Builder builder = AqRecipient.builder().protocol(sender.getProtocol());
            if (sender.getName() != null) {
                builder.name(sender.getName());
            }
            if (sender.getAddress() != null) {
                builder.address(sender.getAddress());
            }
            return Optional.of(builder.build());
        } catch (SQLException e) {
            throw new MessagingException("Cannot snapshot AQ sender", e);
        }
    }
}
