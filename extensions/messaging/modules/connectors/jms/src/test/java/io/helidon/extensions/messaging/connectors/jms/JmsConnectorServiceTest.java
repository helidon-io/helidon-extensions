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

import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorProvider;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.IncomingConnectorProvider;
import io.helidon.extensions.messaging.OutgoingConnector;
import io.helidon.extensions.messaging.OutgoingConnectorProvider;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

class JmsConnectorServiceTest {
    @Test
    void testConnectorsAreDiscoveredByServiceRegistry() {
        ServiceRegistryManager registryManager = ServiceRegistryManager.create();
        try {
            ServiceRegistry registry = registryManager.registry();

            ConnectorProvider provider = registry.get(ConnectorProvider.class);

            assertThat(provider, instanceOf(JmsConnectorProvider.class));
            assertThat(provider, instanceOf(IncomingConnectorProvider.class));
            assertThat(provider, instanceOf(OutgoingConnectorProvider.class));
            assertThat(registry.get(IncomingConnectorProvider.class), sameInstance(provider));
            assertThat(registry.get(OutgoingConnectorProvider.class), sameInstance(provider));
            assertThat(provider instanceof AutoCloseable, is(false));

            JmsConnectorProvider jmsProvider = (JmsConnectorProvider) provider;
            IncomingConnector firstIncoming = jmsProvider.createIncomingConnector(config(ConnectorConfig.Direction.INCOMING));
            IncomingConnector secondIncoming = jmsProvider.createIncomingConnector(config(ConnectorConfig.Direction.INCOMING));
            OutgoingConnector firstOutgoing = jmsProvider.createOutgoingConnector(config(ConnectorConfig.Direction.OUTGOING));
            OutgoingConnector secondOutgoing = jmsProvider.createOutgoingConnector(config(ConnectorConfig.Direction.OUTGOING));

            assertThat(firstIncoming, not(sameInstance(secondIncoming)));
            assertThat(firstOutgoing, not(sameInstance(secondOutgoing)));
        } finally {
            registryManager.shutdown();
        }
    }

    @Test
    void testContractInstanceIsResolvedAsTheDefaultConnectionFactory() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        ServiceRegistryConfig registryConfig = ServiceRegistryConfig.builder()
                .putContractInstance(ConnectionFactory.class, factory)
                .build();
        ServiceRegistryManager registryManager = ServiceRegistryManager.create(registryConfig);
        try {
            JmsConnectorConfig config = config(ConnectorConfig.Direction.INCOMING);

            assertThat(new JmsResourceResolver(registryManager.registry()).resolve(config), sameInstance(factory));
        } finally {
            registryManager.shutdown();
        }
    }

    private static JmsConnectorConfig config(ConnectorConfig.Direction direction) {
        return JmsConnectorConfig.builder()
                .direction(direction)
                .channel("orders")
                .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                .destination("orders")
                .build();
    }
}
