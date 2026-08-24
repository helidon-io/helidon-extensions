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

import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.messaging.ConnectorConfig;
import io.helidon.messaging.ConnectorProvider;
import io.helidon.messaging.IncomingConnectorProvider;
import io.helidon.messaging.OutgoingConnectorProvider;
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
            ConnectorProvider provider = registry.all(ConnectorProvider.class)
                    .stream()
                    .filter(PulsarConnectorProvider.class::isInstance)
                    .findFirst()
                    .orElseThrow();

            assertThat(provider.connectorType(), is(PulsarConnectorProvider.CONNECTOR_TYPE));
            assertThat(provider instanceof IncomingConnectorProvider, is(true));
            assertThat(provider instanceof OutgoingConnectorProvider, is(true));
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
            provider.createOutgoingConnector(PulsarConnectorConfig.builder()
                                                       .direction(ConnectorConfig.Direction.OUTGOING)
                                                       .channel("registry-schema")
                                                       .connector(PulsarConnectorProvider.CONNECTOR_TYPE)
                                                       .serviceUrl("pulsar://127.0.0.1:6650")
                                                       .topic("persistent://public/default/registry-schema")
                                                       .schemaProvider("registry-int32")
                                                       .build());

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
