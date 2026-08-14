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

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

final class ArtemisBroker implements AutoCloseable {
    private final Path dataDirectory;
    private final String name;
    private int port;
    private ActiveMQConnectionFactory connectionFactory;
    private EmbeddedActiveMQ broker;

    private ArtemisBroker(Path dataDirectory, String name) {
        this.dataDirectory = dataDirectory;
        this.name = name;
    }

    static ArtemisBroker create(Path dataDirectory) {
        return new ArtemisBroker(dataDirectory, "helidon-jms-it-" + System.nanoTime());
    }

    synchronized void start() throws Exception {
        if (broker != null) {
            throw new IllegalStateException("Embedded Artemis broker is already running");
        }
        ConfigurationImpl configuration = new ConfigurationImpl()
                .setName(name)
                .setSecurityEnabled(false)
                .setPersistenceEnabled(true)
                .setJournalType(JournalType.NIO)
                .setJournalDirectory(dataDirectory.resolve("journal").toString())
                .setBindingsDirectory(dataDirectory.resolve("bindings").toString())
                .setPagingDirectory(dataDirectory.resolve("paging").toString())
                .setLargeMessagesDirectory(dataDirectory.resolve("large-messages").toString())
                .addAcceptorConfiguration("tcp", acceptorUrl(port));
        EmbeddedActiveMQ started = new EmbeddedActiveMQ().setConfiguration(configuration);
        try {
            started.start();
            if (!started.getActiveMQServer().waitForActivation(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Embedded Artemis broker did not become active");
            }
            int actualPort = started.getActiveMQServer()
                    .getRemotingService()
                    .getAcceptor("tcp")
                    .getActualPort();
            if (actualPort < 1) {
                throw new IllegalStateException("Embedded Artemis broker did not report its bound TCP port");
            }
            if (port == 0) {
                port = actualPort;
                connectionFactory = new ActiveMQConnectionFactory(connectionUrl(port));
            } else if (actualPort != port) {
                throw new IllegalStateException("Embedded Artemis broker did not bind the requested port " + port);
            }
            broker = started;
        } catch (Exception | Error failure) {
            try {
                started.stop();
            } catch (Throwable stopFailure) {
                failure.addSuppressed(stopFailure);
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            throw (Error) failure;
        }
    }

    synchronized void stop() throws Exception {
        EmbeddedActiveMQ current = broker;
        broker = null;
        if (current != null) {
            current.stop();
        }
    }

    synchronized ActiveMQConnectionFactory connectionFactory() {
        return requireConnectionFactory();
    }

    synchronized String connectionUrl() {
        requireConnectionFactory();
        return connectionUrl(port);
    }

    synchronized int queueConsumerCount(String queueName) {
        return requireQueue(queueName).getConsumerCount();
    }

    synchronized long queuePendingMessageCount(String queueName) {
        return requireQueue(queueName).getPendingMessageCount();
    }

    synchronized int queueDeliveringCount(String queueName) {
        return requireQueue(queueName).getDeliveringCount();
    }

    @Override
    public synchronized void close() throws Exception {
        Throwable failure = null;
        ActiveMQConnectionFactory current = connectionFactory;
        connectionFactory = null;
        if (current != null) {
            try {
                current.close();
            } catch (Throwable e) {
                failure = e;
            }
        }
        try {
            awaitTemporaryResourcesRemoved();
        } catch (Throwable e) {
            failure = merge(failure, e);
        }
        try {
            stop();
        } catch (Throwable e) {
            failure = merge(failure, e);
        }
        try {
            ActiveMQClient.clearThreadPools(5, TimeUnit.SECONDS);
        } catch (Throwable e) {
            failure = merge(failure, e);
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new AssertionError("Cannot close embedded Artemis broker", failure);
        }
    }

    private static Throwable merge(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private void awaitTemporaryResourcesRemoved() throws InterruptedException {
        EmbeddedActiveMQ current = broker;
        if (current == null) {
            return;
        }
        var server = current.getActiveMQServer();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hasTemporaryResources(server)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out awaiting embedded Artemis temporary-resource cleanup");
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    private static boolean hasTemporaryResources(org.apache.activemq.artemis.core.server.ActiveMQServer server) {
        boolean temporaryQueue = server.getPostOffice()
                .getAllBindings()
                .map(binding -> binding.getBindable())
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .anyMatch(Queue::isTemporary);
        if (temporaryQueue) {
            return true;
        }
        return server.getPostOffice()
                .getAddresses()
                .stream()
                .map(server::getAddressInfo)
                .filter(address -> address != null)
                .anyMatch(address -> address.isTemporary());
    }

    private ActiveMQConnectionFactory requireConnectionFactory() {
        ActiveMQConnectionFactory current = connectionFactory;
        if (current == null) {
            throw new IllegalStateException("Embedded Artemis broker has not been started");
        }
        return current;
    }

    private Queue requireQueue(String queueName) {
        EmbeddedActiveMQ current = broker;
        if (current == null) {
            throw new IllegalStateException("Embedded Artemis broker is not running");
        }
        Queue queue = current.getActiveMQServer().locateQueue(queueName);
        if (queue == null) {
            throw new IllegalStateException("Embedded Artemis queue does not exist: " + queueName);
        }
        return queue;
    }

    private static String acceptorUrl(int port) {
        return "tcp://127.0.0.1:" + port + "?useEpoll=false";
    }

    private static String connectionUrl(int port) {
        return "tcp://127.0.0.1:" + port
                + "?ha=false"
                + "&reconnectAttempts=0"
                + "&initialConnectAttempts=1"
                + "&useGlobalPools=false"
                + "&useEpoll=false";
    }
}
