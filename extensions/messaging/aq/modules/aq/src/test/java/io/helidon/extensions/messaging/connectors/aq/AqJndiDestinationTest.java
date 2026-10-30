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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.Topic;
import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.ServiceUnavailableException;

import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.ServiceRegistry;

import oracle.jms.AQjmsDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class AqJndiDestinationTest {
    @Test
    void resolvesQueueAndTopicUsingFreshContextsAndConfiguredEnvironment() throws Exception {
        Context first = mock(Context.class);
        Context second = mock(Context.class);
        Queue queue = mock(Queue.class);
        Topic topic = mock(Topic.class);
        when(first.lookup("jms/Orders")).thenReturn(queue);
        when(second.lookup("jms/Orders")).thenReturn(topic);
        AtomicInteger contexts = new AtomicInteger();
        try (var registration = AqTestInitialContextFactory.register(environment -> {
            assertThat(environment.get(Context.PROVIDER_URL), is("test-provider"));
            assertThat(environment.get(Context.SECURITY_PRINCIPAL), is("test-user"));
            return contexts.getAndIncrement() == 0 ? first : second;
        })) {
            AqJndiConfig config = AqJndiConfig.builder(registration.config("jms/Orders"))
                    .putEnvProperty(Context.PROVIDER_URL, "test-provider")
                    .putEnvProperty(Context.SECURITY_PRINCIPAL, "test-user").build();

            assertThat(AqJndiDestination.resolve(config, AqDestinationType.QUEUE), sameInstance(queue));
            assertThat(AqJndiDestination.resolve(config, AqDestinationType.TOPIC), sameInstance(topic));

            assertThat(contexts.get(), is(2));
            verify(first).close();
            verify(second).close();
        }
    }

    @Test
    void lookupFailureClosesItsContextAndRetainsThePrimaryFailure() throws Exception {
        for (NamingException failure : List.of(new NameNotFoundException("missing"),
                                              new InvalidNameException("malformed"),
                                              new AuthenticationException("denied"))) {
            Context context = mock(Context.class);
            when(context.lookup("jms/Orders")).thenThrow(failure);
            NamingException closeFailure = new NamingException("close failed");
            doThrow(closeFailure).when(context).close();
            try (var registration = AqTestInitialContextFactory.register(_ -> context)) {
                NamingException actual = assertThrows(NamingException.class,
                        () -> AqJndiDestination.resolve(registration.config("jms/Orders"), AqDestinationType.QUEUE));

                assertThat(actual, sameInstance(failure));
                assertThat(Arrays.asList(actual.getSuppressed()).contains(closeFailure), is(true));
                verify(context).close();
            }
        }
    }

    @Test
    void closingASuccessfulLookupCanStillReportProviderFailure() throws Exception {
        Context context = mock(Context.class);
        Queue queue = mock(Queue.class);
        when(context.lookup("jms/Orders")).thenReturn(queue);
        NamingException closeFailure = new NamingException("close failed");
        doThrow(closeFailure).when(context).close();
        try (var registration = AqTestInitialContextFactory.register(_ -> context)) {
            NamingException actual = assertThrows(NamingException.class,
                    () -> AqJndiDestination.resolve(registration.config("jms/Orders"), AqDestinationType.QUEUE));

            assertThat(actual, sameInstance(closeFailure));
            verify(context).close();
        }
    }

    @Test
    void missingOrIncompatibleBindingsFailStartupAndCloseResources() throws Exception {
        for (Object value : Arrays.asList(null, "not a destination", mock(Topic.class))) {
            Context context = mock(Context.class);
            when(context.lookup("jms/Orders")).thenReturn(value);
            Client client = client();
            ConnectionFactory factory = factory(client);
            try (var registration = AqTestInitialContextFactory.register(_ -> context);
                 AqResourceManager manager = manager(factory, registration.config("jms/Orders"), policy())) {
                assertThrows(MessagingException.class, manager::open);

                verify(context).close();
                verify(client.session(), never()).createProducer(any(Destination.class));
            }
            verify(client.connection()).close();
        }
    }

    @Test
    void oracleDualInterfaceDestinationsMustMatchTheirNativeKind() throws Exception {
        AQjmsDestination queue = mock(AQjmsDestination.class);
        AQjmsDestination topic = mock(AQjmsDestination.class);
        when(queue.getQueueName()).thenReturn("ORDERS");
        when(topic.getTopicName()).thenReturn("EVENTS");
        for (AQjmsDestination destination : List.of(queue, topic)) {
            Context context = mock(Context.class);
            when(context.lookup("jms/Orders")).thenReturn(destination);
            try (var registration = AqTestInitialContextFactory.register(_ -> context)) {
                AqDestinationType type = destination == queue ? AqDestinationType.QUEUE : AqDestinationType.TOPIC;
                assertThat(AqJndiDestination.resolve(registration.config("jms/Orders"), type), sameInstance(destination));
                AqDestinationType wrongType = type == AqDestinationType.QUEUE ? AqDestinationType.TOPIC : AqDestinationType.QUEUE;
                try (AqResourceManager manager = manager(factory(client()), registration.config("jms/Orders"), policy(),
                                                        wrongType)) {
                    assertThrows(MessagingException.class, manager::open);
                }
                verify(context, times(2)).close();
            }
        }
    }

    @Test
    void reconnectRepeatsLookupAndUsesTheReboundDestination() throws Exception {
        Queue originalQueue = mock(Queue.class);
        Queue reboundQueue = mock(Queue.class);
        Context firstContext = mock(Context.class);
        Context secondContext = mock(Context.class);
        when(firstContext.lookup("jms/Orders")).thenReturn(originalQueue);
        when(secondContext.lookup("jms/Orders")).thenReturn(reboundQueue);
        Client first = client();
        Client second = client();
        AtomicInteger contexts = new AtomicInteger();
        try (var registration = AqTestInitialContextFactory.register(_ -> contexts.getAndIncrement() == 0
                ? firstContext : secondContext);
             AqResourceManager manager = manager(factory(first, second), registration.config("jms/Orders"), policy())) {
            manager.open();
            assertThat(manager.reconnect(manager.awaitReady(), disconnected()), is(true));
            assertThat(manager.awaitReady(Duration.ofSeconds(2)).session(), sameInstance(second.session()));

            verify(first.session()).createProducer(originalQueue);
            verify(second.session()).createProducer(reboundQueue);
            verify(firstContext).close();
            verify(secondContext).close();
            assertThat(contexts.get(), is(2));
        }
    }

    @Test
    void recoveryRetriesTransientNamingFailuresAndClosesEachAttempt() throws Exception {
        for (NamingException transientFailure : List.of(new CommunicationException("offline"),
                                                        new ServiceUnavailableException("unavailable"))) {
            Queue queue = mock(Queue.class);
            Context first = mock(Context.class);
            Context failed = mock(Context.class);
            Context recovered = mock(Context.class);
            when(first.lookup("jms/Orders")).thenReturn(queue);
            when(failed.lookup("jms/Orders")).thenThrow(transientFailure);
            when(recovered.lookup("jms/Orders")).thenReturn(queue);
            List<Context> contexts = List.of(first, failed, recovered);
            AtomicInteger attempts = new AtomicInteger();
            Client originalClient = client();
            Client failedClient = client();
            Client recoveredClient = client();
            try (var registration = AqTestInitialContextFactory.register(_ -> contexts.get(attempts.getAndIncrement()));
                 AqResourceManager manager = manager(factory(originalClient, failedClient, recoveredClient),
                                                     registration.config("jms/Orders"), policy())) {
                manager.open();
                assertThat(manager.reconnect(manager.awaitReady(), disconnected()), is(true));
                assertThat(manager.awaitReady(Duration.ofSeconds(2)).session(), sameInstance(recoveredClient.session()));

                assertThat(attempts.get(), is(3));
                verify(first).close();
                verify(failed).close();
                verify(recovered).close();
                verify(failedClient.connection()).close();
                verify(failedClient.session(), never()).createProducer(any(Destination.class));
            }
        }
    }

    @Test
    void recoveryStopsOnPermanentNamingFailure() throws Exception {
        for (NamingException permanent : List.of(new NameNotFoundException("missing"),
                                                 new AuthenticationException("denied"))) {
            Queue queue = mock(Queue.class);
            Context first = mock(Context.class);
            Context failed = mock(Context.class);
            when(first.lookup("jms/Orders")).thenReturn(queue);
            when(failed.lookup("jms/Orders")).thenThrow(permanent);
            AtomicInteger attempts = new AtomicInteger();
            try (var registration = AqTestInitialContextFactory.register(_ -> attempts.getAndIncrement() == 0 ? first : failed);
                 AqResourceManager manager = manager(factory(client(), client()), registration.config("jms/Orders"), policy())) {
                manager.open();
                assertThat(manager.reconnect(manager.awaitReady(), disconnected()), is(true));
                MessagingException failure = assertThrows(MessagingException.class,
                        () -> manager.awaitReady(Duration.ofSeconds(2)));

                assertThat(failure.getMessage(), containsString("unavailable"));
                assertThrows(MessagingException.class, () -> manager.awaitReady(Duration.ofSeconds(2)));
                assertThat(attempts.get(), is(2));
                verify(failed).close();
            }
        }
    }

    @Test
    void startupDoesNotRetryTransientNamingFailure() throws Exception {
        Context context = mock(Context.class);
        when(context.lookup("jms/Orders")).thenThrow(new CommunicationException("offline"));
        AtomicInteger attempts = new AtomicInteger();
        try (var registration = AqTestInitialContextFactory.register(_ -> {
            attempts.incrementAndGet();
            return context;
        });
             AqResourceManager manager = manager(factory(client()), registration.config("jms/Orders"), policy())) {
            assertThrows(MessagingException.class, manager::open);

            assertThat(attempts.get(), is(1));
            verify(context).close();
        }
    }

    @Test
    void cancelledLookupClosesItsContextAndDoesNotPublishTheLateDestination() throws Exception {
        Context context = mock(Context.class);
        Queue queue = mock(Queue.class);
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(context.lookup("jms/Orders")).thenAnswer(_ -> {
            lookupEntered.countDown();
            awaitIgnoringInterruption(release);
            return queue;
        });
        Client client = client();
        try (var registration = AqTestInitialContextFactory.register(_ -> context)) {
            AqResourceManager manager = manager(factory(client), registration.config("jms/Orders"), policy());
            FutureTask<Void> opening = new FutureTask<>(() -> {
                manager.open();
                return null;
            });
            Thread opener = Thread.ofVirtual().start(opening);
            try {
                assertThat(lookupEntered.await(1, TimeUnit.SECONDS), is(true));
                manager.forceClose();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> opening.get(1, TimeUnit.SECONDS));
                assertThat(failure.getCause(), instanceOf(MessagingException.class));
            } finally {
                release.countDown();
                manager.close();
                opener.join(Duration.ofSeconds(2));
                assertThat(opener.isAlive(), is(false));
            }
            verify(context).close();
            verify(client.connection()).close();
            verify(client.session(), never()).createProducer(any(Destination.class));
            verify(client.session()).close();
        }
    }

    private static Client client() throws JMSException {
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        MessageProducer producer = mock(MessageProducer.class);
        when(connection.createSession(true, Session.SESSION_TRANSACTED)).thenReturn(session);
        when(session.createProducer(any(Destination.class))).thenReturn(producer);
        return new Client(connection, session);
    }

    private static ConnectionFactory factory(Client... clients) throws JMSException {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        AtomicInteger next = new AtomicInteger();
        when(factory.createConnection()).thenAnswer(_ -> clients[Math.min(next.getAndIncrement(), clients.length - 1)].connection());
        return factory;
    }

    private static AqResourceManager manager(ConnectionFactory factory, AqJndiConfig jndi, AqReconnectConfig policy) {
        return manager(factory, jndi, policy, AqDestinationType.QUEUE);
    }

    private static AqResourceManager manager(ConnectionFactory factory, AqJndiConfig jndi, AqReconnectConfig policy,
                                              AqDestinationType type) {
        AqRuntimeConfig config = AqRuntimeConfig.outgoing(AqConnectorConfig.builder().name("aq").connectionFactory(factory)
                .jndi(jndi).destinationType(type).reconnect(policy).closeTimeout(Duration.ofSeconds(2)).buildPrototype(),
                AqOutgoingConfig.builder().connector("aq").channelName("orders").build());
        return new AqResourceManager(config, new AqResourceResolver(mock(ServiceRegistry.class)), false);
    }

    private static AqReconnectConfig policy() {
        return AqReconnectConfig.builder().initialDelay(Duration.ofMillis(1)).maxDelay(Duration.ofMillis(5))
                .jitter(0).connectTimeout(Duration.ofSeconds(2)).waitTimeout(Duration.ofSeconds(2)).build();
    }

    private static JMSException disconnected() {
        JMSException failure = new JMSException("database connection lost");
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

    private record Client(Connection connection, Session session) {
    }
}
