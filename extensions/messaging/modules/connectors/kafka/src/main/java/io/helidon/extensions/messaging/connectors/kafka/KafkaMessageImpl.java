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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.apache.kafka.clients.consumer.ConsumerRecord;

final class KafkaMessageImpl<K, V> implements KafkaMessage<K, V> {
    private static final long REQUIRED_RECORD_METADATA_BYTES = Integer.BYTES
            + Long.BYTES
            + Long.BYTES
            + Byte.BYTES;

    private final K key;
    private final V entity;
    private final Map<String, String> headers;
    private final List<KafkaMessage.Header> kafkaHeaders;
    private final Optional<String> topic;
    private final OptionalInt partition;
    private final OptionalLong offset;
    private final OptionalLong timestamp;
    private final Optional<KafkaMessage.TimestampType> timestampType;
    private final OptionalInt leaderEpoch;
    private final OptionalLong admissionBytes;
    private final OptionalLong recordAdmissionLowerBound;

    private KafkaMessageImpl(K key,
                             V entity,
                             List<? extends KafkaMessage.Header> headers,
                             Optional<String> topic,
                             OptionalInt partition,
                             OptionalLong offset,
                             OptionalLong timestamp,
                             Optional<KafkaMessage.TimestampType> timestampType,
                             OptionalInt leaderEpoch,
                             OptionalLong admissionBytes,
                             OptionalLong recordAdmissionLowerBound) {
        this.key = key;
        this.entity = entity;
        this.kafkaHeaders = snapshot(headers);
        this.headers = commonHeaders(kafkaHeaders);
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.timestamp = timestamp;
        this.timestampType = timestampType;
        this.leaderEpoch = leaderEpoch;
        this.admissionBytes = admissionBytes;
        this.recordAdmissionLowerBound = recordAdmissionLowerBound;
    }

    static KafkaMessage.Header header(String name, byte[] value) {
        return new ImmutableHeader(name, value);
    }

