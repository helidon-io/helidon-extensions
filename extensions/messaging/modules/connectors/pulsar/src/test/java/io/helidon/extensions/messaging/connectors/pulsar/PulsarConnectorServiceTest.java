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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.messaging.MessagingConfig;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class PulsarConnectorServiceTest {
    private static final AtomicInteger SCHEMA_INVOCATIONS = new AtomicInteger();

    @Test
    void shadedRuntimeImplementationLoadsOnClasspath() throws Exception {
        PulsarClient client = PulsarClient.builder()
                .serviceUrl("pulsar://127.0.0.1:6650")
                .build();
        try {
            assertThat(client.isClosed(), is(false));
        } finally {
            client.close();
        }
        assertThat(client.isClosed(), is(true));
    }

    @Test
    void providerIsDiscoveredFromUnnamedClasspathMetadata() {
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        try {
            ServiceRegistry registry = manager.registry();
            MessagingConnectorProvider provider = registry.all(MessagingConnectorProvider.class)
                    .stream()
                    .filter(PulsarConnectorProvider.class::isInstance)
                    .findFirst()
                    .orElseThrow();

            assertThat(provider.configKey(), is(PulsarConnectorProvider.CONNECTOR_TYPE));
            MessagingConfig config = MessagingConfig.builder()
                    .serviceRegistry(registry)
                    .config(Config.just(ConfigSources.create(Map.of(
                            "connector.orders-broker.type", PulsarConnectorProvider.CONNECTOR_TYPE,
                            "connector.orders-broker.service-url", "pulsar://127.0.0.1:6650"))))
                    .buildPrototype();
            MessagingConnector connector = config.connector().getFirst();
            assertThat(connector.name(), is("orders-broker"));
            assertThat(connector.type(), is(PulsarConnectorProvider.CONNECTOR_TYPE));
            assertThat(connector instanceof PulsarConnector, is(true));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void schemaProviderIsInjectedFromServiceRegistry() {
        SCHEMA_INVOCATIONS.set(0);
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        try {
            PulsarConnectorProvider provider = manager.registry().get(PulsarConnectorProvider.class);
            MessagingConnector connector = provider.create(Config.just(ConfigSources.create(Map.of(
                    "service-url", "pulsar://127.0.0.1:6650"))), "pulsar");
            connector.outgoing(Config.just(ConfigSources.create(Map.of(
                    "channel-name", "registry-schema",
                    "topic", "persistent://public/default/registry-schema",
                    "schema-provider", "registry-int32")))).orElseThrow();

            assertThat(SCHEMA_INVOCATIONS.get(), is(1));
        } finally {
            manager.shutdown();
        }
    }

    @Service.Singleton
    static final class RegistrySchemaProvider implements PulsarSchemaProvider {
        @Override
        public String name() {
            return "registry-int32";
        }

        @Override
        public Schema<?> schema() {
            SCHEMA_INVOCATIONS.incrementAndGet();
            return Schema.INT32;
        }
    }
}
