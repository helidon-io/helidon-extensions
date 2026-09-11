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

import java.lang.reflect.Modifier;

import io.helidon.config.Config;
import io.helidon.messaging.spi.MessagingConnectorProvider;
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
    void testConfiguredProviderIsDiscoveredByServiceRegistry() {
        ServiceRegistryManager registryManager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                .discoverServicesFromServiceLoader(false)
                .build());
        try {
            ServiceRegistry registry = registryManager.registry();
            MessagingConnectorProvider provider = registry.get(MessagingConnectorProvider.class);

            assertThat(provider, instanceOf(JmsConnectorProvider.class));
            assertThat(Modifier.isPublic(provider.getClass().getModifiers()), is(false));
            assertThat(provider.configKey(), is(JmsConnector.CONNECTOR_TYPE));

            JmsConnector connector = (JmsConnector) provider.create(Config.empty(), "orders-jms");
            assertThat(connector.name(), is("orders-jms"));
            assertThat(connector.type(), is(JmsConnector.CONNECTOR_TYPE));

            var incoming = JmsIncomingConfig.builder()
                    .connector("orders-jms")
                    .channelName("orders")
                    .destination("orders")
                    .build();
            var outgoing = JmsOutgoingConfig.builder()
                    .connector("orders-jms")
                    .channelName("audit")
                    .destination("audit")
                    .build();
            assertThat(connector.incoming(incoming), not(sameInstance(connector.incoming(incoming))));
            assertThat(connector.outgoing(outgoing), not(sameInstance(connector.outgoing(outgoing))));
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
            JmsRuntimeConfig config = JmsRuntimeConfig.builder()
                    .channelName("orders")
                    .destination("orders")
                    .build();

            assertThat(new JmsResourceResolver(registryManager.registry()).resolve(config), sameInstance(factory));
        } finally {
            registryManager.shutdown();
        }
    }
}
