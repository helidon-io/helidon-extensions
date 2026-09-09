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

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.messaging.spi.IncomingChannel;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.OutgoingChannel;

/**
 * Configured Kafka connector that creates incoming and outgoing channel connections.
 */
@Api.Preview
public final class KafkaConnector implements MessagingConnector, RuntimeType.Api<KafkaConnectorConfig> {
    private final KafkaConnectorConfig config;
    private final KafkaIncomingChannel incomingFactory = new KafkaIncomingChannel();
    private final KafkaOutgoingChannel outgoingFactory = new KafkaOutgoingChannel();

    private KafkaConnector(KafkaConnectorConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Create a Kafka connector from its configuration.
     *
     * @param config connector configuration
     * @return configured connector
     */
    public static KafkaConnector create(KafkaConnectorConfig config) {
        return new KafkaConnector(config);
    }

    /**
     * Create a Kafka connector from configuration.
     *
     * @param config connector configuration node
     * @return configured connector
     */
    public static KafkaConnector create(Config config) {
        return builder().config(Objects.requireNonNull(config)).build();
    }

    /**
     * Create a Kafka connector by updating a builder.
     *
     * @param consumer builder consumer
     * @return configured connector
     */
    public static KafkaConnector create(Consumer<KafkaConnectorConfig.Builder> consumer) {
        return builder().update(Objects.requireNonNull(consumer)).build();
    }

    /**
     * Create a Kafka connector builder.
     *
     * @return connector builder
     */
    public static KafkaConnectorConfig.Builder builder() {
        return KafkaConnectorConfig.builder();
    }

    @Override
    public KafkaConnectorConfig prototype() {
        return config;
    }

    @Override
    public String type() {
        return KafkaConnectorProvider.CONNECTOR_TYPE;
    }

    @Override
    public Optional<IncomingChannel> incoming(Config channelConfig) {
        return Optional.of(incoming(KafkaIncomingConfig.create(Objects.requireNonNull(channelConfig))));
    }

    /**
     * Create an unstarted incoming Kafka channel.
     *
     * @param channelConfig channel configuration
     * @return incoming channel
     */
    public IncomingChannel incoming(KafkaIncomingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        return incomingFactory.createIncomingChannel(KafkaConnectorConfigSupport.incoming(config, channelConfig));
    }

    @Override
    public Optional<OutgoingChannel> outgoing(Config channelConfig) {
        return Optional.of(outgoing(KafkaOutgoingConfig.create(Objects.requireNonNull(channelConfig))));
    }

    /**
     * Create an unstarted outgoing Kafka channel.
     *
     * @param channelConfig channel configuration
     * @return outgoing channel
     */
    public OutgoingChannel outgoing(KafkaOutgoingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        return outgoingFactory.createOutgoingChannel(KafkaConnectorConfigSupport.outgoing(config, channelConfig));
    }
}
