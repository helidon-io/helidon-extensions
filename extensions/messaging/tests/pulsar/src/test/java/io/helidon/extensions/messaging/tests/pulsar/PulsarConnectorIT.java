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

package io.helidon.extensions.messaging.tests.pulsar;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.helidon.extensions.messaging.connectors.pulsar.PulsarMessage;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.FailOnceReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.IncomingReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.OutgoingSender;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingRuntime;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@Testcontainers(disabledWithoutDocker = true)
class PulsarConnectorIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration NO_MESSAGE_TIMEOUT = Duration.ofSeconds(2);
    private static final DockerImageName PULSAR_IMAGE = DockerImageName.parse("apachepulsar/pulsar:4.0.13");

    @Container
    private static final PulsarContainer PULSAR = new PulsarContainer(PULSAR_IMAGE)
            .withEnv("PULSAR_PREFIX_acknowledgmentAtBatchIndexLevelEnabled", "true")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Test
    @Timeout(90)
    void testConfiguredEmitterPublishesPayloadMessageAndBatchWithKeyAndProperties() throws Exception {
        String topic = uniqueName("outgoing");
        String subscription = uniqueName("outgoing-reader");

        try (PulsarClient client = newClient();
                Consumer<String> consumer = stringConsumer(client, topic, subscription)) {
            ServiceRegistryManager manager = outgoingRegistryManager(topic);
            try {
                OutgoingSender sender = manager.registry().get(OutgoingSender.class);
                manager.registry().get(MessagingRuntime.class);

                sender.send("payload");
                sender.send(Message.builder("generic message")
                                    .header("kind", "generic")
                                    .build());
                sender.send(PulsarMessage.<String>builder("native message")
                                    .key("native-key")
                                    .header("kind", "native")
                                    .build());
                sender.sendBatch(MessageBatch.create(List.of(
                        PulsarMessage.<String>builder("batch first")
                                .key("batch-key-1")
                                .header("kind", "batch-1")
                                .build(),
                        PulsarMessage.<String>builder("batch second")
                                .key("batch-key-2")
                                .header("kind", "batch-2")
                                .build())));

                List<org.apache.pulsar.client.api.Message<String>> messages = receive(consumer, 5);
                assertThat(messages.stream().map(org.apache.pulsar.client.api.Message::getValue).toList(),
                           is(List.of("payload", "generic message", "native message", "batch first", "batch second")));
                assertThat(messages.get(1).getProperty("kind"), is("generic"));
                assertNativeMessage(messages.get(2), "native-key", "native");
                assertNativeMessage(messages.get(3), "batch-key-1", "batch-1");
                assertNativeMessage(messages.get(4), "batch-key-2", "batch-2");
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    @Timeout(90)
    void testIncomingPreservesNativeMetadataAndAcknowledgesAcrossStableSubscriptionRestart() throws Exception {
        String topic = uniqueName("incoming");
        String subscription = uniqueName("stable-subscription");
        String yaml = incomingYaml(PulsarMessagingTypes.INCOMING_CHANNEL, topic, subscription, false);

        try (PulsarClient client = newClient();
                Producer<String> producer = client.newProducer(Schema.STRING).topic(topic).create()) {
            ServiceRegistryManager firstManager = PulsarScenarioRegistry.create(yaml, IncomingReceiver.class);
            try {
                ServiceRegistry registry = firstManager.registry();
                IncomingReceiver receiver = registry.get(IncomingReceiver.class);
                registry.get(MessagingRuntime.class);

                long eventTime = System.currentTimeMillis() - 1_000;
                producer.newMessage()
                        .key("incoming-key")
                        .property("trace-id", "incoming-trace")
                        .eventTime(eventTime)
                        .value("incoming message")
                        .send();

                PulsarMessage<String> message = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat(message, notNullValue());
                assertThat(message.entity(), is("incoming message"));
                assertThat(message.key(), is(Optional.of("incoming-key")));
                assertThat(message.headers().get("trace-id"), is("incoming-trace"));
                assertThat(message.topic(), is(Optional.of(canonicalTopic(topic))));
                assertThat(message.messageId().isPresent(), is(true));
                assertThat(message.messageId().orElseThrow().length > 0, is(true));
                assertThat(message.publishTime().isPresent(), is(true));
                assertThat(message.publishTime().orElseThrow() > 0, is(true));
                assertThat(message.eventTime().orElseThrow(), is(eventTime));
                assertThat(message.producerName().isPresent(), is(true));
                assertThat(message.redeliveryCount(), is(OptionalInt.of(0)));
            } finally {
                firstManager.shutdown();
            }

            ServiceRegistryManager secondManager = PulsarScenarioRegistry.create(yaml, IncomingReceiver.class);
            try {
                ServiceRegistry registry = secondManager.registry();
                IncomingReceiver receiver = registry.get(IncomingReceiver.class);
                registry.get(MessagingRuntime.class);

                assertThat("acknowledged message is not redelivered after restart",
                           receiver.awaitMessage(NO_MESSAGE_TIMEOUT),
                           nullValue());

                producer.send("after restart");
                PulsarMessage<String> afterRestart = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat(afterRestart, notNullValue());
                assertThat(afterRestart.entity(), is("after restart"));
                assertThat(afterRestart.redeliveryCount(), is(OptionalInt.of(0)));
            } finally {
                secondManager.shutdown();
            }
        }
    }

    @Test
    @Timeout(90)
    void testTerminalFailOnceDeliveryIsRedeliveredByBroker() throws Exception {
        String topic = uniqueName("redelivery");
        String subscription = uniqueName("redelivery-subscription");
        String yaml = incomingYaml(PulsarMessagingTypes.REDELIVERY_CHANNEL, topic, subscription, true);
        FailOnceReceiver.reset();

        try (PulsarClient client = newClient();
                Producer<String> producer = client.newProducer(Schema.STRING).topic(topic).create()) {
            producer.send("redeliver me");

            ServiceRegistryManager firstManager = PulsarScenarioRegistry.create(yaml, FailOnceReceiver.class);
            FailOnceReceiver firstReceiver = firstManager.registry().get(FailOnceReceiver.class);
            firstManager.registry().get(MessagingRuntime.class);
            PulsarMessage<String> failed = firstReceiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat(failed, notNullValue());
            assertThat(failed.entity(), is("redeliver me"));
            byte[] failedMessageId = failed.messageId().orElseThrow();
            shutdownAfterExpectedSourceFailure(firstManager);

            ServiceRegistryManager secondManager = PulsarScenarioRegistry.create(yaml, FailOnceReceiver.class);
            try {
                FailOnceReceiver secondReceiver = secondManager.registry().get(FailOnceReceiver.class);
                secondManager.registry().get(MessagingRuntime.class);

                PulsarMessage<String> redelivered = secondReceiver.awaitDelivery(WAIT_TIMEOUT);
                assertThat(redelivered, notNullValue());
                assertThat(redelivered.entity(), is("redeliver me"));
                assertArrayEquals(failedMessageId, redelivered.messageId().orElseThrow());
                assertThat(FailOnceReceiver.attemptCount(), is(2));
            } finally {
                secondManager.shutdown();
            }
        }
    }

    private static ServiceRegistryManager outgoingRegistryManager(String topic) {
        return PulsarScenarioRegistry.create("""
                helidon:
                  messaging:
                    outgoing:
                      %s:
                        connector: helidon-pulsar
                        service-url: "%s"
                        topic: "%s"
                        schema: STRING
                """.formatted(PulsarMessagingTypes.OUTGOING_CHANNEL,
                               PULSAR.getPulsarBrokerUrl(),
                               topic),
                                             OutgoingSender.class);
    }

    private static String incomingYaml(String channel,
                                       String topic,
                                       String subscription,
                                       boolean failOnExhausted) {
        if (failOnExhausted) {
            return """
                    helidon:
                      messaging:
                        incoming:
                          %s:
                            connector: helidon-pulsar
                            service-url: "%s"
                            topic: "%s"
                            schema: STRING
                            subscription-name: "%s"
                            subscription-initial-position: EARLIEST
                            batch-index-acknowledgment-enabled: true
                            receive-timeout: PT0.1S
                            negative-ack-redelivery-delay: PT0.1S
                            failure:
                              retry:
                                delay: PT0.01S
                                max-attempts: 1
                              on-exhausted: FAIL
                    """.formatted(channel,
                                   PULSAR.getPulsarBrokerUrl(),
                                   topic,
                                   subscription);
        }
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-pulsar
                        service-url: "%s"
                        topic: "%s"
                        schema: STRING
                        subscription-name: "%s"
                        subscription-initial-position: EARLIEST
                        receive-timeout: PT0.1S
                """.formatted(channel,
                               PULSAR.getPulsarBrokerUrl(),
                               topic,
                               subscription);
    }

    private static PulsarClient newClient() throws Exception {
        return PulsarClient.builder()
                .serviceUrl(PULSAR.getPulsarBrokerUrl())
                .build();
    }

    private static Consumer<String> stringConsumer(PulsarClient client,
                                                   String topic,
                                                   String subscription) throws Exception {
        return client.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName(subscription)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();
    }

    private static List<org.apache.pulsar.client.api.Message<String>> receive(Consumer<String> consumer,
                                                                              int expectedCount) throws Exception {
        List<org.apache.pulsar.client.api.Message<String>> messages = new ArrayList<>(expectedCount);
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (messages.size() < expectedCount) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            int timeoutMillis = (int) Math.min(Integer.MAX_VALUE,
                                               Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            org.apache.pulsar.client.api.Message<String> message = consumer.receive(timeoutMillis,
                                                                                     TimeUnit.MILLISECONDS);
            if (message == null) {
                break;
            }
            messages.add(message);
            consumer.acknowledge(message);
        }
        assertThat("received Pulsar message count", messages.size(), is(expectedCount));
        return List.copyOf(messages);
    }

    private static void assertNativeMessage(org.apache.pulsar.client.api.Message<String> message,
                                            String expectedKey,
                                            String expectedKind) {
        assertThat(message.hasKey(), is(true));
        assertThat(message.getKey(), is(expectedKey));
        assertThat(message.getProperty("kind"), is(expectedKind));
    }

    private static void shutdownAfterExpectedSourceFailure(ServiceRegistryManager manager) {
        try {
            manager.shutdown();
        } catch (RuntimeException ignored) {
            // The first graph is expected to report the terminal handler failure after abandoning the message.
        }
    }

    private static String canonicalTopic(String topic) {
        return "persistent://public/default/" + topic;
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
