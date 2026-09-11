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

package io.helidon.extensions.messaging.tests.jms;

import io.helidon.common.types.ResolvedType;
import io.helidon.extensions.messaging.connectors.jms.JmsConnector;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.service.registry.DescriptorHandler;
import io.helidon.service.registry.ServiceDiscovery;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class JmsModuleDiscoveryIT {
    @Test
    void connectorMetadataIsDiscoveredFromNamedModules() {
        Module testModule = JmsModuleDiscoveryIT.class.getModule();
        Module connectorModule = JmsConnector.class.getModule();
        boolean connectorDescriptorDiscovered = ServiceDiscovery.create()
                .allMetadata()
                .stream()
                .map(DescriptorHandler::descriptor)
                .anyMatch(descriptor -> descriptor.serviceType().packageName().equals(JmsConnector.class.getPackageName())
                        && descriptor.contracts().contains(ResolvedType.create(MessagingConnectorProvider.class)));

        assertThat("integration test module", testModule.isNamed(), is(true));
        assertThat(testModule.getName(), is("io.helidon.extensions.messaging.tests.jms"));
        assertThat("JMS connector module", connectorModule.isNamed(), is(true));
        assertThat(connectorModule.getName(), is("io.helidon.extensions.messaging.connectors.jms"));
        assertThat("JMS connector service descriptor", connectorDescriptorDiscovered, is(true));
        assertThat("Connector provider is not registered with ServiceLoader", connectorModule.getDescriptor().provides()
                .stream().noneMatch(provider -> provider.service().equals(MessagingConnectorProvider.class.getName())),
                   is(true));
    }
}
