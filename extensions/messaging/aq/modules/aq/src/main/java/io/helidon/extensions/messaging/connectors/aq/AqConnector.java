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

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.messaging.spi.IncomingChannel;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingIncomingConfig;
import io.helidon.messaging.spi.MessagingOutgoingConfig;
import io.helidon.messaging.spi.OutgoingChannel;
import io.helidon.service.registry.GlobalServiceRegistry;

/**
 * Standalone Oracle AQ connector for Helidon Messaging.
 * Channels own their connections and sessions. Application data sources and pools remain application-owned.
 */
@Api.Incubating
public final class AqConnector implements MessagingConnector, RuntimeType.Api<AqConnectorConfig> {
    /**
     * Connector type used in Messaging configuration.
     */
    public static final String CONNECTOR_TYPE = "helidon-aq";

    private final AqConnectorConfig config;
    private final AqResourceResolver resolver;

    private AqConnector(AqConnectorConfig config, AqResourceResolver resolver) {
        this.config = Objects.requireNonNull(config);
        this.resolver = Objects.requireNonNull(resolver);
    }

    /**
     * Create a connector using a built configuration.
     *
     * @param config connector configuration
     * @return connector
     */
    public static AqConnector create(AqConnectorConfig config) {
        return new AqConnector(config, new AqResourceResolver(GlobalServiceRegistry::registry));
    }

    /**
     * Create a connector from a configuration node.
     *
     * @param config connector configuration node
     * @return connector
     */
    public static AqConnector create(Config config) {
        return builder().config(Objects.requireNonNull(config)).build();
    }

    /**
     * Create a connector using a builder consumer.
     *
     * @param consumer builder consumer
     * @return connector
     */
    public static AqConnector create(Consumer<AqConnectorConfig.Builder> consumer) {
        return builder().update(Objects.requireNonNull(consumer)).build();
    }

    /**
     * Create a connector builder.
     *
     * @return connector builder
     */
    public static AqConnectorConfig.Builder builder() {
        return AqConnectorConfig.builder();
    }

    static AqConnector create(AqConnectorConfig config, AqResourceResolver resolver) {
        return new AqConnector(config, resolver);
    }

    @Override
    public AqConnectorConfig prototype() {
        return config;
    }

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public Optional<IncomingChannel> incoming(MessagingIncomingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        if (channelConfig instanceof AqIncomingConfig aqConfig) {
            return Optional.of(incoming(aqConfig));
        }
        var builder = AqIncomingConfig.builder();
        channelConfig.config().ifPresent(builder::config);
        return Optional.of(incoming(builder.from(channelConfig).build()));
    }

    /**
     * Create an unstarted incoming channel using connector defaults.
     *
     * @param channelConfig channel configuration
     * @return incoming channel
     */
    public IncomingChannel incoming(AqIncomingConfig channelConfig) {
        return new AqIncomingChannel(AqRuntimeConfig.incoming(config, Objects.requireNonNull(channelConfig)), resolver);
    }

    @Override
    public Optional<OutgoingChannel> outgoing(MessagingOutgoingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        if (channelConfig instanceof AqOutgoingConfig aqConfig) {
            return Optional.of(outgoing(aqConfig));
        }
        var builder = AqOutgoingConfig.builder();
        channelConfig.config().ifPresent(builder::config);
        return Optional.of(outgoing(builder.from(channelConfig).build()));
    }

    /**
     * Create an unstarted outgoing channel using connector defaults.
     *
     * @param channelConfig channel configuration
     * @return outgoing channel
     */
    public OutgoingChannel outgoing(AqOutgoingConfig channelConfig) {
        return new AqOutgoingChannel(AqRuntimeConfig.outgoing(config, Objects.requireNonNull(channelConfig)), resolver);
    }
}
