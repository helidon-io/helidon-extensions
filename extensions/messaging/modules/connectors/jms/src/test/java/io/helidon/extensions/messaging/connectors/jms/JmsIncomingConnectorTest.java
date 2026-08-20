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

package io.helidon.extensions.messaging.connectors.jms;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.messaging.ConnectorConfig;
import io.helidon.messaging.ConnectorDelivery;
import io.helidon.messaging.ConnectorDeliveryReservation;
import io.helidon.messaging.IncomingConnector;
import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.MessagingRejectedException;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.ExceptionListener;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmsIncomingConnectorTest {
    private static final String CHANNEL = "orders";

    @Test
    @Timeout(5)
    void reservesBeforeReceiveAndAcknowledgesOnlyAfterDeliveryCompletes() throws Exception {
        JmsClient client = client();
        TextMessage nativeMessage = textMessage("first");
        List<String> events = new ArrayList<>();
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        TestDelivery delivery = new TestDelivery(deliveryStarted, releaseDelivery, null);
        TestReservation reservation = new TestReservation(events, delivery);
        TestContext context = new TestContext(events, reservation);
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        when(client.consumer.receive(anyLong())).thenAnswer(invocation -> {
            events.add("receive");
            return nativeMessage;
        });
        doAnswer(invocation -> {
            events.add("acknowledge");
            return null;
        }).when(nativeMessage).acknowledge();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(() -> connector.run(context), failure));
        assertThat(deliveryStarted.await(1, TimeUnit.SECONDS), is(true));

        connector.drain();

        assertThat(source.isAlive(), is(true));
        verify(nativeMessage, never()).acknowledge();
        releaseDelivery.countDown();
        source.join(Duration.ofSeconds(2));

        assertThat(source.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(events, is(List.of("reserve", "receive", "start", "acknowledge")));
        assertThat(reservation.closed(), is(true));
        assertThat(delivery.closed(), is(true));
        verify(client.session, never()).recover();
    }

    @Test
    void commitsTransactedDeliveryInsteadOfAcknowledgingIt() throws Exception {
        JmsClient client = client();
        TextMessage nativeMessage = textMessage("first");
        TestDelivery delivery = TestDelivery.completed();
        TestReservation reservation = new TestReservation(new ArrayList<>(), delivery);
        TestContext context = new TestContext(new ArrayList<>(), reservation);
        AtomicReference<IncomingConnector> connectorReference = new AtomicReference<>();
        IncomingConnector connector = JmsIncomingConnector.create(config(true), ignored -> client.factory);
        connectorReference.set(connector);
        when(client.consumer.receive(anyLong())).thenReturn(nativeMessage);
        doAnswer(invocation -> {
            connectorReference.get().drain();
            return null;
        }).when(client.session).commit();

        connector.run(context);

        verify(client.session).commit();
        verify(nativeMessage, never()).acknowledge();
        verify(client.session, never()).rollback();
    }

    @Test
    void configuresClientIdSelectorAndNoLocalForDurableTopicConsumer() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        Topic topic = mock(Topic.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(false, Session.CLIENT_ACKNOWLEDGE)).thenReturn(session);
        when(session.createTopic("events")).thenReturn(topic);
        when(session.createDurableConsumer(topic, "orders-subscription", "region = 'EU'", true))
                .thenReturn(consumer);
        JmsConnectorConfig topicConfig = JmsConnectorConfig.builder()
                .from(config(false))
                .destinationType(JmsDestinationType.TOPIC)
                .clientId("orders-client")
                .durable(true)
                .subscriptionName("orders-subscription")
                .messageSelector("region = 'EU'")
                .noLocal(true)
                .build();
        IncomingConnector connector = JmsIncomingConnector.create(topicConfig, ignored -> factory);
        TestContext context = new TestContext(new ArrayList<>()) {
            @Override
            public boolean awaitRunning() {
                return false;
            }
        };

        connector.run(context);

        verify(connection).setClientID("orders-client");
        verify(session).createDurableConsumer(topic, "orders-subscription", "region = 'EU'", true);
        verify(connection, never()).start();
        verify(connection).close();
    }

    @Test
    void usesAdministrativelyConfiguredClientIdForDurableTopicConsumer() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        Topic topic = mock(Topic.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(false, Session.CLIENT_ACKNOWLEDGE)).thenReturn(session);
        when(session.createTopic("events")).thenReturn(topic);
        when(session.createDurableConsumer(topic, "orders-subscription", null, false)).thenReturn(consumer);
        JmsConnectorConfig topicConfig = JmsConnectorConfig.builder()
                .from(config(false))
                .destinationType(JmsDestinationType.TOPIC)
                .durable(true)
                .subscriptionName("orders-subscription")
                .build();
        IncomingConnector connector = JmsIncomingConnector.create(topicConfig, ignored -> factory);
        TestContext context = new TestContext(new ArrayList<>()) {
            @Override
            public boolean awaitRunning() {
                return false;
            }
        };

        connector.run(context);

        verify(connection, never()).setClientID(any());
        verify(session).createDurableConsumer(topic, "orders-subscription", null, false);
        verify(connection, never()).start();
        verify(connection).close();
    }

    @Test
    void naturalRunCompletionClearsConnectorOwnedCredentials() throws Exception {
        JmsClient client = client();
        when(client.factory.createConnection("scott", "tiger")).thenReturn(client.connection);
        JmsConnectorConfig credentialConfig = JmsConnectorConfig.builder()
                .from(config(false))
                .username("scott")
                .password("tiger")
                .build();
        IncomingConnector connector = JmsIncomingConnector.create(credentialConfig, ignored -> client.factory);
        char[] connectorPassword = connectorPassword(connector);

        connector.run(new TestContext(new ArrayList<>()) {
            @Override
            public boolean awaitRunning() {
                return false;
            }
        });

        assertArrayEquals(new char[connectorPassword.length], connectorPassword);
        verify(client.connection, never()).start();
        verify(client.connection).close();
    }

    @Test
    @Timeout(5)
    void doesNotStartConnectionBeforeContextAllowsRunning() throws Exception {
        JmsClient client = client();
        CountDownLatch awaitingRunning = new CountDownLatch(1);
        CountDownLatch allowRunning = new CountDownLatch(1);
        CountDownLatch receiving = new CountDownLatch(1);
        AtomicReference<IncomingConnector> connectorReference = new AtomicReference<>();
        when(client.consumer.receive(anyLong())).thenAnswer(invocation -> {
            receiving.countDown();
            connectorReference.get().drain();
            return null;
        });
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        connectorReference.set(connector);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>(),
                                                    new TestReservation(new ArrayList<>(), TestDelivery.completed())) {
                    @Override
                    public boolean awaitRunning() {
                        awaitingRunning.countDown();
                        awaitIgnoringInterruption(allowRunning);
                        return true;
                    }
                }),
                sourceFailure));

        try {
            assertThat(awaitingRunning.await(1, TimeUnit.SECONDS), is(true));
            verify(client.connection, never()).start();
            verify(client.consumer, never()).receive(anyLong());

            allowRunning.countDown();
            assertThat(receiving.await(1, TimeUnit.SECONDS), is(true));
            source.join(Duration.ofSeconds(2));
        } finally {
            allowRunning.countDown();
            if (source.isAlive()) {
                connector.forceClose();
                source.join(Duration.ofSeconds(2));
            }
        }

        assertThat(source.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        verify(client.connection).start();
    }

    @Test
    @Timeout(5)
    void connectionStartFailureRetriesAfterActivation() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenReturn(first.connection, second.connection);
        doThrow(new JMSException("start failed")).when(first.connection).start();
        TextMessage delivered = textMessage("after-start-retry");
        when(second.consumer.receive(anyLong())).thenReturn(delivered);
        AtomicReference<IncomingConnector> connectorReference = new AtomicReference<>();
        doAnswer(invocation -> {
            connectorReference.get().drain();
            return null;
        }).when(delivered).acknowledge();
        AtomicInteger activationWaits = new AtomicInteger();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> factory);
        connectorReference.set(connector);

        connector.run(new TestContext(new ArrayList<>(),
                                      new TestReservation(new ArrayList<>(), TestDelivery.completed())) {
            @Override
            public boolean awaitRunning() {
                activationWaits.incrementAndGet();
                return true;
            }
        });

        assertThat(activationWaits.get(), is(1));
        verify(factory, times(2)).createConnection();
        verify(first.connection).start();
        verify(first.consumer, never()).receive(anyLong());
        verify(first.connection).close();
        verify(second.connection).start();
        verify(delivered).acknowledge();
    }

    @Test
    @Timeout(5)
    void minimumPositiveReconnectJitterDoesNotBreakRetry() throws Exception {
        JmsClient client = client();
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection())
                .thenThrow(new JMSException("broker unavailable"))
                .thenReturn(client.connection);
        TextMessage delivered = textMessage("after-retry");
        when(client.consumer.receive(anyLong())).thenReturn(delivered);
        AtomicReference<IncomingConnector> connectorReference = new AtomicReference<>();
        doAnswer(invocation -> {
            connectorReference.get().drain();
            return null;
        }).when(delivered).acknowledge();
        JmsConnectorConfig connectorConfig = JmsConnectorConfig.builder()
                .from(config(false))
                .reconnectJitter(Double.MIN_VALUE)
                .build();
        IncomingConnector connector = JmsIncomingConnector.create(connectorConfig, ignored -> factory);
        connectorReference.set(connector);

        connector.run(new TestContext(new ArrayList<>(),
                                      new TestReservation(new ArrayList<>(), TestDelivery.completed())));

        verify(factory, times(2)).createConnection();
        verify(delivered).acknowledge();
    }

    @Test
    void reconnectJitterIsCappedAtTheConfiguredMaximum() {
        Duration maximum = Duration.ofMillis(100);

        Duration delay = JmsIncomingConnector.jitter(Duration.ofMillis(90), maximum, 0.5, 1);

        assertThat(delay, is(maximum));
    }

    @Test
    @Timeout(5)
    void hugeReconnectDelayWithDefaultAndZeroJitterRemainsCloseable() throws Exception {
        Duration hugeDelay = Duration.ofSeconds(Long.MAX_VALUE);
        JmsConnectorConfig defaultJitter = JmsConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel(CHANNEL)
                .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                .destination("events")
                .reconnectInitialDelay(hugeDelay)
                .reconnectMaxDelay(hugeDelay)
                .build();
        assertThat(defaultJitter.reconnectJitter(), is(0.2));

        assertReconnectWaitIsCloseable(defaultJitter);
        assertReconnectWaitIsCloseable(JmsConnectorConfig.builder()
                                               .from(defaultJitter)
                                               .reconnectJitter(0)
                                               .build());
    }

    @Test
    @Timeout(5)
    void connectionBrokenDuringActivationWaitReconnectsWithoutStartingBrokenGeneration() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        AtomicReference<ExceptionListener> firstListener = new AtomicReference<>();
        doAnswer(invocation -> {
            firstListener.set(invocation.getArgument(0));
            return null;
        }).when(first.connection).setExceptionListener(any(ExceptionListener.class));
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenReturn(first.connection, second.connection);
        TextMessage delivered = textMessage("after-activation-reconnect");
        when(second.consumer.receive(anyLong())).thenReturn(delivered);
        AtomicReference<IncomingConnector> connectorReference = new AtomicReference<>();
        doAnswer(invocation -> {
            connectorReference.get().drain();
            return null;
        }).when(delivered).acknowledge();
        AtomicInteger activationWaits = new AtomicInteger();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> factory);
        connectorReference.set(connector);

        connector.run(new TestContext(new ArrayList<>(),
                                      new TestReservation(new ArrayList<>(), TestDelivery.completed())) {
            @Override
            public boolean awaitRunning() {
                activationWaits.incrementAndGet();
                firstListener.get().onException(new JMSException("failed during activation"));
                return true;
            }
        });

        assertThat(activationWaits.get(), is(1));
        verify(factory, times(2)).createConnection();
        verify(first.connection, never()).start();
        verify(first.connection).close();
        verify(second.connection).start();
        verify(delivered).acknowledge();
    }

    @Test
    void poisonBodyMappingRecoversWithoutAcknowledgingOrReconnecting() throws Exception {
        JmsClient client = client();
        ObjectMessage nativeMessage = mock(ObjectMessage.class);
        when(client.consumer.receive(anyLong())).thenReturn(nativeMessage);
        AtomicInteger resolutions = new AtomicInteger();
        TestReservation reservation = new TestReservation(new ArrayList<>(), TestDelivery.completed());
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> {
            resolutions.incrementAndGet();
            return client.factory;
        });

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.run(new TestContext(new ArrayList<>(), reservation)));

        assertThat(failure.getMessage(), containsString("ObjectMessage is disabled"));
        assertThat(resolutions.get(), is(1));
        assertThat(reservation.starts(), is(0));
        assertThat(reservation.closed(), is(true));
        verify(client.session).recover();
        verify(nativeMessage, never()).acknowledge();
    }

    @Test
    void jmsBodyMappingFailureRecoversWithoutReconnectingUnlessConnectionIsBroken() throws Exception {
        JmsClient client = client();
        TextMessage nativeMessage = mock(TextMessage.class);
        when(nativeMessage.getText()).thenThrow(new JMSException("malformed provider message"));
        when(client.consumer.receive(anyLong())).thenReturn(nativeMessage);
        AtomicInteger resolutions = new AtomicInteger();
        TestReservation reservation = new TestReservation(new ArrayList<>(), TestDelivery.completed());
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> {
            resolutions.incrementAndGet();
            return client.factory;
        });

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.run(new TestContext(new ArrayList<>(), reservation)));

        assertThat(failure.getMessage(), containsString("Cannot snapshot incoming JMS message"));
        assertThat(resolutions.get(), is(1));
        assertThat(reservation.starts(), is(0));
        verify(client.session).recover();
        verify(nativeMessage, never()).acknowledge();
    }

    @Test
    void lifecycleRejectionPropagatesWhenConnectorWasNotAskedToStop() throws Exception {
        JmsClient client = client();
        MessagingRejectedException rejection = new MessagingRejectedException(
                CHANNEL,
                MessagingRejectedException.Reason.SHUTDOWN,
                "runtime rejected admission");
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        TestContext context = new TestContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                throw rejection;
            }
        };

        MessagingRejectedException failure = assertThrows(MessagingRejectedException.class,
                                                           () -> connector.run(context));

        assertThat(failure, is(rejection));
    }

    @Test
    @Timeout(5)
    void receiveFailureReconnectsAndReusesCredentials() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection("orders-user", "secret"))
                .thenReturn(first.connection, second.connection);
        when(first.consumer.receive(anyLong())).thenThrow(new JMSException("broker offline"));
        TextMessage delivered = textMessage("after-reconnect");
        when(second.consumer.receive(anyLong())).thenReturn(delivered);
        AtomicReference<IncomingConnector> connectorReference = new AtomicReference<>();
        doAnswer(invocation -> {
            connectorReference.get().drain();
            return null;
        }).when(delivered).acknowledge();
        JmsConnectorConfig config = JmsConnectorConfig.builder()
                .from(config(false))
                .username("orders-user")
                .password("secret".toCharArray())
                .build();
        IncomingConnector connector = JmsIncomingConnector.create(config, ignored -> factory);
        connectorReference.set(connector);
        TestReservation firstReservation = new TestReservation(new ArrayList<>(), TestDelivery.completed());
        TestReservation secondReservation = new TestReservation(new ArrayList<>(), TestDelivery.completed());
        AtomicInteger activationWaits = new AtomicInteger();

        connector.run(new TestContext(new ArrayList<>(), firstReservation, secondReservation) {
            @Override
            public boolean awaitRunning() {
                activationWaits.incrementAndGet();
                return true;
            }
        });

        assertThat(activationWaits.get(), is(1));
        verify(factory, times(2)).createConnection("orders-user", "secret");
        verify(first.connection).start();
        verify(first.connection).close();
        verify(second.connection).start();
        verify(delivered).acknowledge();
    }

    @Test
    @Timeout(5)
    void listenerFailureDuringConsumerCreationRejectsGenerationBeforeStartAndReceive() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        AtomicReference<ExceptionListener> listener = new AtomicReference<>();
        CountDownLatch firstConnectionClosed = new CountDownLatch(1);
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(first.connection).setExceptionListener(any(ExceptionListener.class));
        when(first.session.createConsumer(first.queue, null, false)).thenAnswer(invocation -> {
            assertThat(listener.get() != null, is(true));
            listener.get().onException(new JMSException("connection lost"));
            awaitIgnoringInterruption(firstConnectionClosed);
            return first.consumer;
        });
        doAnswer(invocation -> {
            firstConnectionClosed.countDown();
            return null;
        }).when(first.connection).close();
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenReturn(first.connection, second.connection);
        TextMessage delivered = textMessage("after-reconnect");
        when(second.consumer.receive(anyLong())).thenReturn(delivered);
        AtomicReference<IncomingConnector> connectorReference = new AtomicReference<>();
        doAnswer(invocation -> {
            connectorReference.get().drain();
            return null;
        }).when(delivered).acknowledge();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> factory);
        connectorReference.set(connector);

        connector.run(new TestContext(new ArrayList<>(),
                                      new TestReservation(new ArrayList<>(), TestDelivery.completed())));

        verify(first.connection, never()).start();
        verify(first.consumer, never()).receive(anyLong());
        verify(first.connection).close();
        verify(second.connection).start();
        verify(delivered).acknowledge();
    }

    @Test
    void staleCleanupFailureStopsIncomingReconnect() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenReturn(first.connection, second.connection);
        when(first.consumer.receive(anyLong())).thenThrow(new JMSException("broker offline"));
        JMSException cleanupFailure = new JMSException("stale cleanup failed");
        doThrow(cleanupFailure).when(first.connection).close();
        AtomicInteger resolutions = new AtomicInteger();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> {
            resolutions.incrementAndGet();
            return factory;
        });

        MessagingException failure = assertThrows(MessagingException.class,
                                                   () -> connector.run(new TestContext(new ArrayList<>(),
                                                                                      new TestReservation(
                                                                                              new ArrayList<>(),
                                                                                              TestDelivery.completed()))));

        assertThat(failure.getCause().getCause(), is(cleanupFailure));
        assertThat(resolutions.get(), is(1));
        verify(factory, times(1)).createConnection();
        verify(second.connection, never()).start();
    }

    @Test
    @Timeout(5)
    void forceCloseClosesResourcesAndUnblocksReceiveThatIgnoresInterruption() throws Exception {
        JmsClient client = client();
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        when(client.consumer.receive(anyLong())).thenAnswer(invocation -> {
            receiving.countDown();
            while (connectionClosed.getCount() != 0) {
                try {
                    connectionClosed.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a provider receive that only Connection.close can unblock.
                }
            }
            throw new JMSException("connection closed");
        });
        doAnswer(invocation -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection).close();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>(),
                                                    new TestReservation(new ArrayList<>(), TestDelivery.completed()))),
                failure));
        assertThat(receiving.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();

        assertThat(connectionClosed.await(1, TimeUnit.SECONDS), is(true));
        source.join(Duration.ofSeconds(2));
        assertThat(source.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        connector.close();
    }

    @Test
    void closeReportsCompletedResourceFailureAfterForceClose() throws Exception {
        JmsClient client = client();
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch connectionCloseAttempted = new CountDownLatch(1);
        when(client.consumer.receive(anyLong())).thenAnswer(invocation -> {
            receiving.countDown();
            awaitIgnoringInterruption(connectionCloseAttempted);
            throw new JMSException("connection closed");
        });
        JMSException cleanupFailure = new JMSException("forced cleanup failed");
        doAnswer(invocation -> {
            connectionCloseAttempted.countDown();
            throw cleanupFailure;
        }).when(client.connection).close();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>(),
                                                    new TestReservation(new ArrayList<>(), TestDelivery.completed()))),
                sourceFailure));
        assertThat(receiving.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();

        source.join(Duration.ofSeconds(2));
        assertThat(source.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        MessagingException failure = assertThrows(MessagingException.class, connector::close);
        assertThat(failure.getCause(), is(cleanupFailure));
    }

    @Test
    @Timeout(5)
    void forceCloseUnblocksSourceAndClosesConnectionCreatedLate() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection lateConnection = mock(Connection.class);
        CountDownLatch creatingConnection = new CountDownLatch(1);
        CountDownLatch releaseConnection = new CountDownLatch(1);
        CountDownLatch lateConnectionClosed = new CountDownLatch(1);
        when(factory.createConnection()).thenAnswer(invocation -> {
            creatingConnection.countDown();
            awaitIgnoringInterruption(releaseConnection);
            return lateConnection;
        });
        doAnswer(invocation -> {
            lateConnectionClosed.countDown();
            return null;
        }).when(lateConnection).close();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>())),
                sourceFailure));
        assertThat(creatingConnection.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();

        source.join(Duration.ofSeconds(1));
        assertThat(source.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        releaseConnection.countDown();
        assertThat(lateConnectionClosed.await(1, TimeUnit.SECONDS), is(true));
        connector.close();
        verify(lateConnection).close();
    }

    @Test
    @Timeout(5)
    void closeReportsFailureFromConnectionCreatedAfterForceClose() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection lateConnection = mock(Connection.class);
        CountDownLatch creatingConnection = new CountDownLatch(1);
        CountDownLatch releaseConnection = new CountDownLatch(1);
        JMSException cleanupFailure = new JMSException("late cleanup failed");
        when(factory.createConnection()).thenAnswer(invocation -> {
            creatingConnection.countDown();
            awaitIgnoringInterruption(releaseConnection);
            return lateConnection;
        });
        doThrow(cleanupFailure).when(lateConnection).close();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>())),
                sourceFailure));
        assertThat(creatingConnection.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        source.join(Duration.ofSeconds(1));
        releaseConnection.countDown();

        MessagingException failure = assertThrows(MessagingException.class, connector::close);
        assertThat(failure.getMessage(), containsString("Cannot close JMS resources"));
        assertThat(failure.getCause(), is(cleanupFailure));
        assertThat(sourceFailure.get(), nullValue());
    }

    @Test
    @Timeout(5)
    void forceCloseLetsSourceReturnWhileResourceCloseIsStillBlocked() throws Exception {
        JmsClient client = client();
        TextMessage nativeMessage = textMessage("in-flight");
        when(client.consumer.receive(anyLong())).thenReturn(nativeMessage);
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch connectionCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseConnectionClose = new CountDownLatch(1);
        doAnswer(invocation -> {
            connectionCloseStarted.countDown();
            while (releaseConnectionClose.getCount() != 0) {
                try {
                    releaseConnectionClose.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a provider close that ignores interruption.
                }
            }
            return null;
        }).when(client.connection).close();
        TestDelivery delivery = new TestDelivery(deliveryStarted, new CountDownLatch(1), null);
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(
                        new ArrayList<>(),
                        new TestReservation(new ArrayList<>(), delivery))),
                sourceFailure));
        assertThat(deliveryStarted.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();

        assertThat(connectionCloseStarted.await(1, TimeUnit.SECONDS), is(true));
        source.join(Duration.ofSeconds(1));
        assertThat(source.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        releaseConnectionClose.countDown();
        connector.close();
    }

    @Test
    @Timeout(5)
    void forceCloseDoesNotWaitForLateSessionCloseButNormalCloseDoes() throws Exception {
        JmsClient client = client();
        CountDownLatch creatingSession = new CountDownLatch(1);
        CountDownLatch releaseSession = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        CountDownLatch sessionCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseSessionClose = new CountDownLatch(1);
        when(client.connection.createSession(anyBoolean(), anyInt())).thenAnswer(invocation -> {
            creatingSession.countDown();
            awaitIgnoringInterruption(releaseSession);
            return client.session;
        });
        doAnswer(invocation -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection).close();
        doAnswer(invocation -> {
            sessionCloseStarted.countDown();
            awaitIgnoringInterruption(releaseSessionClose);
            return null;
        }).when(client.session).close();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>())),
                sourceFailure));
        assertThat(creatingSession.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        assertThat(connectionClosed.await(1, TimeUnit.SECONDS), is(true));
        source.join(Duration.ofSeconds(1));
        assertThat(source.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        releaseSession.countDown();
        assertThat(sessionCloseStarted.await(1, TimeUnit.SECONDS), is(true));

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = Thread.ofVirtual().start(() -> {
            capture(connector::close, closeFailure);
            closeReturned.countDown();
        });
        assertThat(closeReturned.await(50, TimeUnit.MILLISECONDS), is(false));
        releaseSessionClose.countDown();
        assertThat(closeReturned.await(1, TimeUnit.SECONDS), is(true));
        closer.join(Duration.ofSeconds(1));
        assertThat(closeFailure.get(), nullValue());
        verify(client.session).close();
    }

    @Test
    @Timeout(5)
    void forceCloseClosesConsumerCreatedAfterConnectionCloseCompleted() throws Exception {
        JmsClient client = client();
        CountDownLatch creatingConsumer = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        when(client.session.createConsumer(client.queue, null, false)).thenAnswer(invocation -> {
            creatingConsumer.countDown();
            awaitIgnoringInterruption(releaseConsumer);
            return client.consumer;
        });
        doAnswer(invocation -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection).close();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>())),
                sourceFailure));
        assertThat(creatingConsumer.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        assertThat(connectionClosed.await(1, TimeUnit.SECONDS), is(true));
        source.join(Duration.ofSeconds(1));
        assertThat(source.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        releaseConsumer.countDown();
        connector.close();

        verify(client.consumer).close();
    }

    @Test
    @Timeout(5)
    void drainDuringReceiveWaitsOnlyForConfiguredReceiveTimeout() throws Exception {
        JmsClient client = client();
        Duration receiveTimeout = Duration.ofMillis(37);
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);
        when(client.consumer.receive(anyLong())).thenAnswer(invocation -> {
            receiving.countDown();
            releaseReceive.await(100, TimeUnit.MILLISECONDS);
            return null;
        });
        TestReservation reservation = new TestReservation(new ArrayList<>(), TestDelivery.completed());
        JmsConnectorConfig connectorConfig = JmsConnectorConfig.builder()
                .from(config(false))
                .receiveTimeout(receiveTimeout)
                .build();
        IncomingConnector connector = JmsIncomingConnector.create(connectorConfig, ignored -> client.factory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>(), reservation)),
                failure));
        assertThat(receiving.await(1, TimeUnit.SECONDS), is(true));

        connector.drain();

        source.join(Duration.ofSeconds(1));
        assertThat(source.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(reservation.starts(), is(0));
        assertThat(reservation.closed(), is(true));
        verify(client.consumer).receive(receiveTimeout.toMillis());
    }

    @Test
    @Timeout(5)
    void closeAfterForceCloseWaitsForOutstandingResourceClose() throws Exception {
        JmsClient client = client();
        CountDownLatch connectionCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseConnectionClose = new CountDownLatch(1);
        CountDownLatch awaitRunningStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            connectionCloseStarted.countDown();
            while (releaseConnectionClose.getCount() != 0) {
                try {
                    releaseConnectionClose.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a provider close that ignores interruption.
                }
            }
            return null;
        }).when(client.connection).close();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>()) {
                    @Override
                    public boolean awaitRunning() {
                        awaitRunningStarted.countDown();
                        try {
                            new CountDownLatch(1).await();
                            return true;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }),
                sourceFailure));
        assertThat(awaitRunningStarted.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        assertThat(connectionCloseStarted.await(1, TimeUnit.SECONDS), is(true));
        source.join(Duration.ofSeconds(1));
        assertThat(source.isAlive(), is(false));
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = Thread.ofVirtual().start(() -> {
            capture(connector::close, closeFailure);
            closeReturned.countDown();
        });

        assertThat(closeReturned.await(50, TimeUnit.MILLISECONDS), is(false));
        releaseConnectionClose.countDown();
        assertThat(closeReturned.await(1, TimeUnit.SECONDS), is(true));
        closer.join(Duration.ofSeconds(1));
        assertThat(closeFailure.get(), nullValue());
        assertThat(sourceFailure.get(), nullValue());
        verify(client.connection, never()).start();
    }

    @Test
    @Timeout(5)
    void gracefulCloseTimeoutDoesNotPoisonRetry() throws Exception {
        JmsClient client = client();
        CountDownLatch awaitingRunning = new CountDownLatch(1);
        CountDownLatch connectionCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseConnectionClose = new CountDownLatch(1);
        doAnswer(invocation -> {
            connectionCloseStarted.countDown();
            awaitIgnoringInterruption(releaseConnectionClose);
            return null;
        }).when(client.connection).close();
        JmsConnectorConfig connectorConfig = JmsConnectorConfig.builder()
                .from(config(false))
                .closeTimeout(Duration.ofMillis(50))
                .build();
        IncomingConnector connector = JmsIncomingConnector.create(connectorConfig, ignored -> client.factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>()) {
                    @Override
                    public boolean awaitRunning() {
                        awaitingRunning.countDown();
                        try {
                            new CountDownLatch(1).await();
                            return true;
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                }),
                sourceFailure));
        assertThat(awaitingRunning.await(1, TimeUnit.SECONDS), is(true));

        try {
            MessagingException failure = assertThrows(MessagingException.class, connector::close);
            assertThat(failure.getMessage(), containsString("Timed out closing JMS"));
            assertThat(connectionCloseStarted.await(1, TimeUnit.SECONDS), is(true));
            releaseConnectionClose.countDown();

            connector.close();
            source.join(Duration.ofSeconds(1));
            assertThat(source.isAlive(), is(false));
            assertThat(sourceFailure.get(), nullValue());
            verify(client.connection, times(1)).close();
        } finally {
            releaseConnectionClose.countDown();
            connector.forceClose();
            source.join(Duration.ofSeconds(1));
        }
    }

    @Test
    @Timeout(5)
    void hugeCloseTimeoutIsSaturatedBeforeRequestingClose() throws Exception {
        JmsClient client = client();
        CountDownLatch awaitingRunning = new CountDownLatch(1);
        JmsConnectorConfig connectorConfig = JmsConnectorConfig.builder()
                .from(config(false))
                .closeTimeout(Duration.ofSeconds(Long.MAX_VALUE))
                .build();
        IncomingConnector connector = JmsIncomingConnector.create(connectorConfig, ignored -> client.factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>()) {
                    @Override
                    public boolean awaitRunning() {
                        awaitingRunning.countDown();
                        try {
                            new CountDownLatch(1).await();
                            return true;
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                }),
                sourceFailure));
        assertThat(awaitingRunning.await(1, TimeUnit.SECONDS), is(true));

        try {
            connector.close();
        } finally {
            connector.forceClose();
        }

        source.join(Duration.ofSeconds(1));
        assertThat(source.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        verify(client.connection).close();
        verify(client.connection, never()).start();
    }

    @Test
    @Timeout(5)
    void deliveryThreadCloseBeforePublicationDoesNotDeadlock() throws Exception {
        JmsClient client = client();
        TextMessage nativeMessage = textMessage("race");
        when(client.consumer.receive(anyLong())).thenReturn(nativeMessage);
        AtomicReference<IncomingConnector> connectorReference = new AtomicReference<>();
        AtomicReference<Thread> closerReference = new AtomicReference<>();
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        TestDelivery delivery = new TestDelivery(new CountDownLatch(0),
                                                 new CountDownLatch(0),
                                                 closerReference);
        TestReservation reservation = new TestReservation(new ArrayList<>(), delivery) {
            @Override
            public ConnectorDelivery start(MessageBatch<?> batch) {
                recordStart();
                Thread closer = Thread.ofVirtual().unstarted(() -> {
                    capture(connectorReference.get()::close, closeFailure);
                    closeReturned.countDown();
                });
                closerReference.set(closer);
                closer.start();
                try {
                    // requestClose interrupts the source before close waits for this delivery to be published.
                    new CountDownLatch(1).await();
                    throw new AssertionError("JMS close did not interrupt delivery admission");
                } catch (InterruptedException expected) {
                    // The admission can now return and publish the delivery handle used by close's self-call check.
                }
                return delivery;
            }
        };
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        connectorReference.set(connector);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>(), reservation)),
                sourceFailure));

        assertThat(closeReturned.await(2, TimeUnit.SECONDS), is(true));
        source.join(Duration.ofSeconds(2));

        assertThat(source.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        assertThat(closeFailure.get(), nullValue());
    }

    @Test
    void naturalShutdownReportsJmsResourceCleanupFailure() throws Exception {
        JmsClient client = client();
        JMSException connectionFailure = new JMSException("connection close failed");
        JMSException consumerFailure = new JMSException("consumer close failed");
        JMSException sessionFailure = new JMSException("session close failed");
        doThrow(connectionFailure).when(client.connection).close();
        doThrow(consumerFailure).when(client.consumer).close();
        doThrow(sessionFailure).when(client.session).close();
        IncomingConnector connector = JmsIncomingConnector.create(config(false), ignored -> client.factory);
        TestContext context = new TestContext(new ArrayList<>()) {
            @Override
            public boolean awaitRunning() {
                return false;
            }
        };

        MessagingException failure = assertThrows(MessagingException.class, () -> connector.run(context));

        assertThat(failure.getMessage(), containsString("Cannot close JMS resources"));
        assertThat(failure.getCause(), is(connectionFailure));
        assertThat(List.of(failure.getCause().getSuppressed()), is(List.of(consumerFailure, sessionFailure)));
        verify(client.connection, never()).start();
        verify(client.consumer).close();
        verify(client.session).close();
    }

    private static JmsClient client() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        Queue queue = mock(Queue.class);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
        when(session.createQueue("events")).thenReturn(queue);
        when(session.createConsumer(queue, null, false)).thenReturn(consumer);
        return new JmsClient(factory, connection, session, consumer, queue);
    }

    private static TextMessage textMessage(String body) throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn(body);
        when(message.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        return message;
    }

    private static char[] connectorPassword(IncomingConnector connector) throws Exception {
        Field connectionSupportField = connector.getClass().getDeclaredField("connectionSupport");
        connectionSupportField.setAccessible(true);
        Object connectionSupport = connectionSupportField.get(connector);
        Field passwordField = JmsConnectionSupport.class.getDeclaredField("password");
        passwordField.setAccessible(true);
        return (char[]) passwordField.get(connectionSupport);
    }

    private static JmsConnectorConfig config(boolean transacted) {
        return JmsConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel(CHANNEL)
                .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                .destination("events")
                .transacted(transacted)
                .receiveTimeout(Duration.ofMillis(10))
                .closeTimeout(Duration.ofSeconds(1))
                .reconnectInitialDelay(Duration.ofMillis(1))
                .reconnectMaxDelay(Duration.ofMillis(1))
                .reconnectJitter(0)
                .build();
    }

    private static void assertReconnectWaitIsCloseable(JmsConnectorConfig config) throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        CountDownLatch connectionAttempted = new CountDownLatch(1);
        when(factory.createConnection()).thenAnswer(invocation -> {
            connectionAttempted.countDown();
            throw new JMSException("broker unavailable");
        });
        IncomingConnector connector = JmsIncomingConnector.create(config, ignored -> factory);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread source = Thread.ofVirtual().start(() -> capture(
                () -> connector.run(new TestContext(new ArrayList<>())),
                sourceFailure));
        assertThat(connectionAttempted.await(1, TimeUnit.SECONDS), is(true));

        try {
            source.join(Duration.ofMillis(200));
            assertThat(source.isAlive(), is(true));
        } finally {
            connector.forceClose();
            source.join(Duration.ofSeconds(1));
        }

        assertThat(source.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        verify(factory, times(1)).createConnection();
    }

    private static void capture(Runnable task, AtomicReference<Throwable> failure) {
        try {
            task.run();
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private static void awaitIgnoringInterruption(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.interrupted();
        }
    }

    private record JmsClient(ConnectionFactory factory,
                             Connection connection,
                             Session session,
                             MessageConsumer consumer,
                             Queue queue) {
    }

    private static class TestContext implements IncomingConnectorContext {
        private final List<String> events;
        private final List<ConnectorDeliveryReservation> reservations;
        private final AtomicInteger nextReservation = new AtomicInteger();

        private TestContext(List<String> events, ConnectorDeliveryReservation... reservations) {
            this.events = events;
            this.reservations = List.of(reservations);
        }

        @Override
        public boolean awaitRunning() {
            return true;
        }

        @Override
        public String channel() {
            return CHANNEL;
        }

        @Override
        public int maxDeliveryMessages() {
            return 1;
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery() {
            throw new AssertionError("JMS must use non-blocking admission while remaining drainable");
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
            events.add("reserve");
            int index = nextReservation.getAndIncrement();
            return index < reservations.size() ? Optional.of(reservations.get(index)) : Optional.empty();
        }
    }

    private static class TestReservation implements ConnectorDeliveryReservation {
        private final List<String> events;
        private final ConnectorDelivery delivery;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        private TestReservation(List<String> events, ConnectorDelivery delivery) {
            this.events = events;
            this.delivery = delivery;
        }

        @Override
        public ConnectorDelivery start(MessageBatch<?> batch) {
            recordStart();
            return delivery;
        }

        @Override
        public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
            return Optional.of(start(batch));
        }

        @Override
        public void close() {
            closed.set(true);
        }

        protected final void recordStart() {
            starts.incrementAndGet();
            events.add("start");
        }

        private int starts() {
            return starts.get();
        }

        private boolean closed() {
            return closed.get();
        }
    }

    private static final class TestDelivery implements ConnectorDelivery {
        private final CountDownLatch started;
        private final CountDownLatch release;
        private final AtomicReference<Thread> owner;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private TestDelivery(CountDownLatch started,
                             CountDownLatch release,
                             AtomicReference<Thread> owner) {
            this.started = started;
            this.release = release;
            this.owner = owner;
        }

        private static TestDelivery completed() {
            return new TestDelivery(new CountDownLatch(0), new CountDownLatch(0), null);
        }

        @Override
        public boolean isDone() {
            return release.getCount() == 0;
        }

        @Override
        public boolean isCurrentThread() {
            return owner != null && owner.get() == Thread.currentThread();
        }

        @Override
        public void await() throws InterruptedException {
            started.countDown();
            release.await();
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            started.countDown();
            return release.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            release.countDown();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private boolean closed() {
            return closed.get();
        }
    }
}
