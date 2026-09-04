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
import io.helidon.messaging.HeaderValue;
import io.helidon.messaging.Message;

/**
 * Kafka-specific immutable message envelope.
 * <p>
 * Incoming messages expose a snapshot of their Kafka record metadata. Messages created by
 * {@link #builder(Object, Object)} expose a key and native headers for an outgoing Kafka binding, but have no source
 * topic, partition, offset, timestamp, or leader epoch.
 * <p>
 * The portable {@link #headers()} view preserves native header order and duplicate names. Non-null native values are
 * exposed as {@link HeaderValue.BinaryValue}; native null values are exposed as {@link HeaderValue.NullValue}. Use
 * {@link #kafkaHeaders()} when Kafka-native header access is preferred.
 *
 * @param <K> Kafka key type
 * @param <V> Kafka value type
 */
@Api.Preview
public interface KafkaMessage<K, V> extends Message<V> {
    /**
     * Create a keyed Kafka message without native headers.
     *
     * @param key Kafka key, may be {@code null}
     * @param entity non-null message payload
     * @param <K> Kafka key type
     * @param <V> Kafka value type
     * @return immutable Kafka message
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <K, V> KafkaMessage<K, V> create(K key, V entity) {
        return builder(key, entity).build();
    }

    /**
     * Create a builder for an outgoing Kafka message.
     *
     * @param key Kafka key, may be {@code null}
     * @param entity non-null message payload
     * @param <K> Kafka key type
     * @param <V> Kafka value type
     * @return Kafka message builder
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    static <K, V> Builder<K, V> builder(K key, V entity) {
        return new Builder<>(key, entity);
    }

    /**
     * Kafka key.
     *
     * @return Kafka key, or empty for a null key
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
    final class Builder<K, V> {
        private final K key;
        private final V entity;
        private final List<Header> headers = new ArrayList<>();

        private Builder(K key, V entity) {
            this.key = key;
            this.entity = Objects.requireNonNull(entity, "entity");
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
         */
        public Builder<K, V> header(String name, String value) {
            return rawHeader(name, value.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Append a native Kafka header.
         * <p>
         * Repeated names are retained in both header views. The supplied array is defensively copied. A {@code null}
         * value represents a native null-valued header and is exposed as {@link HeaderValue.NullValue} in the portable
         * {@link KafkaMessage#headers()} view.
         *
         * @param name header name
         * @param value raw header value, may be {@code null}
         * @return updated builder
         */
        public Builder<K, V> rawHeader(String name, byte[] value) {
            headers.add(KafkaMessageImpl.header(name, value));
            return this;
        }

        /**
         * Build an immutable Kafka message.
         *
         * @return immutable Kafka message
         */
        public KafkaMessage<K, V> build() {
            return KafkaMessageImpl.create(key, entity, headers);
        }
    }
}
