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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.helidon.messaging.HeaderValue;
import io.helidon.messaging.MessageHeaders;
import io.helidon.messaging.MessagingException;

import org.apache.kafka.clients.consumer.ConsumerRecord;

final class KafkaMessageImpl<K, V> implements KafkaMessage<K, V> {
    private final K key;
    private final V entity;
    private final boolean entityAvailable;
    private final MessageHeaders headers;
    private final List<KafkaMessage.Header> kafkaHeaders;
    private final Optional<String> topic;
    private final OptionalInt partition;
    private final OptionalLong offset;
    private final OptionalLong timestamp;
    private final Optional<KafkaMessage.TimestampType> timestampType;
    private final OptionalInt leaderEpoch;

    private KafkaMessageImpl(K key,
                             V entity,
                             List<? extends KafkaMessage.Header> headers,
                             Optional<String> topic,
                             OptionalInt partition,
                             OptionalLong offset,
                             OptionalLong timestamp,
                             Optional<KafkaMessage.TimestampType> timestampType,
                             OptionalInt leaderEpoch,
                             boolean entityAvailable) {
        this.key = key;
        this.entity = Objects.requireNonNull(entity, "entity");
        this.entityAvailable = entityAvailable;
        this.kafkaHeaders = snapshot(headers);
        this.headers = commonHeaders(kafkaHeaders);
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.timestamp = timestamp;
        this.timestampType = timestampType;
        this.leaderEpoch = leaderEpoch;
    }

    static KafkaMessage.Header header(String name, byte[] value) {
        return new ImmutableHeader(name, value);
    }

    static <K, V> KafkaMessage<K, V> create(K key,
                                             V entity,
                                             List<? extends KafkaMessage.Header> headers) {
        return new KafkaMessageImpl<>(key,
                                      entity,
                                      headers,
                                      Optional.empty(),
                                      OptionalInt.empty(),
                                      OptionalLong.empty(),
                                      OptionalLong.empty(),
                                      Optional.empty(),
                                      OptionalInt.empty(),
                                      true);
    }

    static <K, V> KafkaMessage<K, V> create(ConsumerRecord<K, V> record) {
        Objects.requireNonNull(record);
        List<KafkaMessage.Header> headers = new ArrayList<>();
        for (org.apache.kafka.common.header.Header header : record.headers()) {
            headers.add(header(header.key(), header.value()));
        }
        Optional<Integer> leaderEpoch = record.leaderEpoch();
        return new KafkaMessageImpl<>(record.key(),
                                      record.value(),
                                      headers,
                                      Optional.of(record.topic()),
                                      OptionalInt.of(record.partition()),
                                      OptionalLong.of(record.offset()),
                                      OptionalLong.of(record.timestamp()),
                                      Optional.of(timestampType(record.timestampType())),
                                      leaderEpoch.isPresent()
                                              ? OptionalInt.of(leaderEpoch.get())
                                              : OptionalInt.empty(),
                                      true);
    }

    static KafkaMessage<Object, Object> rejected(ConsumerRecord<Object, Object> record) {
        Objects.requireNonNull(record);
        List<KafkaMessage.Header> headers = new ArrayList<>();
        for (org.apache.kafka.common.header.Header header : record.headers()) {
            headers.add(header(header.key(), header.value()));
        }
        Optional<Integer> leaderEpoch = record.leaderEpoch();
        return new KafkaMessageImpl<>(record.key(),
                                      UnavailableEntity.INSTANCE,
                                      headers,
                                      Optional.of(record.topic()),
                                      OptionalInt.of(record.partition()),
                                      OptionalLong.of(record.offset()),
                                      OptionalLong.of(record.timestamp()),
                                      Optional.of(timestampType(record.timestampType())),
                                      leaderEpoch.isPresent()
                                              ? OptionalInt.of(leaderEpoch.get())
                                              : OptionalInt.empty(),
                                      false);
    }

    @Override
    public V entity() {
        if (!entityAvailable) {
            throw new MessagingException("Kafka message entity is unavailable");
        }
        return entity;
    }

    boolean entityAvailable() {
        return entityAvailable;
    }

    @Override
    public MessageHeaders headers() {
        return headers;
    }

    @Override
    public Optional<K> key() {
        return Optional.ofNullable(key);
    }

    @Override
    public Optional<String> topic() {
        return topic;
    }

    @Override
    public OptionalInt partition() {
        return partition;
    }

    @Override
    public OptionalLong offset() {
        return offset;
    }

    @Override
    public OptionalLong timestamp() {
        return timestamp;
    }

    @Override
    public Optional<KafkaMessage.TimestampType> timestampType() {
        return timestampType;
    }

    @Override
    public OptionalInt leaderEpoch() {
        return leaderEpoch;
    }

    @Override
    public List<KafkaMessage.Header> kafkaHeaders() {
        return kafkaHeaders;
    }

    private static List<KafkaMessage.Header> snapshot(List<? extends KafkaMessage.Header> headers) {
        List<KafkaMessage.Header> result = new ArrayList<>(headers.size());
        for (KafkaMessage.Header header : headers) {
            Objects.requireNonNull(header);
            result.add(new ImmutableHeader(header.name(), header.value().orElse(null)));
        }
        return List.copyOf(result);
    }

    private static MessageHeaders commonHeaders(List<KafkaMessage.Header> headers) {
        MessageHeaders.Builder result = MessageHeaders.builder();
        for (KafkaMessage.Header header : headers) {
            Optional<byte[]> value = header.value();
            result.add(header.name(), value.isPresent()
                    ? HeaderValue.binary(value.orElseThrow())
                    : HeaderValue.nullValue());
        }
        return result.build();
    }

    private static KafkaMessage.TimestampType timestampType(
            org.apache.kafka.common.record.TimestampType timestampType) {
        return switch (timestampType) {
        case NO_TIMESTAMP_TYPE -> KafkaMessage.TimestampType.NO_TIMESTAMP_TYPE;
        case CREATE_TIME -> KafkaMessage.TimestampType.CREATE_TIME;
        case LOG_APPEND_TIME -> KafkaMessage.TimestampType.LOG_APPEND_TIME;
        };
    }

    private enum UnavailableEntity {
        INSTANCE
    }

    private static final class ImmutableHeader implements KafkaMessage.Header {
        private final String name;
        private final byte[] value;

        private ImmutableHeader(String name, byte[] value) {
            this.name = Objects.requireNonNull(name);
            this.value = value == null ? null : value.clone();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Optional<byte[]> value() {
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
    }
}
