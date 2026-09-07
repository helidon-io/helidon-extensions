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

package io.helidon.extensions.messaging.connectors.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.helidon.common.Api;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeaderValue;

/**
 * Kafka-specific immutable message envelope.
 * <p>
 * Incoming messages expose a snapshot of their Kafka record metadata. Application-created messages expose an optional
 * key and native headers for an outgoing Kafka binding, but have no source topic, partition, offset, timestamp, or
 * leader epoch.
 * <p>
 * The portable {@link #headers()} view preserves native header order and duplicate names. Non-null native values are
 * exposed as {@link MessageHeaderValue.BinaryValue}; native null values are exposed as
 * {@link MessageHeaderValue.NullValue}. Use
 * {@link #kafkaHeaders()} when Kafka-native header access is preferred.
 *
 * @param <K> Kafka key type
 * @param <V> Kafka value type
 */
@Api.Preview
public interface KafkaMessage<K, V> extends Message<V> {
    /**
     * Create a builder for an outgoing Kafka message.
     *
     * @param <K> Kafka key type
     * @param <V> Kafka value type
     * @return Kafka message builder
     */
    static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /**
     * Create a Kafka message without a key or native headers.
     *
     * @param entity non-null message payload
     * @param <V> Kafka value type
     * @return immutable Kafka message
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <V> KafkaMessage<Void, V> create(V entity) {
        return KafkaMessage.<Void, V>builder(entity).build();
    }

    /**
     * Create a keyed Kafka message without native headers.
     *
     * @param key non-null Kafka key
     * @param entity non-null message payload
     * @param <K> Kafka key type
     * @param <V> Kafka value type
     * @return immutable Kafka message
     * @throws NullPointerException if {@code key} or {@code entity} is {@code null}
     */
    static <K, V> KafkaMessage<K, V> create(K key, V entity) {
        return KafkaMessage.<K, V>builder(entity).key(key).build();
    }

    /**
     * Create a builder for an outgoing Kafka message without a key.
     *
     * @param entity non-null message payload
     * @param <K> Kafka key type
     * @param <V> Kafka value type
     * @return Kafka message builder
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <K, V> Builder<K, V> builder(V entity) {
        return KafkaMessage.<K, V>builder().entity(entity);
    }

    /**
     * Kafka key.
     *
     * @return Kafka key, or empty when the message has no key
     */
    Optional<K> key();

    /**
     * Source Kafka topic.
     *
     * @return source topic, or empty for an application-created outgoing message
     */
    Optional<String> topic();

    /**
     * Source Kafka partition.
     *
     * @return source partition, or empty for an application-created outgoing message
     */
    OptionalInt partition();

    /**
     * Source Kafka offset.
     *
     * @return source offset, or empty for an application-created outgoing message
     */
    OptionalLong offset();

    /**
     * Source Kafka timestamp, including the Kafka no-timestamp value of {@code -1}.
     *
     * @return source timestamp, or empty for an application-created outgoing message
     */
    OptionalLong timestamp();

    /**
     * Source Kafka timestamp type.
     *
     * @return source timestamp type, or empty for an application-created outgoing message
     */
    Optional<TimestampType> timestampType();

    /**
     * Source Kafka leader epoch.
     *
     * @return source leader epoch when present
     */
    OptionalInt leaderEpoch();

    /**
     * Ordered immutable snapshot of native Kafka headers.
     * <p>
     * Duplicate names, binary values, and null values are preserved.
     *
     * @return native Kafka headers
     */
    List<Header> kafkaHeaders();

    /**
     * Immutable snapshot of the Kafka record timestamp type.
     */
    @Api.Preview
    enum TimestampType {
        /**
         * The record does not have a timestamp.
         */
        NO_TIMESTAMP_TYPE,

        /**
         * The timestamp was assigned when the record was created.
         */
        CREATE_TIME,

        /**
         * The timestamp was assigned when the broker appended the record.
         */
        LOG_APPEND_TIME
    }

    /**
     * Immutable Kafka header view.
     */
    @Api.Preview
    interface Header {
        /**
         * Header name.
         *
         * @return header name
         */
        String name();

        /**
         * Header value.
         * <p>
         * A fresh defensive copy is returned whenever a value is present.
         *
         * @return header value, or empty for a native null value
         */
        Optional<byte[]> value();
    }

    /**
     * Builder for application-created outgoing Kafka messages.
     *
     * @param <K> Kafka key type
     * @param <V> Kafka value type
     */
    @Api.Preview
    final class Builder<K, V> implements io.helidon.common.Builder<Builder<K, V>, KafkaMessage<K, V>> {
        private final List<Header> headers = new ArrayList<>();
        private K key;
        private V entity;

        private Builder() {
        }

        /**
         * Set the message payload.
         *
         * @param entity non-null message payload
         * @return updated builder
         * @throws NullPointerException if {@code entity} is {@code null}
         */
        public Builder<K, V> entity(V entity) {
            this.entity = Objects.requireNonNull(entity, "entity");
            return this;
        }

        /**
         * Set the Kafka key.
         *
         * @param key non-null Kafka key
         * @return updated builder
         * @throws NullPointerException if {@code key} is {@code null}
         */
        public Builder<K, V> key(K key) {
            this.key = Objects.requireNonNull(key, "key");
            return this;
        }

        /**
         * Clear the Kafka key.
         *
         * @return updated builder
         */
        public Builder<K, V> clearKey() {
            this.key = null;
            return this;
        }

        /**
         * Append a native Kafka header whose value is UTF-8 text.
         * <p>
         * Repeated names are retained in both {@link KafkaMessage#kafkaHeaders()} and the ordered portable
         * {@link KafkaMessage#headers()} view.
         *
         * @param name header name
         * @param value header value
         * @return updated builder
         * @throws NullPointerException if {@code name} or {@code value} is {@code null}
         */
        public Builder<K, V> addHeader(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            return addRawHeader(name, value.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Append a native Kafka header.
         * <p>
         * Repeated names are retained in both header views. The supplied array is defensively copied.
         *
         * @param name header name
         * @param value raw header value
         * @return updated builder
         * @throws NullPointerException if {@code name} or {@code value} is {@code null}
         */
        public Builder<K, V> addRawHeader(String name, byte[] value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            headers.add(KafkaMessageImpl.header(name, value));
            return this;
        }

        /**
         * Append a native Kafka null-valued header.
         * <p>
         * Repeated names are retained in both header views. The value is exposed as
         * {@link MessageHeaderValue.NullValue} in the portable {@link KafkaMessage#headers()} view.
         *
         * @param name header name
         * @return updated builder
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public Builder<K, V> addNullHeader(String name) {
            Objects.requireNonNull(name, "name");
            headers.add(KafkaMessageImpl.header(name, null));
            return this;
        }

        /**
         * Build an immutable Kafka message.
         *
         * @return immutable Kafka message
         */
        @Override
        public KafkaMessage<K, V> build() {
            return KafkaMessageImpl.create(key, Objects.requireNonNull(entity, "entity"), headers);
        }
    }
}
