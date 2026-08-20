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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import io.helidon.extensions.messaging.connectors.jms.JmsMessage;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.DurableReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.ReconnectReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.ReconnectSender;
import io.helidon.messaging.MessagingRuntime;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class JmsResilienceIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    private Path temporaryDirectory;

    @Test
    @Timeout(60)
    void reconnectsIncomingAndOutgoingConnectorsAfterBrokerRestart() throws Exception {
        String incomingDestination = uniqueName("reconnect-in");
        String outgoingDestination = uniqueName("reconnect-out");
        ArtemisBroker broker = ArtemisBroker.create(temporaryDirectory.resolve("reconnect-broker"));
        ServiceRegistryManager manager = null;

        try {
            broker.start();
            assertThat("the Artemis client must not mask connector reconnection",
                       broker.connectionUrl(),
                       containsString("reconnectAttempts=0"));
            TrackingConnectionFactory trackingFactory = new TrackingConnectionFactory(broker.connectionFactory());
            manager = reconnectManager(trackingFactory, incomingDestination, outgoingDestination);
            ServiceRegistry registry = manager.registry();
            ReconnectReceiver receiver = registry.get(ReconnectReceiver.class);
            ReconnectSender sender = registry.get(ReconnectSender.class);
            registry.get(MessagingRuntime.class);

            int initialConnections = trackingFactory.connectionCount();
            assertThat("one connection per configured JMS direction", initialConnections, is(2));

            JmsTestClient.sendText(broker.connectionFactory(),
                                   incomingDestination,
                                   false,
                                   "before restart incoming",
                                   ignored -> { });
            assertMessage(receiver.awaitMessage(WAIT_TIMEOUT), "before restart incoming");

            sender.send("before restart outgoing");
            assertNativeText(broker, outgoingDestination, "before restart outgoing");

            broker.stop();
            await(() -> trackingFactory.connectionCount() > initialConnections,
                  WAIT_TIMEOUT,
                  "Helidon JMS connectors did not create a replacement connection while the broker was offline");
            broker.start();

            JmsTestClient.sendText(broker.connectionFactory(),
                                   incomingDestination,
                                   false,
                                   "after restart incoming",
                                   ignored -> { });
            assertMessage(receiver.awaitMessage(WAIT_TIMEOUT), "after restart incoming");

            sender.send("after restart outgoing");
            assertNativeText(broker, outgoingDestination, "after restart outgoing");
            assertThat("connector-created connections after the outage",
                       trackingFactory.connectionCount() > initialConnections,
                       is(true));
        } finally {
            close(broker, manager);
        }
    }

    @Test
    @Timeout(60)
    void durableTopicUsesAdministeredClientIdAndReceivesMessagePublishedWhileOffline() throws Exception {
        String topic = uniqueName("durable-topic");
        String clientId = uniqueName("durable-client");
        String subscriptionName = uniqueName("durable-subscription");
        ArtemisBroker broker = ArtemisBroker.create(temporaryDirectory.resolve("durable-broker"));
        ServiceRegistryManager firstManager = null;
        ServiceRegistryManager secondManager = null;

        try {
            broker.start();
            broker.connectionFactory().setClientID(clientId);
            firstManager = durableManager(broker.connectionFactory(), topic, subscriptionName);
            firstManager.registry().get(MessagingRuntime.class);
            firstManager.shutdown();
            firstManager = null;

            JmsTestClient.sendText(broker.connectionFactory(),
                                   topic,
                                   true,
                                   "published while offline",
                                   ignored -> { });

            secondManager = durableManager(broker.connectionFactory(), topic, subscriptionName);
            ServiceRegistry secondRegistry = secondManager.registry();
            DurableReceiver receiver = secondRegistry.get(DurableReceiver.class);
            secondRegistry.get(MessagingRuntime.class);

            assertMessage(receiver.awaitMessage(WAIT_TIMEOUT), "published while offline");
        } finally {
            close(broker, secondManager, firstManager);
        }
    }

    private static ServiceRegistryManager reconnectManager(ConnectionFactory connectionFactory,
                                                            String incomingDestination,
                                                            String outgoingDestination) {
        return JmsScenarioRegistry.create("""
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        receive-timeout: PT0.05S
                        close-timeout: PT5S
                        reconnect:
                          initial-delay: PT0.05S
                          max-delay: PT0.05S
                          jitter: 0
                    outgoing:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        close-timeout: PT5S
                        reconnect:
                          initial-delay: PT0.05S
                          max-delay: PT0.05S
                          jitter: 0
                """.formatted(JmsMessagingTypes.RECONNECT_INCOMING_CHANNEL,
                               incomingDestination,
                               JmsMessagingTypes.RECONNECT_OUTGOING_CHANNEL,
                               outgoingDestination),
                                          connectionFactory,
                                          ReconnectReceiver.class,
                                          ReconnectSender.class);
    }

    private static ServiceRegistryManager durableManager(ConnectionFactory connectionFactory,
                                                          String topic,
                                                          String subscriptionName) {
        return JmsScenarioRegistry.create("""
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: TOPIC
                        durable: true
                        subscription-name: "%s"
                        receive-timeout: PT0.05S
                """.formatted(JmsMessagingTypes.DURABLE_INCOMING_CHANNEL,
                               topic,
                               subscriptionName),
                                          connectionFactory,
                                          DurableReceiver.class);
    }

    private static void assertMessage(JmsMessage<String> message, String expectedPayload) {
        assertThat("JMS delivery", message, notNullValue());
        assertThat(message.entity(), is(expectedPayload));
    }

    private static void assertNativeText(ArtemisBroker broker,
                                         String destination,
                                         String expectedPayload) throws JMSException {
        TextMessage message = JmsTestClient.receiveText(broker.connectionFactory(),
                                                        destination,
                                                        WAIT_TIMEOUT);
        assertThat("native JMS delivery", message, notNullValue());
        assertThat(message.getText(), is(expectedPayload));
    }

    private static void await(BooleanSupplier condition,
                              Duration timeout,
                              String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(failureMessage, condition.getAsBoolean(), is(true));
    }

    private static String uniqueName(String prefix) {
        return prefix + '-' + Long.toUnsignedString(System.nanoTime());
    }

    private static void close(ArtemisBroker broker, ServiceRegistryManager... managers) throws Exception {
        Throwable failure = null;
        for (ServiceRegistryManager manager : managers) {
            if (manager != null) {
                try {
                    manager.shutdown();
                } catch (Throwable e) {
                    failure = merge(failure, e);
                }
            }
        }
        try {
            broker.close();
        } catch (Throwable e) {
            failure = merge(failure, e);
        }
        rethrow(failure);
    }

    private static Throwable merge(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new AssertionError("Cannot close JMS resilience-test resources", failure);
        }
    }

    private static final class TrackingConnectionFactory implements ConnectionFactory {
        private final ConnectionFactory delegate;
        private final AtomicInteger connectionCount = new AtomicInteger();

        private TrackingConnectionFactory(ConnectionFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection createConnection() throws JMSException {
            connectionCount.incrementAndGet();
            return delegate.createConnection();
        }

        @Override
        public Connection createConnection(String userName, String password) throws JMSException {
            connectionCount.incrementAndGet();
            return delegate.createConnection(userName, password);
        }

        @Override
        public JMSContext createContext() {
            return delegate.createContext();
        }

        @Override
        public JMSContext createContext(int sessionMode) {
            return delegate.createContext(sessionMode);
        }

        @Override
        public JMSContext createContext(String userName, String password) {
            return delegate.createContext(userName, password);
        }

        @Override
        public JMSContext createContext(String userName, String password, int sessionMode) {
            return delegate.createContext(userName, password, sessionMode);
        }

        private int connectionCount() {
            return connectionCount.get();
        }
    }
}
