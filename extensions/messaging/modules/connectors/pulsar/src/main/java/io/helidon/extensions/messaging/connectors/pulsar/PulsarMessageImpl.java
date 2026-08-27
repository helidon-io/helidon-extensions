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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.helidon.messaging.MessageHeaders;
import io.helidon.messaging.MessagingException;

final class PulsarMessageImpl<T> implements PulsarMessage<T> {
    private final T entity;
    private final boolean entityAvailable;
    private final MessageHeaders headers;
    private final String key;
    private final byte[] keyBytes;
    private final boolean base64EncodedKey;
    private final byte[] orderingKey;
    private final String topic;
    private final byte[] messageId;
    private final Long publishTime;
    private final Long eventTime;
    private final Long sequenceId;
    private final String producerName;
    private final Integer redeliveryCount;
    private final byte[] schemaVersion;
    private final Long brokerPublishTime;
    private final Long index;

    private PulsarMessageImpl(T entity,
                              boolean entityAvailable,
                              MessageHeaders headers,
                              String key,
                              byte[] keyBytes,
                              boolean base64EncodedKey,
                              byte[] orderingKey,
                              String topic,
                              byte[] messageId,
                              Long publishTime,
                              Long eventTime,
                              Long sequenceId,
                              String producerName,
                              Integer redeliveryCount,
                              byte[] schemaVersion,
                              Long brokerPublishTime,
                              Long index) {
        T actualEntity = Objects.requireNonNull(entity, "entity");
        this.entity = entityAvailable ? snapshotEntity(actualEntity) : actualEntity;
        this.entityAvailable = entityAvailable;
        this.headers = Objects.requireNonNull(headers);
        this.key = key;
        this.keyBytes = copy(keyBytes);
        this.base64EncodedKey = base64EncodedKey;
        this.orderingKey = copy(orderingKey);
        this.topic = topic;
        this.messageId = copy(messageId);
        this.publishTime = publishTime;
        this.eventTime = eventTime;
        this.sequenceId = sequenceId;
        this.producerName = producerName;
        this.redeliveryCount = redeliveryCount;
        this.schemaVersion = copy(schemaVersion);
        this.brokerPublishTime = brokerPublishTime;
        this.index = index;
    }

    static <T> PulsarMessage<T> outgoing(T entity,
                                         MessageHeaders headers,
                                         String key,
                                         byte[] keyBytes,
                                         byte[] orderingKey,
                                         Long eventTime) {
        String actualKey = key;
        if (actualKey == null && keyBytes != null) {
            actualKey = Base64.getEncoder().encodeToString(keyBytes);
        }
        return new PulsarMessageImpl<>(entity,
                                       true,
                                       headers,
                                       actualKey,
                                       keyBytes == null && key != null
                                               ? key.getBytes(StandardCharsets.UTF_8)
                                               : keyBytes,
                                       keyBytes != null,
                                       orderingKey,
                                       null,
                                       null,
                                       null,
                                       eventTime,
                                       null,
                                       null,
                                       null,
                                       null,
                                       null,
                                       null);
    }

    static <T> PulsarMessage<T> incoming(T entity, org.apache.pulsar.client.api.Message<?> message) {
        return incoming(entity, message, true);
    }

    static PulsarMessage<Object> rejected(org.apache.pulsar.client.api.Message<?> message) {
        return incoming(UnavailableEntity.INSTANCE, message, false);
    }

    static PulsarMessage<Object> rejected() {
        return new PulsarMessageImpl<>(UnavailableEntity.INSTANCE,
                                       false,
                                       MessageHeaders.empty(),
                                       null,
                                       null,
                                       false,
                                       null,
                                       null,
                                       null,
                                       null,
                                       null,
                                       null,
                                       null,
                                       null,
                                       null,
                                       null,
                                       null);
    }

