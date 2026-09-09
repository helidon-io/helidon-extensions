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
import io.helidon.common.Api;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.messaging.spi.MessagingConnectorProviderConfig;

/**
 * Kafka connector configuration.
 */
@Api.Preview
@Prototype.Blueprint(decorator = KafkaConnectorConfigSupport.BuilderDecorator.class)
@Prototype.Sealed
@Prototype.Configured(value = KafkaConnectorProvider.CONNECTOR_TYPE, root = false)
@Prototype.Provides(MessagingConnectorProvider.class)
@Prototype.CustomMethods(KafkaConnectorConfigSupport.class)
interface KafkaConnectorConfigBlueprint extends MessagingConnectorProviderConfig, Prototype.Factory<KafkaConnector> {
    /**
     * Kafka bootstrap servers.
     *
     * @return comma-separated bootstrap servers
     */
    @Option.Required
    @Option.Configured(KafkaConnectorConfigSupport.BOOTSTRAP_SERVERS_PROPERTY)
    String bootstrapServers();

    /**
     * Default Kafka topic. Each channel must supply a topic when this is not configured.
     *
     * @return default topic
     */
    @Option.Configured(KafkaConnectorConfigSupport.TOPIC_PROPERTY)
    Optional<String> topic();

    /**
     * Default Kafka consumer group identifier. Each incoming channel uses its channel name when no group is configured.
     *
     * @return default consumer group identifier
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
     * Maximum duration to wait for records during one consumer poll. Must be at least one millisecond and representable
     * in milliseconds.
     *
     * @return poll timeout
     */
    @Option.Configured(KafkaConnectorConfigSupport.POLL_TIMEOUT_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_POLL_TIMEOUT)
    Duration pollTimeout();

    /**
     * Maximum duration to wait for the future returned by a Kafka producer send.
     * Successful completion follows the producer {@code acks} configuration.
     * Must be positive and representable in nanoseconds.
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
     * Must not be negative and must be representable in nanoseconds. Zero requests shutdown without waiting.
     *
     * @return close timeout
     */
    @Option.Configured(KafkaConnectorConfigSupport.CLOSE_TIMEOUT_PROPERTY)
    @Option.Default(KafkaConnectorConfigSupport.DEFAULT_CLOSE_TIMEOUT)
    Duration closeTimeout();

    /**
     * Additional Kafka client properties not modeled as typed options. These properties are treated as confidential
     * because they may contain credentials.
     *
     * @return additional Kafka client properties
     */
    @Option.Configured("properties")
    @Option.Confidential
    @Option.Singular("property")
    Map<String, String> properties();

}
