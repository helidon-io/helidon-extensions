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

import java.nio.file.Path;
import java.time.Duration;

import io.helidon.extensions.messaging.connectors.jms.JmsMessage;
import io.helidon.messaging.MessagingRuntime;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class JmsJndiIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    private Path temporaryDirectory;

    @Test
    @Timeout(60)
    void testConfiguredJndiFactoryAndDestinationRoundTrip() throws Exception {
        String queue = "jndi-" + System.nanoTime();
        try (ArtemisBroker broker = ArtemisBroker.create(temporaryDirectory)) {
            broker.start();
            ServiceRegistryManager manager = JmsScenarioRegistry.create(config(queue, broker.connectionUrl()),
                                                                         new RejectingConnectionFactory(),
                                                                         JmsMessagingTypes.TextSender.class,
                                                                         JmsMessagingTypes.TextReceiver.class);
            try {
                ServiceRegistry registry = manager.registry();
                JmsMessagingTypes.TextSender sender = registry.get(JmsMessagingTypes.TextSender.class);
                JmsMessagingTypes.TextReceiver receiver = registry.get(JmsMessagingTypes.TextReceiver.class);
                registry.get(MessagingRuntime.class);

                sender.send("jndi round trip");

                JmsMessage<String> received = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat("JNDI message", received, notNullValue());
                assertThat(received.entity(), is("jndi round trip"));
            } finally {
                manager.shutdown();
            }
        }
    }

    private static String config(String destination, String connectionUrl) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        jndi:
                          connection-factory: cf
                          destination: orders
                          environment:
                            java.naming.factory.initial: org.apache.activemq.artemis.jndi.ActiveMQInitialContextFactory
                            connectionFactory.cf: "%s"
                            queue.orders: "%s"
                        receive-timeout: PT0.05S
                    outgoing:
                      %s:
                        connector: helidon-jms
                        jndi:
                          connection-factory: cf
                          destination: orders
                          environment:
                            java.naming.factory.initial: org.apache.activemq.artemis.jndi.ActiveMQInitialContextFactory
                            connectionFactory.cf: "%s"
                            queue.orders: "%s"
                """.formatted(JmsMessagingTypes.TEXT_INCOMING_CHANNEL,
                               connectionUrl,
                               destination,
                               JmsMessagingTypes.TEXT_OUTGOING_CHANNEL,
                               connectionUrl,
                               destination);
    }

    private static final class RejectingConnectionFactory implements ConnectionFactory {
        @Override
        public Connection createConnection() throws JMSException {
            throw new AssertionError("JNDI scenario used the Service Registry connection factory");
        }

        @Override
        public Connection createConnection(String userName, String password) throws JMSException {
            throw new AssertionError("JNDI scenario used the Service Registry connection factory");
        }

        @Override
        public JMSContext createContext() {
            throw new AssertionError("JNDI scenario used the Service Registry connection factory");
        }

        @Override
        public JMSContext createContext(int sessionMode) {
            throw new AssertionError("JNDI scenario used the Service Registry connection factory");
        }

        @Override
        public JMSContext createContext(String userName, String password) {
            throw new AssertionError("JNDI scenario used the Service Registry connection factory");
        }

        @Override
        public JMSContext createContext(String userName, String password, int sessionMode) {
            throw new AssertionError("JNDI scenario used the Service Registry connection factory");
        }
    }
}
