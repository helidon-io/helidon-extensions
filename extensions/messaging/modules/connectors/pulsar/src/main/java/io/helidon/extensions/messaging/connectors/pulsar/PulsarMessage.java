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

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.helidon.common.Api;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeaders;

/**
 * Pulsar-specific immutable message envelope without exposing Pulsar client types.
 * <p>
 * Pulsar string properties are exposed as ordered portable text headers. Pulsar properties cannot represent typed
 * values or duplicate names, so the outgoing connector rejects those header shapes instead of stringifying or
 * dropping them.
 * A metadata-only envelope created after payload mapping fails retains available native metadata but does not retain
 * the raw payload. Its {@link #entity()} method throws a messaging exception. Such envelopes are routed only through
 * failed-delivery policy and may be inspected by local dead-letter envelope consumers.
 *
 * @param <T> payload type
 */
@Api.Preview
public interface PulsarMessage<T> extends Message<T> {
    /**
     * Create an outgoing Pulsar message builder.
     *
     * @param <T> payload type
     * @return builder
     */
    static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Create a payload-only outgoing Pulsar message.
     *
     * @param entity non-null payload
     * @param <T> payload type
     * @return immutable message
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <T> PulsarMessage<T> create(T entity) {
        return builder(entity).build();
    }

    /**
     * Create an outgoing Pulsar message builder.
     *
     * @param entity non-null payload
     * @param <T> payload type
     * @return builder
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <T> Builder<T> builder(T entity) {
        return PulsarMessage.<T>builder().entity(entity);
    }

    /**
     * Textual Pulsar key. A binary key exposes Pulsar's base64 representation here.
     *
     * @return key
     */
    Optional<String> key();

    /**
     * Raw Pulsar key bytes.
     *
     * @return defensive key copy
     */
    Optional<byte[]> keyBytes();

    /**
     * Whether the key was configured as raw bytes.
     *
     * @return whether the textual key is base64 encoded
     */
    boolean base64EncodedKey();

    /**
     * Ordering key.
     *
     * @return defensive ordering-key copy
     */
    Optional<byte[]> orderingKey();

    /**
     * Source topic, absent for application-created messages.
     *
     * @return source topic
     */
    Optional<String> topic();

    /**
     * Serialized source message identifier, absent for application-created messages.
     *
     * @return defensive message-ID copy
     */
    Optional<byte[]> messageId();

    /**
     * Source publication time.
     *
     * @return publication time
     */
    OptionalLong publishTime();

    /**
     * Application event time in epoch milliseconds. Pulsar represents an unset event time as zero, which is exposed as
     * empty.
     *
     * @return event time
     */
    OptionalLong eventTime();

    /**
     * Source producer sequence ID.
     *
     * @return sequence ID
     */
    OptionalLong sequenceId();

    /**
     * Source producer name.
     *
     * @return producer name
     */
    Optional<String> producerName();

    /**
     * Source redelivery count.
     *
     * @return redelivery count
     */
    OptionalInt redeliveryCount();

    /**
     * Source schema version.
     *
     * @return defensive schema-version copy
     */
    Optional<byte[]> schemaVersion();

    /**
     * Broker publication time, when broker entry metadata provides it.
     *
     * @return broker publication time
     */
    OptionalLong brokerPublishTime();

    /**
     * Broker entry index, when broker entry metadata provides it.
     *
     * @return broker index
     */
    OptionalLong index();

    /**
     * Builder for application-created outgoing Pulsar messages.
     *
     * @param <T> payload type
     */
    @Api.Preview
    final class Builder<T> implements io.helidon.common.Builder<Builder<T>, PulsarMessage<T>> {
        private final MessageHeaders.Builder headers = MessageHeaders.builder();
        private T entity;
        private String key;
        private byte[] keyBytes;
        private byte[] orderingKey;
        private Long eventTime;

        private Builder() {
        }

        /**
         * Set the message payload.
         *
         * @param entity non-null payload
         * @return updated builder
         * @throws NullPointerException if {@code entity} is {@code null}
         */
        public Builder<T> entity(T entity) {
            this.entity = Objects.requireNonNull(entity, "entity");
            return this;
        }

        /**
         * Configure a textual partitioning key.
         *
         * @param key key
         * @return updated builder
         */
        public Builder<T> key(String key) {
            this.key = Objects.requireNonNull(key);
            this.keyBytes = null;
            return this;
        }

        /**
         * Configure a binary partitioning key.
         *
         * @param key key bytes
         * @return updated builder
         */
        public Builder<T> keyBytes(byte[] key) {
            this.keyBytes = Objects.requireNonNull(key).clone();
            this.key = null;
            return this;
        }

        /**
         * Clear the partitioning key.
         *
         * @return updated builder
         */
        public Builder<T> clearKey() {
            this.key = null;
            this.keyBytes = null;
            return this;
        }

        /**
         * Configure a binary ordering key.
         *
         * @param key ordering key
         * @return updated builder
         */
        public Builder<T> orderingKey(byte[] key) {
            this.orderingKey = Objects.requireNonNull(key).clone();
            return this;
        }

        /**
         * Clear the ordering key.
         *
         * @return updated builder
         */
        public Builder<T> clearOrderingKey() {
            this.orderingKey = null;
            return this;
        }

        /**
         * Add or replace a portable text header. The header is written as a Pulsar string property.
         *
         * @param name property name
         * @param value property value
         * @return updated builder
         */
        public Builder<T> header(String name, String value) {
            headers.set(name, value);
            return this;
        }

        /**
         * Configure a positive application event time in epoch milliseconds.
         *
         * @param eventTime event time
         * @return updated builder
         * @throws IllegalArgumentException if {@code eventTime} is not positive
         */
        public Builder<T> eventTime(long eventTime) {
            if (eventTime <= 0) {
                throw new IllegalArgumentException("Pulsar event time must be positive");
            }
            this.eventTime = eventTime;
            return this;
        }

        /**
         * Clear the application event time.
         *
         * @return updated builder
         */
        public Builder<T> clearEventTime() {
            this.eventTime = null;
            return this;
        }

        /**
         * Create the immutable message.
         *
         * @return immutable Pulsar message
         */
        @Override
        public PulsarMessage<T> build() {
            return PulsarMessageImpl.outgoing(Objects.requireNonNull(entity, "entity"),
                                              headers.build(),
                                              key,
                                              keyBytes,
                                              orderingKey,
                                              eventTime);
        }
    }
}
