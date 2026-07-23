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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.helidon.builder.api.Prototype;
import io.helidon.extensions.messaging.ConnectorConfig;

/**
 * Support methods and constants for {@link KafkaConnectorConfig}.
 */
final class KafkaConnectorConfigSupport {
    /**
     * Kafka connector name.
     */
    @Prototype.Constant
    static final String CONNECTOR_NAME = "kafka";

    /**
     * Config property for Kafka bootstrap servers.
     */
    @Prototype.Constant
    static final String BOOTSTRAP_SERVERS_PROPERTY = "bootstrap.servers";

    /**
     * Config property for the Kafka topic.
     */
    @Prototype.Constant
    static final String TOPIC_PROPERTY = "topic";

    /**
     * Config property for the Kafka consumer group identifier.
     */
    @Prototype.Constant
    static final String GROUP_ID_PROPERTY = "group.id";

    /**
     * Config property for the Kafka key serializer.
     */
    @Prototype.Constant
    static final String KEY_SERIALIZER_PROPERTY = "key.serializer";

    /**
     * Config property for the Kafka value serializer.
     */
    @Prototype.Constant
    static final String VALUE_SERIALIZER_PROPERTY = "value.serializer";

    /**
     * Config property for the Kafka key deserializer.
     */
    @Prototype.Constant
    static final String KEY_DESERIALIZER_PROPERTY = "key.deserializer";

    /**
     * Config property for the Kafka value deserializer.
     */
    @Prototype.Constant
    static final String VALUE_DESERIALIZER_PROPERTY = "value.deserializer";

    /**
     * Config property for the Kafka consumer offset reset policy.
     */
    @Prototype.Constant
    static final String AUTO_OFFSET_RESET_PROPERTY = "auto.offset.reset";

    /**
     * Config property for the consumer poll timeout.
     */
    @Prototype.Constant
    static final String POLL_TIMEOUT_PROPERTY = "poll.timeout";

    /**
     * Config property for the delay before redelivering a failed incoming batch.
     */
    @Prototype.Constant
    static final String REDELIVERY_DELAY_PROPERTY = "redelivery.delay";

    /**
     * Config property for the producer send timeout.
     */
    @Prototype.Constant
    static final String SEND_TIMEOUT_PROPERTY = "send.timeout";

    /**
     * Config property for the Kafka client close timeout.
     */
    @Prototype.Constant
    static final String CLOSE_TIMEOUT_PROPERTY = "close.timeout";

    /**
     * Kafka property controlling automatic offset commits.
     */
    @Prototype.Constant
    static final String ENABLE_AUTO_COMMIT_PROPERTY = "enable.auto.commit";

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
     * Default delay before redelivering a failed incoming batch.
     */
    @Prototype.Constant
    static final String DEFAULT_REDELIVERY_DELAY = "PT1S";

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

    private static final Set<String> CONNECTOR_PROPERTIES = Set.of(ConnectorConfig.CONNECTOR_ATTRIBUTE,
                                                                   ConnectorConfig.CHANNEL_NAME_ATTRIBUTE,
                                                                   "direction",
                                                                   TOPIC_PROPERTY,
                                                                   POLL_TIMEOUT_PROPERTY,
                                                                   REDELIVERY_DELAY_PROPERTY,
                                                                   SEND_TIMEOUT_PROPERTY,
                                                                   CLOSE_TIMEOUT_PROPERTY);

    private KafkaConnectorConfigSupport() {
    }

    static Map<String, Object> producerProperties(KafkaConnectorConfig config) {
        Map<String, Object> properties = kafkaProperties(config);
        properties.put(BOOTSTRAP_SERVERS_PROPERTY, config.bootstrapServers());
        properties.put(KEY_SERIALIZER_PROPERTY, config.keySerializer());
        properties.put(VALUE_SERIALIZER_PROPERTY, config.valueSerializer());
        return Map.copyOf(properties);
    }

    static Map<String, Object> consumerProperties(KafkaConnectorConfig config) {
        Map<String, Object> properties = kafkaProperties(config);
        properties.put(BOOTSTRAP_SERVERS_PROPERTY, config.bootstrapServers());
        properties.put(GROUP_ID_PROPERTY, config.groupId().orElse(config.channel()));
        properties.put(KEY_DESERIALIZER_PROPERTY, config.keyDeserializer());
        properties.put(VALUE_DESERIALIZER_PROPERTY, config.valueDeserializer());
        properties.put(AUTO_OFFSET_RESET_PROPERTY, config.autoOffsetReset());
        properties.put(ENABLE_AUTO_COMMIT_PROPERTY, false);
        return Map.copyOf(properties);
    }

    private static Map<String, Object> kafkaProperties(KafkaConnectorConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>(config.properties());
        properties.keySet().removeAll(CONNECTOR_PROPERTIES);
        return properties;
    }
}
