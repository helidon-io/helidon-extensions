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
    static final String CONNECTOR_NAME = PulsarConnectorProvider.CONNECTOR_TYPE;
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

    static PulsarClient createClient(PulsarConnectorConfig config) throws PulsarClientException {
        return PulsarClient.builder()
                .loadConf(objectProperties(config.clientProperties()))
                .serviceUrl(config.serviceUrl())
                .build();
    }

    static Consumer<Object> createConsumer(PulsarClient client,
                                           PulsarConnectorConfig config,
                                           PulsarSchemaResolver.ResolvedSchema schema,
                                           int maxDeliveryMessages) throws PulsarClientException {
        int queueSize = Math.min(config.receiverQueueSize(), maxDeliveryMessages);
        ConsumerBuilder<Object> builder = client.newConsumer(schema.schema())
                .loadConf(objectProperties(config.consumerProperties()))
                .topic(config.topic())
                .subscriptionName(config.subscriptionName().orElse(config.channel()))
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
                                                   PulsarConnectorConfig config,
                                                   PulsarSchemaResolver.ResolvedSchema schema) {
        return client.newProducer(schema.schema())
                .loadConf(objectProperties(config.producerProperties()))
                .topic(config.topic())
                .sendTimeout(durationMillisInt(config.sendTimeout(), SEND_TIMEOUT_PROPERTY), TimeUnit.MILLISECONDS);
    }

    static int receiveTimeoutMillis(PulsarConnectorConfig config) {
        return durationMillisInt(config.receiveTimeout(), RECEIVE_TIMEOUT_PROPERTY);
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

    /**
     * Validates Pulsar connector configuration.
     */
    static final class BuilderDecorator implements Prototype.BuilderDecorator<PulsarConnectorConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(PulsarConnectorConfig.BuilderBase<?, ?> target) {
            requireNonBlank(SERVICE_URL_PROPERTY, target.serviceUrl());
            requireNonBlank(TOPIC_PROPERTY, target.topic());
            requireNonBlank(SCHEMA_PROVIDER_PROPERTY, target.schemaProvider());
            requireNonBlank(SUBSCRIPTION_NAME_PROPERTY, target.subscriptionName());
            if (target.receiverQueueSize() < 1) {
                throw new IllegalArgumentException(RECEIVER_QUEUE_SIZE_PROPERTY + " must be greater than zero");
            }
            if (target.maxMessageBytes() < 1) {
                throw new IllegalArgumentException(MAX_MESSAGE_BYTES_PROPERTY + " must be greater than zero");
            }
            requirePositive(RECEIVE_TIMEOUT_PROPERTY, target.receiveTimeout());
            requirePositive(SEND_TIMEOUT_PROPERTY, target.sendTimeout());
            requirePositive(SETTLEMENT_TIMEOUT_PROPERTY, target.settlementTimeout());
            durationMillisInt(target.receiveTimeout(), RECEIVE_TIMEOUT_PROPERTY);
            durationMillisInt(target.sendTimeout(), SEND_TIMEOUT_PROPERTY);
            if (target.negativeAckRedeliveryDelay().isNegative()) {
                throw new IllegalArgumentException(NEGATIVE_ACK_REDELIVERY_DELAY_PROPERTY + " must not be negative");
            }
            try {
                target.negativeAckRedeliveryDelay().toNanos();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(NEGATIVE_ACK_REDELIVERY_DELAY_PROPERTY
                                                           + " must be representable in nanoseconds", e);
            }
            if (target.closeTimeout().isNegative()) {
                throw new IllegalArgumentException(CLOSE_TIMEOUT_PROPERTY + " must not be negative");
            }
            try {
                target.closeTimeout().toNanos();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(CLOSE_TIMEOUT_PROPERTY + " must be representable in nanoseconds", e);
            }
        }
    }
}
