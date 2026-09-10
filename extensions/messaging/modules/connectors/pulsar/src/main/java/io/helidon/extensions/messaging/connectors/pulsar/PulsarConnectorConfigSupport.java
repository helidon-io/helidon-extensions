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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.helidon.builder.api.Prototype;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionMode;

/**
 * Support methods and constants for {@link PulsarConnectorConfig}.
 */
final class PulsarConnectorConfigSupport {
    /** Pulsar connector name. */
    @Prototype.Constant
    static final String CONNECTOR_NAME = PulsarConnector.CONNECTOR_TYPE;
    /** Service URL configuration property. */
    @Prototype.Constant
    static final String SERVICE_URL_PROPERTY = "service-url";
    /** Topic configuration property. */
    @Prototype.Constant
    static final String TOPIC_PROPERTY = "topic";
    /** Schema configuration property. */
    @Prototype.Constant
    static final String SCHEMA_PROPERTY = "schema";
    /** Custom schema provider configuration property. */
    @Prototype.Constant
    static final String SCHEMA_PROVIDER_PROPERTY = "schema-provider";
    /** Subscription name configuration property. */
    @Prototype.Constant
    static final String SUBSCRIPTION_NAME_PROPERTY = "subscription-name";
    /** Subscription type configuration property. */
    @Prototype.Constant
    static final String SUBSCRIPTION_TYPE_PROPERTY = "subscription-type";
    /** Subscription initial position configuration property. */
    @Prototype.Constant
    static final String SUBSCRIPTION_INITIAL_POSITION_PROPERTY = "subscription-initial-position";
    /** Batch-index acknowledgement configuration property. */
    @Prototype.Constant
    static final String BATCH_INDEX_ACKNOWLEDGMENT_ENABLED_PROPERTY = "batch-index-acknowledgment-enabled";
    /** Receiver queue size configuration property. */
    @Prototype.Constant
    static final String RECEIVER_QUEUE_SIZE_PROPERTY = "receiver-queue-size";
    /** Maximum message size configuration property. */
    @Prototype.Constant
    static final String MAX_MESSAGE_BYTES_PROPERTY = "max-message-bytes";
    /** Receive timeout configuration property. */
    @Prototype.Constant
    static final String RECEIVE_TIMEOUT_PROPERTY = "receive-timeout";
    /** Negative acknowledgement redelivery delay configuration property. */
    @Prototype.Constant
    static final String NEGATIVE_ACK_REDELIVERY_DELAY_PROPERTY = "negative-ack-redelivery-delay";
    /** Send timeout configuration property. */
    @Prototype.Constant
    static final String SEND_TIMEOUT_PROPERTY = "send-timeout";
    /** Settlement timeout configuration property. */
    @Prototype.Constant
    static final String SETTLEMENT_TIMEOUT_PROPERTY = "settlement-timeout";
    /** Close timeout configuration property. */
    @Prototype.Constant
    static final String CLOSE_TIMEOUT_PROPERTY = "close-timeout";
    /** Client properties configuration property. */
    @Prototype.Constant
    static final String CLIENT_PROPERTIES_PROPERTY = "client-properties";
    /** Consumer properties configuration property. */
    @Prototype.Constant
    static final String CONSUMER_PROPERTIES_PROPERTY = "consumer-properties";
    /** Producer properties configuration property. */
    @Prototype.Constant
    static final String PRODUCER_PROPERTIES_PROPERTY = "producer-properties";

    /** Default receive timeout. */
    @Prototype.Constant
    static final String DEFAULT_RECEIVE_TIMEOUT = "PT0.1S";
    /** Default negative acknowledgement redelivery delay. */
    @Prototype.Constant
    static final String DEFAULT_NEGATIVE_ACK_REDELIVERY_DELAY = "PT1S";
    /** Default send timeout. */
    @Prototype.Constant
    static final String DEFAULT_SEND_TIMEOUT = "PT30S";
    /** Default settlement timeout. */
    @Prototype.Constant
    static final String DEFAULT_SETTLEMENT_TIMEOUT = "PT30S";
    /** Default close timeout. */
    @Prototype.Constant
    static final String DEFAULT_CLOSE_TIMEOUT = "PT10S";
    /** Default maximum incoming message size. */
    @Prototype.Constant
    static final int DEFAULT_MAX_MESSAGE_BYTES = 10 * 1024 * 1024;

