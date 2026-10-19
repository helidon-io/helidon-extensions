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
import javax.jms.Queue;
import javax.jms.Topic;

import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeaders;
import io.helidon.messaging.MessageMetadata;
import io.helidon.messaging.MessagingException;

import oracle.jms.AQjmsAgent;
import oracle.jms.AQjmsDestination;
import oracle.jms.AQjmsMessage;
import oracle.jms.AdtMessage;

/**
 * AQ message with detached payload and immutable native metadata.
 * Binary and map payloads are defensively copied. Use the Map interface for map entity types and
 * {@link java.nio.ByteBuffer} for buffer entity types; concrete buffer subclasses are not retained by snapshots.
 * An outgoing application object is retained until mapping; the application must keep custom values stable.
 * Serializable object payloads are copied only after the connector's explicit object-message opt-in.
 * No accessor exposes a native message, session, connection, LOB, or XML database resource.
 *
 * @param <T> payload type
 */
public final class AqMessage<T> implements Message<T> {
    /**
     * Local metadata entry controlling the delay before an outgoing message becomes available for dequeue.
     * The value must be a {@link io.helidon.messaging.MessageHeaderValue.IntegerValue} between zero and
     * {@link java.lang.Integer#MAX_VALUE}, in seconds. Missing or zero means no added delay.
     * The connector interprets this entry as Oracle's delivery-delay option, not as an application property.
     * It is ignored on {@link io.helidon.messaging.DeadLetterMessage} envelopes and is not populated on received messages.
     */
    public static final String DELIVERY_DELAY_METADATA = "helidon.messaging.aq.delivery-delay-seconds";

    private final Object entity;
    private final byte[] serializedEntity;
    private final boolean bodyAvailable;
    private final Map<String, Object> properties;
    private final MessageHeaders headers;
    private final MessageMetadata localMetadata;
    private final List<AqRecipient> recipients;
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

    private AqMessage(AqMessageConfig<T> config, boolean bodyAvailable) {
        Objects.requireNonNull(config);
        this.entity = AqValues.body(config.entity());
        this.serializedEntity = null;
        this.bodyAvailable = bodyAvailable;
        this.properties = AqValues.properties(config.properties());
        this.headers = AqValues.headers(properties);
        MessageMetadata.Builder metadata = MessageMetadata.builder();
        config.localMetadata().forEach(metadata::set);
        this.localMetadata = metadata.build();
        this.recipients = List.copyOf(config.recipients());
        this.messageId = Optional.empty();
        this.correlationId = config.correlationId();
        this.type = config.type();
        this.replyTo = config.replyTo();
        this.replyToAgent = config.replyToAgent();
        if (replyTo.isPresent() && replyToAgent.isPresent()) {
            throw new IllegalArgumentException("AQ reply-to destination and reply-to agent are mutually exclusive");
        }
        this.replyToType = config.replyToType();
        this.timestamp = OptionalLong.empty();
        this.expiration = OptionalLong.empty();
        this.priority = OptionalInt.empty();
        this.deliveryMode = OptionalInt.empty();
        this.redelivered = Optional.empty();
        this.attempts = OptionalInt.empty();
        this.sender = Optional.empty();
    }

