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

package io.helidon.extensions.messaging.tests.jms;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.extensions.messaging.BatchDeliveryException;
import io.helidon.extensions.messaging.BatchItemStatus;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.OutgoingConnector;
import io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfig;
import io.helidon.extensions.messaging.connectors.jms.JmsConnectorProvider;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JmsTransactionsIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(300);

    @TempDir
    private Path brokerDataDirectory;

    @Test
    @Timeout(60)
    void transactedOutgoingBatchCommitsTogetherAndRollsBackAtomically() throws Exception {
        String committedQueue = "transaction-commit-" + System.nanoTime();
        String rolledBackQueue = "transaction-rollback-" + System.nanoTime();
        try (ArtemisBroker broker = ArtemisBroker.create(brokerDataDirectory)) {
            broker.start();

            OutgoingConnector committed = connector(broker.connectionFactory(), committedQueue);
            try {
                committed.start();
                committed.sendBatch(batch("committed-first", "committed-second"));
            } finally {
                committed.close();
            }

            TextMessage first = JmsTestClient.receiveText(broker.connectionFactory(), committedQueue, WAIT_TIMEOUT);
            TextMessage second = JmsTestClient.receiveText(broker.connectionFactory(), committedQueue, WAIT_TIMEOUT);
            assertThat(first, notNullValue());
            assertThat(second, notNullValue());
            assertThat(first.getText(), is("committed-first"));
            assertThat(second.getText(), is("committed-second"));

            AtomicInteger rollbacks = new AtomicInteger();
            OutgoingConnector rolledBack = connector(failSecondSend(broker.connectionFactory(), rollbacks),
                                                      rolledBackQueue);
            try {
                rolledBack.start();

                BatchDeliveryException failure = assertThrows(
                        BatchDeliveryException.class,
                        () -> rolledBack.sendBatch(batch("rolled-back-first", "rejected-second")));

                assertThat(failure.outcomes().stream().map(outcome -> outcome.status()).toList(),
                           is(List.of(BatchItemStatus.FAILED, BatchItemStatus.FAILED)));
                assertThat("connector must explicitly roll back the real provider transaction",
                           rollbacks.get(), is(1));
            } finally {
                rolledBack.close();
            }

            assertThat("first transactional send must be removed by rollback",
                       JmsTestClient.receiveText(broker.connectionFactory(), rolledBackQueue, POLL_TIMEOUT),
                       nullValue());
        }
    }

    private static OutgoingConnector connector(ConnectionFactory connectionFactory, String destination) {
        return new JmsConnectorProvider(connectionFactory)
                .createOutgoingConnector(JmsConnectorConfig.builder()
                                                 .direction(ConnectorConfig.Direction.OUTGOING)
                                                 .channel(destination)
                                                 .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                                                 .destination(destination)
                                                 .transacted(true)
                                                 .closeTimeout(Duration.ofSeconds(5))
                                                 .build());
    }

    private static MessageBatch<String> batch(String... payloads) {
        return MessageBatch.create(java.util.Arrays.stream(payloads).map(Message::create).toList());
    }

    private static ConnectionFactory failSecondSend(ConnectionFactory delegate, AtomicInteger rollbacks) {
        AtomicInteger sends = new AtomicInteger();
        return proxy(ConnectionFactory.class, (ignored, method, arguments) -> {
            Object result = invoke(delegate, method, arguments);
            if (result instanceof Connection connection) {
                return connectionProxy(connection, sends, rollbacks);
            }
            return result;
        });
    }

    private static Connection connectionProxy(Connection delegate, AtomicInteger sends, AtomicInteger rollbacks) {
        return proxy(Connection.class, (ignored, method, arguments) -> {
            Object result = invoke(delegate, method, arguments);
            if (result instanceof Session session) {
                return sessionProxy(session, sends, rollbacks);
            }
            return result;
        });
    }

    private static Session sessionProxy(Session delegate, AtomicInteger sends, AtomicInteger rollbacks) {
        return proxy(Session.class, (ignored, method, arguments) -> {
            if (method.getName().equals("rollback")) {
                rollbacks.incrementAndGet();
            }
            Object result = invoke(delegate, method, arguments);
            if (result instanceof MessageProducer producer) {
                return producerProxy(producer, sends);
            }
            return result;
        });
    }

    private static MessageProducer producerProxy(MessageProducer delegate, AtomicInteger sends) {
        return proxy(MessageProducer.class, (ignored, method, arguments) -> {
            if (method.getName().equals("send") && sends.incrementAndGet() == 2) {
                throw new JMSException("expected second transactional send failure");
            }
            return invoke(delegate, method, arguments);
        });
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
