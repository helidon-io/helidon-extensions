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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;

import io.helidon.messaging.ConnectorConfig;
import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.ServiceRegistry;

import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JmsResourceResolverTest {
    @AfterEach
    void resetContext() {
        TestInitialContextFactory.context = null;
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

    private static JmsConnectorConfig config() {
        return JmsConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel("orders")
                .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                .destination("orders")
                .jndiConnectionFactory("jms/ConnectionFactory")
                .putJndiEnvironmentProperty(Context.INITIAL_CONTEXT_FACTORY,
                                            TestInitialContextFactory.class.getName())
                .build();
    }

    private static JmsConnectorConfig configWithoutJndi() {
        return JmsConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel("orders")
                .connector(JmsConnectorProvider.CONNECTOR_TYPE)
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
