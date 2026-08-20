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

import java.util.List;

import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.BytesReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.ForwardingReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.FtRetryPoisonReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.TextReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.TextSender;
import io.helidon.messaging.ConsumerRegistration;
import io.helidon.messaging.EmitterRegistration;
import io.helidon.messaging.MessagingRuntime;
import io.helidon.messaging.ProcessorRegistration;
import io.helidon.service.registry.Interception;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class JmsScenarioRegistryTest {
    private static final ConnectionFactory UNUSED_CONNECTION_FACTORY = new UnusedConnectionFactory();

    @Test
    void activatesOnlySelectedFixtureTopologyWithoutJmsBroker() {
        String yaml = """
                helidon:
                  messaging:
                    incoming:
                      jms-text-in:
                        topology-only: true
                """;
        ServiceRegistryManager manager = JmsScenarioRegistry.create(yaml,
                                                                     UNUSED_CONNECTION_FACTORY,
                                                                     TextReceiver.class);
        try {
            ServiceRegistry registry = manager.registry();

            assertDoesNotThrow(() -> registry.get(MessagingRuntime.class));
            assertThat(registry.get(ConnectionFactory.class), sameInstance(UNUSED_CONNECTION_FACTORY));
            assertThat(registry.first(BytesReceiver.class).isEmpty(), is(true));
            assertThat(registry.all(EmitterRegistration.class).isEmpty(), is(true));
            assertThat(registry.all(ProcessorRegistration.class).isEmpty(), is(true));

            List<ConsumerRegistration> registrations = registry.all(ConsumerRegistration.class);
            assertThat(registrations.size(), is(1));
            assertThat(registrations.getFirst().channel(), is(JmsMessagingTypes.TEXT_INCOMING_CHANNEL));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void retainsGeneratedRegistrationsOwnedBySelectedProducerFixtures() {
        ServiceRegistryManager emitterManager = JmsScenarioRegistry.create("{}",
                                                                            UNUSED_CONNECTION_FACTORY,
                                                                            TextSender.class);
        try {
            ServiceRegistry registry = emitterManager.registry();
            assertThat(registry.all(EmitterRegistration.class).size(), is(1));
            assertThat(registry.all(ConsumerRegistration.class).isEmpty(), is(true));
        } finally {
            emitterManager.shutdown();
        }

        ServiceRegistryManager processorManager = JmsScenarioRegistry.create("{}",
                                                                              UNUSED_CONNECTION_FACTORY,
                                                                              ForwardingReceiver.class);
        try {
            ServiceRegistry registry = processorManager.registry();
            assertThat(registry.all(ProcessorRegistration.class).size(), is(1));
            assertThat(registry.all(ConsumerRegistration.class).size(), is(1));
            assertThat(registry.all(EmitterRegistration.class).isEmpty(), is(true));
        } finally {
            processorManager.shutdown();
        }
    }

    @Test
    void retainsGeneratedFaultToleranceInterceptorOwnedBySelectedFixture() {
        ServiceRegistryManager manager = JmsScenarioRegistry.create("{}",
                                                                     UNUSED_CONNECTION_FACTORY,
                                                                     FtRetryPoisonReceiver.class);
        try {
            ServiceRegistry registry = manager.registry();
            assertThat(registry.all(Interception.ElementInterceptor.class)
                               .stream()
                               .anyMatch(interceptor -> interceptor.getClass()
                                       .getName()
                                       .contains("FtRetryPoisonReceiver_receive__Retry")),
                       is(true));
        } finally {
            manager.shutdown();
        }
    }

    private static final class UnusedConnectionFactory implements ConnectionFactory {
        @Override
        public Connection createConnection() throws JMSException {
            throw new AssertionError("JMS connection factory must not be used by a topology-only registry test");
        }

        @Override
        public Connection createConnection(String userName, String password) throws JMSException {
            throw new AssertionError("JMS connection factory must not be used by a topology-only registry test");
        }

        @Override
        public JMSContext createContext() {
            throw new AssertionError("JMS connection factory must not be used by a topology-only registry test");
        }

        @Override
        public JMSContext createContext(String userName, String password) {
            throw new AssertionError("JMS connection factory must not be used by a topology-only registry test");
        }

        @Override
        public JMSContext createContext(String userName, String password, int sessionMode) {
            throw new AssertionError("JMS connection factory must not be used by a topology-only registry test");
        }

        @Override
        public JMSContext createContext(int sessionMode) {
            throw new AssertionError("JMS connection factory must not be used by a topology-only registry test");
        }
    }
}