    private PulsarConnectorConfigSupport() {
    }

    static PulsarClient createClient(String serviceUrl, Map<String, String> clientProperties) throws PulsarClientException {
        return PulsarClient.builder()
                .loadConf(objectProperties(clientProperties))
                .serviceUrl(serviceUrl)
                .build();
    }

    static Consumer<Object> createConsumer(PulsarClient client,
                                           IncomingSettings config,
                                           PulsarSchemaResolver.ResolvedSchema schema,
                                           int maxDeliveryMessages) throws PulsarClientException {
        int queueSize = Math.min(config.receiverQueueSize(), maxDeliveryMessages);
        ConsumerBuilder<Object> builder = client.newConsumer(schema.schema())
                .loadConf(objectProperties(config.consumerProperties()))
                .topic(config.topic())
                .subscriptionName(config.subscriptionName().orElse(config.channelName()))
                .subscriptionType(config.subscriptionType().nativeType())
                .subscriptionMode(SubscriptionMode.Durable)
                .subscriptionInitialPosition(config.subscriptionInitialPosition().nativePosition())
                .receiverQueueSize(queueSize)
                .negativeAckRedeliveryDelay(durationMillis(config.negativeAckRedeliveryDelay()), TimeUnit.MILLISECONDS)
                .ackTimeout(0, TimeUnit.MILLISECONDS)
                .isAckReceiptEnabled(true)
                .enableRetry(false)
                .enableBatchIndexAcknowledgment(config.batchIndexAcknowledgmentEnabled())
                .poolMessages(false)
                .startPaused(true);
        return builder.subscribe();
    }

    static ProducerBuilder<Object> producerBuilder(PulsarClient client,
                                                   OutgoingSettings config,
                                                   PulsarSchemaResolver.ResolvedSchema schema) {
        return client.newProducer(schema.schema())
                .loadConf(objectProperties(config.producerProperties()))
                .topic(config.topic())
                .sendTimeout(durationMillisInt(config.sendTimeout(), SEND_TIMEOUT_PROPERTY), TimeUnit.MILLISECONDS);
    }

    static int receiveTimeoutMillis(IncomingSettings config) {
        return durationMillisInt(config.receiveTimeout(), RECEIVE_TIMEOUT_PROPERTY);
    }

    static IncomingSettings incoming(PulsarConnectorConfig common, PulsarIncomingConfig channel) {
        return new IncomingSettings(channel.channelName(),
                                    channel.serviceUrl().orElse(common.serviceUrl()),
                                    properties(common.clientProperties(), channel.clientProperties()),
                                    channel.topic().or(common::topic)
                                            .orElseThrow(() -> new IllegalArgumentException(
                                                    "Pulsar topic is required for channel " + channel.channelName())),
                                    channel.schema().orElse(common.schema()),
                                    channel.schemaProvider().or(common::schemaProvider),
                                    channel.subscriptionName().or(common::subscriptionName),
                                    channel.subscriptionType().orElse(common.subscriptionType()),
                                    channel.subscriptionInitialPosition().orElse(common.subscriptionInitialPosition()),
                                    channel.batchIndexAcknowledgmentEnabled().orElse(common.batchIndexAcknowledgmentEnabled()),
                                    channel.receiverQueueSize().orElse(common.receiverQueueSize()),
                                    channel.maxMessageBytes().orElse(common.maxMessageBytes()),
                                    channel.receiveTimeout().orElse(common.receiveTimeout()),
                                    channel.negativeAckRedeliveryDelay().orElse(common.negativeAckRedeliveryDelay()),
                                    channel.settlementTimeout().orElse(common.settlementTimeout()),
                                    channel.closeTimeout().orElse(common.closeTimeout()),
                                    properties(common.consumerProperties(), channel.consumerProperties()));
    }

