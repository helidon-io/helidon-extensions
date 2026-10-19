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

import java.sql.Blob;
import java.sql.SQLRecoverableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import io.helidon.messaging.BatchDeliveryException;
import io.helidon.messaging.BatchItemStatus;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.ServiceRegistry;

import oracle.jdbc.OracleData;
import oracle.jms.AQjmsAdtMessage;
import oracle.jms.AQjmsSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class AqOutgoingReconnectTest {
    @Test
    void nonTransactedFailureRetainsPrefixAndDoesNotReplayTheFailedBatch() throws Exception {
        Client original = client(false);
        Client replacement = client(false);
        ConnectionFactory factory = factory(original, replacement);
        JMSException lost = connectionFailure();
        failSecondSend(original, lost);
        AqOutgoingChannel channel = channel(config(factory, false).buildPrototype());
        channel.start();
        try {
            MessageBatch<String> batch = batch("first", "second", "third");
            BatchDeliveryException failure = assertThrows(BatchDeliveryException.class, () -> channel.sendBatch(batch));

            assertThat(failure.batch(), sameInstance(batch));
            assertThat(failure.getCause(), sameInstance(lost));
            assertThat(failure.outcome(0).status(), is(BatchItemStatus.SUCCEEDED));
            assertThat(failure.outcome(1).status(), is(BatchItemStatus.INDETERMINATE));
            assertThat(failure.outcome(2).status(), is(BatchItemStatus.NOT_ATTEMPTED));
            channel.sendBatch(batch("next"));

            verify(original.session(), never()).createTextMessage("third");
            verifyReplacementOnlySentNext(replacement);
            verify(factory, times(2)).createConnection();
            verify(original.connection()).close();
        } finally {
            channel.close();
        }
        verify(replacement.connection()).close();
    }

    @Test
    void confirmedRollbackFailsAttemptedItemsAndReconnectsWithoutReplayingThem() throws Exception {
        Client original = client(true);
        Client replacement = client(true);
        ConnectionFactory factory = factory(original, replacement);
        JMSException lost = connectionFailure();
        failSecondSend(original, lost);
        AqOutgoingChannel channel = channel(config(factory, true).buildPrototype());
        channel.start();
        try {
            BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                           () -> channel.sendBatch(batch("first", "second", "third")));

            assertThat(failure.outcome(0).status(), is(BatchItemStatus.FAILED));
            assertThat(failure.outcome(1).status(), is(BatchItemStatus.FAILED));
            assertThat(failure.outcome(2).status(), is(BatchItemStatus.NOT_ATTEMPTED));
            channel.sendBatch(batch("next"));

            verify(original.session()).rollback();
            verify(original.session(), never()).commit();
            verifyReplacementOnlySentNext(replacement);
            verify(replacement.session()).commit();
        } finally {
            channel.close();
        }
    }

    @Test
    void lostCommitRemainsIndeterminateEvenWhenRollbackReturnsNormally() throws Exception {
        Client original = client(true);
        Client replacement = client(true);
        ConnectionFactory factory = factory(original, replacement);
        doThrow(connectionFailure()).when(original.session()).commit();
        AqOutgoingChannel channel = channel(config(factory, true).buildPrototype());
        channel.start();
        try {
            BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                           () -> channel.sendBatch(batch("first", "second")));

            assertThat(failure.outcome(0).status(), is(BatchItemStatus.INDETERMINATE));
            assertThat(failure.outcome(1).status(), is(BatchItemStatus.INDETERMINATE));
            channel.sendBatch(batch("next"));

            verify(original.session()).rollback();
            verifyReplacementOnlySentNext(replacement);
            verify(replacement.session()).commit();
        } finally {
            channel.close();
        }
    }

    @Test
    void failedRollbackPreservesUncertainSendAndUsesAReplacementSession() throws Exception {
        Client original = client(true);
        Client replacement = client(true);
        ConnectionFactory factory = factory(original, replacement);
        JMSException lost = connectionFailure();
        failSecondSend(original, lost);
        doThrow(connectionFailure()).when(original.session()).rollback();
        AqOutgoingChannel channel = channel(config(factory, true).buildPrototype());
        channel.start();
        try {
            BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                           () -> channel.sendBatch(batch("first", "second", "third")));

            assertThat(failure.outcome(0).status(), is(BatchItemStatus.INDETERMINATE));
            assertThat(failure.outcome(1).status(), is(BatchItemStatus.INDETERMINATE));
            assertThat(failure.outcome(2).status(), is(BatchItemStatus.NOT_ATTEMPTED));
            assertThat(lost.getSuppressed().length, is(1));
            channel.sendBatch(batch("next"));
            verifyReplacementOnlySentNext(replacement);
        } finally {
            channel.close();
        }
    }

    @Test
    void providerFailureDuringMessageCreationAlsoReconnects() throws Exception {
        Client original = client(false);
        Client replacement = client(false);
        ConnectionFactory factory = factory(original, replacement);
        when(original.session().createTextMessage("first")).thenThrow(connectionFailure());
        AqOutgoingChannel channel = channel(config(factory, false).buildPrototype());
        channel.start();
        try {
            BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                           () -> channel.sendBatch(batch("first", "second")));

            assertThat(failure.outcome(0).status(), is(BatchItemStatus.FAILED));
            assertThat(failure.outcome(1).status(), is(BatchItemStatus.NOT_ATTEMPTED));
            channel.sendBatch(batch("next"));

            verify(original.producer(), never()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
            verifyReplacementOnlySentNext(replacement);
        } finally {
            channel.close();
        }
    }

    @Test
    void applicationConverterCannotTriggerConnectionRecovery() throws Exception {
        Client original = client(true);
        Client replacement = client(true);
        ConnectionFactory factory = factory(original, replacement);
        AtomicBoolean fail = new AtomicBoolean(true);
        AqPayloadConverter converter = mock(AqPayloadConverter.class);
        when(converter.toPayload(any())).thenAnswer(invocation -> {
            if (fail.getAndSet(false)) {
                throw new MessagingException("application conversion failed", new SQLRecoverableException("application SQL"));
            }
            return invocation.getArgument(0);
        });
        AqOutgoingChannel channel = channel(config(factory, true).payloadConverter(converter).buildPrototype());
        channel.start();
        try {
            BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                           () -> channel.sendBatch(batch("first")));
            assertThat(failure.outcome(0).status(), is(BatchItemStatus.FAILED));
            channel.sendBatch(batch("next"));

            verify(factory, times(1)).createConnection();
            verify(original.connection(), never()).close();
            verify(original.session()).createTextMessage("next");
            verify(original.session()).commit();
            verify(replacement.session(), never()).createTextMessage(anyString());
        } finally {
            channel.close();
        }
    }

    @Test
    void waitTimeoutReportsEveryItemNotAttemptedAndLaterSendStillSucceeds() throws Exception {
        Client original = client(false);
        Client replacement = client(false);
        ConnectionFactory factory = factory(original, replacement);
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(factory.createConnection()).thenReturn(original.connection()).thenAnswer(_ -> {
            connecting.countDown();
            release.await();
            return replacement.connection();
        });
        doThrow(connectionFailure()).when(original.producer()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
        AqOutgoingChannel channel = channel(config(factory, false)
                                                    .reconnect(reconnect().waitTimeout(Duration.ofMillis(100)).build())
                                                    .buildPrototype());
        channel.start();
        try {
            assertThrows(BatchDeliveryException.class, () -> channel.sendBatch(batch("first")));
            assertThat(connecting.await(2, TimeUnit.SECONDS), is(true));
            BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                           () -> channel.sendBatch(batch("second", "third")));

            assertThat(failure.outcome(0).status(), is(BatchItemStatus.NOT_ATTEMPTED));
            assertThat(failure.outcome(1).status(), is(BatchItemStatus.NOT_ATTEMPTED));
            release.countDown();
            channel.sendBatch(batch("next"));
            verifyReplacementOnlySentNext(replacement);
        } finally {
            release.countDown();
            channel.close();
        }
    }

    @Test
    void concurrentSendersHaveIndependentRecoveryWaitTimeouts() throws Exception {
        Client original = client(false);
        Client replacement = client(false);
        ConnectionFactory factory = factory(original, replacement);
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(factory.createConnection()).thenReturn(original.connection()).thenAnswer(_ -> {
            connecting.countDown();
            release.await();
            return replacement.connection();
        });
        doThrow(connectionFailure()).when(original.producer()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
        AqOutgoingChannel channel = channel(config(factory, false)
                                                    .reconnect(reconnect().waitTimeout(Duration.ofMillis(500)).build())
                                                    .buildPrototype());
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<FutureTask<Long>> sends = new ArrayList<>();
        List<Thread> senders = new ArrayList<>();
        channel.start();
        try {
            assertThrows(BatchDeliveryException.class, () -> channel.sendBatch(batch("first")));
            assertThat(connecting.await(2, TimeUnit.SECONDS), is(true));
            for (int i = 0; i < 4; i++) {
                MessageBatch<String> pending = batch("pending-" + i, "tail-" + i);
                FutureTask<Long> send = new FutureTask<>(() -> {
                    ready.countDown();
                    start.await();
                    long started = System.nanoTime();
                    BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                                 () -> channel.sendBatch(pending));
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    assertThat(failure.batch(), sameInstance(pending));
                    assertThat(failure.getCause().getMessage(), containsString("Timed out waiting for AQ connection recovery"));
                    assertThat(failure.outcome(0).status(), is(BatchItemStatus.NOT_ATTEMPTED));
                    assertThat(failure.outcome(1).status(), is(BatchItemStatus.NOT_ATTEMPTED));
                    return elapsed;
                });
                sends.add(send);
                senders.add(Thread.ofVirtual().start(send));
            }
            assertThat(ready.await(2, TimeUnit.SECONDS), is(true));
            start.countDown();
            for (FutureTask<Long> send : sends) {
                assertThat("Each caller has its own recovery wait budget", send.get(3, TimeUnit.SECONDS), lessThan(1500L));
            }
            verify(original.session(), times(1)).createTextMessage(anyString());
            verify(original.producer(), times(1)).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
            verify(replacement.session(), never()).createTextMessage(anyString());
            verify(replacement.producer(), never()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
            release.countDown();
            channel.sendBatch(batch("next"));
            verifyReplacementOnlySentNext(replacement);
        } finally {
            start.countDown();
            release.countDown();
            channel.forceClose();
            for (Thread sender : senders) {
                sender.join(Duration.ofSeconds(2));
                assertThat(sender.isAlive(), is(false));
            }
            channel.close();
        }
    }

    @Test
    void queuedSendUsesReplacementWhenTheActiveSendRetiresItsConnection() throws Exception {
        Client original = client(false);
        Client replacement = client(false);
        ConnectionFactory factory = factory(original, replacement);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch failSend = new CountDownLatch(1);
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch releaseConnection = new CountDownLatch(1);
        when(factory.createConnection()).thenReturn(original.connection()).thenAnswer(_ -> {
            connecting.countDown();
            releaseConnection.await();
            return replacement.connection();
        });
        doAnswer(_ -> {
            sending.countDown();
            failSend.await();
            throw connectionFailure();
        }).when(original.producer()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
        AqOutgoingChannel channel = channel(config(factory, false).buildPrototype());
        channel.start();
        FutureTask<BatchDeliveryException> first = new FutureTask<>(() -> assertThrows(BatchDeliveryException.class,
                                                                                    () -> channel.sendBatch(batch("first"))));
        FutureTask<Void> next = new FutureTask<>(() -> {
            channel.sendBatch(batch("next"));
            return null;
        });
        Thread firstSender = Thread.ofVirtual().start(first);
        Thread nextSender = null;
        try {
            assertThat(sending.await(2, TimeUnit.SECONDS), is(true));
            nextSender = Thread.ofVirtual().start(next);
            assertThrows(TimeoutException.class, () -> next.get(100, TimeUnit.MILLISECONDS));
            failSend.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).outcome(0).status(), is(BatchItemStatus.INDETERMINATE));
            assertThat(connecting.await(2, TimeUnit.SECONDS), is(true));
            assertThrows(TimeoutException.class, () -> next.get(100, TimeUnit.MILLISECONDS));
            verify(original.session(), never()).createTextMessage("next");
            releaseConnection.countDown();
            next.get(2, TimeUnit.SECONDS);

            verify(original.producer(), times(1)).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
            verifyReplacementOnlySentNext(replacement);
            verify(original.connection()).close();
        } finally {
            failSend.countDown();
            releaseConnection.countDown();
            channel.forceClose();
            firstSender.join(Duration.ofSeconds(2));
            if (nextSender != null) {
                nextSender.join(Duration.ofSeconds(2));
                assertThat(nextSender.isAlive(), is(false));
            }
            channel.close();
        }
        assertThat(firstSender.isAlive(), is(false));
    }

    @Test
    void healthyNativeSendSerializationIsNotLimitedByRecoveryWaitTimeout() throws Exception {
        Client original = client(false);
        ConnectionFactory factory = factory(original, client(false));
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if ("first".equals(((TextMessage) invocation.getArgument(0)).getText())) {
                sending.countDown();
                release.await();
            }
            return null;
        }).when(original.producer()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
        AqOutgoingChannel channel = channel(config(factory, false)
                                                    .reconnect(reconnect().waitTimeout(Duration.ofMillis(100)).build())
                                                    .buildPrototype());
        channel.start();
        FutureTask<Void> first = new FutureTask<>(() -> {
            channel.sendBatch(batch("first"));
            return null;
        });
        FutureTask<Void> next = new FutureTask<>(() -> {
            channel.sendBatch(batch("next"));
            return null;
        });
        Thread firstSender = Thread.ofVirtual().start(first);
        Thread nextSender = null;
        try {
            assertThat(sending.await(2, TimeUnit.SECONDS), is(true));
            nextSender = Thread.ofVirtual().start(next);
            assertThrows(TimeoutException.class, () -> next.get(250, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            next.get(2, TimeUnit.SECONDS);

            verify(factory, times(1)).createConnection();
            verify(original.session()).createTextMessage("next");
            verify(original.producer(), times(2)).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
        } finally {
            release.countDown();
            channel.forceClose();
            firstSender.join(Duration.ofSeconds(2));
            if (nextSender != null) {
                nextSender.join(Duration.ofSeconds(2));
                assertThat(nextSender.isAlive(), is(false));
            }
            channel.close();
        }
        assertThat(firstSender.isAlive(), is(false));
    }

    @Test
    void closeCancelsConcurrentWaitingSendsAndRecoveryBackoff() throws Exception {
        Client original = client(false);
        Client replacement = client(false);
        ConnectionFactory factory = factory(original, replacement);
        doThrow(connectionFailure()).when(original.producer()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
        AqOutgoingChannel channel = channel(config(factory, false)
                                                    .reconnect(reconnect().initialDelay(Duration.ofSeconds(5))
                                                                       .maxDelay(Duration.ofSeconds(5))
                                                                       .waitTimeout(Duration.ofSeconds(30)).build())
                                                    .buildPrototype());
        channel.start();
        assertThrows(BatchDeliveryException.class, () -> channel.sendBatch(batch("first")));
        List<FutureTask<BatchDeliveryException>> sends = new ArrayList<>();
        List<Thread> senders = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                MessageBatch<String> pending = batch("pending-" + i);
                FutureTask<BatchDeliveryException> send = new FutureTask<>(() -> {
                    BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                                 () -> channel.sendBatch(pending));
                    assertThat(failure.batch(), sameInstance(pending));
                    return failure;
                });
                sends.add(send);
                senders.add(Thread.ofVirtual().start(send));
            }
            for (FutureTask<BatchDeliveryException> send : sends) {
                assertThrows(TimeoutException.class, () -> send.get(100, TimeUnit.MILLISECONDS));
            }
            channel.close();
            for (FutureTask<BatchDeliveryException> send : sends) {
                BatchDeliveryException failure = send.get(1, TimeUnit.SECONDS);
                assertThat(failure.outcome(0).status(), is(BatchItemStatus.NOT_ATTEMPTED));
            }
            verify(factory, times(1)).createConnection();
            verify(original.connection()).close();
            assertThrows(MessagingException.class, () -> channel.sendBatch(batch("after-close")));
        } finally {
            channel.forceClose();
            for (Thread sender : senders) {
                sender.join(Duration.ofSeconds(2));
                assertThat(sender.isAlive(), is(false));
            }
            channel.close();
        }
    }

    @Test
    void disabledReconnectRetainsTerminalConnectionFailure() throws Exception {
        Client original = client(false);
        Client replacement = client(false);
        ConnectionFactory factory = factory(original, replacement);
        doThrow(connectionFailure()).when(original.producer()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
        AqOutgoingChannel channel = channel(config(factory, false)
                                                    .reconnect(reconnect().enabled(false).build())
                                                    .buildPrototype());
        channel.start();
        try {
            BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                           () -> channel.sendBatch(batch("first")));
            assertThat(failure.outcome(0).status(), is(BatchItemStatus.INDETERMINATE));
            MessagingException closed = assertThrows(MessagingException.class, () -> channel.sendBatch(batch("next")));
            assertThat(closed.getMessage(), containsString("closing"));
            verify(factory, times(1)).createConnection();
        } finally {
            channel.close();
        }
    }

    @Test
    void nativeLobsAreReleasedBeforeTheFailedConnectionIsRetired() throws Exception {
        AQjmsSession session = mock(AQjmsSession.class);
        Client original = client(true, session);
        Client replacement = client(true, mock(AQjmsSession.class));
        ConnectionFactory factory = factory(original, replacement);
        java.sql.Connection jdbc = mock(java.sql.Connection.class);
        Blob blob = mock(Blob.class);
        when(jdbc.createBlob()).thenReturn(blob);
        when(session.createORAMessage(any(OracleData.class))).thenAnswer(invocation -> {
            OracleData data = invocation.getArgument(0);
            data.toJDBCObject(jdbc);
            return nativeAdt(data);
        });
        when(((AQjmsSession) replacement.session()).createORAMessage(any(OracleData.class))).thenAnswer(invocation ->
                nativeAdt(invocation.getArgument(0)));
        doThrow(connectionFailure()).when(original.producer()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
        AqOutgoingChannel channel = channel(config(factory, true).payloadType(AqPayloadType.ADT).buildPrototype());
        AqAdt value = AqAdt.builder().typeName("APP.LOB_VALUE")
                .addAttribute(AqBlob.builder().bytes(new byte[]{1}).build()).build();
        channel.start();
        try {
            assertThrows(BatchDeliveryException.class, () -> channel.sendBatch(MessageBatch.create(Message.create(value))));
            channel.sendBatch(MessageBatch.create(Message.create(value)));

            var order = inOrder(blob, original.connection(), replacement.session());
            order.verify(blob).free();
            order.verify(original.connection()).close();
            order.verify(replacement.session()).commit();
            verify(original.producer(), times(1)).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
            verify(replacement.producer(), times(1)).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
        } finally {
            channel.close();
        }
    }

    private static Client client(boolean transacted) throws Exception {
        return client(transacted, mock(Session.class));
    }

    private static AQjmsAdtMessage nativeAdt(OracleData data) throws JMSException {
        AQjmsAdtMessage message = mock(AQjmsAdtMessage.class);
        when(message.getAdtPayload()).thenReturn(data);
        return message;
    }

    private static Client client(boolean transacted, Session session) throws Exception {
        Connection connection = mock(Connection.class);
        MessageProducer producer = mock(MessageProducer.class);
        Queue queue = mock(Queue.class);
        when(connection.createSession(transacted, transacted ? Session.SESSION_TRANSACTED : Session.CLIENT_ACKNOWLEDGE))
                .thenReturn(session);
        when(session.createQueue("ORDERS")).thenReturn(queue);
        when(session.createProducer(queue)).thenReturn(producer);
        when(session.createTextMessage(anyString())).thenAnswer(invocation -> {
            TextMessage message = mock(TextMessage.class);
            when(message.getText()).thenReturn(invocation.getArgument(0));
            return message;
        });
        return new Client(connection, session, producer);
    }

    private static ConnectionFactory factory(Client first, Client second) throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenReturn(first.connection(), second.connection());
        return factory;
    }

    private static JMSException connectionFailure() {
        JMSException failure = new JMSException("AQ database connection lost");
        failure.setLinkedException(new SQLRecoverableException("IO Error", "08006", 17002));
        return failure;
    }

    private static void failSecondSend(Client client, JMSException failure) throws Exception {
        doAnswer(invocation -> {
            if ("second".equals(((TextMessage) invocation.getArgument(0)).getText())) {
                throw failure;
            }
            return null;
        }).when(client.producer()).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
    }

    private static void verifyReplacementOnlySentNext(Client client) throws Exception {
        verify(client.session(), times(1)).createTextMessage(anyString());
        verify(client.session()).createTextMessage("next");
        verify(client.producer(), times(1)).send(any(javax.jms.Message.class), eq(2), eq(4), eq(0L));
    }

    private static AqConnectorConfig.Builder config(ConnectionFactory factory, boolean transacted) {
        return AqConnectorConfig.builder().name("aq").connectionFactory(factory).destination("ORDERS")
                .transacted(transacted).closeTimeout(Duration.ofSeconds(2)).reconnect(reconnect().build());
    }

    private static AqReconnectConfig.Builder reconnect() {
        return AqReconnectConfig.builder().initialDelay(Duration.ofMillis(1)).maxDelay(Duration.ofMillis(2))
                .jitter(0).waitTimeout(Duration.ofSeconds(2)).connectTimeout(Duration.ofSeconds(5));
    }

    private static AqOutgoingChannel channel(AqConnectorConfig connector) {
        return new AqOutgoingChannel(AqRuntimeConfig.outgoing(connector,
                                                             AqOutgoingConfig.builder().connector("aq")
                                                                     .channelName("orders").build()),
                                     new AqResourceResolver(mock(ServiceRegistry.class)));
    }

    private static MessageBatch<String> batch(String... values) {
        return MessageBatch.create(List.of(values).stream().map(Message::create).toList());
    }

    private record Client(Connection connection, Session session, MessageProducer producer) {
    }
}
