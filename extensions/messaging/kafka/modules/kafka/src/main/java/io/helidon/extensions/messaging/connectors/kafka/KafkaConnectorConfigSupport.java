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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.helidon.builder.api.Prototype;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;

/**
 * Support methods and constants for {@link KafkaConnectorConfig}.
 */
final class KafkaConnectorConfigSupport {
    /**
     * Kafka connector name.
     */
    @Prototype.Constant
    static final String CONNECTOR_NAME = KafkaConnector.CONNECTOR_TYPE;

    /**
     * Config property for Kafka bootstrap servers.
     */
    @Prototype.Constant
    static final String BOOTSTRAP_SERVERS_PROPERTY = "bootstrap-servers";

    /**
     * Config property for the Kafka topic.
     */
    @Prototype.Constant
    static final String TOPIC_PROPERTY = "topic";

    /**
     * Config property for the Kafka consumer group identifier.
     */
    @Prototype.Constant
    static final String GROUP_ID_PROPERTY = "group-id";

    /**
     * Config property for the Kafka key serializer.
     */
    @Prototype.Constant
    static final String KEY_SERIALIZER_PROPERTY = "key-serializer";

    /**
     * Config property for the Kafka value serializer.
     */
    @Prototype.Constant
    static final String VALUE_SERIALIZER_PROPERTY = "value-serializer";

    /**
     * Config property for the Kafka key deserializer.
     */
    @Prototype.Constant
    static final String KEY_DESERIALIZER_PROPERTY = "key-deserializer";

    /**
     * Config property for the Kafka value deserializer.
     */
    @Prototype.Constant
    static final String VALUE_DESERIALIZER_PROPERTY = "value-deserializer";

    /**
     * Config property for the Kafka consumer offset reset policy.
     */
    @Prototype.Constant
    static final String AUTO_OFFSET_RESET_PROPERTY = "auto-offset-reset";

    /**
     * Config property for the consumer poll timeout.
     */
    @Prototype.Constant
    static final String POLL_TIMEOUT_PROPERTY = "poll-timeout";

    /**
     * Config property for the producer send timeout.
     */
    @Prototype.Constant
    static final String SEND_TIMEOUT_PROPERTY = "send-timeout";

    /**
     * Config property for the Kafka client close timeout.
     */
    @Prototype.Constant
    static final String CLOSE_TIMEOUT_PROPERTY = "close-timeout";

    /**
     * Kafka property controlling automatic offset commits.
     */
    @Prototype.Constant
    static final String ENABLE_AUTO_COMMIT_PROPERTY = ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG;

    /**
     * Default Kafka key serializer.
     */
    @Prototype.Constant
    static final String DEFAULT_KEY_SERIALIZER = "org.apache.kafka.common.serialization.StringSerializer";

    /**
     * Default Kafka value serializer.
     */
    @Prototype.Constant
    static final String DEFAULT_VALUE_SERIALIZER = "org.apache.kafka.common.serialization.StringSerializer";

    /**
     * Default Kafka key deserializer.
     */
    @Prototype.Constant
    static final String DEFAULT_KEY_DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer";

    /**
     * Default Kafka value deserializer.
     */
    @Prototype.Constant
    static final String DEFAULT_VALUE_DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer";

    /**
     * Default Kafka consumer offset reset policy.
     */
    @Prototype.Constant
    static final String DEFAULT_AUTO_OFFSET_RESET = "latest";

    /**
     * Default consumer poll timeout.
     */
    @Prototype.Constant
    static final String DEFAULT_POLL_TIMEOUT = "PT0.1S";

    /**
     * Default producer send timeout.
     */
    @Prototype.Constant
    static final String DEFAULT_SEND_TIMEOUT = "PT30S";

    /**
     * Default Kafka client close timeout.
     */
    @Prototype.Constant
    static final String DEFAULT_CLOSE_TIMEOUT = "PT10S";

    private KafkaConnectorConfigSupport() {
    }

