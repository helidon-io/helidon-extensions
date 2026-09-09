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
 * Configured Apache Pulsar connector that creates incoming and outgoing channels.
 */
@Api.Preview
public final class PulsarConnector implements MessagingConnector, RuntimeType.Api<PulsarConnectorConfig> {
    private final PulsarConnectorConfig config;
    private final PulsarIncomingChannel incomingFactory = new PulsarIncomingChannel();
    private final PulsarOutgoingChannel outgoingFactory = new PulsarOutgoingChannel();

    private PulsarConnector(PulsarConnectorConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Create a connector from its configuration.
     *
     * @param config connector configuration
     * @return configured connector
     */
    public static PulsarConnector create(PulsarConnectorConfig config) {
        return new PulsarConnector(config);
    }

    /**
     * Create a connector from a configuration node.
     *
     * @param config connector configuration node
     * @return configured connector
     */
    public static PulsarConnector create(Config config) {
        return builder().config(Objects.requireNonNull(config)).build();
    }

    /**
     * Create a connector by updating a builder.
     *
     * @param consumer builder consumer
     * @return configured connector
     */
    public static PulsarConnector create(Consumer<PulsarConnectorConfig.Builder> consumer) {
        return builder().update(Objects.requireNonNull(consumer)).build();
    }

    /**
     * Create a connector builder.
     *
     * @return connector builder
     */
    public static PulsarConnectorConfig.Builder builder() {
        return PulsarConnectorConfig.builder();
    }

    @Override
    public PulsarConnectorConfig prototype() {
        return config;
    }

    @Override
    public String type() {
        return PulsarConnectorProvider.CONNECTOR_TYPE;
    }

    @Override
    public Optional<IncomingChannel> incoming(Config channelConfig) {
        return Optional.of(incoming(PulsarIncomingConfig.create(Objects.requireNonNull(channelConfig))));
    }

    /**
     * Create an unstarted incoming channel.
     *
     * @param channelConfig incoming channel configuration
     * @return incoming channel
     */
    public IncomingChannel incoming(PulsarIncomingConfig channelConfig) {
        return incomingFactory.createIncomingChannel(config, Objects.requireNonNull(channelConfig));
    }

    @Override
    public Optional<OutgoingChannel> outgoing(Config channelConfig) {
        return Optional.of(outgoing(PulsarOutgoingConfig.create(Objects.requireNonNull(channelConfig))));
    }

    /**
     * Create an unstarted outgoing channel.
     *
     * @param channelConfig outgoing channel configuration
     * @return outgoing channel
     */
    public OutgoingChannel outgoing(PulsarOutgoingConfig channelConfig) {
        return outgoingFactory.createOutgoingChannel(config, Objects.requireNonNull(channelConfig));
    }
}
