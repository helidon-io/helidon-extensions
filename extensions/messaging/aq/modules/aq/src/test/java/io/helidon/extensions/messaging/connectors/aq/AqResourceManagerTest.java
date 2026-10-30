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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;

import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.ServiceRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class AqResourceManagerTest {
    @Test
    void testRecoveryReplacesGenerationAfterTransientOpenFailure() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection first = connection();
        Connection recovered = connection();
        when(factory.createConnection()).thenReturn(first).thenThrow(disconnected()).thenReturn(recovered);
        try (AqResourceManager manager = manager(factory, policy())) {
            manager.open();
            AqResources old = manager.awaitReady();
            assertThat(manager.reconnect(old, disconnected()), is(true));
            AqResources next = manager.awaitReady(Duration.ofSeconds(2));
            assertThat(next.session(), sameInstance(recovered.createSession(true, Session.SESSION_TRANSACTED)));
            verify(first).close();
            verify(factory, times(3)).createConnection();
        }
        verify(recovered).close();
    }

    @Test
    void testRecoveryDoesNotRetryPermanentOpenFailure() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = connection();
        when(factory.createConnection()).thenReturn(connection).thenThrow(new JMSException("invalid destination"));
        try (AqResourceManager manager = manager(factory, policy())) {
            manager.open();
            assertThat(manager.reconnect(manager.awaitReady(), disconnected()), is(true));
            var failure = assertThrows(MessagingException.class, () -> manager.awaitReady(Duration.ofSeconds(2)));
            assertThat(failure.getMessage(), containsString("unavailable"));
            verify(factory, times(2)).createConnection();
        }
    }

    @Test
    void testDisabledAndNonTransportFailuresKeepCurrentGeneration() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = connection();
        when(factory.createConnection()).thenReturn(connection);
        try (AqResourceManager manager = manager(factory, AqReconnectConfig.builder(policy()).enabled(false).build())) {
            manager.open();
            AqResources old = manager.awaitReady();
            assertThat(manager.reconnect(old, disconnected()), is(false));
            assertThat(manager.awaitReady(), sameInstance(old));
        }
        try (AqResourceManager manager = manager(factory, policy())) {
            manager.open();
            AqResources old = manager.awaitReady();
            assertThat(manager.reconnect(old, new JMSException("payload rejected")), is(false));
            assertThat(manager.awaitReady(), sameInstance(old));
        }
        verify(factory, times(2)).createConnection();
    }

    @Test
    void testStopRecoveryCancelsBackoffAndWakesWaiters() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = connection();
        when(factory.createConnection()).thenReturn(connection);
        AqReconnectConfig policy = AqReconnectConfig.builder(policy())
                .initialDelay(Duration.ofSeconds(30)).maxDelay(Duration.ofSeconds(30)).build();
        try (AqResourceManager manager = manager(factory, policy)) {
            manager.open();
            assertThat(manager.reconnect(manager.awaitReady(), disconnected()), is(true));
            FutureTask<AqResources> awaiting = new FutureTask<>(manager::awaitReady);
            Thread waiter = Thread.ofVirtual().start(awaiting);
            manager.stopRecovery();
            assertThat(awaiting.get(1, TimeUnit.SECONDS), nullValue());
            waiter.join(Duration.ofSeconds(1));
            assertThat(waiter.isAlive(), is(false));
            assertThrows(MessagingException.class, () -> manager.awaitReady(Duration.ofSeconds(1)));
            verify(factory, times(1)).createConnection();
        }
    }

    @Test
    void testStopRecoveryPreservesHealthyGenerationForSettlement() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = connection();
        when(factory.createConnection()).thenReturn(connection);
        try (AqResourceManager manager = manager(factory, policy())) {
            manager.open();
            AqResources resources = manager.awaitReady();
            manager.stopRecovery();
            resources.session().commit();
            verify(connection, never()).close();
            assertThat(manager.awaitReady(), nullValue());
        }
        verify(connection).close();
    }

    @Test
    void testStalledRetirementDoesNotOpenConcurrentGeneration() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection first = connection();
        Connection next = connection();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(_ -> {
            closing.countDown();
            release.await();
            return null;
        }).when(first).close();
        when(factory.createConnection()).thenReturn(first, next);
        try (AqResourceManager manager = manager(factory, policy())) {
            manager.open();
            manager.reconnect(manager.awaitReady(), disconnected());
            assertThat(closing.await(1, TimeUnit.SECONDS), is(true));
            assertThrows(MessagingException.class, () -> manager.awaitReady(Duration.ofMillis(100)));
            verify(factory, times(1)).createConnection();
            release.countDown();
            manager.awaitReady(Duration.ofSeconds(2));
            verify(factory, times(2)).createConnection();
        } finally {
            release.countDown();
        }
    }

    @Test
    void testTimedOutProviderAttemptDoesNotAccumulateInitializers() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection first = connection();
        Connection late = connection();
        Connection recovered = connection();
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(factory.createConnection()).thenReturn(first).thenAnswer(_ -> {
            creating.countDown();
            awaitIgnoringInterruption(release);
            return late;
        }).thenReturn(recovered);
        AqResourceManager manager = manager(factory, AqReconnectConfig.builder(policy())
                .connectTimeout(Duration.ofMillis(50)).build());
        try {
            manager.open();
            manager.reconnect(manager.awaitReady(), disconnected());
            assertThat(creating.await(1, TimeUnit.SECONDS), is(true));
            assertThrows(MessagingException.class, () -> manager.awaitReady(Duration.ofMillis(200)));
            verify(factory, times(2)).createConnection();
            release.countDown();
            manager.awaitReady(Duration.ofSeconds(2));
            verify(late).close();
            verify(late, never()).createSession(true, Session.SESSION_TRANSACTED);
            verify(factory, times(3)).createConnection();
        } finally {
            release.countDown();
            manager.close();
        }
    }

    @Test
    void testStopDuringBlockedRecoveryClosesLateConnection() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection first = connection();
        Connection late = connection();
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(factory.createConnection()).thenReturn(first).thenAnswer(_ -> {
            creating.countDown();
            awaitIgnoringInterruption(release);
            return late;
        });
        AqResourceManager manager = manager(factory, policy());
        try {
            manager.open();
            manager.reconnect(manager.awaitReady(), disconnected());
            assertThat(creating.await(1, TimeUnit.SECONDS), is(true));
            manager.stopRecovery();
            assertThat(manager.awaitReady(), nullValue());
        } finally {
            release.countDown();
            manager.close();
        }
        verify(late).close();
        verify(late, never()).createSession(true, Session.SESSION_TRANSACTED);
    }

    @Test
    void testInitialAttemptIsBoundedAndLateResultClosed() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection late = connection();
        CountDownLatch release = new CountDownLatch(1);
        when(factory.createConnection()).thenAnswer(_ -> {
            awaitIgnoringInterruption(release);
            return late;
        });
        AqResourceManager manager = manager(factory, AqReconnectConfig.builder(policy())
                .connectTimeout(Duration.ofMillis(50)).build());
        try {
            assertThrows(AqConnectionTimeoutException.class, manager::open);
            verify(factory, times(1)).createConnection();
        } finally {
            release.countDown();
            manager.close();
        }
        verify(late).close();
    }

    private static AqResourceManager manager(ConnectionFactory factory, AqReconnectConfig policy) {
        AqRuntimeConfig config = AqRuntimeConfig.outgoing(AqConnectorConfig.builder()
                .name("aq").destination("ORDERS").connectionFactory(factory).reconnect(policy).buildPrototype(),
                AqOutgoingConfig.builder().connector("aq").channelName("orders").build());
        return new AqResourceManager(config, new AqResourceResolver(mock(ServiceRegistry.class)), false);
    }

    private static AqReconnectConfig policy() {
        return AqReconnectConfig.builder().initialDelay(Duration.ofMillis(10)).maxDelay(Duration.ofMillis(20))
                .jitter(0).connectTimeout(Duration.ofSeconds(2)).build();
    }

    private static Connection connection() throws JMSException {
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        Queue queue = mock(Queue.class);
        MessageProducer producer = mock(MessageProducer.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        when(connection.createSession(true, Session.SESSION_TRANSACTED)).thenReturn(session);
        when(session.createQueue("ORDERS")).thenReturn(queue);
        when(session.createProducer(queue)).thenReturn(producer);
        when(session.createConsumer(queue, null, false)).thenReturn(consumer);
        return connection;
    }

    private static JMSException disconnected() {
        JMSException failure = new JMSException("JMS-120");
        failure.setLinkedException(new SQLRecoverableException("connection lost"));
        return failure;
    }

    private static void awaitIgnoringInterruption(CountDownLatch release) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    release.await();
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
}
