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

package io.helidon.extensions.messaging.examples.imperative;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.config.ConfigException;
import io.helidon.extensions.messaging.connectors.kafka.KafkaConnector;
import io.helidon.extensions.messaging.connectors.kafka.KafkaIncomingConfig;
import io.helidon.extensions.messaging.connectors.kafka.KafkaOutgoingConfig;
import io.helidon.http.Status;
import io.helidon.messaging.Emitter;
import io.helidon.messaging.MessagingChannel;
import io.helidon.messaging.MessagingGraph;
import io.helidon.webserver.WebServer;

/**
 * Owns the messaging graph and HTTP server, including startup and shutdown ordering.
 */
@SuppressWarnings(Api.SUPPRESS_PREVIEW)
final class KafkaApplication implements AutoCloseable {
    private final MessagingGraph graph;
    private final WebServer server;

    private KafkaApplication(MessagingGraph graph, WebServer server) {
        this.graph = graph;
        this.server = server;
    }

    static KafkaApplication start(Config config) {
        String kafkaGroupId = config.get("app.kafka-group-id")
                .asString()
                .orElseThrow(() -> new ConfigException("app.kafka-group-id is missing"));
        List<String> kafkaBootstrapServers = config.get("app.kafka-bootstrap-servers")
                .asList(String.class)
                .orElseThrow(() -> new ConfigException("app.kafka-bootstrap-servers is missing"));

        String ordersTopic = config.get("app.orders-topic")
                .asString()
                .orElseThrow(() -> new ConfigException("app.orders-topic is missing"));
        String messagesTopic = config.get("app.messages-topic")
                .asString()
                .orElseThrow(() -> new ConfigException("app.messages-topic is missing"));

        KafkaConnector kafka = KafkaConnector.builder()
                .name("orders-kafka")
                .bootstrapServers(kafkaBootstrapServers)
                .build();


        MessagingChannel<String> orders = MessagingChannel.create("orders", String.class);
        MessagingChannel<String> httpMessages = MessagingChannel.create("http-messages", String.class);

        KafkaIncomingConfig ordersConfig = KafkaIncomingConfig.builder()
                .connector(kafka.name())
                .channelName(orders.name())
                .execution(execution -> execution.maxInFlightMessages(64))
                .topic(ordersTopic)
                .groupId(kafkaGroupId)
                .autoOffsetReset("earliest")
                .build();

        KafkaOutgoingConfig messagesConfig = KafkaOutgoingConfig.builder()
                .connector(kafka.name())
                .channelName(httpMessages.name())
                .topic(messagesTopic)
                .putProperty("linger.ms", "5")
                .build();

        // "business logic"
        var latestOrder = new AtomicReference<>("No orders received");

        // prepare the messaging graph
        MessagingGraph graph = MessagingGraph.builder()
                .addConnector(kafka)
                .channel(orders)
                .channel(httpMessages)
                .incoming(Map.of(orders.name(), ordersConfig))
                .outgoing(Map.of(httpMessages.name(), messagesConfig))
                .messageSink(orders, message -> {
                    latestOrder.set(message.entity());
                    System.out.println("Received order: " + message.entity());
                })
                .build()
                .start();

        Emitter<String> emitter = graph.emitter(httpMessages);

        WebServer server;
        try {
            server = WebServer.builder()
                    .config(config.get("server"))
                    .shutdownHook(false)
                    .routing(routing -> routing
                            .post("/messages", (request, response) -> {
                                emitter.emit(request.content().as(String.class));
                                response.status(Status.NO_CONTENT_204).send();
                            })
                            .get("/orders/latest", (_, response) -> response.send(latestOrder.get())))
                    .build();

            server.start();
        } catch (Exception e) {
            // server failed to start, close the messaging graph
            graph.close();
            throw e;
        }

        // server started, graph started - we expect close() to be called
        return new KafkaApplication(graph, server);
    }

    WebServer server() {
        return server;
    }

    @Override
    public void close() {
        // Stop accepting HTTP requests before draining and closing the graph's channel connections.
        try {
            server.stop();
        } finally {
            graph.close();
        }
    }
}
