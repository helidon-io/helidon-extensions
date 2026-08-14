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

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

final class ArtemisBroker implements AutoCloseable {
    private final Path dataDirectory;
    private final int port;
    private final String name;
    private final ActiveMQConnectionFactory connectionFactory;
    private EmbeddedActiveMQ broker;

    private ArtemisBroker(Path dataDirectory, int port, String name) {
        this.dataDirectory = dataDirectory;
        this.port = port;
        this.name = name;
        this.connectionFactory = new ActiveMQConnectionFactory(connectionUrl(port));
    }

    static ArtemisBroker create(Path dataDirectory) throws IOException {
        return new ArtemisBroker(dataDirectory, availablePort(), "helidon-jms-it-" + System.nanoTime());
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
        started.start();
        if (!started.getActiveMQServer().waitForActivation(10, TimeUnit.SECONDS)) {
            started.stop();
            throw new IllegalStateException("Embedded Artemis broker did not become active");
        }
        broker = started;
    }

    synchronized void stop() throws Exception {
        EmbeddedActiveMQ current = broker;
        broker = null;
        if (current != null) {
            current.stop();
        }
    }

    ActiveMQConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    String connectionUrl() {
        return connectionUrl(port);
    }

    @Override
    public void close() throws Exception {
        try {
            stop();
        } finally {
            connectionFactory.close();
            ActiveMQClient.clearThreadPools(5, TimeUnit.SECONDS);
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        }
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
