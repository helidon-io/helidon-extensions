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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.helidon.messaging.Message;

/**
 * JMS-specific message envelope with immutable metadata and property snapshots.
 * <p>
 * Incoming messages snapshot the native message body, selected JMS metadata, and application properties before the
 * connector hands them to the runtime. The metadata view includes message and String correlation identifiers, type,
 * timestamp, expiration, delivery time, priority, and redelivery state. This interface never exposes a live JMS
 * message, session, or connection.
 * <p>
 * Application-created messages snapshot byte-array, map, and stream bodies when built. An application-created
 * {@link java.io.Serializable} object body is retained by reference until an object-message-enabled connector maps it,
 * so the disabled security gate can reject it without invoking serialization callbacks. The connector defensively
 * snapshots an enabled object body before giving it to the JMS provider; the application must not mutate that body
 * between building and sending the message.
 * Map and list factory overloads normalize their generic payload to the {@link Map} and {@link List} interfaces because
 * immutable snapshots do not preserve a caller's concrete collection implementation. A map or list hidden behind an
 * unconstrained generic or {@link Object} type is rejected at construction; expose it as {@code Map} or {@code List}
 * so the corresponding normalized overload can be selected.
 * <p>
 * A metadata-only envelope created after body mapping fails reports {@link #bodyAvailable()} as {@code false}.
 * Its native metadata and properties remain readable, but {@link #entity()} throws a messaging exception instead of
 * exposing an internal placeholder. A local dead-letter consumer can inspect {@link #bodyAvailable()} on the original
 * JMS message before accessing its body.
 * <p>
 * Portable {@link #headers()} retain the JMS Boolean, integer, floating-point, and String value kinds. JMS integer
 * width is available through {@link #jmsProperties()}, because the portable integer representation intentionally does
 * not retain transport-specific width.
 *
 * @param <T> payload type
 */
public interface JmsMessage<T> extends Message<T> {
    /**
     * Create an outgoing JMS message builder.
     *
     * @param entity non-null payload
     * @param <T> payload type
     * @return builder
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <T> Builder<T> builder(T entity) {
        T actualEntity = Objects.requireNonNull(entity, "entity");
        if (actualEntity instanceof Map<?, ?> || actualEntity instanceof List<?>) {
            throw new IllegalArgumentException("JMS map and list bodies must use the Map- or List-typed factory overload");
        }
        return new Builder<>(actualEntity);
    }

    /**
     * Create an outgoing JMS map-message builder while normalizing the payload type to the {@link Map} contract.
     *
     * @param entity non-null map payload
     * @param <K> map key type
     * @param <V> map value type
     * @return builder
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <K, V> Builder<Map<K, V>> builder(Map<K, V> entity) {
        return new Builder<>(entity);
    }

    /**
     * Create an outgoing JMS stream-message builder while normalizing the payload type to the {@link List} contract.
     *
     * @param entity non-null list payload
     * @param <E> list element type
     * @return builder
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <E> Builder<List<E>> builder(List<E> entity) {
        return new Builder<>(entity);
    }

    /**
     * Create an outgoing JMS message with no native metadata or properties.
     *
     * @param entity non-null payload
     * @param <T> payload type
     * @return JMS message
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <T> JmsMessage<T> create(T entity) {
        return builder(entity).build();
    }

    /**
     * Create an outgoing JMS map message while normalizing the payload type to the {@link Map} contract.
     *
     * @param entity non-null map payload
     * @param <K> map key type
     * @param <V> map value type
     * @return JMS message
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <K, V> JmsMessage<Map<K, V>> create(Map<K, V> entity) {
        return JmsMessage.<K, V>builder(entity).build();
    }

    /**
     * Create an outgoing JMS stream message while normalizing the payload type to the {@link List} contract.
     *
     * @param entity non-null list payload
     * @param <E> list element type
     * @return JMS message
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <E> JmsMessage<List<E>> create(List<E> entity) {
        return JmsMessage.<E>builder(entity).build();
    }

    /**
     * Whether the native JMS body was retained in this immutable envelope.
     *
     * @return whether {@link #entity()} can return the body
     */
    default boolean bodyAvailable() {
        return true;
    }

    /**
     * Native JMS message identifier.
     *
     * @return message identifier
     */
    Optional<String> messageId();

    /**
     * JMS correlation identifier.
     *
     * @return correlation identifier
     */
    Optional<String> correlationId();

    /**
     * JMS type.
     *
     * @return message type
     */
    Optional<String> type();

    /**
     * Provider-assigned timestamp in milliseconds since the epoch.
     *
     * @return timestamp, including a native value of zero when present
     */
    OptionalLong timestamp();

    /**
     * Expiration time in milliseconds since the epoch.
     *
     * @return expiration, including a native value of zero when present
     */
    OptionalLong expiration();

    /**
     * Earliest delivery time in milliseconds since the epoch.
     *
     * @return delivery time, including a native value of zero when present
     */
    OptionalLong deliveryTime();

    /**
     * JMS priority.
     *
     * @return priority
     */
    OptionalInt priority();

    /**
     * Whether the provider marks this incoming message as redelivered.
     *
     * @return redelivered flag, or empty for an application-created outgoing message
     */
    Optional<Boolean> redelivered();

    /**
     * Immutable JMS application-property snapshot. Supported values are {@link Boolean}, {@link Byte}, {@link Short},
     * {@link Integer}, {@link Long}, {@link Float}, {@link Double}, and {@link String}. The standard client-settable
     * {@code JMSXGroupID} and {@code JMSXGroupSeq} properties are included when present.
     *
     * @return JMS properties
     */
    Map<String, Object> jmsProperties();

    /**
     * Builder for an application-created outgoing JMS message.
     *
     * @param <T> payload type
     */
    final class Builder<T> {
        private final T entity;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private String correlationId;
        private String type;

        private Builder(T entity) {
            this.entity = Objects.requireNonNull(entity, "entity");
        }

        /**
         * Set the JMS correlation identifier.
         *
         * @param correlationId correlation identifier
         * @return updated builder
         */
        public Builder<T> correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        /**
         * Set the JMS type.
         *
         * @param type JMS type
         * @return updated builder
         */
        public Builder<T> type(String type) {
            this.type = type;
            return this;
        }

        /**
         * Set a JMS application property. Setting the same name again replaces its previous value.
         *
         * @param name property name
         * @param value supported JMS property value
         * @return updated builder
         */
        public Builder<T> property(String name, Object value) {
            String actualName = JmsMessageImpl.requirePropertyName(name);
            properties.put(actualName, JmsMessageImpl.snapshotProperty(actualName, value));
            return this;
        }

        /**
         * Build a message with immutable metadata and application-property snapshots.
         *
         * @return JMS message
         */
        public JmsMessage<T> build() {
            return JmsMessageImpl.outgoing(entity, correlationId, type, properties);
        }
    }
}
