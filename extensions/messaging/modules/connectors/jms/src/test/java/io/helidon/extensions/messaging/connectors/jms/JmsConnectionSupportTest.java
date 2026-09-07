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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.messaging.spi.ConnectorDirection;

import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class JmsConnectionSupportTest {
    @Test
    void forceCloseRejectsNewConnectionsAndRetainsNoCredentialsInRuntimeConfig() {
        JmsConnectorConfig source = config();
        AtomicBoolean resolved = new AtomicBoolean();
        JmsConnectionSupport support = new JmsConnectionSupport(
                source,
                ignored -> {
                    resolved.set(true);
                    return mock(ConnectionFactory.class);
                });

        assertThat(support.runtimeConfig().username().isEmpty(), is(true));
        assertThat(support.runtimeConfig().password().isEmpty(), is(true));

        support.forceClose();

        assertThrows(IllegalStateException.class, support::createConnection);
        assertThat(resolved.get(), is(false));
        assertThat(source.username().orElseThrow(), is("scott"));
        assertThat(source.password().orElseThrow(), is("tiger".toCharArray()));
    }

    @Test
    @Timeout(5)
    void forceCloseRejectsCallerBlockedInConnectionFactoryResolution() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        AtomicBoolean resolverCompleted = new AtomicBoolean();
        ConnectionFactory factory = mock(ConnectionFactory.class);
        JmsConnectionSupport support = new JmsConnectionSupport(config(), ignored -> {
            resolving.countDown();
            awaitIgnoringInterruption(releaseResolver);
            resolverCompleted.set(true);
            return factory;
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                support.createConnection();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        assertThat(resolving.await(1, TimeUnit.SECONDS), is(true));

        try {
            support.forceClose();
            caller.join(Duration.ofSeconds(1));

            assertThat(caller.isAlive(), is(false));
            assertThat(failure.get(), instanceOf(IllegalStateException.class));
            assertThrows(IllegalStateException.class, support::createConnection);
            assertThat(resolverCompleted.get(), is(false));
        } finally {
            releaseResolver.countDown();
            support.awaitClose(System.nanoTime() + Duration.ofSeconds(1).toNanos());
            caller.join(Duration.ofSeconds(1));
        }

        assertThat(resolverCompleted.get(), is(true));
        verify(factory, never()).createConnection();
        verify(factory, never()).createConnection(anyString(), anyString());
    }

    private static JmsConnectorConfig config() {
        return JmsConnectorConfig.builder()
                .direction(ConnectorDirection.OUTGOING)
                .channelName("orders")
                .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                .destination("orders")
                .username("scott")
                .password("tiger")
                .build();
    }

    private static void awaitIgnoringInterruption(CountDownLatch latch) {
        while (latch.getCount() != 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                // Simulate a provider operation that does not respond to interruption.
            }
        }
    }
}
