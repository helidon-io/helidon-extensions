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

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.config.spi.ConfigNode;
import io.helidon.messaging.MessagingConfig;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class KafkaConnectorServiceTest {
    @Test
    void testProviderIsDiscoveredByServiceLoader() {
        assertThat(ServiceLoader.load(MessagingConnectorProvider.class)
                           .stream()
                           .anyMatch(provider -> provider.type().equals(KafkaConnectorProvider.class)),
                   is(true));
    }

    @Test
    void testNamedConnectorMapConfiguration() {
        Config config = Config.just(ConfigSources.create(Map.of(
                "connector.orders-kafka.type", KafkaConnectorProvider.CONNECTOR_TYPE,
                "connector.orders-kafka.bootstrap.servers", "broker:9092")));
        assertNamedConnector(config);
    }

    @Test
    void testNamedConnectorListConfiguration() {
        ConfigNode.ObjectNode connector = ConfigNode.ObjectNode.builder()
                .addValue("name", "orders-kafka")
                .addValue("type", KafkaConnectorProvider.CONNECTOR_TYPE)
                .addObject("bootstrap", ConfigNode.ObjectNode.builder().addValue("servers", "broker:9092").build())
                .build();
        Config config = Config.just(ConfigSources.create(ConfigNode.ObjectNode.builder()
                .addList("connector", ConfigNode.ListNode.builder().addObject(connector).build())
                .build()));
        assertNamedConnector(config);
    }

    @Test
    void testConnectorsAreDiscoveredByServiceRegistry() {
        ServiceRegistryManager registryManager = ServiceRegistryManager.create();
        try {
            ServiceRegistry registry = registryManager.registry();

            MessagingConnectorProvider provider = registry.get(MessagingConnectorProvider.class);

            assertThat(provider, instanceOf(KafkaConnectorProvider.class));
            assertThat(provider.configKey(), is(KafkaConnectorProvider.CONNECTOR_TYPE));
            assertThat(provider instanceof AutoCloseable, is(false));
        } finally {
            registryManager.shutdown();
        }
    }

    private static void assertNamedConnector(Config config) {
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        try {
            List<MessagingConnector> connectors = MessagingConfig.builder()
                    .serviceRegistry(manager.registry())
                    .config(config)
                    .buildPrototype()
                    .connector();
            assertThat(connectors.size(), is(1));
            assertThat(connectors.getFirst(), instanceOf(KafkaConnector.class));
            KafkaConnector connector = (KafkaConnector) connectors.getFirst();
            assertThat(connector.name(), is("orders-kafka"));
            assertThat(connector.prototype().bootstrapServers(), is("broker:9092"));
        } finally {
            manager.shutdown();
        }
    }
}