    static <K, V> KafkaMessage<K, V> create(K key,
                                             V entity,
                                             List<? extends KafkaMessage.Header> headers,
                                             OptionalLong admissionBytes) {
        Optional<ProgrammaticAdmissionBytes> knownBytes = programmaticAdmissionBytes(key, entity, headers);
        OptionalLong actualAdmissionBytes;
        if (knownBytes.isEmpty()) {
            actualAdmissionBytes = OptionalLong.empty();
        } else if (admissionBytes.isPresent()) {
            actualAdmissionBytes = OptionalLong.of(Math.max(admissionBytes.getAsLong(),
                                                            knownBytes.get().lowerBoundBytes()));
        } else if (knownBytes.get().complete()) {
            actualAdmissionBytes = OptionalLong.of(knownBytes.get().lowerBoundBytes());
        } else {
            actualAdmissionBytes = OptionalLong.empty();
        }
        return new KafkaMessageImpl<>(key,
                                      entity,
                                      headers,
                                      Optional.empty(),
                                      OptionalInt.empty(),
                                      OptionalLong.empty(),
                                      OptionalLong.empty(),
                                      Optional.empty(),
                                      OptionalInt.empty(),
                                      actualAdmissionBytes,
                                      OptionalLong.empty());
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
                                      admissionBytes(record, headers),
                                      recordAdmissionLowerBound(record, headers));
    }

    @Override
    public V entity() {
        return entity;
    }

    @Override
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public OptionalLong admissionBytes() {
        return admissionBytes;
    }

    OptionalLong recordAdmissionLowerBound() {
        return recordAdmissionLowerBound;
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

    private static Map<String, String> commonHeaders(List<KafkaMessage.Header> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        for (KafkaMessage.Header header : headers) {
            header.value().ifPresent(value -> result.put(header.name(),
                                                        new String(value, StandardCharsets.UTF_8)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static KafkaMessage.TimestampType timestampType(
            org.apache.kafka.common.record.TimestampType timestampType) {
        return switch (timestampType) {
        case NO_TIMESTAMP_TYPE -> KafkaMessage.TimestampType.NO_TIMESTAMP_TYPE;
        case CREATE_TIME -> KafkaMessage.TimestampType.CREATE_TIME;
        case LOG_APPEND_TIME -> KafkaMessage.TimestampType.LOG_APPEND_TIME;
        };
    }

    private static OptionalLong admissionBytes(ConsumerRecord<?, ?> record,
                                               List<? extends KafkaMessage.Header> headers) {
        OptionalLong keyBytes = contentBytes(record.key(), record.serializedKeySize());
        OptionalLong valueBytes = contentBytes(record.value(), record.serializedValueSize());
        if (keyBytes.isEmpty() || valueBytes.isEmpty()) {
            return OptionalLong.empty();
        }
        return completeRecordBytes(record, headers, keyBytes.getAsLong(), valueBytes.getAsLong());
    }

    private static OptionalLong recordAdmissionLowerBound(ConsumerRecord<?, ?> record,
                                                          List<? extends KafkaMessage.Header> headers) {
        long keyBytes = Math.max(0, record.serializedKeySize());
        long valueBytes = Math.max(0, record.serializedValueSize());
        OptionalLong result = completeRecordBytes(record, headers, keyBytes, valueBytes);
        return result.isPresent() ? result : OptionalLong.of(Long.MAX_VALUE);
    }

    private static OptionalLong completeRecordBytes(ConsumerRecord<?, ?> record,
                                                    List<? extends KafkaMessage.Header> headers,
                                                    long keyBytes,
                                                    long valueBytes) {
        try {
            long result = keyBytes;
            result = Math.addExact(result, valueBytes);
            result = Math.addExact(result, record.topic().getBytes(StandardCharsets.UTF_8).length);
            result = Math.addExact(result, REQUIRED_RECORD_METADATA_BYTES);
            if (record.leaderEpoch().isPresent()) {
                result = Math.addExact(result, Integer.BYTES);
            }
            for (KafkaMessage.Header header : headers) {
                result = Math.addExact(result, header.name().getBytes(StandardCharsets.UTF_8).length);
                Optional<byte[]> value = header.value();
                if (value.isPresent()) {
                    result = Math.addExact(result, value.get().length);
                }
            }
            result = Math.addExact(result, portableHeaderValueBytes(headers));
            return OptionalLong.of(result);
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    private static Optional<ProgrammaticAdmissionBytes> programmaticAdmissionBytes(
            Object key,
            Object entity,
            List<? extends KafkaMessage.Header> headers) {
        OptionalLong keyBytes = contentBytes(key, ConsumerRecord.NULL_SIZE);
        OptionalLong valueBytes = contentBytes(entity, ConsumerRecord.NULL_SIZE);
        try {
            long result = keyBytes.orElse(0);
            result = Math.addExact(result, valueBytes.orElse(0));
            for (KafkaMessage.Header header : headers) {
                result = Math.addExact(result, header.name().getBytes(StandardCharsets.UTF_8).length);
                Optional<byte[]> value = header.value();
                if (value.isPresent()) {
                    result = Math.addExact(result, value.get().length);
                }
            }
            result = Math.addExact(result, portableHeaderValueBytes(headers));
            return Optional.of(new ProgrammaticAdmissionBytes(result,
                                                              keyBytes.isPresent() && valueBytes.isPresent()));
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static long portableHeaderValueBytes(List<? extends KafkaMessage.Header> headers) {
        Map<String, String> values = new LinkedHashMap<>();
        for (KafkaMessage.Header header : headers) {
            header.value().ifPresent(value -> values.put(header.name(),
                                                        new String(value, StandardCharsets.UTF_8)));
        }
        return portableHeaderValueBytes(values);
    }

    private static long portableHeaderValueBytes(Map<String, String> values) {
        long result = 0;
        for (String value : values.values()) {
            result = Math.addExact(result, value.getBytes(StandardCharsets.UTF_8).length);
        }
        return result;
    }

    private static OptionalLong contentBytes(Object value, int serializedBytes) {
        OptionalLong retainedBytes = retainedContentBytes(value);
        if (retainedBytes.isEmpty()) {
            return OptionalLong.empty();
        }
        if (serializedBytes >= 0) {
            return OptionalLong.of(Math.max(serializedBytes, retainedBytes.getAsLong()));
        }
        return retainedBytes;
    }

    private static OptionalLong retainedContentBytes(Object value) {
        if (value == null) {
            return OptionalLong.of(0);
        }
        if (value instanceof byte[] bytes) {
            return OptionalLong.of(bytes.length);
        }
        if (value instanceof ByteBuffer buffer) {
            return OptionalLong.of(buffer.capacity());
        }
        if (value instanceof String text) {
            return OptionalLong.of(text.getBytes(StandardCharsets.UTF_8).length);
        }
        if (value instanceof Byte || value instanceof Boolean) {
            return OptionalLong.of(Byte.BYTES);
        }
        if (value instanceof Short || value instanceof Character) {
            return OptionalLong.of(Short.BYTES);
        }
        if (value instanceof Integer || value instanceof Float) {
            return OptionalLong.of(Integer.BYTES);
        }
        if (value instanceof Long || value instanceof Double) {
            return OptionalLong.of(Long.BYTES);
        }
        return OptionalLong.empty();
    }

    private record ProgrammaticAdmissionBytes(long lowerBoundBytes, boolean complete) {
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