    static OutgoingSettings outgoing(PulsarConnectorConfig common, PulsarOutgoingConfig channel) {
        return new OutgoingSettings(channel.channelName(),
                                    channel.serviceUrl().orElse(common.serviceUrl()),
                                    properties(common.clientProperties(), channel.clientProperties()),
                                    channel.topic().or(common::topic)
                                            .orElseThrow(() -> new IllegalArgumentException(
                                                    "Pulsar topic is required for channel " + channel.channelName())),
                                    channel.schema().orElse(common.schema()),
                                    channel.schemaProvider().or(common::schemaProvider),
                                    channel.sendTimeout().orElse(common.sendTimeout()),
                                    channel.closeTimeout().orElse(common.closeTimeout()),
                                    properties(common.producerProperties(), channel.producerProperties()));
    }

    static long durationMillis(Duration duration) {
        long seconds;
        try {
            seconds = Math.multiplyExact(duration.getSeconds(), 1000);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
        long fraction = (duration.getNano() + 999_999L) / 1_000_000L;
        return Long.MAX_VALUE - seconds < fraction ? Long.MAX_VALUE : Math.max(0, seconds + fraction);
    }

    private static Map<String, String> properties(Map<String, String> common, Optional<Map<String, String>> channel) {
        Map<String, String> properties = new LinkedHashMap<>(common);
        channel.ifPresent(properties::putAll);
        return Map.copyOf(properties);
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    private static void requireReceiveTimeout(Duration timeout) {
        requirePositive(RECEIVE_TIMEOUT_PROPERTY, timeout);
        durationMillisInt(timeout, RECEIVE_TIMEOUT_PROPERTY);
    }

    private static void requireSendTimeout(Duration timeout) {
        requirePositive(SEND_TIMEOUT_PROPERTY, timeout);
        durationMillisInt(timeout, SEND_TIMEOUT_PROPERTY);
    }

    private static int durationMillisInt(Duration duration, String property) {
        long millis = durationMillis(duration);
        if (millis < 1 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(property + " must be between 1 ms and " + Integer.MAX_VALUE + " ms");
        }
        return (int) millis;
    }

    private static Map<String, Object> objectProperties(Map<String, String> source) {
        return Map.copyOf(new LinkedHashMap<>(source));
    }

    private static void requireNonBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNonBlank(String name, Optional<String> value) {
        value.ifPresent(it -> requireNonBlank(name, it));
    }

    private static void requirePositive(String name, Duration value) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        try {
            value.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " must be representable in nanoseconds", e);
        }
    }

