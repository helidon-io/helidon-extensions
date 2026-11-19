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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.helidon.common.Api;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeaders;
import io.helidon.messaging.MessageMetadata;

/**
 * AQ message with detached payload and immutable native metadata.
 * Application-created messages are {@link AqOutgoingMessage} instances.
 * Received snapshots expose the same envelope contract.
 * Binary payloads, map structure, and byte-array map values are defensively copied.
 * Map keys and other map values are retained; applications and converters must keep these values stable.
 * Use the Map interface for map entity types and
 * {@link java.nio.ByteBuffer} for buffer entity types; concrete buffer subclasses are not retained by snapshots.
 * Other application objects are retained; applications and converters must keep custom values stable.
 * Serializable object payloads are copied only after the connector's explicit object-message opt-in.
 * No accessor exposes a native message, session, connection, LOB, or XML database resource.
 *
 * @param <T> payload type
 */
@Api.Incubating
public sealed interface AqMessage<T> extends Message<T> permits AqOutgoingMessage, AqIncomingMessage {
    /**
     * Local metadata entry controlling the delay before an outgoing message becomes available for dequeue.
     * The value must be a {@link io.helidon.messaging.MessageHeaderValue.IntegerValue} between zero and
     * {@link java.lang.Integer#MAX_VALUE}, in seconds. Missing or zero means no added delay.
     * The connector interprets this entry as Oracle's delivery-delay option, not as an application property.
     * It is ignored on {@link io.helidon.messaging.DeadLetterMessage} envelopes and is not populated on received messages.
     */
    String DELIVERY_DELAY_METADATA = "helidon.messaging.aq.delivery-delay-seconds";

    /**
     * Outgoing application identifier, represented by non-blank
     * {@link io.helidon.messaging.MessageHeaderValue.TextValue} metadata.
     * Sets Oracle's JMSXAppID on JMS payloads; ADT and XML payloads do not support it.
     * Ignored on {@link io.helidon.messaging.DeadLetterMessage} envelopes.
     */
    String APPLICATION_ID_METADATA = "helidon.messaging.aq.application-id";

    /**
     * Outgoing Oracle exception queue name, represented by non-blank
     * {@link io.helidon.messaging.MessageHeaderValue.TextValue} metadata.
     * Sets JMS_OracleExcpQ for JMS, ADT and XML payloads. The named queue must be an existing Oracle exception queue;
     * use a schema-qualified name to select its owner. Ignored on {@link io.helidon.messaging.DeadLetterMessage} envelopes.
     */
    String EXCEPTION_QUEUE_METADATA = "helidon.messaging.aq.exception-queue";

    /**
     * Received JMSXAppID as text, when available on a JMS payload. Observation only; not applied to outgoing messages.
     */
    String RECEIVED_APPLICATION_ID_METADATA = "helidon.messaging.aq.received.application-id";

    /**
     * Received JMSXUserID as text, when available on a JMS payload. Observation only; not applied to outgoing messages.
     */
    String RECEIVED_USER_ID_METADATA = "helidon.messaging.aq.received.user-id";

    /**
     * Received Oracle exception queue name as text, when available. Observation only; not applied to outgoing messages.
     */
    String RECEIVED_EXCEPTION_QUEUE_METADATA = "helidon.messaging.aq.received.exception-queue";

    /**
     * Received JMSXState as integer metadata: 0 READY, 1 WAITING, 2 PROCESSED, or 3 EXPIRED.
     * Observation only; not applied to outgoing messages.
     */
    String RECEIVED_STATE_METADATA = "helidon.messaging.aq.received.state";

    /**
     * Receive time recorded by the Oracle Java client, as integer milliseconds since the epoch.
     * Snapshots JMSXRcvTimestamp. Observation only; not applied to outgoing messages.
     */
    String RECEIVED_TIMESTAMP_METADATA = "helidon.messaging.aq.received.timestamp-millis";

    /**
     * Original message identifier from Oracle propagation, as text when the provider supplies it.
     * Snapshots JMS_OracleOriginalMessageID; absence is not replaced with the current message ID.
     * Observation only; not applied to outgoing messages.
     */
    String RECEIVED_ORIGINAL_MESSAGE_ID_METADATA = "helidon.messaging.aq.received.original-message-id";

    /**
     * Original Oracle delivery delay as integer seconds, when available. This is not the remaining wait time.
     * Observation only; not applied to outgoing messages.
     */
    String RECEIVED_DELIVERY_DELAY_METADATA = "helidon.messaging.aq.received.delivery-delay-seconds";

