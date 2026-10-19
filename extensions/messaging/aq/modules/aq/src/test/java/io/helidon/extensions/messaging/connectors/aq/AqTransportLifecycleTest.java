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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import io.helidon.messaging.BatchDeliveryException;
import io.helidon.messaging.BatchItemStatus;
import io.helidon.messaging.ConnectorDelivery;
import io.helidon.messaging.ConnectorDeliveryReservation;
import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class AqTransportLifecycleTest {
    @Test
    void testForceCloseCancelsBlockedStartupAndClosesLateConnection() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(factory.createConnection()).thenAnswer(_ -> {
            creating.countDown();
            awaitIgnoringInterruption(release);
            return connection;
        });
        AqResources resources = new AqResources(config(factory, true), resolver());
        FutureTask<MessagingException> start = new FutureTask<>(
                () -> assertThrows(MessagingException.class, () -> resources.open(true)));
        Thread starter = Thread.ofVirtual().start(start);
        try {
            assertThat(creating.await(2, TimeUnit.SECONDS), is(true));
            resources.forceClose();
            assertThat(start.get(1, TimeUnit.SECONDS).getMessage(), containsString("closed during startup"));
            verify(connection, never()).close();
        } finally {
            release.countDown();
            resources.close();
            starter.join(Duration.ofSeconds(2));
        }
        assertThat(starter.isAlive(), is(false));
        verify(connection).close();
        verify(connection, never()).createSession(true, Session.SESSION_TRANSACTED);
    }

    @Test
    void testCleanupTimeoutIsBoundedAndLaterCloseObservesCompletion() throws Exception {
        Client client = client(true);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(_ -> {
            closing.countDown();
            awaitIgnoringInterruption(release);
            return null;
        }).when(client.connection()).close();
        AqRuntimeConfig config = AqRuntimeConfig.incoming(AqConnectorConfig.builder()
                .name("aq")
                .connectionFactory(client.factory())
                .destination("ORDERS")
                .closeTimeout(Duration.ofMillis(100))
                .buildPrototype(), channelConfig());
        AqResources resources = new AqResources(config, resolver());
        resources.open(true);
        try {
            resources.forceClose();
            assertThat(closing.await(2, TimeUnit.SECONDS), is(true));
            var failure = assertThrows(MessagingException.class, resources::close);
            assertThat(failure.getMessage(), containsString("Timed out closing"));
        } finally {
            release.countDown();
        }
        resources.close();
        verify(client.connection()).close();
    }

    @Test
    void testDrainWaitsForDeliveryBeforeCommitAndReleasesAdmissionAfterSettlement() throws Exception {
        Client client = client(true);
        TextMessage nativeMessage = message();
        AtomicReference<Thread> receiveOwner = new AtomicReference<>();
        AtomicReference<Thread> settlementOwner = new AtomicReference<>();
        when(client.consumer().receive(100)).thenAnswer(_ -> {
            receiveOwner.set(Thread.currentThread());
            return nativeMessage;
        });
        doAnswer(_ -> {
            settlementOwner.set(Thread.currentThread());
            return null;
        }).when(client.session()).commit();
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(_ -> {
            awaiting.countDown();
            release.await();
            return null;
        }).when(delivery).await();
        ConnectorDeliveryReservation reservation = reservation(delivery);
        AqIncomingChannel channel = new AqIncomingChannel(config(client.factory(), true), resolver());
        FutureTask<Void> run = new FutureTask<>(() -> {
            channel.run(context(reservation));
            return null;
        });
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(awaiting.await(2, TimeUnit.SECONDS), is(true));
            verify(client.consumer(), times(1)).receive(100);
            verify(client.session(), never()).commit();
            verify(delivery, never()).close();
            channel.drain();
            assertThat(run.isDone(), is(false));
            release.countDown();
            run.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            channel.forceClose();
            runner.join(Duration.ofSeconds(2));
            channel.close();
        }
        assertThat(runner.isAlive(), is(false));
        assertThat(settlementOwner.get(), sameInstance(receiveOwner.get()));
        var order = inOrder(client.session(), delivery, reservation, client.connection());
        order.verify(client.session()).commit();
        order.verify(delivery).close();
        order.verify(reservation).close();
        order.verify(client.connection()).close();
        verify(client.consumer(), times(1)).receive(100);
    }

    @Test
    void testFailedDeliveryRollsBackBeforeAdmissionIsReleased() throws Exception {
        Client client = client(true);
        TextMessage nativeMessage = message();
        when(client.consumer().receive(100)).thenReturn(nativeMessage);
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        IllegalStateException failure = new IllegalStateException("handler failed");
        doThrow(failure).when(delivery).await();
        ConnectorDeliveryReservation reservation = reservation(delivery);
        AqIncomingChannel channel = new AqIncomingChannel(config(client.factory(), true), resolver());

        assertThat(assertThrows(IllegalStateException.class, () -> channel.run(context(reservation))), sameInstance(failure));
        channel.close();

        var order = inOrder(client.session(), delivery, reservation, client.connection());
        order.verify(client.session()).rollback();
        order.verify(delivery).close();
        order.verify(reservation).close();
        order.verify(client.connection()).close();
        verify(client.session(), never()).commit();
    }

    @Test
    void testNonTransactedDeliveryAcknowledgesOnlyAfterCompletion() throws Exception {
        Client client = client(false);
        TextMessage nativeMessage = message();
        when(client.consumer().receive(100)).thenReturn(nativeMessage);
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        ConnectorDeliveryReservation reservation = reservation(delivery);
        AqIncomingChannel channel = new AqIncomingChannel(config(client.factory(), false), resolver());
        doAnswer(_ -> {
            verify(nativeMessage, never()).acknowledge();
            channel.drain();
            return null;
        }).when(delivery).await();

        channel.run(context(reservation));
        channel.close();

        var order = inOrder(delivery, nativeMessage);
        order.verify(delivery).await();
        order.verify(nativeMessage).acknowledge();
        order.verify(delivery).close();
        verify(client.session(), never()).commit();
        verify(client.session(), never()).recover();
    }

    @Test
    void testNonTransactedFailedDeliveryRecoversWithoutAcknowledgement() throws Exception {
        Client client = client(false);
        TextMessage nativeMessage = message();
        when(client.consumer().receive(100)).thenReturn(nativeMessage);
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        doThrow(new IllegalStateException("handler failed")).when(delivery).await();
        AqIncomingChannel channel = new AqIncomingChannel(config(client.factory(), false), resolver());

        assertThrows(IllegalStateException.class, () -> channel.run(context(reservation(delivery))));
        channel.close();

        verify(client.session()).recover();
        verify(nativeMessage, never()).acknowledge();
    }

    @Test
    void testDrainDuringReceiveSettlesAlreadyAcquiredMessage() throws Exception {
        Client client = client(true);
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TextMessage nativeMessage = message();
        when(client.consumer().receive(100)).thenAnswer(_ -> {
            receiving.countDown();
            awaitIgnoringInterruption(release);
            return nativeMessage;
        });
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        ConnectorDeliveryReservation reservation = reservation(delivery);
        AqIncomingChannel channel = new AqIncomingChannel(config(client.factory(), true), resolver());
        FutureTask<Void> run = new FutureTask<>(() -> {
            channel.run(context(reservation));
            return null;
        });
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(receiving.await(2, TimeUnit.SECONDS), is(true));
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
        verify(reservation).start(any(MessageBatch.class));
        verify(client.session()).commit();
        verify(client.session(), never()).rollback();
        verify(reservation).close();
    }

    @Test
    void testForceCloseDoesNotDispatchMessageReturnedAfterCancellation() throws Exception {
        Client client = client(true);
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TextMessage nativeMessage = message();
        when(client.consumer().receive(100)).thenAnswer(_ -> {
            receiving.countDown();
            awaitIgnoringInterruption(release);
            return nativeMessage;
        });
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        ConnectorDeliveryReservation reservation = reservation(delivery);
        AqIncomingChannel channel = new AqIncomingChannel(config(client.factory(), true), resolver());
        FutureTask<Void> run = new FutureTask<>(() -> {
            channel.run(context(reservation));
            return null;
        });
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(receiving.await(2, TimeUnit.SECONDS), is(true));
            channel.forceClose();
            release.countDown();
            run.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            channel.forceClose();
            runner.join(Duration.ofSeconds(2));
            channel.close();
        }

        assertThat(runner.isAlive(), is(false));
        verify(reservation, never()).start(any(MessageBatch.class));
        verify(client.session(), never()).commit();
        verify(reservation).close();
        verify(client.connection()).close();
    }

    @Test
    void testSessionReturnedAfterConnectionClosedIsClosed() throws Exception {
        Client client = client(true);
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        when(client.connection().createSession(true, Session.SESSION_TRANSACTED)).thenAnswer(_ -> {
            creating.countDown();
            awaitIgnoringInterruption(release);
            return client.session();
        });
        doAnswer(_ -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection()).close();
        AqResources resources = new AqResources(config(client.factory(), true), resolver());
        FutureTask<MessagingException> start = new FutureTask<>(
                () -> assertThrows(MessagingException.class, () -> resources.open(true)));
        Thread starter = Thread.ofVirtual().start(start);
        try {
            assertThat(creating.await(2, TimeUnit.SECONDS), is(true));
            resources.forceClose();
            assertThat(connectionClosed.await(2, TimeUnit.SECONDS), is(true));
            start.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            resources.close();
            starter.join(Duration.ofSeconds(2));
        }
        assertThat(starter.isAlive(), is(false));
        verify(client.session()).close();
        verify(client.session(), never()).createQueue("ORDERS");
    }

    @Test
    void testConcurrentSendsSerializeSessionUseAndCommitEachBatch() throws Exception {
        Client client = client(true);
        TextMessage first = message();
        TextMessage second = message();
        when(client.session().createTextMessage("first")).thenReturn(first);
        when(client.session().createTextMessage("second")).thenReturn(second);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondInvoked = new CountDownLatch(1);
        doAnswer(_ -> {
            sending.countDown();
            release.await();
            return null;
        }).when(client.producer()).send(first, 2, 4, 0);
        AqOutgoingChannel channel = outgoingChannel(client, true);
        channel.start();
        FutureTask<Void> firstSend = new FutureTask<>(() -> {
            channel.sendBatch(MessageBatch.create(Message.create("first")));
            return null;
        });
        FutureTask<Void> secondSend = new FutureTask<>(() -> {
            secondInvoked.countDown();
            channel.sendBatch(MessageBatch.create(Message.create("second")));
            return null;
        });
        Thread firstSender = Thread.ofVirtual().start(firstSend);
        Thread secondSender = null;
        try {
            assertThat(sending.await(2, TimeUnit.SECONDS), is(true));
            secondSender = Thread.ofVirtual().start(secondSend);
            assertThat(secondInvoked.await(2, TimeUnit.SECONDS), is(true));
            verify(client.session(), never()).createTextMessage("second");
            verify(client.session(), never()).commit();
            release.countDown();
            firstSend.get(2, TimeUnit.SECONDS);
            secondSend.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            channel.forceClose();
            firstSender.join(Duration.ofSeconds(2));
            if (secondSender != null) {
                secondSender.join(Duration.ofSeconds(2));
            }
            channel.close();
        }
        assertThat(firstSender.isAlive(), is(false));
        assertThat(secondSender.isAlive(), is(false));
        var order = inOrder(client.session(), client.producer());
        order.verify(client.session()).createTextMessage("first");
        order.verify(client.producer()).send(first, 2, 4, 0);
        order.verify(client.session()).commit();
        order.verify(client.session()).createTextMessage("second");
        order.verify(client.producer()).send(second, 2, 4, 0);
        order.verify(client.session()).commit();
    }

    @Test
    void testLateConsumerCleanupFailureStillClosesSessionAndIsReported() throws Exception {
        Client client = client(true);
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        when(client.session().createConsumer(any(Queue.class), isNull(), eq(false))).thenAnswer(_ -> {
            creating.countDown();
            awaitIgnoringInterruption(release);
            return client.consumer();
        });
        doAnswer(_ -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection()).close();
        JMSException failure = new JMSException("late consumer close failed");
        doThrow(failure).when(client.consumer()).close();
        AqResources resources = new AqResources(config(client.factory(), true), resolver());
        FutureTask<MessagingException> start = new FutureTask<>(
                () -> assertThrows(MessagingException.class, () -> resources.open(true)));
        Thread starter = Thread.ofVirtual().start(start);
        MessagingException cleanupFailure;
        try {
            assertThat(creating.await(2, TimeUnit.SECONDS), is(true));
            resources.forceClose();
            assertThat(connectionClosed.await(2, TimeUnit.SECONDS), is(true));
            start.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            try {
                cleanupFailure = assertThrows(MessagingException.class, resources::close);
            } finally {
                starter.join(Duration.ofSeconds(2));
            }
        }

        assertThat(starter.isAlive(), is(false));
        assertThat(cleanupFailure.getCause(), sameInstance(failure));
        verify(client.consumer()).close();
        verify(client.session()).close();
    }

    @Test
    void testCommitFailureMarksWholeTransactionIndeterminate() throws Exception {
        Client client = client(true);
        TextMessage first = message();
        TextMessage second = message();
        when(client.session().createTextMessage("first")).thenReturn(first);
        when(client.session().createTextMessage("second")).thenReturn(second);
        JMSException failure = new JMSException("commit connection lost");
        doThrow(failure).when(client.session()).commit();
        MessageBatch<String> batch = MessageBatch.create(List.of(Message.create("first"), Message.create("second")));
        AqOutgoingChannel channel = outgoingChannel(client, true);
        channel.start();
        try {
            BatchDeliveryException result = assertThrows(BatchDeliveryException.class, () -> channel.sendBatch(batch));
            assertThat(result.batch(), sameInstance(batch));
            assertThat(result.getCause(), sameInstance(failure));
            assertThat(result.outcome(0).status(), is(BatchItemStatus.INDETERMINATE));
            assertThat(result.outcome(1).status(), is(BatchItemStatus.INDETERMINATE));
        } finally {
            channel.close();
        }
        verify(client.session()).rollback();
        verify(client.connection()).close();
    }

    @Test
    void testGracefulOutgoingCloseLetsAcceptedBatchCommit() throws Exception {
        Client client = client(true);
        TextMessage nativeMessage = message();
        when(client.session().createTextMessage("accepted")).thenReturn(nativeMessage);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeRequested = new CountDownLatch(1);
        doAnswer(_ -> {
            sending.countDown();
            release.await();
            return null;
        }).when(client.producer()).send(nativeMessage, 2, 4, 0);
        AqOutgoingChannel channel = outgoingChannel(client, true);
        channel.start();
        FutureTask<Void> send = new FutureTask<>(() -> {
            channel.sendBatch(MessageBatch.create(Message.create("accepted")));
            return null;
        });
        FutureTask<Void> close = new FutureTask<>(() -> {
            closeRequested.countDown();
            channel.close();
            return null;
        });
        Thread sender = Thread.ofVirtual().start(send);
        Thread closer = null;
        try {
            assertThat(sending.await(2, TimeUnit.SECONDS), is(true));
            closer = Thread.ofVirtual().start(close);
            assertThat(closeRequested.await(2, TimeUnit.SECONDS), is(true));
            assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));
            verify(client.session(), never()).commit();
            verify(client.connection(), never()).close();
            release.countDown();
            send.get(2, TimeUnit.SECONDS);
            close.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            channel.forceClose();
            sender.join(Duration.ofSeconds(2));
            if (closer != null) {
                closer.join(Duration.ofSeconds(2));
            }
            channel.close();
        }
        assertThat(sender.isAlive(), is(false));
        assertThat(closer.isAlive(), is(false));
        var order = inOrder(client.producer(), client.session(), client.connection());
        order.verify(client.producer()).send(nativeMessage, 2, 4, 0);
        order.verify(client.session()).commit();
        order.verify(client.connection()).close();
        verify(client.session(), never()).rollback();
    }

    @Test
    void testFailedSendWithSuccessfulRollbackMarksAttemptedItemsFailed() throws Exception {
        Client client = client(true);
        TextMessage first = message();
        TextMessage second = message();
        when(client.session().createTextMessage("first")).thenReturn(first);
        when(client.session().createTextMessage("second")).thenReturn(second);
        doThrow(new JMSException("enqueue failed")).when(client.producer()).send(second, 2, 4, 0);
        MessageBatch<String> batch = MessageBatch.create(List.of(Message.create("first"), Message.create("second")));
        AqOutgoingChannel channel = outgoingChannel(client, true);
        channel.start();
        try {
            BatchDeliveryException result = assertThrows(BatchDeliveryException.class, () -> channel.sendBatch(batch));
            assertThat(result.batch(), sameInstance(batch));
            assertThat(result.outcome(0).status(), is(BatchItemStatus.FAILED));
            assertThat(result.outcome(1).status(), is(BatchItemStatus.FAILED));
        } finally {
            channel.close();
        }
        verify(client.session()).rollback();
        verify(client.session(), never()).commit();
    }

    private static Client client(boolean transacted) throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        MessageProducer producer = mock(MessageProducer.class);
        Queue queue = mock(Queue.class);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(transacted, transacted ? Session.SESSION_TRANSACTED : Session.CLIENT_ACKNOWLEDGE))
                .thenReturn(session);
        when(session.createQueue("ORDERS")).thenReturn(queue);
        when(session.createConsumer(queue, null, false)).thenReturn(consumer);
        when(session.createProducer(queue)).thenReturn(producer);
        return new Client(factory, connection, session, consumer, producer);
    }

    private static TextMessage message() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn("payload");
        when(message.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        return message;
    }

    private static ConnectorDeliveryReservation reservation(ConnectorDelivery delivery) {
        ConnectorDeliveryReservation reservation = mock(ConnectorDeliveryReservation.class);
        when(reservation.start(any(MessageBatch.class))).thenReturn(delivery);
        return reservation;
    }

    private static IncomingConnectorContext context(ConnectorDeliveryReservation reservation) {
        IncomingConnectorContext context = mock(IncomingConnectorContext.class);
        when(context.awaitRunning()).thenReturn(true);
        when(context.maxDeliveryMessages()).thenReturn(1);
        when(context.tryReserveDelivery()).thenReturn(Optional.of(reservation), Optional.empty());
        return context;
    }

    private static AqRuntimeConfig config(ConnectionFactory factory, boolean transacted) {
        return AqRuntimeConfig.incoming(AqConnectorConfig.builder()
                .name("aq")
                .connectionFactory(factory)
                .destination("ORDERS")
                .transacted(transacted)
                .closeTimeout(Duration.ofSeconds(2))
                .buildPrototype(), channelConfig());
    }

    private static AqIncomingConfig channelConfig() {
        return AqIncomingConfig.builder().connector("aq").channelName("orders").build();
    }

    private static AqResourceResolver resolver() {
        return new AqResourceResolver(mock(ServiceRegistry.class));
    }

    private static AqOutgoingChannel outgoingChannel(Client client, boolean transacted) {
        AqRuntimeConfig config = AqRuntimeConfig.outgoing(AqConnectorConfig.builder()
                .name("aq")
                .connectionFactory(client.factory())
                .destination("ORDERS")
                .transacted(transacted)
                .closeTimeout(Duration.ofSeconds(2))
                .buildPrototype(), AqOutgoingConfig.builder().connector("aq").channelName("orders").build());
        return new AqOutgoingChannel(config, resolver());
    }

    private static void awaitIgnoringInterruption(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record Client(ConnectionFactory factory,
                          Connection connection,
                          Session session,
                          MessageConsumer consumer,
                          MessageProducer producer) {
    }
}
