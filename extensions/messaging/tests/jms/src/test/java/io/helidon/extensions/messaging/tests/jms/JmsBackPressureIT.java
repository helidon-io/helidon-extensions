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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorDeliveryReservation;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.IncomingConnectorContext;
import io.helidon.extensions.messaging.MessagingRuntime;
import io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfig;
import io.helidon.extensions.messaging.connectors.jms.JmsConnectorProvider;
import io.helidon.extensions.messaging.connectors.jms.JmsMessage;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class JmsBackPressureIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration NO_DELIVERY_TIMEOUT = Duration.ofMillis(500);

    @TempDir
    private Path temporaryDirectory;

    @Test
    @Timeout(60)
    void testIncomingConnectorDoesNotDispatchAnotherMessageWhileTheFirstDeliveryIsActive() throws Exception {
        String queue = "back-pressure-" + System.nanoTime();
        try (ArtemisBroker broker = ArtemisBroker.create(temporaryDirectory)) {
            broker.start();
            ServiceRegistryManager manager = JmsScenarioRegistry.create(incomingConfig(queue),
                                                                         broker.connectionFactory(),
                                                                         JmsMessagingTypes.BackPressureReceiver.class);
            ServiceRegistry registry = manager.registry();
            JmsMessagingTypes.BackPressureReceiver receiver = registry.get(JmsMessagingTypes.BackPressureReceiver.class);

            try {
                registry.get(MessagingRuntime.class);
                JmsTestClient.sendText(broker.connectionFactory(), queue, false, "first", ignored -> { });
                JmsTestClient.sendText(broker.connectionFactory(), queue, false, "second", ignored -> { });

                JmsMessage<String> first = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat("first message", first, notNullValue());
                assertThat(first.entity(), is("first"));
                assertThat("no second application delivery while the first handler is blocked",
                           receiver.awaitMessage(NO_DELIVERY_TIMEOUT), nullValue());
                assertThat(receiver.deliveryCount(), is(1));

                receiver.releaseFirstMessage();
                JmsMessage<String> second = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat("second message", second, notNullValue());
                assertThat(second.entity(), is("second"));
                assertThat(receiver.deliveryCount(), is(2));
            } finally {
                receiver.releaseFirstMessage();
                manager.shutdown();
            }
        }
    }

    @Test
    @Timeout(60)
    void testIncomingConnectionRemainsStoppedUntilGraphActivation() throws Exception {
        String queue = "activation-gate-" + System.nanoTime();
        try (ArtemisBroker broker = ArtemisBroker.create(temporaryDirectory.resolve("activation-gate"));
                ActiveMQConnectionFactory connectorFactory = new ActiveMQConnectionFactory(broker.connectionUrl())) {
            broker.start();
            connectorFactory.setConsumerWindowSize(1024 * 1024);
            JmsTestClient.sendText(broker.connectionFactory(), queue, false, "waiting", ignored -> { });
            IncomingConnector connector = new JmsConnectorProvider(connectorFactory)
                    .createIncomingConnector(JmsConnectorConfig.builder()
                                                     .direction(ConnectorConfig.Direction.INCOMING)
                                                     .channel("activation-gate")
                                                     .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                                                     .destination(queue)
                                                     .closeTimeout(Duration.ofSeconds(5))
                                                     .build());
            ActivationGateContext context = new ActivationGateContext();
            AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
            Thread source = Thread.ofVirtual().start(() -> {
                try {
                    connector.run(context);
                } catch (Throwable failure) {
                    sourceFailure.set(failure);
                }
            });

            try {
                assertThat("connector reached graph activation",
                           context.awaitingActivation(WAIT_TIMEOUT), is(true));
                await(() -> broker.queueConsumerCount(queue) == 1,
                      WAIT_TIMEOUT,
                      "JMS consumer was not created before graph activation");
                long observationDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                while (System.nanoTime() < observationDeadline) {
                    assertThat("message acquired before graph activation",
                               broker.queueDeliveringCount(queue), is(0));
                    assertThat("message must remain pending before graph activation",
                               broker.queuePendingMessageCount(queue), is(1L));
                    Thread.sleep(20);
                }
            } finally {
                context.cancelActivation();
                connector.forceClose();
                source.join(Duration.ofSeconds(5));
                connector.close();
            }

            assertThat("JMS source stopped", source.isAlive(), is(false));
            assertThat("JMS source failure", sourceFailure.get(), nullValue());
        }
    }

    private static String incomingConfig(String destination) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: jms
                        destination: "%s"
                        receive-timeout: PT0.05S
                """.formatted(JmsMessagingTypes.BACK_PRESSURE_INCOMING_CHANNEL, destination);
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

    private static final class ActivationGateContext implements IncomingConnectorContext {
        private final CountDownLatch awaitingActivation = new CountDownLatch(1);
        private final CountDownLatch activationCancelled = new CountDownLatch(1);

        @Override
        public boolean awaitRunning() {
            awaitingActivation.countDown();
            try {
                activationCancelled.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        @Override
        public String channel() {
            return "activation-gate";
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery() {
            throw new AssertionError("JMS delivery reserved before graph activation");
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
            throw new AssertionError("JMS delivery reserved before graph activation");
        }

        private boolean awaitingActivation(Duration timeout) throws InterruptedException {
            return awaitingActivation.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void cancelActivation() {
            activationCancelled.countDown();
        }
    }
}
