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

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.config.spi.ConfigNode;
import io.helidon.messaging.MessagingConfig;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class KafkaConnectorServiceTest {
    @Test
    void testNamedConnectorMapConfiguration() {
        ConfigNode.ObjectNode connector = ConfigNode.ObjectNode.builder()
                .addValue("type", KafkaConnector.CONNECTOR_TYPE)
                .addList("bootstrap-servers", ConfigNode.ListNode.builder().addValue("broker:9092").build())
                .build();
        Config config = Config.just(ConfigSources.create(ConfigNode.ObjectNode.builder()
                .addObject("connector", ConfigNode.ObjectNode.builder().addObject("orders-kafka", connector).build())
                .build()));
        assertNamedConnector(config);
    }

    @Test
    void testNamedConnectorListConfiguration() {
        ConfigNode.ObjectNode connector = ConfigNode.ObjectNode.builder()
                .addValue("name", "orders-kafka")
                .addValue("type", KafkaConnector.CONNECTOR_TYPE)
                .addList("bootstrap-servers", ConfigNode.ListNode.builder().addValue("broker:9092").build())
                .build();
        Config config = Config.just(ConfigSources.create(ConfigNode.ObjectNode.builder()
                .addList("connector", ConfigNode.ListNode.builder().addObject(connector).build())
                .build()));
        assertNamedConnector(config);
    }

    @Test
    void testConnectorsAreDiscoveredByServiceRegistry() {
        ServiceRegistryManager registryManager = registryManager();
        try {
            ServiceRegistry registry = registryManager.registry();

            MessagingConnectorProvider provider = registry.get(MessagingConnectorProvider.class);

            assertThat(provider.configKey(), is(KafkaConnector.CONNECTOR_TYPE));
            assertThat(provider instanceof AutoCloseable, is(false));
        } finally {
            registryManager.shutdown();
        }
    }

    private static void assertNamedConnector(Config config) {
        ServiceRegistryManager manager = registryManager();
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
            assertThat(connector.prototype().bootstrapServers(), is(List.of("broker:9092")));
        } finally {
            manager.shutdown();
        }
    }

    private static ServiceRegistryManager registryManager() {
        return ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                     .discoverServicesFromServiceLoader(false)
                                                     .build());
    }
}
