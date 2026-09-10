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

package io.helidon.extensions.messaging.tests.pulsar;

import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarConnector;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.IncomingReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.JsonSchemaProvider;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.OutgoingSender;
import io.helidon.messaging.ConsumerRegistration;
import io.helidon.messaging.EmitterRegistration;
import io.helidon.messaging.MessagingRuntime;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class PulsarScenarioRegistryTest {
    @Test
    void discoversConnectorProviderFromClasspathMetadata() {
        ServiceRegistryManager manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                                              .discoverServicesFromServiceLoader(false)
                                                                              .build());
        try {
            ServiceRegistry registry = manager.registry();

            MessagingConnectorProvider provider = registry.get(MessagingConnectorProvider.class);
            assertThat(provider.configKey(), is(PulsarConnector.CONNECTOR_TYPE));
            MessagingConnector connector = provider.create(Config.just(ConfigSources.create(Map.of(
                    "service-url", "pulsar://127.0.0.1:6650"))), "orders-broker");
            assertThat(connector, instanceOf(PulsarConnector.class));
            assertThat(connector.name(), is("orders-broker"));
            assertThat(connector.type(), is(PulsarConnector.CONNECTOR_TYPE));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void activatesOnlySelectedIncomingFixtureWithoutBroker() {
        ServiceRegistryManager manager = PulsarScenarioRegistry.create("{}", IncomingReceiver.class);
        try {
            ServiceRegistry registry = manager.registry();

            registry.get(MessagingRuntime.class);
            assertThat(registry.all(EmitterRegistration.class).isEmpty(), is(true));
            assertThat(registry.all(ConsumerRegistration.class).size(), is(1));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void retainsGeneratedEmitterOwnedBySelectedOutgoingFixture() {
        ServiceRegistryManager manager = PulsarScenarioRegistry.create("{}", OutgoingSender.class);
        try {
            ServiceRegistry registry = manager.registry();

            assertThat(registry.all(EmitterRegistration.class).size(), is(1));
            assertThat(registry.all(ConsumerRegistration.class).isEmpty(), is(true));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void customJsonSchemaProviderRoundTripsFixturePayload() {
        Schema<PulsarTestPayload> schema = (Schema<PulsarTestPayload>) new JsonSchemaProvider().schema();
        PulsarTestPayload payload = new PulsarTestPayload("order-42", 3);

        assertThat(schema.decode(schema.encode(payload)), is(payload));
    }
}
