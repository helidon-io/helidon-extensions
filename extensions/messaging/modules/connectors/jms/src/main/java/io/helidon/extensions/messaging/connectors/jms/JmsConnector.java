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

package io.helidon.extensions.messaging.connectors.jms;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.messaging.spi.IncomingChannel;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.OutgoingChannel;
import io.helidon.service.registry.GlobalServiceRegistry;

/**
 * Configured JMS connector that creates incoming and outgoing channel connections.
 */
@Api.Preview
public final class JmsConnector implements MessagingConnector, RuntimeType.Api<JmsConnectorConfig> {
    private final JmsConnectorConfig config;
    private final JmsConnectionFactoryResolver resolver;

    private JmsConnector(JmsConnectorConfig config, JmsConnectionFactoryResolver resolver) {
        this.config = Objects.requireNonNull(config);
        this.resolver = Objects.requireNonNull(resolver);
    }

    /**
     * Create a JMS connector from its configuration.
     *
     * @param config connector configuration
     * @return configured connector
     */
    public static JmsConnector create(JmsConnectorConfig config) {
        return new JmsConnector(config, channel -> new JmsResourceResolver(GlobalServiceRegistry.registry()).resolve(channel));
    }

    /**
     * Create a JMS connector from a configuration node.
     *
     * @param config connector configuration node
     * @return configured connector
     */
    public static JmsConnector create(Config config) {
        return builder().config(Objects.requireNonNull(config)).build();
    }

    /**
     * Create a JMS connector by updating a builder.
     *
     * @param consumer builder consumer
     * @return configured connector
     */
    public static JmsConnector create(Consumer<JmsConnectorConfig.Builder> consumer) {
        return builder().update(Objects.requireNonNull(consumer)).build();
    }

    /**
     * Create a JMS connector builder.
     *
     * @return connector builder
     */
    public static JmsConnectorConfig.Builder builder() {
        return JmsConnectorConfig.builder();
    }

    static JmsConnector create(JmsConnectorConfig config, JmsConnectionFactoryResolver resolver) {
        return new JmsConnector(config, resolver);
    }

    @Override
    public JmsConnectorConfig prototype() {
        return config;
    }

    @Override
    public String type() {
        return JmsConnectorProvider.CONNECTOR_TYPE;
    }

    @Override
    public Optional<IncomingChannel> incoming(Config channelConfig) {
        return Optional.of(incoming(JmsIncomingConfig.create(Objects.requireNonNull(channelConfig))));
    }

    /**
     * Create an unstarted incoming JMS channel, inheriting absent options from this connector.
     *
     * @param channelConfig channel configuration
     * @return incoming channel
     */
    public IncomingChannel incoming(JmsIncomingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        var target = runtimeConfig(channelConfig, channelConfig.channelName());
        config.messageSelector().ifPresent(target::messageSelector);
        config.durable().ifPresent(target::durable);
        config.subscriptionName().ifPresent(target::subscriptionName);
        config.noLocal().ifPresent(target::noLocal);
        config.maxBodyBytes().ifPresent(target::maxBodyBytes);
        config.receiveTimeout().ifPresent(target::receiveTimeout);
        channelConfig.messageSelector().ifPresent(target::messageSelector);
        channelConfig.durable().ifPresent(target::durable);
        channelConfig.subscriptionName().ifPresent(target::subscriptionName);
        channelConfig.noLocal().ifPresent(target::noLocal);
        channelConfig.maxBodyBytes().ifPresent(target::maxBodyBytes);
        channelConfig.receiveTimeout().ifPresent(target::receiveTimeout);
        return JmsIncomingChannel.create(target.build(), resolver(channelConfig));
    }

    @Override
    public Optional<OutgoingChannel> outgoing(Config channelConfig) {
        return Optional.of(outgoing(JmsOutgoingConfig.create(Objects.requireNonNull(channelConfig))));
    }

    /**
     * Create an unstarted outgoing JMS channel, inheriting absent options from this connector.
     *
     * @param channelConfig channel configuration
     * @return outgoing channel
     */
    public OutgoingChannel outgoing(JmsOutgoingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        return JmsOutgoingChannel.create(runtimeConfig(channelConfig, channelConfig.channelName()).build(),
                                         resolver(channelConfig));
    }

    private static void apply(JmsRuntimeConfig.Builder target, JmsChannelOptions source) {
        if (source.connectionFactoryName().isPresent() || source.jndiConnectionFactory().isPresent()
                || source.connectionFactory().isPresent()) {
            target.clearConnectionFactory().clearJndiConnectionFactory();
        }
        if (source.destination().isPresent() || source.jndiDestination().isPresent()) {
            target.clearDestination().clearJndiDestination();
        }
        source.connectionFactoryName().ifPresent(target::connectionFactory);
        source.jndiConnectionFactory().ifPresent(target::jndiConnectionFactory);
        source.jndiDestination().ifPresent(target::jndiDestination);
        target.addJndiEnvironment(source.jndiEnvironment());
        source.destination().ifPresent(target::destination);
        source.destinationType().ifPresent(target::destinationType);
        source.username().ifPresent(target::username);
        source.password().ifPresent(password -> {
            try {
                target.password(password);
            } finally {
                Arrays.fill(password, '\0');
            }
        });
        source.clientId().ifPresent(target::clientId);
        source.transacted().ifPresent(target::transacted);
        source.allowObjectMessages().ifPresent(target::allowObjectMessages);
        source.closeTimeout().ifPresent(target::closeTimeout);
        source.reconnectInitialDelay().ifPresent(target::reconnectInitialDelay);
        source.reconnectMaxDelay().ifPresent(target::reconnectMaxDelay);
        source.reconnectJitter().ifPresent(target::reconnectJitter);
    }

    private JmsRuntimeConfig.Builder runtimeConfig(JmsChannelConfig channelConfig, String channelName) {
        var target = JmsRuntimeConfig.builder().channelName(channelName);
        apply(target, config);
        apply(target, channelConfig);
        return target;
    }

    private JmsConnectionFactoryResolver resolver(JmsChannelConfig channelConfig) {
        if (channelConfig.connectionFactory().isPresent()) {
            var factory = channelConfig.connectionFactory().orElseThrow();
            return _ -> factory;
        }
        if (channelConfig.connectionFactoryName().isEmpty() && channelConfig.jndiConnectionFactory().isEmpty()
                && config.connectionFactory().isPresent()) {
            var factory = config.connectionFactory().orElseThrow();
            return _ -> factory;
        }
        return resolver;
    }
}
