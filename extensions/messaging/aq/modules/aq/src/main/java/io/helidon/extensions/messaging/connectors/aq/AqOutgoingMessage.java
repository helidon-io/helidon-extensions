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
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.Api;
import io.helidon.messaging.MessageHeaders;
import io.helidon.messaging.MessageMetadata;

/**
 * Application-created AQ message with detached payload and outgoing metadata.
 * The original construction prototype is retained separately from the message's defensive snapshots.
 * Binary payloads, map structure, and byte-array map values are copied when the message is created and read.
 * Other application values must remain stable as described by {@link AqMessage}.
 * Provider-assigned metadata is absent on outgoing envelopes.
 *
 * @param <T> payload type
 */
@Api.Incubating
public final class AqOutgoingMessage<T> implements AqMessage<T>, RuntimeType.Api<AqMessageConfig<T>> {
    private final AqMessageConfig<T> prototype;
    private final Object entity;
    private final Map<String, Object> properties;
    private final MessageHeaders headers;
    private final MessageMetadata localMetadata;
    private final List<AqRecipient> recipients;
    private final Optional<String> correlationId;
    private final Optional<String> type;
    private final Optional<String> replyTo;
    private final Optional<AqRecipient> replyToAgent;
    private final AqDestinationType replyToType;

    private AqOutgoingMessage(AqMessageConfig<T> config) {
        this.prototype = Objects.requireNonNull(config);
        this.entity = AqValues.body(config.entity());
        this.properties = AqValues.properties(config.properties());
        this.headers = AqValues.headers(properties);
        MessageMetadata.Builder metadata = MessageMetadata.builder();
        config.localMetadata().forEach(metadata::set);
        this.localMetadata = metadata.build();
        this.recipients = List.copyOf(config.recipients());
        this.correlationId = config.correlationId();
        this.type = config.type();
        this.replyTo = config.replyTo();
        this.replyToAgent = config.replyToAgent();
        if (replyTo.isPresent() && replyToAgent.isPresent()) {
            throw new IllegalArgumentException("AQ reply-to destination and reply-to agent are mutually exclusive");
        }
        this.replyToType = config.replyToType();
    }

    /**
     * Create an outgoing message from its construction prototype.
     *
     * @param config non-null construction prototype
     * @param <T> payload type
     * @return outgoing AQ message
     * @throws NullPointerException if the prototype is null
     */
    public static <T> AqOutgoingMessage<T> create(AqMessageConfig<T> config) {
        return new AqOutgoingMessage<>(Objects.requireNonNull(config));
    }

    /**
     * Create an outgoing message using a builder consumer.
     *
     * @param consumer non-null builder consumer
     * @param <T> payload type
     * @return outgoing AQ message
     * @throws NullPointerException if the consumer is null
     */
    public static <T> AqOutgoingMessage<T> create(Consumer<AqMessageConfig.Builder<T>> consumer) {
        AqMessageConfig.Builder<T> builder = builder();
        Objects.requireNonNull(consumer).accept(builder);
        return builder.build();
    }

    /**
     * Create an outgoing message builder.
     * Map entity types must use the Map interface; {@link AqMessage#mapBuilder(Map)} supplies that type automatically.
     * Buffer entity types must use {@link java.nio.ByteBuffer}, rather than a concrete buffer subclass.
     *
     * @param <T> payload type
     * @return outgoing message builder
     */
    public static <T> AqMessageConfig.Builder<T> builder() {
        return AqMessageConfig.builder();
    }

    /**
     * The construction prototype as it was received when this message was created.
     * Its application values may be mutable; the message's defensive snapshots are independent of this prototype.
     *
     * @return original construction prototype
     */
    @Override
    public AqMessageConfig<T> prototype() {
        return prototype;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T entity() {
        return (T) AqValues.body(entity);
    }

    @Override
    public MessageHeaders headers() {
        return headers;
    }

    @Override
    public MessageMetadata localMetadata() {
        return localMetadata;
    }

    @Override
    public boolean bodyAvailable() {
        return true;
    }

    @Override
    public Optional<String> messageId() {
        return Optional.empty();
    }

    @Override
    public Optional<String> correlationId() {
        return correlationId;
    }

    @Override
    public Optional<String> type() {
        return type;
    }

    @Override
    public Optional<String> replyTo() {
        return replyTo;
    }

    @Override
    public Optional<AqRecipient> replyToAgent() {
        return replyToAgent;
    }

    @Override
    public AqDestinationType replyToType() {
        return replyToType;
    }

    @Override
    public OptionalLong timestamp() {
        return OptionalLong.empty();
    }

    @Override
    public OptionalLong expiration() {
        return OptionalLong.empty();
    }

    @Override
    public OptionalInt priority() {
        return OptionalInt.empty();
    }

    @Override
    public OptionalInt deliveryMode() {
        return OptionalInt.empty();
    }

    @Override
    public Optional<Boolean> redelivered() {
        return Optional.empty();
    }

    @Override
    public OptionalInt attempts() {
        return OptionalInt.empty();
    }

    @Override
    public Optional<AqRecipient> sender() {
        return Optional.empty();
    }

    @Override
    public Map<String, Object> properties() {
        return properties;
    }

    @Override
    public List<AqRecipient> recipients() {
        return recipients;
    }
}