    private static <T> PulsarMessage<T> incoming(T entity,
                                                  org.apache.pulsar.client.api.Message<?> message,
                                                  boolean entityAvailable) {
        Objects.requireNonNull(message);
        boolean hasKey = message.hasKey();
        long eventTime = message.getEventTime();
        long sequenceId = message.getSequenceId();
        byte[] schemaVersion = message.getSchemaVersion();
        return new PulsarMessageImpl<>(entity,
                                       entityAvailable,
                                       messageHeaders(message.getProperties()),
                                       hasKey ? message.getKey() : null,
                                       hasKey ? message.getKeyBytes() : null,
                                       hasKey && message.hasBase64EncodedKey(),
                                       message.hasOrderingKey() ? message.getOrderingKey() : null,
                                       message.getTopicName(),
                                       message.getMessageId() == null ? null : message.getMessageId().toByteArray(),
                                       message.getPublishTime(),
                                       eventTime == 0 ? null : eventTime,
                                       sequenceId < 0 ? null : sequenceId,
                                       message.getProducerName(),
                                       message.getRedeliveryCount(),
                                       schemaVersion,
                                       message.getBrokerPublishTime().orElse(null),
                                       message.getIndex().orElse(null));
    }

    @Override
    public T entity() {
        if (!entityAvailable) {
            throw new MessagingException("Pulsar message entity is unavailable");
        }
        return snapshotEntity(entity);
    }

    boolean entityAvailable() {
        return entityAvailable;
    }

    @Override
    public MessageHeaders headers() {
        return headers;
    }

    @Override
    public Optional<String> key() {
        return Optional.ofNullable(key);
    }

    @Override
    public Optional<byte[]> keyBytes() {
        return optionalCopy(keyBytes);
    }

    @Override
    public boolean base64EncodedKey() {
        return base64EncodedKey;
    }

    @Override
    public Optional<byte[]> orderingKey() {
        return optionalCopy(orderingKey);
    }

    @Override
    public Optional<String> topic() {
        return Optional.ofNullable(topic);
    }

    @Override
    public Optional<byte[]> messageId() {
        return optionalCopy(messageId);
    }

    @Override
    public OptionalLong publishTime() {
        return optionalLong(publishTime);
    }

    @Override
    public OptionalLong eventTime() {
        return optionalLong(eventTime);
    }

    @Override
    public OptionalLong sequenceId() {
        return optionalLong(sequenceId);
    }

    @Override
    public Optional<String> producerName() {
        return Optional.ofNullable(producerName);
    }

    @Override
    public OptionalInt redeliveryCount() {
        return redeliveryCount == null ? OptionalInt.empty() : OptionalInt.of(redeliveryCount);
    }

    @Override
    public Optional<byte[]> schemaVersion() {
        return optionalCopy(schemaVersion);
    }

    @Override
    public OptionalLong brokerPublishTime() {
        return optionalLong(brokerPublishTime);
    }

    @Override
    public OptionalLong index() {
        return optionalLong(index);
    }

    @SuppressWarnings("unchecked")
    static <T> T snapshotEntity(T entity) {
        if (entity instanceof byte[] bytes) {
            return (T) bytes.clone();
        }
        if (entity instanceof ByteBuffer buffer) {
            return (T) copy(buffer);
        }
        if (entity instanceof Date date) {
            return (T) date.clone();
        }
        return entity;
    }

    private static MessageHeaders messageHeaders(Map<String, String> properties) {
        MessageHeaders.Builder result = MessageHeaders.builder();
        Objects.requireNonNull(properties).forEach((name, value) -> result.add(Objects.requireNonNull(name),
                                                                               Objects.requireNonNull(value)));
        return result.build();
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    private static ByteBuffer copy(ByteBuffer value) {
        ByteBuffer source = value.duplicate();
        source.position(0);
        ByteBuffer result = ByteBuffer.allocate(source.remaining()).order(value.order());
        result.put(source);
        result.flip();
        return result;
    }

    private static Optional<byte[]> optionalCopy(byte[] value) {
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    private static OptionalLong optionalLong(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private enum UnavailableEntity {
        INSTANCE
    }
}