    static Map<String, Object> producerProperties(OutgoingSettings config) {
        Map<String, Object> properties = new LinkedHashMap<>(config.properties());
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, String.join(",", config.bootstrapServers()));
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, config.keySerializer());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, config.valueSerializer());
        return Map.copyOf(properties);
    }

    static Map<String, Object> consumerProperties(IncomingSettings config) {
        return consumerProperties(config, Integer.MAX_VALUE);
    }

    static Map<String, Object> consumerProperties(IncomingSettings config,
                                                 int maxDeliveryMessages) {
        Map<String, Object> properties = new LinkedHashMap<>(config.properties());
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, String.join(",", config.bootstrapServers()));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, config.groupId());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, config.keyDeserializer());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, config.valueDeserializer());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.autoOffsetReset());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        bound(properties,
              ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
              maxDeliveryMessages,
              ConsumerConfig.DEFAULT_MAX_POLL_RECORDS);
        return Map.copyOf(properties);
    }

    static IncomingSettings incoming(KafkaConnectorConfig common, KafkaIncomingConfig channel) {
        return new IncomingSettings(channel.channelName(),
                                    channel.topic().or(common::topic)
                                            .orElseThrow(() -> missingTopic(channel.channelName())),
                                    channel.bootstrapServers().orElse(common.bootstrapServers()),
                                    channel.groupId().or(common::groupId).orElse(channel.channelName()),
                                    channel.keyDeserializer().orElse(common.keyDeserializer()),
                                    channel.valueDeserializer().orElse(common.valueDeserializer()),
                                    channel.autoOffsetReset().orElse(common.autoOffsetReset()),
                                    channel.pollTimeout().orElse(common.pollTimeout()),
                                    channel.closeTimeout().orElse(common.closeTimeout()),
                                    properties(common.properties(), channel.properties().orElseGet(Map::of)));
    }

    static OutgoingSettings outgoing(KafkaConnectorConfig common, KafkaOutgoingConfig channel) {
        return new OutgoingSettings(channel.topic().or(common::topic)
                                            .orElseThrow(() -> missingTopic(channel.channelName())),
                                   channel.bootstrapServers().orElse(common.bootstrapServers()),
                                   channel.keySerializer().orElse(common.keySerializer()),
                                   channel.valueSerializer().orElse(common.valueSerializer()),
                                   channel.sendTimeout().orElse(common.sendTimeout()),
                                   channel.closeTimeout().orElse(common.closeTimeout()),
                                   properties(common.properties(), channel.properties().orElseGet(Map::of)));
    }

    private static Map<String, String> properties(Map<String, String> common, Map<String, String> channel) {
        Map<String, String> properties = new LinkedHashMap<>(common);
        properties.putAll(channel);
        return Map.copyOf(properties);
    }

    private static IllegalArgumentException missingTopic(String channelName) {
        return new IllegalArgumentException("Kafka topic is required for channel " + channelName);
    }

    private static void requirePositive(String name, Duration value) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    private static void requirePollTimeout(Duration value) {
        requirePositive(POLL_TIMEOUT_PROPERTY, value);
        try {
            if (value.toMillis() == 0) {
                throw new IllegalArgumentException(POLL_TIMEOUT_PROPERTY + " must be at least 1 ms");
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(POLL_TIMEOUT_PROPERTY + " must be representable in milliseconds", e);
        }
    }

    private static void requireNanosecondRange(String name, Duration value) {
        try {
            value.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " must be representable in nanoseconds", e);
        }
    }

    private static void requireCloseTimeout(Duration value) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(CLOSE_TIMEOUT_PROPERTY + " must not be negative");
        }
        requireNanosecondRange(CLOSE_TIMEOUT_PROPERTY, value);
    }

    private static void requireSendTimeout(Duration value) {
        requirePositive(SEND_TIMEOUT_PROPERTY, value);
        requireNanosecondRange(SEND_TIMEOUT_PROPERTY, value);
    }

    private static void requireNonNullEntries(String name, Map<?, ?> values) {
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, name + " key");
            Objects.requireNonNull(value, name + " value");
        });
    }

    private static void bound(Map<String, Object> properties,
                              String name,
                              int runtimeLimit,
                              int kafkaDefault) {
        Object configured = properties.get(name);
        int current = configured == null ? kafkaDefault : Integer.parseInt(String.valueOf(configured));
        if (runtimeLimit < current) {
            properties.put(name, runtimeLimit);
        }
    }

    /**
     * Validates Kafka connector configuration.
     */
    static final class BuilderDecorator implements Prototype.BuilderDecorator<KafkaConnectorConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(KafkaConnectorConfig.BuilderBase<?, ?> target) {
            if (target.bootstrapServers().isEmpty()) {
                throw new IllegalArgumentException(BOOTSTRAP_SERVERS_PROPERTY + " must not be empty");
            }
            requireNonNullEntries("properties", target.properties());
            requirePollTimeout(target.pollTimeout());
            requireSendTimeout(target.sendTimeout());
            requireCloseTimeout(target.closeTimeout());
        }
    }

    static final class IncomingBuilderDecorator
            implements Prototype.BuilderDecorator<KafkaIncomingConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(KafkaIncomingConfig.BuilderBase<?, ?> target) {
            target.properties().ifPresent(properties -> requireNonNullEntries("properties", properties));
            target.pollTimeout().ifPresent(KafkaConnectorConfigSupport::requirePollTimeout);
            target.closeTimeout().ifPresent(KafkaConnectorConfigSupport::requireCloseTimeout);
        }
    }

    static final class OutgoingBuilderDecorator
            implements Prototype.BuilderDecorator<KafkaOutgoingConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(KafkaOutgoingConfig.BuilderBase<?, ?> target) {
            target.properties().ifPresent(properties -> requireNonNullEntries("properties", properties));
            target.sendTimeout().ifPresent(KafkaConnectorConfigSupport::requireSendTimeout);
            target.closeTimeout().ifPresent(KafkaConnectorConfigSupport::requireCloseTimeout);
        }
    }

    record IncomingSettings(String channelName,
                            String topic,
                            List<String> bootstrapServers,
                            String groupId,
                            String keyDeserializer,
                            String valueDeserializer,
                            String autoOffsetReset,
                            Duration pollTimeout,
                            Duration closeTimeout,
                            Map<String, String> properties) {
    }

    record OutgoingSettings(String topic,
                            List<String> bootstrapServers,
                            String keySerializer,
                            String valueSerializer,
                            Duration sendTimeout,
                            Duration closeTimeout,
                            Map<String, String> properties) {
    }
}
