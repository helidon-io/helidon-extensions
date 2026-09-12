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

package io.helidon.extensions.messaging.jms.examples.imperative;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.config.ConfigException;
import io.helidon.extensions.messaging.connectors.jms.JmsConnector;
import io.helidon.extensions.messaging.connectors.jms.JmsIncomingConfig;
import io.helidon.extensions.messaging.connectors.jms.JmsOutgoingConfig;
import io.helidon.http.Status;
import io.helidon.messaging.Emitter;
import io.helidon.messaging.MessagingChannel;
import io.helidon.messaging.MessagingGraph;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

/**
 * Sends HTTP messages through JMS and exposes the latest received message.
 */
@SuppressWarnings(Api.SUPPRESS_PREVIEW)
final class JmsService implements HttpService {
    private final AtomicReference<String> latestMessage = new AtomicReference<>("No messages received");
    private final ActiveMQConnectionFactory connectionFactory;
    private final MessagingGraph graph;
    private final Emitter<String> messages;

    JmsService(Config config) {
        String jmsBrokerUrl = config.get("jms-broker-url")
                .asString()
                .orElseThrow(() -> new ConfigException("jms-broker-url is missing"));
        String jmsUsername = config.get("jms-username")
                .asString()
                .orElseThrow(() -> new ConfigException("jms-username is missing"));
        String jmsPassword = config.get("jms-password")
                .asString()
                .orElseThrow(() -> new ConfigException("jms-password is missing"));
        String messagesDestination = config.get("messages-destination")
                .asString()
                .orElseThrow(() -> new ConfigException("messages-destination is missing"));

        connectionFactory = new ActiveMQConnectionFactory(jmsBrokerUrl);
        JmsConnector jms = JmsConnector.builder()
                .name("jms-1")
                .connectionFactory(connectionFactory)
                .username(jmsUsername)
                .password(jmsPassword)
                .build();

        MessagingChannel<String> incoming = MessagingChannel.create("messages-from-jms", String.class);
        MessagingChannel<String> outgoing = MessagingChannel.create("messages-to-jms", String.class);

        JmsIncomingConfig incomingConfig = JmsIncomingConfig.builder()
                .connector(jms.name())
                .channelName(incoming.name())
                .execution(execution -> execution.maxInFlightMessages(64))
                .destination(messagesDestination)
                .build();

        JmsOutgoingConfig outgoingConfig = JmsOutgoingConfig.builder()
                .connector(jms.name())
                .channelName(outgoing.name())
                .destination(messagesDestination)
                .build();

        graph = MessagingGraph.builder()
                .addConnector(jms)
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
        try {
            graph.close();
        } finally {
            connectionFactory.close();
        }
    }
}
