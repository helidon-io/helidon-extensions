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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.messaging.BatchAtomicity;
import io.helidon.messaging.BatchDeliveryException;
import io.helidon.messaging.BatchItemStatus;
import io.helidon.messaging.ConnectorConfig;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.OutgoingConnector;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.ExceptionListener;
import jakarta.jms.JMSException;
import jakarta.jms.JMSRuntimeException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.TransactionRolledBackException;
import jakarta.jms.TransactionRolledBackRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmsOutgoingConnectorTest {
    private static final String CHANNEL = "audit";

    @Test
    void perMessageSendFailureReportsPrefixAmbiguousItemAndSuffix() throws Exception {
        JmsClient client = client();
        AtomicInteger sends = new AtomicInteger();
        doAnswer(invocation -> {
            if (sends.getAndIncrement() == 1) {
                throw new JMSException("broker disconnected");
            }
            return null;
        }).when(client.producer).send(any(jakarta.jms.Message.class));
        OutgoingConnector connector = start(config(false), ignored -> client.factory);
        MessageBatch<String> batch = batch("first", "second", "third");

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.sendBatch(batch));

        assertStatuses(failure,
                       BatchItemStatus.SUCCEEDED,
                       BatchItemStatus.INDETERMINATE,
                       BatchItemStatus.NOT_ATTEMPTED);
        verify(client.producer, times(2)).send(any(jakarta.jms.Message.class));
        connector.close();
    }

    @Test
    void mapperJmsFailureIsDefinitelyFailedAndReconnectsBeforeNextBatch() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        when(first.session.createTextMessage("bad")).thenThrow(new JMSException("cannot encode"));
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = start(config(false), ignored -> {
            return resolutions.getAndIncrement() == 0 ? first.factory : second.factory;
        });
        MessageBatch<String> batch = batch("good", "bad", "untouched");

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.sendBatch(batch));

        assertStatuses(failure,
                       BatchItemStatus.SUCCEEDED,
                       BatchItemStatus.FAILED,
                       BatchItemStatus.NOT_ATTEMPTED);
        connector.send("after-local-failure");
        assertThat(resolutions.get(), is(2));
        verify(first.connection).close();
        verify(second.producer).send(second.messages.getFirst());
        connector.close();
    }

    @Test
    void portableHeaderJmsFailureIsDefinitelyFailedAndReconnectsBeforeNextBatch() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        TextMessage failedMessage = mock(TextMessage.class);
        when(first.session.createTextMessage("bad")).thenReturn(failedMessage);
        doThrow(new JMSException("provider rejected property"))
                .when(failedMessage)
                .setStringProperty("region", "EU");
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = start(config(false), ignored -> resolutions.getAndIncrement() == 0
                ? first.factory
                : second.factory);

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.send(Message.builder("bad")
                                                               .header("region", "EU")
                                                               .build()));

        assertStatuses(failure, BatchItemStatus.FAILED);
        connector.send("after-property-failure");
        assertThat(resolutions.get(), is(2));
        verify(first.connection).close();
        verify(second.producer).send(second.messages.getFirst());
        connector.close();
    }

    @Test
    void jmsRuntimeSendFailureIsAmbiguousAndReconnectsBeforeNextBatch() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        doThrow(new JMSRuntimeException("provider failed"))
                .when(first.producer)
                .send(any(jakarta.jms.Message.class));
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = start(config(false), ignored -> resolutions.getAndIncrement() == 0
                ? first.factory
                : second.factory);

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.send("ambiguous"));
        assertStatuses(failure, BatchItemStatus.INDETERMINATE);

        connector.send("next-batch");

        assertThat(resolutions.get(), is(2));
        verify(first.connection).close();
        verify(second.producer).send(second.messages.getFirst());
        connector.close();
    }

    @Test
    void reconnectCleanupTimeoutStopsConnectorWithoutOpeningReplacement() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        doThrow(new JMSRuntimeException("provider failed"))
                .when(first.producer)
                .send(any(jakarta.jms.Message.class));
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            closing.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a JMS provider close that ignores interruption.
                }
            }
            return null;
        }).when(first.connection).close();
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = start(config(false, Duration.ofMillis(50)), ignored ->
                resolutions.getAndIncrement() == 0 ? first.factory : second.factory);
        assertThrows(BatchDeliveryException.class, () -> connector.send("ambiguous"));
        AtomicReference<Throwable> sendFailure = new AtomicReference<>();
        Thread sender = Thread.ofVirtual().start(() -> capture(() -> connector.send("next-batch"), sendFailure));

        assertThat(closing.await(1, TimeUnit.SECONDS), is(true));
        sender.join(Duration.ofSeconds(2));

        assertThat(sender.isAlive(), is(false));
        BatchDeliveryException failure = (BatchDeliveryException) sendFailure.get();
        assertStatuses(failure, BatchItemStatus.NOT_ATTEMPTED);
        assertThat(resolutions.get(), is(1));
        verify(second.connection, never()).start();
        release.countDown();
        connector.close();
    }

    @Test
    void transactedSendFailureWithConfirmedRollbackReportsAllFailed() throws Exception {
        JmsClient client = client();
        AtomicInteger sends = new AtomicInteger();
        doAnswer(invocation -> {
            if (sends.getAndIncrement() == 1) {
                throw new JMSException("send failed");
            }
            return null;
        }).when(client.producer).send(any(jakarta.jms.Message.class));
        OutgoingConnector connector = start(config(true), ignored -> client.factory);
        MessageBatch<String> batch = batch("first", "second", "third");

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.sendBatch(batch));

        assertThat(connector.batchAtomicity(), is(BatchAtomicity.ATOMIC));
        assertStatuses(failure, BatchItemStatus.FAILED, BatchItemStatus.FAILED, BatchItemStatus.FAILED);
        verify(client.session).rollback();
        verify(client.session, never()).commit();
        connector.close();
    }

    @Test
    void transactedCommitFailureRemainsIndeterminateAfterRollback() throws Exception {
        JmsClient client = client();
        doThrow(new JMSException("commit reply lost")).when(client.session).commit();
        OutgoingConnector connector = start(config(true), ignored -> client.factory);
        MessageBatch<String> batch = batch("first", "second");

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.sendBatch(batch));

        assertStatuses(failure, BatchItemStatus.INDETERMINATE, BatchItemStatus.INDETERMINATE);
        verify(client.session).rollback();
        verify(client.producer, times(2)).send(any(jakarta.jms.Message.class));
        connector.close();
    }

    @Test
    void confirmedCommitRollbackReportsAllFailedWithoutRedundantRollback() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        TransactionRolledBackException commitFailure = new TransactionRolledBackException("transaction rejected");
        doThrow(commitFailure).when(first.session).commit();
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = start(config(true), ignored -> resolutions.getAndIncrement() == 0
                ? first.factory
                : second.factory);

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.sendBatch(batch("first", "second")));

        assertStatuses(failure, BatchItemStatus.FAILED, BatchItemStatus.FAILED);
        assertThat(failure.getCause(), is(commitFailure));
        verify(first.session, never()).rollback();
        connector.send("after-rollback");
        assertThat(resolutions.get(), is(2));
        verify(first.connection).close();
        verify(second.session).commit();
        connector.close();
    }

    @Test
    void confirmedRuntimeCommitRollbackReportsAllFailedWithoutRedundantRollback() throws Exception {
        JmsClient client = client();
        TransactionRolledBackRuntimeException commitFailure =
                new TransactionRolledBackRuntimeException("transaction rejected");
        doThrow(commitFailure).when(client.session).commit();
        OutgoingConnector connector = start(config(true), ignored -> client.factory);

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.sendBatch(batch("first", "second")));

        assertStatuses(failure, BatchItemStatus.FAILED, BatchItemStatus.FAILED);
        assertThat(failure.getCause(), is(commitFailure));
        verify(client.session, never()).rollback();
        connector.close();
    }

    @Test
    void transactedRuntimeCommitFailureAndRollbackFailureRemainIndeterminate() throws Exception {
        JmsClient client = client();
        JMSRuntimeException commitFailure = new JMSRuntimeException("commit failed");
        JMSRuntimeException rollbackFailure = new JMSRuntimeException("rollback failed");
        doThrow(commitFailure).when(client.session).commit();
        doThrow(rollbackFailure).when(client.session).rollback();
        OutgoingConnector connector = start(config(true), ignored -> client.factory);

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.sendBatch(batch("first", "second")));

        assertStatuses(failure, BatchItemStatus.INDETERMINATE, BatchItemStatus.INDETERMINATE);
        assertThat(failure.getCause().getSuppressed()[0], is(rollbackFailure));
        connector.close();
    }

    @Test
    void reconnectsDuringStartAndConfiguresClientIdBeforeTheListenerAndStart() throws Exception {
        JmsClient client = client();
        AtomicInteger resolutions = new AtomicInteger();
        JmsConnectorConfig config = JmsConnectorConfig.builder()
                .from(config(false))
                .clientId("audit-client")
                .build();
        OutgoingConnector connector = JmsOutgoingConnector.create(config, ignored -> {
            if (resolutions.getAndIncrement() == 0) {
                throw new MessagingException("temporarily unavailable");
            }
            return client.factory;
        });

        connector.start();

        assertThat(resolutions.get(), is(2));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(client.connection);
        order.verify(client.connection).setClientID("audit-client");
        order.verify(client.connection).setExceptionListener(any(ExceptionListener.class));
        order.verify(client.connection).start();
        connector.close();
    }

    @Test
    @Timeout(5)
    void listenerFailureDuringProducerCreationRejectsAndClosesTheProducer() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        AtomicReference<ExceptionListener> listener = new AtomicReference<>();
        CountDownLatch firstConnectionClosed = new CountDownLatch(1);
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(first.connection).setExceptionListener(any(ExceptionListener.class));
        when(first.session.createProducer(any())).thenAnswer(invocation -> {
            listener.get().onException(new JMSException("connection lost"));
            awaitIgnoringInterruption(firstConnectionClosed);
            return first.producer;
        });
        doAnswer(invocation -> {
            firstConnectionClosed.countDown();
            return null;
        }).when(first.connection).close();
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored ->
                resolutions.getAndIncrement() == 0 ? first.factory : second.factory);

        connector.start();

        assertThat(resolutions.get(), is(2));
        verify(first.producer).close();
        verify(first.connection).close();
        verify(second.connection).start();
        connector.close();
    }

    @Test
    void rejectedProducerCleanupFailureStopsStartupBeforeOpeningReplacement() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        AtomicReference<ExceptionListener> listener = new AtomicReference<>();
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(first.connection).setExceptionListener(any(ExceptionListener.class));
        when(first.session.createProducer(any())).thenAnswer(invocation -> {
            listener.get().onException(new JMSException("connection lost"));
            return first.producer;
        });
        JMSException cleanupFailure = new JMSException("producer cleanup failed");
        doThrow(cleanupFailure).when(first.producer).close();
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored ->
                resolutions.getAndIncrement() == 0 ? first.factory : second.factory);

        MessagingException startupFailure = assertThrows(MessagingException.class, connector::start);

        assertThat(startupFailure.getMessage(), containsString("Cannot close rejected JMS producer"));
        assertThat(resolutions.get(), is(1));
        verify(second.connection, never()).start();
        MessagingException closeFailure = assertThrows(MessagingException.class, connector::close);
        assertThat(closeFailure.getCause(), is(cleanupFailure));
    }

    @Test
    void listenerFailureDuringConnectionStartDoesNotPromoteBrokenResources() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        AtomicReference<ExceptionListener> listener = new AtomicReference<>();
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(first.connection).setExceptionListener(any(ExceptionListener.class));
        doAnswer(invocation -> {
            listener.get().onException(new JMSException("connection lost"));
            return null;
        }).when(first.connection).start();
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored ->
                resolutions.getAndIncrement() == 0 ? first.factory : second.factory);

        connector.start();

        assertThat(resolutions.get(), is(2));
        verify(first.connection).close();
        verify(second.connection).start();
        connector.close();
    }

    @Test
    void staleCleanupFailureStopsStartupBeforeOpeningReplacement() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        JMSException cleanupFailure = new JMSException("stale cleanup failed");
        doThrow(new JMSException("temporarily unavailable")).when(first.connection).start();
        doThrow(cleanupFailure).when(first.connection).close();
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored ->
                resolutions.getAndIncrement() == 0 ? first.factory : second.factory);

        MessagingException startupFailure = assertThrows(MessagingException.class, connector::start);

        assertThat(startupFailure.getCause().getCause().getCause(), is(cleanupFailure));
        assertThat(resolutions.get(), is(1));
        verify(second.connection, never()).start();
        MessagingException failure = assertThrows(MessagingException.class, connector::close);
        assertThat(failure.getMessage(), containsString("Cannot close JMS resources"));
        assertThat(failure.getCause().getCause(), is(cleanupFailure));
    }

    @Test
    void resourceCleanupFailureMakesStartupTerminal() {
        JmsResourceCleanupException cleanupFailure = new JmsResourceCleanupException(
                "Cannot close JNDI context",
                new IllegalStateException("context leaked"));
        AtomicInteger resolutions = new AtomicInteger();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored -> {
            resolutions.incrementAndGet();
            throw cleanupFailure;
        });

        assertThat(assertThrows(JmsResourceCleanupException.class, connector::start), is(cleanupFailure));
        IllegalStateException secondStart = assertThrows(IllegalStateException.class, connector::start);

        assertThat(secondStart.getMessage(), containsString("closed"));
        assertThat(resolutions.get(), is(1));
    }

    @Test
    @Timeout(5)
    void reconnectUsesTheConfiguredCredentialsOnEveryAttempt() throws Exception {
        JmsClient first = client();
        JmsClient second = client();
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection("orders-user", "secret"))
                .thenReturn(first.connection, second.connection);
        when(factory.createConnection("orders-user", "xxxxxx"))
                .thenThrow(new AssertionError("connector observed a mutated password"));
        doThrow(new JMSException("temporarily unavailable")).when(first.connection).start();
        char[] mutablePassword = "secret".toCharArray();
        JmsConnectorConfig baseConfig = JmsConnectorConfig.builder()
                .from(config(false))
                .username("orders-user")
                .password("ignored")
                .build();
        JmsConnectorConfig config = mock(JmsConnectorConfig.class, delegatesTo(baseConfig));
        when(config.password()).thenReturn(Optional.of(mutablePassword));
        OutgoingConnector connector = JmsOutgoingConnector.create(config, ignored -> factory);
        Arrays.fill(mutablePassword, 'x');

        connector.start();

        verify(factory, times(2)).createConnection("orders-user", "secret");
        connector.close();
    }

    @Test
    void forceCloseInterruptsReconnectAndPreventsSend() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored -> {
            attempted.countDown();
            throw new MessagingException("offline");
        });
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> capture(connector::start, startupFailure));
        assertThat(attempted.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        starter.join(Duration.ofSeconds(2));

        assertThat(starter.isAlive(), is(false));
        assertThat(startupFailure.get() instanceof RuntimeException, is(true));
        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.send("not-sent"));
        assertStatuses(failure, BatchItemStatus.NOT_ATTEMPTED);
    }

    @Test
    @Timeout(5)
    void forceCloseAbandonsPerMessageSendThatIgnoresInterruption() throws Exception {
        JmsClient client = client();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch sendFinished = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            try {
                awaitIgnoringInterruption(releaseSend);
            } finally {
                sendFinished.countDown();
            }
            return null;
        }).when(client.producer).send(any(jakarta.jms.Message.class));
        doAnswer(invocation -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection).close();
        OutgoingConnector connector = start(config(false), ignored -> client.factory);
        AtomicReference<Throwable> sendFailure = new AtomicReference<>();
        Thread sender = Thread.ofVirtual().start(() -> capture(
                () -> connector.sendBatch(batch("first", "untouched")), sendFailure));

        try {
            assertThat(sending.await(1, TimeUnit.SECONDS), is(true));

            connector.forceClose();

            assertThat(connectionClosed.await(1, TimeUnit.SECONDS), is(true));
            sender.join(Duration.ofSeconds(1));
            assertThat(sender.isAlive(), is(false));
            assertThat(sendFailure.get() instanceof BatchDeliveryException, is(true));
            BatchDeliveryException failure = (BatchDeliveryException) sendFailure.get();
            assertStatuses(failure, BatchItemStatus.INDETERMINATE, BatchItemStatus.NOT_ATTEMPTED);

            releaseSend.countDown();
            assertThat(sendFinished.await(1, TimeUnit.SECONDS), is(true));
            assertThat(sendFailure.get(), is(failure));
            verify(client.producer, times(1)).send(any(jakarta.jms.Message.class));
            connector.close();
        } finally {
            releaseSend.countDown();
            connector.forceClose();
            sender.join(Duration.ofSeconds(1));
        }
    }

    @Test
    @Timeout(5)
    void forceCloseAbandonsTransactionalSendWithoutConcurrentRollback() throws Exception {
        JmsClient client = client();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch sendFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            try {
                awaitIgnoringInterruption(releaseSend);
            } finally {
                sendFinished.countDown();
            }
            return null;
        }).when(client.producer).send(any(jakarta.jms.Message.class));
        OutgoingConnector connector = start(config(true), ignored -> client.factory);
        AtomicReference<Throwable> sendFailure = new AtomicReference<>();
        Thread sender = Thread.ofVirtual().start(() -> capture(
                () -> connector.sendBatch(batch("first", "untouched")), sendFailure));

        try {
            assertThat(sending.await(1, TimeUnit.SECONDS), is(true));

            connector.forceClose();

            sender.join(Duration.ofSeconds(1));
            assertThat(sender.isAlive(), is(false));
            assertThat(sendFailure.get() instanceof BatchDeliveryException, is(true));
            BatchDeliveryException failure = (BatchDeliveryException) sendFailure.get();
            assertStatuses(failure, BatchItemStatus.INDETERMINATE, BatchItemStatus.INDETERMINATE);
            verify(client.session, never()).rollback();
            verify(client.session, never()).commit();

            releaseSend.countDown();
            assertThat(sendFinished.await(1, TimeUnit.SECONDS), is(true));
            assertThat(sendFailure.get(), is(failure));
            verify(client.producer, times(1)).send(any(jakarta.jms.Message.class));
            verify(client.session, never()).rollback();
            connector.close();
        } finally {
            releaseSend.countDown();
            connector.forceClose();
            sender.join(Duration.ofSeconds(1));
        }
    }

    @Test
    @Timeout(5)
    void forceCloseAbandonsTransactionalCommitWithoutPublishingLateSuccess() throws Exception {
        JmsClient client = client();
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch commitFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            committing.countDown();
            try {
                awaitIgnoringInterruption(releaseCommit);
            } finally {
                commitFinished.countDown();
            }
            return null;
        }).when(client.session).commit();
        OutgoingConnector connector = start(config(true), ignored -> client.factory);
        AtomicReference<Throwable> sendFailure = new AtomicReference<>();
        Thread sender = Thread.ofVirtual().start(() -> capture(
                () -> connector.sendBatch(batch("first", "second")), sendFailure));

        try {
            assertThat(committing.await(1, TimeUnit.SECONDS), is(true));

            connector.forceClose();

            sender.join(Duration.ofSeconds(1));
            assertThat(sender.isAlive(), is(false));
            assertThat(sendFailure.get() instanceof BatchDeliveryException, is(true));
            BatchDeliveryException failure = (BatchDeliveryException) sendFailure.get();
            assertStatuses(failure, BatchItemStatus.INDETERMINATE, BatchItemStatus.INDETERMINATE);
            verify(client.session, never()).rollback();

            releaseCommit.countDown();
            assertThat(commitFinished.await(1, TimeUnit.SECONDS), is(true));
            assertThat(sendFailure.get(), is(failure));
            verify(client.producer, times(2)).send(any(jakarta.jms.Message.class));
            verify(client.session, never()).rollback();
            connector.close();
        } finally {
            releaseCommit.countDown();
            connector.forceClose();
            sender.join(Duration.ofSeconds(1));
        }
    }

    @Test
    @Timeout(5)
    void forceCloseUnblocksStartupWhileCreateConnectionIsStillBlocked() throws Exception {
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
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored -> factory);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> capture(connector::start, startupFailure));
        assertThat(creatingConnection.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();

        starter.join(Duration.ofSeconds(1));
        assertThat(starter.isAlive(), is(false));
        assertThat(startupFailure.get() instanceof RuntimeException, is(true));
        releaseConnection.countDown();
        assertThat(lateConnectionClosed.await(1, TimeUnit.SECONDS), is(true));
        connector.close();
        verify(lateConnection).close();
    }

    @Test
    @Timeout(5)
    void closeWaitsForLateConnectionAndReportsItsCleanupFailure() throws Exception {
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
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored -> factory);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> capture(connector::start, startupFailure));
        assertThat(creatingConnection.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        starter.join(Duration.ofSeconds(1));
        releaseConnection.countDown();

        MessagingException failure = assertThrows(MessagingException.class, connector::close);
        assertThat(failure.getMessage(), containsString("Cannot close JMS resources"));
        assertThat(failure.getCause(), is(cleanupFailure));
    }

    @Test
    @Timeout(5)
    void closeTimesOutWhileCreateConnectionIgnoresInterruptionAndCanFinishLater() throws Exception {
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
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false, Duration.ofMillis(50)),
                                                                   ignored -> factory);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> capture(connector::start, startupFailure));
        assertThat(creatingConnection.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        starter.join(Duration.ofSeconds(1));

        MessagingException failure = assertThrows(MessagingException.class, connector::close);
        assertThat(failure.getMessage(), containsString("Timed out closing JMS resources"));
        releaseConnection.countDown();
        assertThat(lateConnectionClosed.await(1, TimeUnit.SECONDS), is(true));
        connector.close();
    }

    @Test
    void forceCloseClosesPublishedConnectionAndUnblocksConnectionStart() throws Exception {
        JmsClient client = client();
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        doAnswer(invocation -> {
            starting.countDown();
            while (connectionClosed.getCount() != 0) {
                try {
                    connectionClosed.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a provider operation that ignores interruption and needs Connection.close().
                }
            }
            throw new JMSException("connection closed during start");
        }).when(client.connection).start();
        doAnswer(invocation -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection).close();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> capture(connector::start, startupFailure));
        assertThat(starting.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();

        assertThat(connectionClosed.await(1, TimeUnit.SECONDS), is(true));
        starter.join(Duration.ofSeconds(2));
        connector.close();
        assertThat(starter.isAlive(), is(false));
        assertThat(startupFailure.get() instanceof RuntimeException, is(true));
        verify(client.connection, times(1)).close();
    }

    @Test
    void forceCloseClosesSessionCreatedAfterTheConnectionWasClosed() throws Exception {
        JmsClient client = client();
        CountDownLatch creatingSession = new CountDownLatch(1);
        CountDownLatch releaseSession = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        CountDownLatch sessionClosed = new CountDownLatch(1);
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
            sessionClosed.countDown();
            return null;
        }).when(client.session).close();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> capture(connector::start, startupFailure));
        assertThat(creatingSession.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        assertThat(connectionClosed.await(1, TimeUnit.SECONDS), is(true));
        starter.join(Duration.ofSeconds(1));
        assertThat(starter.isAlive(), is(false));
        assertThat(startupFailure.get() instanceof RuntimeException, is(true));
        releaseSession.countDown();
        connector.close();

        assertThat(sessionClosed.await(1, TimeUnit.SECONDS), is(true));
        verify(client.session, times(1)).close();
    }

    @Test
    void forceCloseClosesProducerCreatedAfterTheConnectionWasClosed() throws Exception {
        JmsClient client = client();
        CountDownLatch creatingProducer = new CountDownLatch(1);
        CountDownLatch releaseProducer = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        CountDownLatch producerClosed = new CountDownLatch(1);
        when(client.session.createProducer(any())).thenAnswer(invocation -> {
            creatingProducer.countDown();
            awaitIgnoringInterruption(releaseProducer);
            return client.producer;
        });
        doAnswer(invocation -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection).close();
        doAnswer(invocation -> {
            producerClosed.countDown();
            return null;
        }).when(client.producer).close();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> capture(connector::start, startupFailure));
        assertThat(creatingProducer.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        assertThat(connectionClosed.await(1, TimeUnit.SECONDS), is(true));
        starter.join(Duration.ofSeconds(1));
        assertThat(starter.isAlive(), is(false));
        assertThat(startupFailure.get() instanceof RuntimeException, is(true));
        releaseProducer.countDown();
        connector.close();

        assertThat(producerClosed.await(1, TimeUnit.SECONDS), is(true));
        verify(client.producer, times(1)).close();
    }

    @Test
    @Timeout(5)
    void closeReportsRejectedLateProducerCleanupFailure() throws Exception {
        JmsClient client = client();
        CountDownLatch creatingProducer = new CountDownLatch(1);
        CountDownLatch releaseProducer = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        JMSException cleanupFailure = new JMSException("producer cleanup failed");
        when(client.session.createProducer(any())).thenAnswer(invocation -> {
            creatingProducer.countDown();
            awaitIgnoringInterruption(releaseProducer);
            return client.producer;
        });
        doAnswer(invocation -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection).close();
        doThrow(cleanupFailure).when(client.producer).close();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> capture(connector::start, startupFailure));
        assertThat(creatingProducer.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        assertThat(connectionClosed.await(1, TimeUnit.SECONDS), is(true));
        releaseProducer.countDown();
        starter.join(Duration.ofSeconds(1));

        MessagingException failure = assertThrows(MessagingException.class, connector::close);
        assertThat(failure.getMessage(), containsString("Cannot close JMS resources"));
        assertThat(failure.getCause(), is(cleanupFailure));
        assertThat(startupFailure.get() instanceof RuntimeException, is(true));
    }

    @Test
    void hangingLateProducerCloseDoesNotBlockForcedStartup() throws Exception {
        JmsClient client = client();
        CountDownLatch creatingProducer = new CountDownLatch(1);
        CountDownLatch releaseProducer = new CountDownLatch(1);
        CountDownLatch producerClosing = new CountDownLatch(1);
        CountDownLatch releaseProducerClose = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        when(client.session.createProducer(any())).thenAnswer(invocation -> {
            creatingProducer.countDown();
            awaitIgnoringInterruption(releaseProducer);
            return client.producer;
        });
        doAnswer(invocation -> {
            producerClosing.countDown();
            awaitIgnoringInterruption(releaseProducerClose);
            return null;
        }).when(client.producer).close();
        doAnswer(invocation -> {
            connectionClosed.countDown();
            return null;
        }).when(client.connection).close();
        OutgoingConnector connector = JmsOutgoingConnector.create(config(false), ignored -> client.factory);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> capture(connector::start, startupFailure));
        assertThat(creatingProducer.await(1, TimeUnit.SECONDS), is(true));

        connector.forceClose();
        assertThat(connectionClosed.await(1, TimeUnit.SECONDS), is(true));
        releaseProducer.countDown();
        assertThat(producerClosing.await(1, TimeUnit.SECONDS), is(true));
        starter.join(Duration.ofSeconds(1));

        assertThat(starter.isAlive(), is(false));
        assertThat(startupFailure.get() instanceof RuntimeException, is(true));
        releaseProducerClose.countDown();
        connector.close();
    }

    @Test
    void closeIsIdempotentAndClosesTheOwnedConnection() throws Exception {
        JmsClient client = client();
        OutgoingConnector connector = start(config(false), ignored -> client.factory);

        connector.close();
        connector.close();

        verify(client.connection, times(1)).close();
        assertThrows(BatchDeliveryException.class, () -> connector.send("closed"));
    }

    @Test
    @Timeout(5)
    void hugeCloseTimeoutIsSaturated() throws Exception {
        JmsClient client = client();
        OutgoingConnector connector = start(config(false, Duration.ofSeconds(Long.MAX_VALUE)),
                                             ignored -> client.factory);

        connector.close();

        verify(client.connection).close();
    }

    @Test
    void failedCloseIsIdempotentAndDoesNotRetryNativeResources() throws Exception {
        JmsClient client = client();
        JMSException connectionFailure = new JMSException("connection close failed");
        JMSException producerFailure = new JMSException("producer close failed");
        JMSException sessionFailure = new JMSException("session close failed");
        doThrow(connectionFailure).when(client.connection).close();
        doThrow(producerFailure).when(client.producer).close();
        doThrow(sessionFailure).when(client.session).close();
        OutgoingConnector connector = start(config(false), ignored -> client.factory);

        MessagingException first = assertThrows(MessagingException.class, connector::close);
        MessagingException second = assertThrows(MessagingException.class, connector::close);

        assertThat(first.getCause(), is(second.getCause()));
        assertThat(first.getCause().getCause(), is(connectionFailure));
        assertThat(List.of(connectionFailure.getSuppressed()), is(List.of(producerFailure, sessionFailure)));
        verify(client.connection, times(1)).close();
        verify(client.producer, times(1)).close();
        verify(client.session, times(1)).close();
    }

    @Test
    @Timeout(5)
    void gracefulCloseIsBoundedAndCanFinishOnRetry() throws Exception {
        JmsClient client = client();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            closing.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a JMS provider close that ignores interruption.
                }
            }
            return null;
        }).when(client.connection).close();
        OutgoingConnector connector = null;
        try {
            connector = start(config(false, Duration.ofMillis(50)), ignored -> client.factory);

            MessagingException failure = assertThrows(MessagingException.class, connector::close);

            assertThat(failure.getMessage(), containsString("Timed out closing JMS resources"));
            assertThat(closing.await(1, TimeUnit.SECONDS), is(true));
            release.countDown();
            connector.close();
            verify(client.connection, times(1)).close();
        } finally {
            release.countDown();
            if (connector != null) {
                connector.forceClose();
            }
        }
    }

    @Test
    void reconnectJitterIsCappedAtTheConfiguredMaximum() {
        Duration maximum = Duration.ofMillis(100);

        Duration actual = JmsOutgoingConnector.jitter(Duration.ofMillis(90), maximum, 0.5, 1);

        assertThat(actual, is(maximum));
    }

    @Test
    void reconnectJitterAcceptsATinyNonZeroVariation() {
        Duration delay = Duration.ofNanos(1);

        Duration actual = JmsOutgoingConnector.jitter(delay,
                                                      Duration.ofSeconds(1),
                                                      Double.MIN_VALUE,
                                                      0.5);

        assertThat(actual, is(delay));
    }

    @Test
    void reconnectJitterHandlesDurationsLargerThanNanosecondsCanRepresent() {
        Duration huge = Duration.ofSeconds(Long.MAX_VALUE);

        Duration actual = JmsOutgoingConnector.jitter(huge, huge, 0.5, 1);

        assertThat(actual.compareTo(huge) <= 0, is(true));
        assertThat(actual.isPositive(), is(true));
    }

    private static JmsClient client() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        MessageProducer producer = mock(MessageProducer.class);
        Queue queue = mock(Queue.class);
        List<jakarta.jms.Message> messages = new ArrayList<>();
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
        when(session.createQueue("events")).thenReturn(queue);
        when(session.createProducer(queue)).thenReturn(producer);
        when(session.createTextMessage(anyString())).thenAnswer(invocation -> {
            TextMessage message = mock(TextMessage.class);
            messages.add(message);
            return message;
        });
        return new JmsClient(factory, connection, session, producer, messages);
    }

    private static JmsConnectorConfig config(boolean transacted) {
        return config(transacted, Duration.ofSeconds(1));
    }

    private static JmsConnectorConfig config(boolean transacted, Duration closeTimeout) {
        return JmsConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel(CHANNEL)
                .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                .destination("events")
                .transacted(transacted)
                .closeTimeout(closeTimeout)
                .reconnectInitialDelay(Duration.ofMillis(1))
                .reconnectMaxDelay(Duration.ofMillis(1))
                .reconnectJitter(0)
                .build();
    }

    private static OutgoingConnector start(JmsConnectorConfig config, JmsConnectionFactoryResolver resolver) {
        OutgoingConnector connector = JmsOutgoingConnector.create(config, resolver);
        connector.start();
        return connector;
    }

    private static MessageBatch<String> batch(String... values) {
        return MessageBatch.create(java.util.Arrays.stream(values).map(Message::create).toList());
    }

    private static void assertStatuses(BatchDeliveryException failure, BatchItemStatus... statuses) {
        assertThat(failure.outcomes().stream().map(outcome -> outcome.status()).toList(),
                   is(List.of(statuses)));
    }

    private static void capture(Runnable action, AtomicReference<Throwable> failure) {
        try {
            action.run();
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
                             MessageProducer producer,
                             List<jakarta.jms.Message> messages) {
    }
}
