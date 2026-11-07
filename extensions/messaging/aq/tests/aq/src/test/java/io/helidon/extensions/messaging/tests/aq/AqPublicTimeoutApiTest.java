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

package io.helidon.extensions.messaging.tests.aq;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.JMSContext;

import io.helidon.extensions.messaging.connectors.aq.AqConnectionTimeoutException;
import io.helidon.extensions.messaging.connectors.aq.AqConnector;
import io.helidon.extensions.messaging.connectors.aq.AqOutgoingConfig;
import io.helidon.extensions.messaging.connectors.aq.AqReconnectConfig;
import io.helidon.messaging.MessagingChannel;
import io.helidon.messaging.MessagingGraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(10)
class AqPublicTimeoutApiTest {
    @Test
    void graphStartupExposesCatchableAqConnectionTimeout() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch factoryFinished = new CountDownLatch(1);
        ConnectionFactory factory = new ConnectionFactory() {
            @Override
            public Connection createConnection() throws JMSException {
                creating.countDown();
                try {
                    awaitIgnoringInterruption(release);
                    throw new JMSException("Late provider failure");
                } finally {
                    factoryFinished.countDown();
                }
            }

            @Override
            public Connection createConnection(String username, String password) throws JMSException {
                throw new JMSException("Unexpected credentials");
            }

            @Override
            public JMSContext createContext() {
                throw new IllegalStateException("Unexpected JMS context");
            }

            @Override
            public JMSContext createContext(String username, String password) {
                throw new IllegalStateException("Unexpected JMS context");
            }

            @Override
            public JMSContext createContext(String username, String password, int sessionMode) {
                throw new IllegalStateException("Unexpected JMS context");
            }

            @Override
            public JMSContext createContext(int sessionMode) {
                throw new IllegalStateException("Unexpected JMS context");
            }
        };
        AqConnector connector = AqConnector.builder().name("aq").destination("ORDERS")
                .connectionFactory(factory).closeTimeout(Duration.ofMillis(100))
                .reconnect(AqReconnectConfig.builder().connectTimeout(Duration.ofMillis(200)).build())
                .build();
        var channel = MessagingChannel.create("orders", String.class);
        MessagingGraph graph = MessagingGraph.builder().channel(channel)
                .payloadSource(channel, Stream.<String>empty())
                .outgoingChannel(channel, connector.outgoing(AqOutgoingConfig.builder()
                        .connector("aq").channelName("orders").build()))
                .build();
        FutureTask<AqConnectionTimeoutException> startup = new FutureTask<>(
                () -> assertThrows(AqConnectionTimeoutException.class, graph::start));
        Thread starter = Thread.ofVirtual().start(startup);
        try {
            assertThat(startup.get(5, TimeUnit.SECONDS).getCause(), instanceOf(TimeoutException.class));
        } finally {
            release.countDown();
            if (creating.getCount() == 0) {
                assertThat(factoryFinished.await(2, TimeUnit.SECONDS), is(true));
            }
            starter.join(Duration.ofSeconds(2));
            assertThat(starter.isAlive(), is(false));
        }
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
