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

/**
 * Kafka incoming channel configuration. Unspecified client options use the configured connector defaults.
 */
@Api.Preview
@Prototype.Blueprint(decorator = KafkaConnectorConfigSupport.IncomingBuilderDecorator.class)
@Prototype.Configured
interface KafkaIncomingConfigBlueprint {
    /**
     * Logical messaging channel name.
     *
     * @return channel name
     */
    @Option.Required
    @Option.Configured
    String channelName();

    /**
     * Kafka topic, overriding the connector default.
     *
     * @return topic
     */
    @Option.Configured
    Optional<String> topic();

    /**
     * Channel-specific bootstrap servers.
     *
     * @return configured bootstrapServers
     */
    @Option.Configured(KafkaConnectorConfigSupport.BOOTSTRAP_SERVERS_PROPERTY)
    Optional<String> bootstrapServers();

    /**
     * Channel-specific client close timeout.
     *
     * @return configured closeTimeout
     */
    @Option.Configured(KafkaConnectorConfigSupport.CLOSE_TIMEOUT_PROPERTY)
    Optional<Duration> closeTimeout();

    /**
     * Channel-specific consumer group identifier; defaults to the channel name.
     *
     * @return configured groupId
     */
    @Option.Configured(KafkaConnectorConfigSupport.GROUP_ID_PROPERTY)
    Optional<String> groupId();

    /**
     * Channel-specific key deserializer class name.
     *
     * @return configured keyDeserializer
     */
    @Option.Configured(KafkaConnectorConfigSupport.KEY_DESERIALIZER_PROPERTY)
    Optional<String> keyDeserializer();

    /**
     * Channel-specific value deserializer class name.
     *
     * @return configured valueDeserializer
     */
    @Option.Configured(KafkaConnectorConfigSupport.VALUE_DESERIALIZER_PROPERTY)
    Optional<String> valueDeserializer();

    /**
     * Channel-specific consumer offset reset policy.
     *
     * @return configured autoOffsetReset
     */
    @Option.Configured(KafkaConnectorConfigSupport.AUTO_OFFSET_RESET_PROPERTY)
    Optional<String> autoOffsetReset();

    /**
     * Channel-specific consumer poll timeout.
     *
     * @return configured pollTimeout
     */
    @Option.Configured(KafkaConnectorConfigSupport.POLL_TIMEOUT_PROPERTY)
    Optional<Duration> pollTimeout();

    /**
     * Additional Kafka client properties, overriding properties from the connector.
     * These properties are confidential because they may contain credentials.
     *
     * @return additional client properties
     */
    @Option.Configured
    @Option.Confidential
    @Option.Singular("property")
    Map<String, String> properties();
}