    /**
     * Create an outgoing message builder.
     * When setting a map entity, declare its payload type as the Map interface or use {@link #mapBuilder(Map)}.
     * Buffer entity types must be declared as {@link java.nio.ByteBuffer}, rather than a concrete subclass.
     *
     * @param <T> entity type
     * @return builder
     */
    static <T> AqMessageConfig.Builder<T> builder() {
        return AqOutgoingMessage.builder();
    }

    /**
     * Create an outgoing message builder with an entity.
     * Map entities require {@link #mapBuilder(Map)}. For a payload-only map message, use {@link #create(Map)}.
     *
     * @param entity non-null entity
     * @param <T> entity type
     * @return builder
     * @throws IllegalArgumentException if the entity is a map
     * @throws NullPointerException if the entity is {@code null}
     */
    static <T> AqMessageConfig.Builder<T> builder(T entity) {
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
     * @throws NullPointerException if the entity is {@code null}
     */
    static <K, V> AqMessageConfig.Builder<Map<K, V>> mapBuilder(Map<K, V> entity) {
        return AqMessage.<Map<K, V>>builder().entity(Objects.requireNonNull(entity));
    }

    /**
     * Create an outgoing payload-only message.
     * This overload rejects map entities. Use the {@link #create(Map) Map overload} or {@link #mapBuilder(Map)}
     * so the envelope's entity type is the Map interface.
     *
     * @param entity non-null entity
     * @param <T> entity type
     * @return message
     * @throws IllegalArgumentException if the entity is a map
     * @throws NullPointerException if the entity is {@code null}
     */
    static <T> AqMessage<T> create(T entity) {
        return builder(entity).build();
    }

    /**
     * Create a map message, normalizing concrete map classes to the Map contract.
     *
     * @param entity non-null map entity
     * @param <K> key type
     * @param <V> value type
     * @return map message
     * @throws NullPointerException if the entity is {@code null}
     */
    static <K, V> AqMessage<Map<K, V>> create(Map<K, V> entity) {
        return mapBuilder(entity).build();
    }

    @Override
    T entity();

    @Override
    MessageHeaders headers();

    @Override
    MessageMetadata localMetadata();

    /**
     * Whether payload mapping succeeded.
     *
     * @return availability of the entity
     */
    boolean bodyAvailable();

    /**
     * Native message identifier.
     *
     * @return identifier
     */
    Optional<String> messageId();

    /**
     * Correlation identifier.
     *
     * @return identifier
     */
    Optional<String> correlationId();

    /**
     * JMS message type, absent for native ADT and XML payloads.
     *
     * @return type
     */
    Optional<String> type();

    /**
     * Detached reply-to destination name.
     * Oracle destination names retain their owning schema.
     *
     * @return destination
     */
    Optional<String> replyTo();

    /**
     * Native AQ reply-to agent, used by messages from AQ and PL/SQL producers.
     *
     * @return detached reply-to agent
     */
    Optional<AqRecipient> replyToAgent();

    /**
     * Reply-to destination kind.
     *
     * @return kind
     */
    AqDestinationType replyToType();

    /**
     * Provider timestamp in milliseconds since the epoch.
     *
     * @return timestamp
     */
    OptionalLong timestamp();

    /**
     * Received provider expiration in milliseconds since the epoch.
     * Outgoing message lifetime is selected by the target channel's {@code time-to-live} setting.
     *
     * @return expiration
     */
    OptionalLong expiration();

    /**
     * Received provider-assigned priority.
     * Outgoing sends use the target channel's {@code priority} setting.
     *
     * @return priority
     */
    OptionalInt priority();

    /**
     * Received provider delivery mode.
     * Outgoing sends use the target channel's {@code delivery-mode} setting.
     *
     * @return delivery mode
     */
    OptionalInt deliveryMode();

    /**
     * Native redelivery flag.
     *
     * @return flag
     */
    Optional<Boolean> redelivered();

    /**
     * AQ delivery attempt count.
     *
     * @return attempts
     */
    OptionalInt attempts();

    /**
     * Detached AQ sender.
     *
     * @return sender
     */
    Optional<AqRecipient> sender();

    /**
     * Immutable application properties, retaining native numeric widths.
     *
     * @return properties
     */
    Map<String, Object> properties();

    /**
     * Explicit outgoing recipients.
     *
     * @return recipients
     */
    List<AqRecipient> recipients();
}