    private AqMessage(Object entity, byte[] serializedEntity, Map<String, Object> properties,
                      javax.jms.Message message, boolean bodyAvailable) throws JMSException {
        this.entity = entity == null || serializedEntity != null ? null : AqValues.body(entity);
        this.serializedEntity = serializedEntity;
        this.bodyAvailable = bodyAvailable;
        this.properties = AqValues.properties(properties);
        this.headers = AqValues.headers(this.properties);
        this.localMetadata = MessageMetadata.empty();
        this.recipients = List.of();
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

    /**
     * Create an outgoing message builder.
     * When setting a map entity, declare its payload type as the Map interface or use {@link #mapBuilder(Map)}.
     * Buffer entity types must be declared as {@link java.nio.ByteBuffer}, rather than a concrete subclass.
     *
     * @param <T> entity type
     * @return builder
     */
    public static <T> AqMessageConfig.Builder<T> builder() {
        return AqMessageConfig.builder();
    }

    /**
     * Create an outgoing message builder with an entity.
     *
     * @param entity non-null entity
     * @param <T> entity type
     * @return builder
     */
    public static <T> AqMessageConfig.Builder<T> builder(T entity) {
        if (entity instanceof Map<?, ?>) {
            throw new IllegalArgumentException("AQ map payloads must use mapBuilder or the Map-typed create overload");
        }
        return AqMessage.<T>builder().entity(Objects.requireNonNull(entity));
    }

    /**
     * Create a map-message builder, normalizing concrete map classes to the Map contract.
     *
     * @param entity non-null map entity
     * @param <K> key type
     * @param <V> value type
     * @return builder
     */
    public static <K, V> AqMessageConfig.Builder<Map<K, V>> mapBuilder(Map<K, V> entity) {
        return AqMessage.<Map<K, V>>builder().entity(Objects.requireNonNull(entity));
    }

    /**
     * Create an outgoing payload-only message.
     *
     * @param entity non-null entity
     * @param <T> entity type
     * @return message
     */
    public static <T> AqMessage<T> create(T entity) {
        return builder(entity).build();
    }

    /**
     * Create a map message, normalizing concrete map classes to the Map contract.
     *
     * @param entity non-null map entity
     * @param <K> key type
     * @param <V> value type
     * @return map message
     */
    public static <K, V> AqMessage<Map<K, V>> create(Map<K, V> entity) {
        return mapBuilder(entity).build();
    }

    static <T> AqMessage<T> create(AqMessageConfig<T> config) {
        return new AqMessage<>(Objects.requireNonNull(config), true);
    }

    static AqMessage<?> incoming(Object entity, byte[] serialized, Map<String, Object> properties,
                                 javax.jms.Message message, boolean bodyAvailable) throws JMSException {
        return new AqMessage<>(entity, serialized, properties, message, bodyAvailable);
    }

    static AqMessage<?> unavailable() {
        return new AqMessage<>(AqMessageConfig.builder().entity(UnavailableBody.INSTANCE).buildPrototype(), false);
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

    /**
     * Whether payload mapping succeeded.
     *
     * @return availability of the entity
     */
    public boolean bodyAvailable() {
        return bodyAvailable;
    }

    /**
     * Native message identifier.
     *
     * @return identifier
     */
    public Optional<String> messageId() {
        return messageId;
    }

    /**
     * Correlation identifier.
     *
     * @return identifier
     */
    public Optional<String> correlationId() {
        return correlationId;
    }

    /**
     * JMS message type, absent for native ADT and XML payloads.
     *
     * @return type
     */
    public Optional<String> type() {
        return type;
    }

    /**
     * Detached reply-to destination name.
     * Oracle destination names retain their owning schema.
     *
     * @return destination
     */
    public Optional<String> replyTo() {
        return replyTo;
    }

    /**
     * Native AQ reply-to agent, used by messages from AQ and PL/SQL producers.
     *
     * @return detached reply-to agent
     */
    public Optional<AqRecipient> replyToAgent() {
        return replyToAgent;
    }

    /**
     * Reply-to destination kind.
     *
     * @return kind
     */
    public AqDestinationType replyToType() {
        return replyToType;
    }

    /**
     * Provider timestamp in milliseconds since the epoch.
     *
     * @return timestamp
     */
    public OptionalLong timestamp() {
        return timestamp;
    }

    /**
     * Provider expiration in milliseconds since the epoch.
     *
     * @return expiration
     */
    public OptionalLong expiration() {
        return expiration;
    }

    /**
     * Provider-assigned priority.
     *
     * @return priority
     */
    public OptionalInt priority() {
        return priority;
    }

    /**
     * Provider delivery mode.
     *
     * @return delivery mode
     */
    public OptionalInt deliveryMode() {
        return deliveryMode;
    }

    /**
     * Native redelivery flag.
     *
     * @return flag
     */
    public Optional<Boolean> redelivered() {
        return redelivered;
    }

    /**
     * AQ delivery attempt count.
     *
     * @return attempts
     */
    public OptionalInt attempts() {
        return attempts;
    }

    /**
     * Detached AQ sender.
     *
     * @return sender
     */
    public Optional<AqRecipient> sender() {
        return sender;
    }

    /**
     * Immutable application properties, retaining native numeric widths.
     *
     * @return properties
     */
    public Map<String, Object> properties() {
        return properties;
    }

    /**
     * Explicit outgoing recipients.
     *
     * @return recipients
     */
    public List<AqRecipient> recipients() {
        return recipients;
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

    private enum UnavailableBody {
        INSTANCE
    }
}
