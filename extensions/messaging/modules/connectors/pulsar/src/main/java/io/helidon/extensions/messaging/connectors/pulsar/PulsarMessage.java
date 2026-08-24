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

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.helidon.messaging.Message;

/**
 * Pulsar-specific immutable message envelope without exposing Pulsar client types.
 *
 * @param <T> payload type
 */
public interface PulsarMessage<T> extends Message<T> {
    /**
     * Create a payload-only outgoing Pulsar message.
     *
     * @param entity payload
     * @param <T> payload type
     * @return immutable message
     */
    static <T> PulsarMessage<T> create(T entity) {
        return builder(entity).build();
    }

    /**
     * Create an outgoing Pulsar message builder.
     *
     * @param entity payload
     * @param <T> payload type
     * @return builder
     */
    static <T> Builder<T> builder(T entity) {
        return new Builder<>(entity);
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
     * Application event time.
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
    final class Builder<T> {
        private final T entity;
        private final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        private String key;
        private byte[] keyBytes;
        private byte[] orderingKey;
        private Long eventTime;

        private Builder(T entity) {
            this.entity = entity;
        }

        /**
         * Configure a textual partitioning key.
         *
         * @param key key
         * @return updated builder
         */
        public Builder<T> key(String key) {
            this.key = java.util.Objects.requireNonNull(key);
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
            this.keyBytes = java.util.Objects.requireNonNull(key).clone();
            this.key = null;
            return this;
        }

        /**
         * Configure a binary ordering key.
         *
         * @param key ordering key
         * @return updated builder
         */
        public Builder<T> orderingKey(byte[] key) {
            this.orderingKey = java.util.Objects.requireNonNull(key).clone();
            return this;
        }

        /**
         * Add or replace an application property.
         *
         * @param name property name
         * @param value property value
         * @return updated builder
         */
        public Builder<T> header(String name, String value) {
            headers.put(java.util.Objects.requireNonNull(name), java.util.Objects.requireNonNull(value));
            return this;
        }

        /**
         * Configure a non-negative application event time in epoch milliseconds.
         *
         * @param eventTime event time
         * @return updated builder
         */
        public Builder<T> eventTime(long eventTime) {
            if (eventTime < 0) {
                throw new IllegalArgumentException("Pulsar event time must not be negative");
            }
            this.eventTime = eventTime;
            return this;
        }

        /**
         * Create the immutable message.
         *
         * @return immutable Pulsar message
         */
        public PulsarMessage<T> build() {
            return PulsarMessageImpl.outgoing(entity, headers, key, keyBytes, orderingKey, eventTime);
        }
    }
}
