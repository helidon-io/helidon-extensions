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

package io.helidon.extensions.messaging.connectors.aq;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import io.helidon.messaging.ConnectorDelivery;
import io.helidon.messaging.ConnectorDeliveryReservation;
import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingChannel;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.MessagingGraph;
import io.helidon.service.registry.ServiceRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class AqDrainTest {
    @Test
    void testIdleGraphClosesBeforeLongReceiveTimeout() throws Exception {
        Client client = client();
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch consumerClosed = new CountDownLatch(1);
        when(client.consumer().receive(30_000)).thenAnswer(_ -> {
            receiving.countDown();
            try {
                consumerClosed.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        AtomicInteger deliveries = new AtomicInteger();
        var incoming = AqConnector.builder()
                .name("aq")
                .connectionFactory(client.factory())
                .destination("ORDERS")
                .receiveTimeout(Duration.ofSeconds(30))
                .closeTimeout(Duration.ofSeconds(2))
                .build()
                .incoming(channelConfig());
        var channel = MessagingChannel.create("orders", String.class);
        var graph = MessagingGraph.builder()
                .shutdownTimeout(Duration.ofMillis(500))
                .channel(channel)
                .incomingChannel(channel, incoming)
                .payloadSink(channel, _ -> deliveries.incrementAndGet())
                .build();
        try {
            graph.start();
            assertThat(receiving.await(2, TimeUnit.SECONDS), is(true));
            graph.close();
        } finally {
            consumerClosed.countDown();
            incoming.forceClose();
            incoming.close();
        }

        assertThat(deliveries.get(), is(0));
        verify(client.consumer()).receive(30_000);
        verify(client.consumer(), never()).close();
        verify(client.connection()).close();
        verify(client.session(), never()).commit();
    }

    @Test
    void testMessageReturnedDuringDrainClearsAcquisitionInterruptBeforeSettlement() throws Exception {
        Client client = client();
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);
        TextMessage message = message();
        when(client.consumer().receive(30_000)).thenAnswer(_ -> {
            receiving.countDown();
            awaitIgnoringInterruption(releaseReceive, interrupted);
            return message;
        });
        doAnswer(_ -> {
            assertThat(Thread.currentThread().isInterrupted(), is(false));
            return null;
        }).when(client.session()).commit();
        ConnectorDeliveryReservation reservation = reservation();
        AqIncomingChannel channel = incoming(client);
        FutureTask<Void> run = run(channel, reservation);
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(receiving.await(2, TimeUnit.SECONDS), is(true));
            channel.drain();
            assertThat(interrupted.await(2, TimeUnit.SECONDS), is(true));
            verify(reservation, never()).start(any(MessageBatch.class));
            verify(client.session(), never()).commit();
            verify(client.connection(), never()).close();
            releaseReceive.countDown();
            run.get(2, TimeUnit.SECONDS);
        } finally {
            releaseReceive.countDown();
            channel.forceClose();
            runner.join(Duration.ofSeconds(2));
            channel.close();
        }

        assertThat(runner.isAlive(), is(false));
        var order = inOrder(reservation, client.session(), client.connection());
        order.verify(reservation).start(any(MessageBatch.class));
        order.verify(client.session()).commit();
        order.verify(client.connection()).close();
        verify(client.session(), never()).rollback();
        verify(client.consumer(), never()).close();
    }

    @Test
    void testReceiveCancelledDuringDrainCompletesNormally() throws Exception {
        Client client = client();
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch consumerClosed = new CountDownLatch(1);
        when(client.consumer().receive(30_000)).thenAnswer(_ -> {
            receiving.countDown();
            try {
                consumerClosed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                JMSException failure = new JMSException("receive cancelled");
                failure.setLinkedException(e);
                throw failure;
            }
            return null;
        });
        ConnectorDeliveryReservation reservation = reservation();
        AqIncomingChannel channel = incoming(client);
        FutureTask<Void> run = run(channel, reservation);
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(receiving.await(2, TimeUnit.SECONDS), is(true));
            channel.drain();
            run.get(2, TimeUnit.SECONDS);
        } finally {
            consumerClosed.countDown();
            channel.forceClose();
            runner.join(Duration.ofSeconds(2));
            channel.close();
        }

        assertThat(runner.isAlive(), is(false));
        verify(reservation, never()).start(any(MessageBatch.class));
        verify(client.consumer(), never()).close();
        verify(client.connection()).close();
    }

    @Test
    void testDrainPreservesPreexistingReceiveInterrupt() throws Exception {
        Client client = client();
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);
        TextMessage message = message();
        when(client.consumer().receive(30_000)).thenAnswer(_ -> {
            Thread.currentThread().interrupt();
            receiving.countDown();
            // Keep the external interrupt visible while drain inspects the source thread.
            while (releaseReceive.getCount() != 0) {
                Thread.onSpinWait();
            }
            return message;
        });
        doAnswer(_ -> {
            assertThat(Thread.currentThread().isInterrupted(), is(true));
            return null;
        }).when(client.session()).commit();
        AqIncomingChannel channel = incoming(client);
        FutureTask<Void> run = run(channel, reservation());
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(receiving.await(2, TimeUnit.SECONDS), is(true));
            channel.drain();
            releaseReceive.countDown();
            run.get(2, TimeUnit.SECONDS);
        } finally {
            releaseReceive.countDown();
            channel.forceClose();
            runner.join(Duration.ofSeconds(2));
            channel.close();
        }

        assertThat(runner.isAlive(), is(false));
        verify(client.session()).commit();
        verify(client.connection()).close();
    }

    @Test
    void testDrainDuringDeliveryPreservesSessionUntilCommit() throws Exception {
        Client client = client();
        TextMessage message = message();
        when(client.consumer().receive(30_000)).thenReturn(message);
        CountDownLatch delivering = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        doAnswer(_ -> {
            delivering.countDown();
            releaseDelivery.await();
            return null;
        }).when(delivery).await();
        ConnectorDeliveryReservation reservation = reservation();
        when(reservation.start(any(MessageBatch.class))).thenReturn(delivery);
        AqIncomingChannel channel = incoming(client);
        FutureTask<Void> run = run(channel, reservation);
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(delivering.await(2, TimeUnit.SECONDS), is(true));
            channel.drain();
            verify(client.consumer(), never()).close();
            verify(client.connection(), never()).close();
            verify(client.session(), never()).commit();
            releaseDelivery.countDown();
            run.get(2, TimeUnit.SECONDS);
        } finally {
            releaseDelivery.countDown();
            channel.forceClose();
            runner.join(Duration.ofSeconds(2));
            channel.close();
        }

        assertThat(runner.isAlive(), is(false));
        var order = inOrder(client.session(), client.connection());
        order.verify(client.session()).commit();
        order.verify(client.connection()).close();
        verify(client.consumer(), never()).close();
        verify(client.consumer()).receive(30_000);
    }

    @Test
    void testDrainDuringStartupClosesLateConsumerWithoutReceiving() throws Exception {
        Client client = client();
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.session().createConsumer(any(Queue.class), isNull(), eq(false))).thenAnswer(_ -> {
            creating.countDown();
            release.await();
            return client.consumer();
        });
        ConnectorDeliveryReservation reservation = reservation();
        AqIncomingChannel channel = incoming(client);
        FutureTask<Void> run = run(channel, reservation);
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(creating.await(2, TimeUnit.SECONDS), is(true));
            channel.drain();
            release.countDown();
            run.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            channel.forceClose();
            runner.join(Duration.ofSeconds(2));
            channel.close();
        }

        assertThat(runner.isAlive(), is(false));
        verify(client.consumer(), never()).receive(30_000);
        verify(client.connection(), never()).start();
        verify(client.connection()).close();
        verify(reservation, never()).start(any(MessageBatch.class));
    }

    @Test
    void testConnectionCloseFailureIsReportedAfterCancelledReceive() throws Exception {
        Client client = client();
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        when(client.consumer().receive(30_000)).thenAnswer(_ -> {
            receiving.countDown();
            try {
                closing.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        JMSException closeFailure = new JMSException("connection close failed");
        doAnswer(_ -> {
            closing.countDown();
            throw closeFailure;
        }).when(client.connection()).close();
        AqIncomingChannel channel = incoming(client);
        FutureTask<Void> run = run(channel, reservation());
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(receiving.await(2, TimeUnit.SECONDS), is(true));
            channel.drain();
            ExecutionException result = assertThrows(ExecutionException.class, () -> run.get(2, TimeUnit.SECONDS));
            assertThat(result.getCause().getMessage(), containsString("Cannot close AQ channel"));
            assertThat(result.getCause().getCause(), sameInstance(closeFailure));
        } finally {
            closing.countDown();
            channel.forceClose();
            runner.join(Duration.ofSeconds(2));
            MessagingException result = assertThrows(MessagingException.class, channel::close);
            assertThat(result.getCause(), sameInstance(closeFailure));
        }

        assertThat(runner.isAlive(), is(false));
        verify(client.consumer(), never()).close();
        verify(client.connection()).close();
        verify(client.session(), never()).commit();
    }

    private static Client client() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        Queue queue = mock(Queue.class);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(true, Session.SESSION_TRANSACTED)).thenReturn(session);
        when(session.createQueue("ORDERS")).thenReturn(queue);
        when(session.createConsumer(queue, null, false)).thenReturn(consumer);
        return new Client(factory, connection, session, consumer);
    }

    private static TextMessage message() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn("payload");
        when(message.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        return message;
    }

    private static ConnectorDeliveryReservation reservation() {
        ConnectorDeliveryReservation reservation = mock(ConnectorDeliveryReservation.class);
        when(reservation.start(any(MessageBatch.class))).thenReturn(mock(ConnectorDelivery.class));
        return reservation;
    }

    private static FutureTask<Void> run(AqIncomingChannel channel, ConnectorDeliveryReservation reservation) {
        IncomingConnectorContext context = mock(IncomingConnectorContext.class);
        when(context.awaitRunning()).thenReturn(true);
        when(context.maxDeliveryMessages()).thenReturn(1);
        when(context.tryReserveDelivery()).thenReturn(Optional.of(reservation));
        return new FutureTask<>(() -> {
            channel.run(context);
            return null;
        });
    }

    private static AqIncomingChannel incoming(Client client) {
        AqRuntimeConfig config = AqRuntimeConfig.incoming(AqConnectorConfig.builder()
                .name("aq")
                .connectionFactory(client.factory())
                .destination("ORDERS")
                .receiveTimeout(Duration.ofSeconds(30))
                .closeTimeout(Duration.ofSeconds(2))
                .buildPrototype(), channelConfig());
        return new AqIncomingChannel(config, new AqResourceResolver(mock(ServiceRegistry.class)));
    }

    private static AqIncomingConfig channelConfig() {
        return AqIncomingConfig.builder().connector("aq").channelName("orders").build();
    }

    private static void awaitIgnoringInterruption(CountDownLatch latch, CountDownLatch interruptedSignal) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException _) {
                    interrupted = true;
                    interruptedSignal.countDown();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record Client(ConnectionFactory factory, Connection connection, Session session, MessageConsumer consumer) {
    }
}
