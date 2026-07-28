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
import java.util.Map;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.extensions.messaging.ConnectorConfig;

/**
 * Kafka connector configuration.
 */
@Prototype.Blueprint
@Prototype.Configured
@Prototype.CustomMethods(KafkaConnectorConfigSupport.class)
interface KafkaConnectorConfigBlueprint extends ConnectorConfig {
    /**
     * Kafka bootstrap servers.
     *
     * @return comma-separated bootstrap servers
     */
    @Option.Required
    @Option.Configured(KafkaConnectorConfigSupport.BOOTSTRAP_SERVERS_PROPERTY)
    String bootstrapServers();

    /**
     * Kafka topic.
     *
     * @return Kafka topic
     */
    @Option.Required
    @Option.Configured(KafkaConnectorConfigSupport.TOPIC_PROPERTY)
    String topic();

    /**
     * Kafka consumer group identifier. The channel name is used when this is not configured.
     *
     * @return configured consumer group identifier
     */
    @Option.Configured(KafkaConnectorConfigSupport.GROUP_ID_PROPERTY)
    Optional<String> groupId();

    /**
     * Kafka key serializer class name.
     *
     * @return key serializer class name
     */
    @Option.Configured(KafkaConnectorConfigSupport.KEY_SERIALIZER_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_KEY_SERIALIZER)
    String keySerializer();

    /**
     * Kafka value serializer class name.
     *
     * @return value serializer class name
     */
    @Option.Configured(KafkaConnectorConfigSupport.VALUE_SERIALIZER_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_VALUE_SERIALIZER)
    String valueSerializer();

    /**
     * Kafka key deserializer class name.
     *
     * @return key deserializer class name
     */
    @Option.Configured(KafkaConnectorConfigSupport.KEY_DESERIALIZER_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_KEY_DESERIALIZER)
    String keyDeserializer();

    /**
     * Kafka value deserializer class name.
     *
     * @return value deserializer class name
     */
    @Option.Configured(KafkaConnectorConfigSupport.VALUE_DESERIALIZER_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_VALUE_DESERIALIZER)
    String valueDeserializer();

    /**
     * Kafka consumer offset reset policy.
     *
     * @return offset reset policy
     */
    @Option.Configured(KafkaConnectorConfigSupport.AUTO_OFFSET_RESET_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_AUTO_OFFSET_RESET)
    String autoOffsetReset();

    /**
     * Maximum duration to wait for records during one consumer poll.
     *
     * @return poll timeout
     */
    @Option.Configured(KafkaConnectorConfigSupport.POLL_TIMEOUT_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_POLL_TIMEOUT)
    Duration pollTimeout();

    /**
     * Maximum duration to wait for the future returned by a Kafka producer send.
     * Successful completion follows the producer {@code acks} configuration.
     *
     * @return send timeout
     */
    @Option.Configured(KafkaConnectorConfigSupport.SEND_TIMEOUT_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_SEND_TIMEOUT)
    Duration sendTimeout();

    /**
     * Maximum duration to wait for an active incoming delivery to become quiescent after interruption,
     * and while closing a Kafka client. If an incoming delivery does not finish within this duration,
     * connector close reports a failure and retains the delivery until it finishes.
     *
     * @return close timeout
     */
    @Option.Configured(KafkaConnectorConfigSupport.CLOSE_TIMEOUT_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_CLOSE_TIMEOUT)
    Duration closeTimeout();

    /**
     * Additional Kafka client properties not modeled as typed options.
     *
     * @return additional Kafka client properties
     */
    @Option.Configured("properties")
    @Option.Singular("property")
    Map<String, String> properties();

}
