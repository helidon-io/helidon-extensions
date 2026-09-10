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
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.naming.CommunicationException;
import javax.naming.ConfigurationException;
import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.NoInitialContextException;
import javax.naming.NotContextException;
import javax.naming.ServiceUnavailableException;
import javax.naming.spi.InitialContextFactory;

import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.spi.ChannelConnection;
import io.helidon.messaging.spi.IncomingChannel;
import io.helidon.messaging.spi.OutgoingChannel;
import io.helidon.service.registry.ServiceRegistry;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Destination;
import jakarta.jms.Session;
import jakarta.jms.Topic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmsResourceResolverTest {
    @AfterEach
    void resetContext() {
        TestInitialContextFactory.context = null;
    }

    @Test
    void testMissingDefaultConnectionFactoryIsNotRetryable() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.first(ConnectionFactory.class)).thenReturn(Optional.empty());
        JmsResourceResolver resolver = new JmsResourceResolver(registry);

        JmsResourceConfigurationException failure = assertThrows(JmsResourceConfigurationException.class,
                                                                 () -> resolver.resolve(configWithoutJndi()));

        assertThat(failure.getMessage(), containsString("No JMS ConnectionFactory is registered for channel orders"));
        verify(registry).first(ConnectionFactory.class);
    }

    @Test
    void testInvalidJndiLookupIsNotRetryable() throws NamingException {
        for (NamingException lookupFailure : List.of(new NameNotFoundException("name not found"),
                                                    new InvalidNameException("invalid name"),
                                                    new NotContextException("not a context"),
                                                    new ConfigurationException("invalid environment"),
                                                    new NoInitialContextException("no initial context"))) {
            Context context = mock(Context.class);
            when(context.lookup("jms/ConnectionFactory")).thenThrow(lookupFailure);
            TestInitialContextFactory.context = context;

            JmsResourceConfigurationException failure = assertThrows(JmsResourceConfigurationException.class,
                                                                     () -> resolver().resolve(config()),
                                                                     lookupFailure.toString());

            assertThat(failure.getCause(), sameInstance(lookupFailure));
            assertThat(failure.getMessage(), containsString("Cannot resolve JMS resource jms/ConnectionFactory"));
            assertThat(failure.getMessage(), containsString("channel orders"));
            verify(context).close();
        }
    }

    @Test
    void testTransientJndiLookupRemainsRetryable() throws NamingException {
        for (NamingException lookupFailure : List.of(new NamingException("lookup failed"),
                                                    new CommunicationException("connection lost"),
                                                    new ServiceUnavailableException("service unavailable"))) {
            Context context = mock(Context.class);
            when(context.lookup("jms/ConnectionFactory")).thenThrow(lookupFailure);
            TestInitialContextFactory.context = context;

            MessagingException failure = assertThrows(MessagingException.class, () -> resolver().resolve(config()));

            assertThat(failure, not(instanceOf(JmsResourceConfigurationException.class)));
            assertThat(failure.getCause(), sameInstance(lookupFailure));
            assertThat(failure.getMessage(), containsString("Cannot resolve JMS resource jms/ConnectionFactory"));
            assertThat(failure.getMessage(), containsString("channel orders"));
            verify(context).close();
        }
    }

    @Test
    void testWrongJndiDestinationTypeIsNotRetryable() throws Exception {
        Context context = mock(Context.class);
        when(context.lookup("jms/orders")).thenReturn("not a destination");
        TestInitialContextFactory.context = context;
        JmsRuntimeConfig config = JmsRuntimeConfig.builder()
                .from(config())
                .clearDestination()
                .jndiDestination("jms/orders")
                .build();
        Session session = mock(Session.class);

        JmsResourceConfigurationException failure = assertThrows(JmsResourceConfigurationException.class,
                () -> JmsResourceResolver.resolveDestination(session, config));

        assertThat(failure.getMessage(), containsString("JNDI name jms/orders does not resolve to "
                                                               + Destination.class.getName()));
        verify(context).close();
    }

    @Test
    @Timeout(10)
    void testWrongJndiConnectionFactoryTypeStopsIncomingStartupWithoutRetry() throws Exception {
        Context context = mock(Context.class);
        when(context.lookup("jms/ConnectionFactory")).thenReturn("not a connection factory");
        TestInitialContextFactory.context = context;
        IncomingConnectorContext incomingContext = mock(IncomingConnectorContext.class);
        when(incomingContext.maxDeliveryMessages()).thenReturn(1);
        IncomingChannel connector = JmsIncomingChannel.create(config(), resolver());

        MessagingException failure = startupFailure(connector, () -> connector.run(incomingContext));

        assertThat(failure, instanceOf(JmsResourceConfigurationException.class));
        assertThat(failure.getMessage(), containsString("JNDI name jms/ConnectionFactory does not resolve to "
                                                               + ConnectionFactory.class.getName()));
        verify(context).lookup("jms/ConnectionFactory");
        verify(context).close();
        verify(incomingContext, never()).awaitRunning();
    }

    @Test
    @Timeout(10)
    void testDestinationTypeMismatchStopsOutgoingStartupWithoutRetry() throws Exception {
        Context context = mock(Context.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        when(context.lookup("jms/ConnectionFactory")).thenReturn(factory);
        when(context.lookup("jms/orders")).thenReturn(mock(Topic.class));
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
        TestInitialContextFactory.context = context;
        JmsRuntimeConfig config = JmsRuntimeConfig.builder()
                .from(config())
                .clearDestination()
                .jndiDestination("jms/orders")
                .destinationType(JmsDestinationType.QUEUE)
                .build();
        OutgoingChannel connector = JmsOutgoingChannel.create(config, resolver());

        MessagingException failure = startupFailure(connector, connector::start);

        assertThat(failure, instanceOf(JmsResourceConfigurationException.class));
        assertThat(failure.getMessage(), containsString("JMS destination for channel orders"));
        assertThat(failure.getMessage(), containsString("does not match destination-type QUEUE"));
        verify(context).lookup("jms/ConnectionFactory");
        verify(context).lookup("jms/orders");
        verify(context, times(2)).close();
        verify(factory).createConnection();
        verify(connection).createSession(false, Session.AUTO_ACKNOWLEDGE);
        verify(connection, never()).start();
        // Closing the connection also closes its session.
        verify(connection).close();
    }

    @Test
    void testCloseFailureInvalidatesSuccessfulLookup() throws NamingException {
        Context context = mock(Context.class);
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        NamingException closeFailure = new NamingException("close failed");
        when(context.lookup("jms/ConnectionFactory")).thenReturn(connectionFactory);
        doThrow(closeFailure).when(context).close();
        TestInitialContextFactory.context = context;

        JmsResourceCleanupException failure = assertThrows(JmsResourceCleanupException.class,
                                                            () -> resolver().resolve(config()));

        assertThat(failure.getCause(), sameInstance(closeFailure));
        assertThat(failure.getSuppressed().length, is(0));
    }

    @Test
    void testCloseFailurePreservesLookupFailure() throws NamingException {
        Context context = mock(Context.class);
        NamingException lookupFailure = new NamingException("lookup failed");
        NamingException closeFailure = new NamingException("close failed");
        when(context.lookup("jms/ConnectionFactory")).thenThrow(lookupFailure);
        doThrow(closeFailure).when(context).close();
        TestInitialContextFactory.context = context;

        JmsResourceCleanupException failure = assertThrows(JmsResourceCleanupException.class,
                                                            () -> resolver().resolve(config()));

        assertThat(failure.getCause(), sameInstance(closeFailure));
        assertThat(failure.getSuppressed().length, is(1));
        assertThat(failure.getSuppressed()[0], instanceOf(MessagingException.class));
        assertThat(failure.getSuppressed()[0].getCause(), sameInstance(lookupFailure));
    }

    @Test
    void testCloseFailurePreservesTypeFailure() throws NamingException {
        Context context = mock(Context.class);
        NamingException closeFailure = new NamingException("close failed");
        when(context.lookup("jms/ConnectionFactory")).thenReturn("not a connection factory");
        doThrow(closeFailure).when(context).close();
        TestInitialContextFactory.context = context;

        JmsResourceCleanupException failure = assertThrows(JmsResourceCleanupException.class,
                                                            () -> resolver().resolve(config()));

        assertThat(failure.getCause(), sameInstance(closeFailure));
        assertThat(failure.getSuppressed().length, is(1));
        assertThat(failure.getSuppressed()[0].getMessage(), containsString("does not resolve to"));
    }

    @Test
    void testLookupErrorRemainsPrimaryWhenCloseFails() throws NamingException {
        Context context = mock(Context.class);
        AssertionError lookupFailure = new AssertionError("lookup failed");
        NamingException closeFailure = new NamingException("close failed");
        when(context.lookup("jms/ConnectionFactory")).thenThrow(lookupFailure);
        doThrow(closeFailure).when(context).close();
        TestInitialContextFactory.context = context;

        AssertionError failure = assertThrows(AssertionError.class, () -> resolver().resolve(config()));

        assertThat(failure, sameInstance(lookupFailure));
        assertThat(failure.getSuppressed().length, is(1));
        assertThat(failure.getSuppressed()[0], instanceOf(JmsResourceCleanupException.class));
        assertThat(failure.getSuppressed()[0].getCause(), sameInstance(closeFailure));
    }

    @Test
    @Timeout(5)
    void testLateCleanupErrorAfterForceCloseIsReportedByNormalClose() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AssertionError lookupFailure = new AssertionError("lookup failed after force close");
        JmsResourceCleanupException cleanupFailure = new JmsResourceCleanupException(
                "context close failed",
                new NamingException("close failed"));
        lookupFailure.addSuppressed(cleanupFailure);
        JmsConnectionSupport support = new JmsConnectionSupport(
                configWithoutJndi(),
                ignored -> {
                    resolving.countDown();
                    awaitIgnoringInterruption(release);
                    throw lookupFailure;
                });
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> {
            try {
                support.createConnection();
            } catch (Throwable failure) {
                ownerFailure.set(failure);
            }
        });
        assertThat(resolving.await(1, TimeUnit.SECONDS), is(true));

        support.forceClose();
        owner.join(Duration.ofSeconds(1));
        assertThat(owner.isAlive(), is(false));
        release.countDown();

        AssertionError reported = assertThrows(AssertionError.class,
                                               () -> support.awaitClose(System.nanoTime()
                                                                                + Duration.ofSeconds(1).toNanos()));
        assertThat(reported, sameInstance(lookupFailure));
        assertThat(ownerFailure.get(), instanceOf(IllegalStateException.class));
    }

    @Test
    void testCompletedJndiCleanupErrorRemainsReportedAfterForceClose() throws NamingException {
        Context context = mock(Context.class);
        AssertionError lookupFailure = new AssertionError("lookup failed before force close");
        NamingException closeFailure = new NamingException("close failed");
        when(context.lookup("jms/ConnectionFactory")).thenThrow(lookupFailure);
        doThrow(closeFailure).when(context).close();
        TestInitialContextFactory.context = context;
        JmsConnectionSupport support = new JmsConnectionSupport(config(), resolver());

        AssertionError initial = assertThrows(AssertionError.class, support::createConnection);
        assertThat(initial, sameInstance(lookupFailure));
        assertThat(initial.getSuppressed()[0], instanceOf(JmsResourceCleanupException.class));

        support.forceClose();

        AssertionError reported = assertThrows(AssertionError.class,
                                               () -> support.awaitClose(System.nanoTime()
                                                                                + Duration.ofSeconds(1).toNanos()));
        assertThat(reported, sameInstance(lookupFailure));
        assertThat(reported.getSuppressed()[0].getCause(), sameInstance(closeFailure));
    }

    private static JmsResourceResolver resolver() {
        return new JmsResourceResolver(mock(ServiceRegistry.class));
    }

    private static MessagingException startupFailure(ChannelConnection connector, Runnable startup) throws Exception {
        FutureTask<MessagingException> failure = new FutureTask<>(() -> assertThrows(MessagingException.class, startup::run));
        Thread starter = Thread.ofVirtual().start(failure);
        try {
            return failure.get(2, TimeUnit.SECONDS);
        } finally {
            try {
                connector.forceClose();
            } finally {
                starter.interrupt();
                starter.join(Duration.ofSeconds(2));
            }
            connector.close();
            assertThat("JMS startup thread terminated", starter.isAlive(), is(false));
        }
    }

    private static JmsRuntimeConfig config() {
        return JmsRuntimeConfig.builder()
                .channelName("orders")
                .destination("orders")
                .jndiConnectionFactory("jms/ConnectionFactory")
                .putJndiEnvironmentProperty(Context.INITIAL_CONTEXT_FACTORY,
                                            TestInitialContextFactory.class.getName())
                .build();
    }

    private static JmsRuntimeConfig configWithoutJndi() {
        return JmsRuntimeConfig.builder()
                .channelName("orders")
                .destination("orders")
                .build();
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

    public static final class TestInitialContextFactory implements InitialContextFactory {
        private static volatile Context context;

        @Override
        public Context getInitialContext(Hashtable<?, ?> environment) {
            return context;
        }
    }
}
