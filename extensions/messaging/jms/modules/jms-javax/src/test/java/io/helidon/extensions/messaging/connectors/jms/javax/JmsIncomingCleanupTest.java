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

package io.helidon.extensions.messaging.connectors.jms.javax;

import java.time.Duration;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.ExceptionListener;
import javax.jms.JMSRuntimeException;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;

import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.spi.IncomingChannel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmsIncomingCleanupTest {
    private final ConnectionFactory factory = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final Session session = mock(Session.class);
    private final JMSRuntimeException providerFailure = new JMSRuntimeException("Provider unavailable");

    @BeforeEach
    void setUp() throws Exception {
        Queue queue = mock(Queue.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        when(factory.createConnection()).thenReturn(connection)
                .thenThrow(new AssertionError("Must not reconnect after failed cleanup"));
        when(connection.createSession(false, Session.CLIENT_ACKNOWLEDGE)).thenReturn(session);
        when(session.createQueue("events")).thenReturn(queue);
        when(session.createConsumer(queue, null, false)).thenReturn(consumer);
        doThrow(providerFailure).when(connection).close();
    }

    @Test
    void sharedListenerSetupAndCloseFailureStopsReconnect() throws Exception {
        doThrow(providerFailure).when(connection).setExceptionListener(any(ExceptionListener.class));

        assertCleanupStopsReconnect();
    }

    @Test
    void sharedSessionSetupAndCloseFailureStopsReconnect() throws Exception {
        when(connection.createSession(false, Session.CLIENT_ACKNOWLEDGE)).thenThrow(providerFailure);

        assertCleanupStopsReconnect();
    }

    @Test
    void sharedConnectionStartAndCloseFailureStopsReconnect() throws Exception {
        doThrow(providerFailure).when(connection).start();

        assertCleanupStopsReconnect();
    }

    private void assertCleanupStopsReconnect() throws Exception {
        JmsRuntimeConfig config = JmsRuntimeConfig.builder()
                .channelName("orders")
                .destination("events")
                .closeTimeout(Duration.ofSeconds(1))
                .reconnectInitialDelay(Duration.ofMillis(1))
                .reconnectMaxDelay(Duration.ofMillis(1))
                .reconnectJitter(0)
                .build();
        IncomingConnectorContext context = mock(IncomingConnectorContext.class);
        when(context.maxDeliveryMessages()).thenReturn(1);
        when(context.awaitRunning()).thenReturn(true);
        IncomingChannel connector = JmsIncomingChannel.create(config, _ -> factory);

        JmsResourceCleanupException failure = assertThrows(JmsResourceCleanupException.class,
                                                          () -> connector.run(context));

        assertThat(failure.getCause(), sameInstance(providerFailure));
        verify(factory).createConnection();
        verify(connection).close();
    }
}
