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

import java.util.Objects;

import io.helidon.config.Config;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.IncomingConnectorProvider;
import io.helidon.extensions.messaging.OutgoingConnector;
import io.helidon.extensions.messaging.OutgoingConnectorProvider;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;

import jakarta.jms.ConnectionFactory;

/**
 * Stateless JMS connector provider.
 */
@Service.Singleton
public final class JmsConnectorProvider
        implements IncomingConnectorProvider, OutgoingConnectorProvider {
    /**
     * JMS connector type used in messaging configuration.
     */
    public static final String CONNECTOR_TYPE = "jms";

    private final JmsConnectionFactoryResolver connectionFactoryResolver;

    @Service.Inject
    JmsConnectorProvider(ServiceRegistry registry) {
        this(new JmsResourceResolver(registry));
    }

    /**
     * Create an imperative provider using one connection factory for every binding.
     *
     * @param connectionFactory JMS connection factory
     */
    public JmsConnectorProvider(ConnectionFactory connectionFactory) {
        this(fixedResolver(connectionFactory));
    }

    JmsConnectorProvider(JmsConnectionFactoryResolver connectionFactoryResolver) {
        this.connectionFactoryResolver = Objects.requireNonNull(connectionFactoryResolver);
    }

    @Override
    public String connectorType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public IncomingConnector createIncomingConnector(Config config) {
        return createIncomingConnector(JmsConnectorConfig.create(Objects.requireNonNull(config)));
    }

    /**
     * Create one unstarted incoming JMS connector from typed configuration.
     *
     * @param config typed JMS configuration
     * @return incoming connector
     */
    public IncomingConnector createIncomingConnector(JmsConnectorConfig config) {
        requireDirection(config, ConnectorConfig.Direction.INCOMING);
        return JmsIncomingConnector.create(config, connectionFactoryResolver);
    }

    @Override
    public OutgoingConnector createOutgoingConnector(Config config) {
        return createOutgoingConnector(JmsConnectorConfig.create(Objects.requireNonNull(config)));
    }

    /**
     * Create one unstarted outgoing JMS connector from typed configuration.
     *
     * @param config typed JMS configuration
     * @return outgoing connector
     */
    public OutgoingConnector createOutgoingConnector(JmsConnectorConfig config) {
        requireDirection(config, ConnectorConfig.Direction.OUTGOING);
        return JmsOutgoingConnector.create(config, connectionFactoryResolver);
    }

    private static void requireDirection(JmsConnectorConfig config, ConnectorConfig.Direction expected) {
        Objects.requireNonNull(config);
        if (config.direction() != expected) {
            throw new IllegalArgumentException("JMS connector configuration for channel " + config.channel()
                                                       + " has direction " + config.direction()
                                                       + ", expected " + expected);
        }
    }

    private static JmsConnectionFactoryResolver fixedResolver(ConnectionFactory connectionFactory) {
        ConnectionFactory actual = Objects.requireNonNull(connectionFactory);
        return config -> actual;
    }
}
