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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.messaging.ConnectorConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class JmsConnectionSupportTest {
    @Test
    void forceCloseClearsConnectorOwnedPasswordAndRetainsNoCredentialsInRuntimeConfig() throws Exception {
        JmsConnectorConfig source = config();
        JmsConnectionSupport support = new JmsConnectionSupport(
                source,
                ignored -> mock(jakarta.jms.ConnectionFactory.class));
        char[] connectorPassword = connectorPassword(support);

        assertThat(support.runtimeConfig().username().isEmpty(), is(true));
        assertThat(support.runtimeConfig().password().isEmpty(), is(true));
        assertArrayEquals("tiger".toCharArray(), connectorPassword);

        support.forceClose();

        assertArrayEquals(new char[connectorPassword.length], connectorPassword);
        assertThat(source.username().orElseThrow(), is("scott"));
        assertArrayEquals("tiger".toCharArray(), source.password().orElseThrow());
    }

    @Test
    @Timeout(5)
    void forceCloseClearsPasswordOwnedByAStuckConnectionAttempt() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        JmsConnectionSupport support = new JmsConnectionSupport(config(), ignored -> {
            resolving.countDown();
            awaitIgnoringInterruption(releaseResolver);
            return mock(jakarta.jms.ConnectionFactory.class);
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
        Object attempt = onlyAttempt(support, "attempts");
        char[] attemptPassword = password(attempt);
        Thread worker = worker(attempt);
        assertArrayEquals("tiger".toCharArray(), attemptPassword);

        try {
            support.forceClose();
            caller.join(Duration.ofSeconds(1));

            assertFalse(caller.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertArrayEquals(new char[attemptPassword.length], attemptPassword);
        } finally {
            releaseResolver.countDown();
            worker.join(Duration.ofSeconds(1));
            caller.join(Duration.ofSeconds(1));
        }
    }

    @Test
    @Timeout(5)
    void forceClosePreventsAnAbandonedSetupOperationFromStarting() throws Exception {
        JmsConnectionSupport support = new JmsConnectionSupport(
                config(),
                ignored -> mock(jakarta.jms.ConnectionFactory.class));
        AtomicBoolean invoked = new AtomicBoolean();
        Object attempt = setupAttempt(support, () -> {
            invoked.set(true);
            return null;
        });
        attempts(support, "setupAttempts").add(attempt);
        Thread worker = worker(attempt);

        support.forceClose();
        invoke(attempt, "start");
        worker.join(Duration.ofSeconds(1));

        assertFalse(worker.isAlive());
        assertFalse(invoked.get());
    }

    private static JmsConnectorConfig config() {
        return JmsConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel("orders")
                .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                .destination("orders")
                .username("scott")
                .password("tiger")
                .build();
    }

    private static char[] connectorPassword(JmsConnectionSupport support) throws Exception {
        return password(support);
    }

    private static char[] password(Object owner) throws Exception {
        Field field = owner.getClass().getDeclaredField("password");
        field.setAccessible(true);
        return (char[]) field.get(owner);
    }

    private static Thread worker(Object attempt) throws Exception {
        Field field = attempt.getClass().getDeclaredField("worker");
        field.setAccessible(true);
        return (Thread) field.get(attempt);
    }

    private static Object onlyAttempt(JmsConnectionSupport support, String fieldName) throws Exception {
        Set<Object> attempts = attempts(support, fieldName);
        assertThat(attempts.size(), is(1));
        return attempts.iterator().next();
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> attempts(JmsConnectionSupport support, String fieldName) throws Exception {
        Field field = JmsConnectionSupport.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Set<Object>) field.get(support);
    }

    private static Object setupAttempt(JmsConnectionSupport support,
                                       JmsConnectionSupport.SetupOperation<Void> operation) throws Exception {
        Class<?> type = Class.forName(JmsConnectionSupport.class.getName() + "$SetupAttempt");
        var constructor = type.getDeclaredConstructor(JmsConnectionSupport.class,
                                                      String.class,
                                                      JmsConnectionSupport.SetupOperation.class);
        constructor.setAccessible(true);
        return constructor.newInstance(support, "test operation", operation);
    }

    private static void invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
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
