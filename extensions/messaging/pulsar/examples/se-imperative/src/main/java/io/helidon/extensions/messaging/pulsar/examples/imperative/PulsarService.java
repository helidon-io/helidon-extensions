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

package io.helidon.extensions.messaging.pulsar.examples.imperative;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.config.ConfigException;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarConnector;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarIncomingConfig;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarOutgoingConfig;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarSubscriptionInitialPosition;
import io.helidon.http.Status;
import io.helidon.messaging.Emitter;
import io.helidon.messaging.MessagingChannel;
import io.helidon.messaging.MessagingGraph;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;

/**
 * Sends HTTP messages through Pulsar and exposes the latest received message.
 */
@SuppressWarnings(Api.SUPPRESS_PREVIEW)
final class PulsarService implements HttpService {
    private final AtomicReference<String> latestMessage = new AtomicReference<>("No messages received");
    private final MessagingGraph graph;
    private final Emitter<String> messages;

    PulsarService(Config config) {
        String pulsarSubscriptionName = config.get("pulsar-subscription-name")
                .asString()
                .orElseThrow(() -> new ConfigException("pulsar-subscription-name is missing"));
        String pulsarServiceUrl = config.get("pulsar-service-url")
                .asString()
                .orElseThrow(() -> new ConfigException("pulsar-service-url is missing"));

        String messagesTopic = config.get("messages-topic")
                .asString()
                .orElseThrow(() -> new ConfigException("messages-topic is missing"));

        PulsarConnector pulsar = PulsarConnector.builder()
                .name("pulsar-1")
                .serviceUrl(pulsarServiceUrl)
                .build();

        MessagingChannel<String> incoming = MessagingChannel.create("messages-from-pulsar", String.class);
        MessagingChannel<String> outgoing = MessagingChannel.create("messages-to-pulsar", String.class);

        PulsarIncomingConfig incomingConfig = PulsarIncomingConfig.builder()
                .connector(pulsar.name())
                .channelName(incoming.name())
                .execution(execution -> execution.maxInFlightMessages(64))
                .topic(messagesTopic)
                .subscriptionName(pulsarSubscriptionName)
                .subscriptionInitialPosition(PulsarSubscriptionInitialPosition.EARLIEST)
                .build();

        PulsarOutgoingConfig outgoingConfig = PulsarOutgoingConfig.builder()
                .connector(pulsar.name())
                .channelName(outgoing.name())
                .topic(messagesTopic)
                .build();

        graph = MessagingGraph.builder()
                .addConnector(pulsar)
                .channel(incoming)
                .channel(outgoing)
                .incoming(Map.of(incoming.name(), incomingConfig))
                .outgoing(Map.of(outgoing.name(), outgoingConfig))
                .messageSink(incoming, message -> {
                    latestMessage.set(message.entity());
                    System.out.println("Received message: " + message.entity());
                })
                .build();
        messages = graph.emitter(outgoing);
    }

    @Override
    public void routing(HttpRules rules) {
        rules.post("/messages", (request, response) -> {
            messages.emit(request.content().as(String.class));
            response.status(Status.NO_CONTENT_204).send();
        }).get("/messages/latest", (_, response) -> response.send(latestMessage.get()));
    }

    @Override
    public void beforeStart() {
        graph.start();
    }

    @Override
    public void afterStop() {
        graph.close();
    }
}