    private static void requireNonNegative(String name, Duration value) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        try {
            value.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " must be representable in nanoseconds", e);
        }
    }

    private static void requireNonNullEntries(String name, Map<?, ?> values) {
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, name + " key");
            Objects.requireNonNull(value, name + " value");
        });
    }

    /**
     * Validates shared Pulsar connector configuration.
     */
    static final class BuilderDecorator implements Prototype.BuilderDecorator<PulsarConnectorConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(PulsarConnectorConfig.BuilderBase<?, ?> target) {
            requireNonBlank("name", target.name());
            requireNonBlank(SERVICE_URL_PROPERTY, target.serviceUrl());
            requireNonBlank(TOPIC_PROPERTY, target.topic());
            requireNonBlank(SCHEMA_PROVIDER_PROPERTY, target.schemaProvider());
            requireNonBlank(SUBSCRIPTION_NAME_PROPERTY, target.subscriptionName());
            requireNonNullEntries(CLIENT_PROPERTIES_PROPERTY, target.clientProperties());
            requireNonNullEntries(CONSUMER_PROPERTIES_PROPERTY, target.consumerProperties());
            requireNonNullEntries(PRODUCER_PROPERTIES_PROPERTY, target.producerProperties());
            requirePositive(RECEIVER_QUEUE_SIZE_PROPERTY, target.receiverQueueSize());
            requirePositive(MAX_MESSAGE_BYTES_PROPERTY, target.maxMessageBytes());
            requireReceiveTimeout(target.receiveTimeout());
            requireSendTimeout(target.sendTimeout());
            requirePositive(SETTLEMENT_TIMEOUT_PROPERTY, target.settlementTimeout());
            requireNonNegative(NEGATIVE_ACK_REDELIVERY_DELAY_PROPERTY, target.negativeAckRedeliveryDelay());
            requireNonNegative(CLOSE_TIMEOUT_PROPERTY, target.closeTimeout());
        }
    }

    /**
     * Validates incoming channel overrides.
     */
    static final class IncomingBuilderDecorator implements Prototype.BuilderDecorator<PulsarIncomingConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(PulsarIncomingConfig.BuilderBase<?, ?> target) {
            requireNonBlank("channel-name", target.channelName());
            requireNonBlank(SERVICE_URL_PROPERTY, target.serviceUrl());
            requireNonBlank(TOPIC_PROPERTY, target.topic());
            requireNonBlank(SCHEMA_PROVIDER_PROPERTY, target.schemaProvider());
            requireNonBlank(SUBSCRIPTION_NAME_PROPERTY, target.subscriptionName());
            target.clientProperties().ifPresent(values -> requireNonNullEntries(CLIENT_PROPERTIES_PROPERTY, values));
            target.consumerProperties().ifPresent(values -> requireNonNullEntries(CONSUMER_PROPERTIES_PROPERTY, values));
            target.receiverQueueSize().ifPresent(value -> requirePositive(RECEIVER_QUEUE_SIZE_PROPERTY, value));
            target.maxMessageBytes().ifPresent(value -> requirePositive(MAX_MESSAGE_BYTES_PROPERTY, value));
            target.receiveTimeout().ifPresent(PulsarConnectorConfigSupport::requireReceiveTimeout);
            target.settlementTimeout().ifPresent(value -> requirePositive(SETTLEMENT_TIMEOUT_PROPERTY, value));
            target.negativeAckRedeliveryDelay()
                    .ifPresent(value -> requireNonNegative(NEGATIVE_ACK_REDELIVERY_DELAY_PROPERTY, value));
            target.closeTimeout().ifPresent(value -> requireNonNegative(CLOSE_TIMEOUT_PROPERTY, value));
        }
    }

    /**
     * Validates outgoing channel overrides.
     */
    static final class OutgoingBuilderDecorator implements Prototype.BuilderDecorator<PulsarOutgoingConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(PulsarOutgoingConfig.BuilderBase<?, ?> target) {
            requireNonBlank("channel-name", target.channelName());
            requireNonBlank(SERVICE_URL_PROPERTY, target.serviceUrl());
            requireNonBlank(TOPIC_PROPERTY, target.topic());
            requireNonBlank(SCHEMA_PROVIDER_PROPERTY, target.schemaProvider());
            target.clientProperties().ifPresent(values -> requireNonNullEntries(CLIENT_PROPERTIES_PROPERTY, values));
            target.producerProperties().ifPresent(values -> requireNonNullEntries(PRODUCER_PROPERTIES_PROPERTY, values));
            target.sendTimeout().ifPresent(PulsarConnectorConfigSupport::requireSendTimeout);
            target.closeTimeout().ifPresent(value -> requireNonNegative(CLOSE_TIMEOUT_PROPERTY, value));
        }
    }

    record IncomingSettings(String channelName,
                            String serviceUrl,
                            Map<String, String> clientProperties,
                            String topic,
                            PulsarSchemaType schema,
                            Optional<String> schemaProvider,
                            Optional<String> subscriptionName,
                            PulsarSubscriptionType subscriptionType,
                            PulsarSubscriptionInitialPosition subscriptionInitialPosition,
                            boolean batchIndexAcknowledgmentEnabled,
                            int receiverQueueSize,
                            int maxMessageBytes,
                            Duration receiveTimeout,
                            Duration negativeAckRedeliveryDelay,
                            Duration settlementTimeout,
                            Duration closeTimeout,
                            Map<String, String> consumerProperties) {
    }

    record OutgoingSettings(String channelName,
                            String serviceUrl,
                            Map<String, String> clientProperties,
                            String topic,
                            PulsarSchemaType schema,
                            Optional<String> schemaProvider,
                            Duration sendTimeout,
                            Duration closeTimeout,
                            Map<String, String> producerProperties) {
    }
}
