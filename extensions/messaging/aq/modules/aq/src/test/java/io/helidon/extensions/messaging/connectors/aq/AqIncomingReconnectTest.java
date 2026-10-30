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

import java.sql.SQLRecoverableException;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.ServiceRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class AqIncomingReconnectTest {
    @Test
    void testReceiveFailureReleasesReservationAndResumesOnNewConnection() throws Exception {
        Client first = client(true);
        Client second = client(true);
        ConnectionFactory factory = factory(first, second);
        TextMessage nextMessage = message("next");
        when(first.consumer().receive(100)).thenThrow(disconnected());
        when(second.consumer().receive(100)).thenReturn(nextMessage);
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        ConnectorDeliveryReservation unused = reservation(mock(ConnectorDelivery.class));
        ConnectorDeliveryReservation accepted = reservation(delivery);
        IncomingConnectorContext context = context(unused, accepted);
        AqIncomingChannel channel = channel(factory, true, policy());
        drainAfterDelivery(channel, delivery);

        channel.run(context);
        channel.close();

        verify(context).awaitRunning();
        verify(unused, never()).start(any(MessageBatch.class));
        verify(unused).close();
        verify(accepted).start(any(MessageBatch.class));
        verify(first.session(), never()).commit();
        verify(first.session(), never()).rollback();
        verify(second.session()).commit();
        var order = inOrder(first.connection(), second.connection());
        order.verify(first.connection()).start();
        order.verify(first.connection()).close();
        order.verify(second.connection()).start();
        order.verify(second.connection()).close();
    }

    @Test
    void testConnectionStartFailureDoesNotRepeatRuntimeStartupBarrier() throws Exception {
        Client first = client(true);
        Client second = client(true);
        ConnectionFactory factory = factory(first, second);
        TextMessage nextMessage = message("next");
        doThrow(disconnected()).when(first.connection()).start();
        when(second.consumer().receive(100)).thenReturn(nextMessage);
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        IncomingConnectorContext context = context(reservation(delivery));
        AqIncomingChannel channel = channel(factory, true, policy());
        drainAfterDelivery(channel, delivery);

        channel.run(context);
        channel.close();

        verify(context).awaitRunning();
        verify(first.consumer(), never()).receive(100);
        verify(second.connection()).start();
        verify(second.session()).commit();
    }

    @Test
    void testUnknownCommitOutcomeResumesWithoutRepeatingOldDelivery() throws Exception {
        Client first = client(true);
        Client second = client(true);
        ConnectionFactory factory = factory(first, second);
        TextMessage firstMessage = message("first");
        TextMessage nextMessage = message("next");
        when(first.consumer().receive(100)).thenReturn(firstMessage);
        when(second.consumer().receive(100)).thenReturn(nextMessage);
        doThrow(disconnected()).when(first.session()).commit();
        ConnectorDelivery firstDelivery = mock(ConnectorDelivery.class);
        ConnectorDelivery secondDelivery = mock(ConnectorDelivery.class);
        IncomingConnectorContext context = context(reservation(firstDelivery), reservation(secondDelivery));
        AqIncomingChannel channel = channel(factory, true, policy());
        drainAfterDelivery(channel, secondDelivery);

        channel.run(context);
        channel.close();

        verify(firstDelivery).await();
        verify(firstDelivery).close();
        verify(secondDelivery).await();
        verify(first.session()).commit();
        verify(first.session(), never()).rollback();
        verify(second.session()).commit();
        var order = inOrder(first.connection(), second.connection());
        order.verify(first.connection()).close();
        order.verify(second.connection()).start();
    }

    @Test
    void testUnknownAcknowledgementDoesNotAcknowledgeOldMessageOnNewSession() throws Exception {
        Client first = client(false);
        Client second = client(false);
        ConnectionFactory factory = factory(first, second);
        TextMessage firstMessage = message("first");
        TextMessage nextMessage = message("next");
        when(first.consumer().receive(100)).thenReturn(firstMessage);
        when(second.consumer().receive(100)).thenReturn(nextMessage);
        doThrow(disconnected()).when(firstMessage).acknowledge();
        ConnectorDelivery firstDelivery = mock(ConnectorDelivery.class);
        ConnectorDelivery secondDelivery = mock(ConnectorDelivery.class);
        IncomingConnectorContext context = context(reservation(firstDelivery), reservation(secondDelivery));
        AqIncomingChannel channel = channel(factory, false, policy());
        drainAfterDelivery(channel, secondDelivery);

        channel.run(context);
        channel.close();

        verify(firstMessage).acknowledge();
        verify(nextMessage).acknowledge();
        verify(firstDelivery).await();
        verify(first.session(), never()).recover();
        verify(second.session(), never()).recover();
        verify(first.session(), never()).commit();
        verify(second.session(), never()).commit();
    }

    @Test
    void testApplicationFailureWithRecoverableCauseRemainsTerminal() throws Exception {
        Client first = client(true);
        ConnectionFactory factory = factory(first, client(true));
        TextMessage firstMessage = message("first");
        when(first.consumer().receive(100)).thenReturn(firstMessage);
        MessagingException failure = new MessagingException("handler failed", new SQLRecoverableException("application SQL"));
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        doThrow(failure).when(delivery).await();
        AqIncomingChannel channel = channel(factory, true, policy());

        assertThat(assertThrows(MessagingException.class, () -> channel.run(context(reservation(delivery)))),
                   sameInstance(failure));
        channel.close();

        verify(factory).createConnection();
        verify(first.session()).rollback();
        verify(first.session(), never()).commit();
        verify(delivery).close();
    }

    @Test
    void testMappingFailureUsesDeliveryFailurePolicyWithoutReconnecting() throws Exception {
        Client first = client(true);
        ConnectionFactory factory = factory(first, client(true));
        TextMessage nativeMessage = message("first");
        when(nativeMessage.getText()).thenThrow(new JMSException("invalid message body"));
        when(first.consumer().receive(100)).thenReturn(nativeMessage);
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        ConnectorDeliveryReservation reservation = reservation(delivery);
        when(reservation.startFailed(any(MessageBatch.class), any(RuntimeException.class))).thenReturn(delivery);
        AqIncomingChannel channel = channel(factory, true, policy());
        drainAfterDelivery(channel, delivery);

        channel.run(context(reservation));
        channel.close();

        verify(reservation).startFailed(any(MessageBatch.class), any(RuntimeException.class));
        verify(reservation, never()).start(any(MessageBatch.class));
        verify(factory).createConnection();
        verify(first.session()).commit();
        verify(first.session(), never()).rollback();
    }

    @Test
    void testProviderSnapshotConnectionFailureRecoversWithoutDispatchingFailedPayload() throws Exception {
        Client first = client(true);
        Client second = client(true);
        ConnectionFactory factory = factory(first, second);
        TextMessage firstMessage = message("first");
        TextMessage nextMessage = message("next");
        when(firstMessage.getText()).thenThrow(disconnected());
        when(first.consumer().receive(100)).thenReturn(firstMessage);
        when(second.consumer().receive(100)).thenReturn(nextMessage);
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        ConnectorDeliveryReservation unused = reservation(mock(ConnectorDelivery.class));
        ConnectorDeliveryReservation accepted = reservation(delivery);
        AqIncomingChannel channel = channel(factory, true, policy());
        drainAfterDelivery(channel, delivery);

        channel.run(context(unused, accepted));
        channel.close();

        verify(unused, never()).startFailed(any(MessageBatch.class), any(RuntimeException.class));
        verify(unused, never()).start(any(MessageBatch.class));
        verify(unused).close();
        verify(accepted).start(any(MessageBatch.class));
        verify(first.session(), never()).rollback();
        verify(first.session(), never()).commit();
        verify(firstMessage, never()).acknowledge();
        verify(second.session()).commit();
        var order = inOrder(first.connection(), second.connection());
        order.verify(first.connection()).close();
        order.verify(second.connection()).start();
    }

    @Test
    void testApplicationConverterWithRecoverableCauseUsesDeliveryFailurePolicy() throws Exception {
        Client first = client(true);
        ConnectionFactory factory = factory(first, client(true));
        TextMessage nativeMessage = message("first");
        when(first.consumer().receive(100)).thenReturn(nativeMessage);
        AqPayloadConverter converter = mock(AqPayloadConverter.class);
        MessagingException failure = new MessagingException("application conversion failed",
                                                           new SQLRecoverableException("application SQL"));
        when(converter.fromPayload("first")).thenThrow(failure);
        AqRuntimeConfig config = AqRuntimeConfig.incoming(AqConnectorConfig.builder()
                .name("aq")
                .connectionFactory(factory)
                .destination("ORDERS")
                .payloadConverter(converter)
                .reconnect(policy())
                .closeTimeout(Duration.ofSeconds(2))
                .buildPrototype(), AqIncomingConfig.builder().connector("aq").channelName("orders").build());
        AqIncomingChannel channel = new AqIncomingChannel(config, new AqResourceResolver(mock(ServiceRegistry.class)));
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        ConnectorDeliveryReservation reservation = reservation(delivery);
        when(reservation.startFailed(any(MessageBatch.class), any(RuntimeException.class))).thenReturn(delivery);
        drainAfterDelivery(channel, delivery);

        channel.run(context(reservation));
        channel.close();

        verify(reservation).startFailed(any(MessageBatch.class), any(RuntimeException.class));
        verify(reservation, never()).start(any(MessageBatch.class));
        verify(factory).createConnection();
        verify(converter).fromPayload("first");
        verify(first.session()).commit();
        verify(first.session(), never()).rollback();
    }

    @Test
    void testRollbackConnectionFailureDoesNotHideTerminalApplicationFailure() throws Exception {
        Client first = client(true);
        ConnectionFactory factory = factory(first, client(true));
        TextMessage firstMessage = message("first");
        when(first.consumer().receive(100)).thenReturn(firstMessage);
        IllegalStateException failure = new IllegalStateException("handler failed");
        JMSException rollbackFailure = disconnected();
        doThrow(rollbackFailure).when(first.session()).rollback();
        ConnectorDelivery delivery = mock(ConnectorDelivery.class);
        doThrow(failure).when(delivery).await();
        AqIncomingChannel channel = channel(factory, true, policy());

        assertThat(assertThrows(IllegalStateException.class, () -> channel.run(context(reservation(delivery)))),
                   sameInstance(failure));
        channel.close();

        assertThat(failure.getSuppressed().length, is(1));
        assertThat(failure.getSuppressed()[0], sameInstance(rollbackFailure));
        verify(factory).createConnection();
        verify(first.session(), never()).commit();
        verify(delivery).close();
    }

    @Test
    void testUnclassifiedReceiveFailureRemainsTerminal() throws Exception {
        Client first = client(true);
        ConnectionFactory factory = factory(first, client(true));
        JMSException failure = new JMSException("queue removed");
        when(first.consumer().receive(100)).thenThrow(failure);
        ConnectorDeliveryReservation reservation = reservation(mock(ConnectorDelivery.class));
        AqIncomingChannel channel = channel(factory, true, policy());

        MessagingException result = assertThrows(MessagingException.class, () -> channel.run(context(reservation)));
        channel.close();

        assertThat(result.getCause(), sameInstance(failure));
        verify(factory).createConnection();
        verify(reservation, never()).start(any(MessageBatch.class));
        verify(reservation).close();
    }

    @Test
    void testDisabledRecoveryRetainsTerminalConnectionFailure() throws Exception {
        Client first = client(true);
        ConnectionFactory factory = factory(first, client(true));
        JMSException failure = disconnected();
        when(first.consumer().receive(100)).thenThrow(failure);
        AqIncomingChannel channel = channel(factory, true, AqReconnectConfig.builder().enabled(false).build());

        MessagingException result = assertThrows(MessagingException.class,
                                                () -> channel.run(context(reservation(mock(ConnectorDelivery.class)))));
        channel.close();

        assertThat(result.getCause(), sameInstance(failure));
        verify(factory).createConnection();
    }

    @Test
    void testDrainCancelsBackoffWithoutOpeningReplacement() throws Exception {
        Client first = client(true);
        ConnectionFactory factory = factory(first, client(true));
        CountDownLatch oldConnectionClosed = new CountDownLatch(1);
        doAnswer(_ -> {
            oldConnectionClosed.countDown();
            return null;
        }).when(first.connection()).close();
        when(first.consumer().receive(100)).thenThrow(disconnected());
        AqIncomingChannel channel = channel(factory, true, AqReconnectConfig.builder()
                .initialDelay(Duration.ofSeconds(30))
                .maxDelay(Duration.ofSeconds(30))
                .jitter(0)
                .build());
        FutureTask<Void> run = new FutureTask<>(() -> {
            channel.run(context(reservation(mock(ConnectorDelivery.class))));
            return null;
        });
        Thread runner = Thread.ofVirtual().start(run);
        try {
            assertThat(oldConnectionClosed.await(2, TimeUnit.SECONDS), is(true));
            channel.drain();
            run.get(500, TimeUnit.MILLISECONDS);
        } finally {
            channel.forceClose();
            runner.join(Duration.ofSeconds(2));
            channel.close();
        }

        assertThat(runner.isAlive(), is(false));
        verify(factory).createConnection();
        verify(first.session(), never()).commit();
    }

    private static Client client(boolean transacted) throws Exception {
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        Queue queue = mock(Queue.class);
        when(connection.createSession(transacted, transacted ? Session.SESSION_TRANSACTED : Session.CLIENT_ACKNOWLEDGE))
                .thenReturn(session);
        when(session.createQueue("ORDERS")).thenReturn(queue);
        when(session.createConsumer(queue, null, false)).thenReturn(consumer);
        return new Client(connection, session, consumer);
    }

    private static ConnectionFactory factory(Client first, Client second) throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenReturn(first.connection(), second.connection());
        return factory;
    }

    private static TextMessage message(String text) throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn(text);
        when(message.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        return message;
    }

    private static JMSException disconnected() {
        JMSException failure = new JMSException("connection lost");
        failure.setLinkedException(new SQLRecoverableException("connection lost", "08006"));
        return failure;
    }

    private static ConnectorDeliveryReservation reservation(ConnectorDelivery delivery) {
        ConnectorDeliveryReservation reservation = mock(ConnectorDeliveryReservation.class);
        when(reservation.start(any(MessageBatch.class))).thenReturn(delivery);
        return reservation;
    }

    private static IncomingConnectorContext context(ConnectorDeliveryReservation first,
                                                    ConnectorDeliveryReservation... remaining) {
        IncomingConnectorContext context = mock(IncomingConnectorContext.class);
        when(context.awaitRunning()).thenReturn(true);
        when(context.maxDeliveryMessages()).thenReturn(1);
        var reservations = when(context.tryReserveDelivery()).thenReturn(Optional.of(first));
        for (ConnectorDeliveryReservation reservation : remaining) {
            reservations = reservations.thenReturn(Optional.of(reservation));
        }
        reservations.thenReturn(Optional.empty());
        return context;
    }

    private static AqIncomingChannel channel(ConnectionFactory factory, boolean transacted, AqReconnectConfig policy) {
        AqRuntimeConfig config = AqRuntimeConfig.incoming(AqConnectorConfig.builder()
                .name("aq")
                .connectionFactory(factory)
                .destination("ORDERS")
                .transacted(transacted)
                .reconnect(policy)
                .closeTimeout(Duration.ofSeconds(2))
                .buildPrototype(), AqIncomingConfig.builder().connector("aq").channelName("orders").build());
        return new AqIncomingChannel(config, new AqResourceResolver(mock(ServiceRegistry.class)));
    }

    private static AqReconnectConfig policy() {
        return AqReconnectConfig.builder()
                .initialDelay(Duration.ofMillis(1))
                .maxDelay(Duration.ofMillis(1))
                .jitter(0)
                .build();
    }

    private static void drainAfterDelivery(AqIncomingChannel channel, ConnectorDelivery delivery) {
        doAnswer(_ -> {
            channel.drain();
            return null;
        }).when(delivery).await();
    }

    private record Client(Connection connection, Session session, MessageConsumer consumer) {
    }
}
