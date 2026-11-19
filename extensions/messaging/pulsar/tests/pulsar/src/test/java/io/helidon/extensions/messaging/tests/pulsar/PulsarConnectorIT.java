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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarConnector;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarIncomingConfig;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarMessage;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarOutgoingConfig;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarSchemaType;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarSubscriptionInitialPosition;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.AutoIncomingReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.AutoOutgoingSender;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.BatchMessageReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.BatchPayloadReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.BatchReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.BlockingBatchReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.FailOnceReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.FailedMappingReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.IncomingReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.JsonOutgoingSender;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.JsonSchemaProvider;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.OutgoingSender;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.PartialBatchReceiver;
import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingChannel;
import io.helidon.messaging.MessagingConfig;
import io.helidon.messaging.MessagingGraph;
import io.helidon.messaging.MessagingRuntime;
import io.helidon.messaging.spi.OutgoingChannel;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.common.schema.SchemaType;
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
import static org.hamcrest.Matchers.lessThanOrEqualTo;

@Testcontainers(disabledWithoutDocker = true)
class PulsarConnectorIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration NO_MESSAGE_TIMEOUT = Duration.ofSeconds(2);
    private static final DockerImageName PULSAR_IMAGE = DockerImageName.parse("apachepulsar/pulsar:4.0.13");
    private static final String LEGACY_FAILURE_MESSAGE_HEADER = "helidon_messaging_dead_letter_failure_message";
    private static final String LEGACY_FAILURE_TYPE_HEADER = "helidon_messaging_dead_letter_failure_type";

    @Container
    private static final PulsarContainer PULSAR = new PulsarContainer(PULSAR_IMAGE)
            .withEnv("PULSAR_PREFIX_acknowledgmentAtBatchIndexLevelEnabled", "true")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Test
    @Timeout(90)
    void testImperativeGraphUsesConfiguredConnectorAndTypedChannels() throws Exception {
        String topic = uniqueName("imperative");
        PulsarConnector pulsar = PulsarConnector.builder()
                .name("imperative-broker")
                .serviceUrl(PULSAR.getPulsarBrokerUrl())
                .topic(topic)
                .subscriptionName(uniqueName("imperative-reader"))
                .subscriptionInitialPosition(PulsarSubscriptionInitialPosition.EARLIEST)
                .schemaProvider(PulsarMessagingTypes.JSON_SCHEMA_PROVIDER)
                .addSchemaProvider(new JsonSchemaProvider())
                .build();
        MessagingChannel<PulsarTestPayload> incoming =
                MessagingChannel.create("imperative-in", PulsarTestPayload.class);
        MessagingChannel<PulsarTestPayload> outgoing =
                MessagingChannel.create("imperative-out", PulsarTestPayload.class);
        MessagingConfig.Builder builder = MessagingGraph.builder()
                .channel(incoming)
                .channel(outgoing);
        LinkedBlockingQueue<PulsarTestPayload> received = new LinkedBlockingQueue<>();
        builder.incomingChannel(incoming, pulsar.incoming(PulsarIncomingConfig.builder()
                .connector(pulsar.name())
                .channelName(incoming.name())
                .build()))
                .messageSink(incoming, message -> received.add(message.entity()));
        builder.outgoingChannel(outgoing, pulsar.outgoing(PulsarOutgoingConfig.builder()
                .connector(pulsar.name())
                .channelName(outgoing.name())
                .build()));

        PulsarTestPayload payload = new PulsarTestPayload("imperative-order", 3);
        try (MessagingGraph graph = builder.build()) {
            graph.start();
            graph.emitter(outgoing).emit(Message.create(payload));
            assertThat(received.poll(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(payload));
        }
    }

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
    @Timeout(180)
    void testConfiguredBuiltInSchemasRoundTripThroughBroker() throws Exception {
        List<SchemaRoundTrip<?>> schemaCases = fixedSchemaCases();
        assertThat(schemaCases.stream().map(SchemaRoundTrip::type).toList(),
                   is(Stream.of(PulsarSchemaType.values())
                              .filter(type -> type != PulsarSchemaType.AUTO)
                              .toList()));

        try (PulsarClient client = newClient()) {
            for (SchemaRoundTrip<?> schemaCase : schemaCases) {
                try {
                    roundTrip(client, schemaCase);
                } catch (Exception | AssertionError e) {
                    throw new AssertionError("Pulsar " + schemaCase.type() + " broker round trip failed", e);
                }
            }
        }
    }

    @Test
    @Timeout(90)
    void testAutoOutgoingUsesTopicSchema() throws Exception {
        String autoTopic = uniqueName("auto-outgoing");

        try (PulsarClient client = newClient()) {
            try (Producer<String> ignored = client.newProducer(Schema.STRING).topic(autoTopic).create()) {
                // Producer creation registers the topic schema used by AUTO_PRODUCE_BYTES.
            }
            try (Consumer<String> autoConsumer = consumer(client,
                                                          Schema.STRING,
                                                          autoTopic,
                                                          uniqueName("auto-reader"))) {
                ServiceRegistryManager autoManager = PulsarScenarioRegistry.create("""
                        messaging:
                          connector:
                            test-pulsar:
                              type: helidon-pulsar
                              service-url: "%2$s"
                          outgoing:
                            %1$s:
                              connector: test-pulsar
                              topic: "%3$s"
                              schema: AUTO
                        """.formatted(PulsarMessagingTypes.AUTO_OUTGOING_CHANNEL,
                                       PULSAR.getPulsarBrokerUrl(),
                                       autoTopic),
                                                                                    AutoOutgoingSender.class);
                try {
                    AutoOutgoingSender sender = autoManager.registry().get(AutoOutgoingSender.class);
                    autoManager.registry().get(MessagingRuntime.class);
                    sender.send("serialized payload".getBytes(StandardCharsets.UTF_8));
                    assertThat(receiveOne(autoConsumer).getValue(), is("serialized payload"));
                } finally {
                    autoManager.shutdown();
                }
            }
        }
    }

    private static List<SchemaRoundTrip<?>> fixedSchemaCases() {
        ByteBuffer byteBuffer = ByteBuffer.wrap(new byte[] {4, 5, 6, 7, 8});
        byteBuffer.position(2);
        byteBuffer.limit(4);
        // Pulsar TIMESTAMP stores epoch milliseconds, so sub-millisecond nanos are intentionally truncated.
        Timestamp timestamp = Timestamp.from(Instant.parse("2026-08-24T12:34:56.123456789Z"));
        return List.of(
                schemaCase(PulsarSchemaType.STRING, Schema.STRING, "schema-value"),
                schemaCase(PulsarSchemaType.BYTES, Schema.BYTES, new byte[] {1, 2, 3}),
                schemaCase(PulsarSchemaType.BYTEBUFFER,
                           Schema.BYTEBUFFER,
                           byteBuffer,
                           ByteBuffer.wrap(new byte[] {4, 5, 6, 7})),
                schemaCase(PulsarSchemaType.BOOLEAN, Schema.BOOL, true),
                schemaCase(PulsarSchemaType.INT8, Schema.INT8, (byte) -12),
                schemaCase(PulsarSchemaType.INT16, Schema.INT16, (short) 12_345),
                schemaCase(PulsarSchemaType.INT32, Schema.INT32, 123_456),
                schemaCase(PulsarSchemaType.INT64, Schema.INT64, 9_876_543_210L),
                schemaCase(PulsarSchemaType.FLOAT, Schema.FLOAT, 1.25F),
                schemaCase(PulsarSchemaType.DOUBLE, Schema.DOUBLE, -4.5D),
                schemaCase(PulsarSchemaType.DATE, Schema.DATE, new Date(1_725_000_000_123L)),
                schemaCase(PulsarSchemaType.TIME, Schema.TIME, new Time(45_296_000L)),
                schemaCase(PulsarSchemaType.TIMESTAMP,
                           Schema.TIMESTAMP,
                           timestamp,
                           new Timestamp(timestamp.getTime())),
                schemaCase(PulsarSchemaType.INSTANT,
                           Schema.INSTANT,
                           Instant.parse("2026-08-24T12:34:56.123456789Z")),
                schemaCase(PulsarSchemaType.LOCAL_DATE, Schema.LOCAL_DATE, LocalDate.of(2026, 8, 24)),
                schemaCase(PulsarSchemaType.LOCAL_TIME,
                           Schema.LOCAL_TIME,
                           LocalTime.of(12, 34, 56, 123_456_789)),
                schemaCase(PulsarSchemaType.LOCAL_DATE_TIME,
                           Schema.LOCAL_DATE_TIME,
                           LocalDateTime.of(2026, 8, 24, 12, 34, 56, 123_456_789)));
    }

    private static <T> SchemaRoundTrip<T> schemaCase(PulsarSchemaType type, Schema<T> schema, T payload) {
        return schemaCase(type, schema, payload, payload);
    }

    private static <T> SchemaRoundTrip<T> schemaCase(PulsarSchemaType type,
                                                     Schema<T> schema,
                                                     T payload,
                                                     T expected) {
        return new SchemaRoundTrip<>(type, schema, payload, expected);
    }

    private static <T> void roundTrip(PulsarClient client, SchemaRoundTrip<T> schemaCase) throws Exception {
        String topic = uniqueName("schema-" + schemaCase.type());
        PulsarConnector pulsar = PulsarConnector.builder()
                .name("schema-broker")
                .serviceUrl(PULSAR.getPulsarBrokerUrl())
                .schema(schemaCase.type())
                .build();
        PulsarOutgoingConfig channel = PulsarOutgoingConfig.builder()
                .connector(pulsar.name())
                .channelName(uniqueName("schema-out"))
                .topic(topic)
                .build();

        try (OutgoingChannel connector = pulsar.outgoing(channel)) {
            connector.start();
            try (Consumer<T> consumer = consumer(client,
                                                 schemaCase.schema(),
                                                 topic,
                                                 uniqueName("schema-reader"))) {
                connector.send(schemaCase.payload());
                schemaCase.assertPayload(receiveOne(consumer).getValue());
            }
        }
    }

    @Test
    @Timeout(90)
    void testAutoIncomingUsesTopicSchema() throws Exception {
        String topic = uniqueName("auto-incoming");
        String subscription = uniqueName("auto-incoming-reader");
        String yaml = """
                messaging:
                  connector:
                    test-pulsar:
                      type: helidon-pulsar
                      service-url: "%2$s"
                  incoming:
                    %1$s:
                      connector: test-pulsar
                      topic: "%3$s"
                      schema: AUTO
                      subscription-name: "%4$s"
                      subscription-initial-position: EARLIEST
                """.formatted(PulsarMessagingTypes.AUTO_INCOMING_CHANNEL,
                               PULSAR.getPulsarBrokerUrl(),
                               topic,
                               subscription);

        try (PulsarClient client = newClient();
                Producer<Integer> producer = client.newProducer(Schema.INT32).topic(topic).create()) {
            ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, AutoIncomingReceiver.class);
            try {
                AutoIncomingReceiver receiver = manager.registry().get(AutoIncomingReceiver.class);
                manager.registry().get(MessagingRuntime.class);
                producer.send(42);

                PulsarMessage<GenericRecord> message = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat(message, notNullValue());
                assertThat(message.entity().getSchemaType(), is(SchemaType.INT32));
                assertThat(message.entity().getNativeObject(), is(42));
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    @Timeout(90)
    void testServiceRegistryCustomJsonSchemaProvider() throws Exception {
        String topic = uniqueName("json-outgoing");
        PulsarTestPayload payload = new PulsarTestPayload("order-42", 3);

        try (PulsarClient client = newClient();
                Consumer<PulsarTestPayload> consumer = consumer(client,
                                                                Schema.JSON(PulsarTestPayload.class),
                                                                topic,
                                                                uniqueName("json-reader"))) {
            ServiceRegistryManager manager = PulsarScenarioRegistry.create("""
                    messaging:
                      connector:
                        test-pulsar:
                          type: helidon-pulsar
                          service-url: "%2$s"
                      outgoing:
                        %1$s:
                          connector: test-pulsar
                          topic: "%3$s"
                          schema-provider: %4$s
                    """.formatted(PulsarMessagingTypes.JSON_OUTGOING_CHANNEL,
                                   PULSAR.getPulsarBrokerUrl(),
                                   topic,
                                   PulsarMessagingTypes.JSON_SCHEMA_PROVIDER),
                                                                               JsonOutgoingSender.class,
                                                                               JsonSchemaProvider.class);
            try {
                JsonOutgoingSender sender = manager.registry().get(JsonOutgoingSender.class);
                manager.registry().get(MessagingRuntime.class);
                sender.send(payload);
                assertThat(receiveOne(consumer).getValue(), is(payload));
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
                assertThat(message.header("trace-id").orElseThrow(), is("incoming-trace"));
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
    @Timeout(120)
    void testNullAndOversizedMappingFailuresDeadLetterAndSettleAcrossRestart() throws Exception {
        String sourceTopic = uniqueName("failed-mapping-source");
        String deadLetterTopic = uniqueName("failed-mapping-dlq");
        String sourceSubscription = uniqueName("failed-mapping-subscription");
        String yaml = failedMappingYaml(sourceTopic, deadLetterTopic, sourceSubscription)
                .replace("receive-timeout: PT0.1S", "receive-timeout: PT30S\n      batch-receive-max-messages: 4");

        try (PulsarClient client = newClient();
                Producer<String> producer = client.newProducer(Schema.STRING)
                        .topic(sourceTopic)
                        .enableBatching(false)
                        .create();
                Consumer<String> deadLetterConsumer = stringConsumer(client,
                                                                     deadLetterTopic,
                                                                     uniqueName("failed-mapping-dlq-reader"))) {
            try (Consumer<String> consumer = stringConsumer(client, sourceTopic, sourceSubscription)) {
                assertThat(consumer.isConnected(), is(true));
            }
            producer.send("a");
            producer.newMessage()
                    .key("null-key")
                    .property("kind", "null")
                    .value(null)
                    .send();
            producer.newMessage()
                    .key("oversized-key")
                    .property("kind", "oversized")
                    .value("oversized")
                    .send();
            producer.send("b");

            ServiceRegistryManager firstManager = PulsarScenarioRegistry.create(yaml, FailedMappingReceiver.class);
            try {
                FailedMappingReceiver receiver = firstManager.registry().get(FailedMappingReceiver.class);
                firstManager.registry().get(MessagingRuntime.class);

                org.apache.pulsar.client.api.Message<String> nullDeadLetter = receiveOne(deadLetterConsumer);
                org.apache.pulsar.client.api.Message<String> oversizedDeadLetter = receiveOne(deadLetterConsumer);
                assertFailedMappingDeadLetter(nullDeadLetter, "null-key", "null", sourceTopic);
                assertFailedMappingDeadLetter(oversizedDeadLetter,
                                              "oversized-key",
                                              "oversized",
                                              sourceTopic);
                PulsarMessage<String> first = receiver.awaitMessage(WAIT_TIMEOUT);
                PulsarMessage<String> last = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat(first, notNullValue());
                assertThat(last, notNullValue());
                assertThat(List.of(first.entity(), last.entity()), is(List.of("a", "b")));
                assertThat("failed mappings must not reach the application handler",
                           receiver.awaitMessage(NO_MESSAGE_TIMEOUT),
                           nullValue());
            } finally {
                firstManager.shutdown();
            }

            ServiceRegistryManager secondManager = PulsarScenarioRegistry.create(
                    yaml.replace("batch-receive-max-messages: 4", "batch-receive-max-messages: 1"),
                    FailedMappingReceiver.class);
            try {
                FailedMappingReceiver receiver = secondManager.registry().get(FailedMappingReceiver.class);
                secondManager.registry().get(MessagingRuntime.class);

                assertThat("settled mapping failures must not be dead-lettered again after restart",
                           deadLetterConsumer.receive((int) NO_MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                           nullValue());
                assertThat("valid siblings are settled with the failed mappings",
                           receiver.awaitMessage(NO_MESSAGE_TIMEOUT), nullValue());
                producer.send("x");
                PulsarMessage<String> valid = receiver.awaitMessage(WAIT_TIMEOUT);
                assertThat(valid, notNullValue());
                assertThat(valid.entity(), is("x"));
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
                assertThat(redelivered.messageId().orElseThrow(), is(failedMessageId));
                assertThat(FailOnceReceiver.attemptCount(), is(2));
            } finally {
                secondManager.shutdown();
            }
        }
    }

    @Test
    @Timeout(90)
    void testDefaultNativeBatchDispatchesEveryScalarHandlerAndSettlesAcrossRestart() throws Exception {
        String topic = uniqueName("default-batch");
        String subscription = uniqueName("default-batch-reader");
        List<String> payloads = Stream.iterate(0, i -> i + 1)
                .limit(101)
                .map(i -> "message-" + i)
                .toList();
        String yaml = batchYaml(topic, subscription, "");

        try (PulsarClient client = newClient()) {
            seedBacklog(client, topic, subscription, payloads);
            ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml,
                                                                          BatchReceiver.class,
                                                                          BatchPayloadReceiver.class,
                                                                          BatchMessageReceiver.class);
            try {
                ServiceRegistry registry = manager.registry();
                BatchReceiver batchReceiver = registry.get(BatchReceiver.class);
                BatchPayloadReceiver payloadReceiver = registry.get(BatchPayloadReceiver.class);
                BatchMessageReceiver messageReceiver = registry.get(BatchMessageReceiver.class);
                registry.get(MessagingRuntime.class);

                List<MessageBatch<String>> batches = receiveBatches(batchReceiver, payloads.size());
                assertThat("default receive policy collects native messages into batches",
                           batches.stream().anyMatch(batch -> batch.size() > 1), is(true));
                assertThat("default count cap", batches.stream().allMatch(batch -> batch.size() <= 100), is(true));
                assertThat(batchPayloads(batches), is(payloads));
                for (String expected : payloads) {
                    assertThat(payloadReceiver.awaitPayload(WAIT_TIMEOUT), is(expected));
                    Message<String> message = messageReceiver.awaitMessage(WAIT_TIMEOUT);
                    assertThat(message, notNullValue());
                    assertThat(message.entity(), is(expected));
                    assertThat(message.header("sequence").orElseThrow(), is(expected));
                }
                assertThat(payloadReceiver.awaitPayload(NO_MESSAGE_TIMEOUT), nullValue());
                assertThat(messageReceiver.awaitMessage(NO_MESSAGE_TIMEOUT), nullValue());
                assertThat(batchReceiver.awaitBatch(NO_MESSAGE_TIMEOUT), nullValue());
            } finally {
                manager.shutdown();
            }
            assertSubscriptionEmpty(client, topic, subscription);
        }
    }

    @Test
    @Timeout(90)
    void testConfiguredCountBoundsNativeBatchesIncludingSingletonOverride() throws Exception {
        try (PulsarClient client = newClient()) {
            for (int maximum : List.of(1, 3)) {
                String topic = uniqueName("count-batch");
                String subscription = uniqueName("count-batch-reader");
                List<String> payloads = List.of("a", "b", "c", "d", "e", "f");
                seedBacklog(client, topic, subscription, payloads);
                String yaml = batchYaml(topic, subscription, """
                        batch-receive-max-messages: %d
                        receive-timeout: PT30S
                        """.formatted(maximum));
                ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, BatchReceiver.class);
                try {
                    BatchReceiver receiver = manager.registry().get(BatchReceiver.class);
                    manager.registry().get(MessagingRuntime.class);
                    List<MessageBatch<String>> batches = receiveBatches(receiver, payloads.size());
                    assertThat(batches.stream().map(MessageBatch::size).toList(),
                               is(Collections.nCopies(payloads.size() / maximum, maximum)));
                    assertThat(batchPayloads(batches), is(payloads));
                } finally {
                    manager.shutdown();
                }
                assertSubscriptionEmpty(client, topic, subscription);
            }
        }
    }

    @Test
    @Timeout(90)
    void testNativeAutoscalingCannotExceedConfiguredPrefetchLimit() throws Exception {
        String topic = uniqueName("autoscale-batch");
        String subscription = uniqueName("autoscale-batch-reader");
        List<String> payloads = Stream.iterate(0, i -> i + 1)
                .limit(30)
                .map(i -> "message-" + i)
                .toList();
        String yaml = batchYaml(topic, subscription, """
                batch-receive-max-messages: 5
                receiver-queue-size: 5
                receive-timeout: PT30S
                consumer-properties:
                  autoScaledReceiverQueueSizeEnabled: true
                execution:
                  max-pending-messages: 5
                  max-in-flight-messages: 5
                """);
        try (PulsarClient client = newClient()) {
            seedBacklog(client, topic, subscription, payloads);
            ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, BlockingBatchReceiver.class);
            BlockingBatchReceiver receiver = manager.registry().get(BlockingBatchReceiver.class);
            try {
                manager.registry().get(MessagingRuntime.class);
                MessageBatch<String> batch = receiver.awaitBatch(WAIT_TIMEOUT);
                assertThat(batch, notNullValue());
                assertThat(batch.size(), is(5));
                assertThat("the first delivery remains blocked", receiver.awaitBatch(NO_MESSAGE_TIMEOUT), nullValue());

                var result = PULSAR.execInContainer("/pulsar/bin/pulsar-admin",
                                                    "topics",
                                                    "stats",
                                                    canonicalTopic(topic));
                assertThat(result.getStderr(), result.getExitCode(), is(0));
                Config stats = Config.just(result.getStdout(), MediaTypes.APPLICATION_YAML);
                assertThat("broker deliveries cannot exceed the retained batch plus the configured prefetch queue",
                           stats.get("msgOutCounter").asLong().get(), lessThanOrEqualTo(10L));
            } finally {
                receiver.release();
                manager.shutdown();
            }
        }
    }

    @Test
    @Timeout(90)
    void testByteThresholdFlushesBeforeCountOrTimeoutAndAllowsOversizedSingleton() throws Exception {
        try (PulsarClient client = newClient()) {
            for (List<String> payloads : List.of(List.of("aa", "bb", "cc"), List.of("oversized"))) {
                String topic = uniqueName("bytes-batch");
                String subscription = uniqueName("bytes-batch-reader");
                seedBacklog(client, topic, subscription, payloads);
                String yaml = batchYaml(topic, subscription, """
                        batch-receive-max-bytes: 6
                        receive-timeout: PT30S
                        """);
                ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, BatchReceiver.class);
                try {
                    BatchReceiver receiver = manager.registry().get(BatchReceiver.class);
                    manager.registry().get(MessagingRuntime.class);
                    MessageBatch<String> batch = receiver.awaitBatch(WAIT_TIMEOUT);
                    assertThat("byte threshold completes before the 30-second collection timeout", batch, notNullValue());
                    assertThat(batch.messages().stream().map(Message::entity).toList(), is(payloads));
                } finally {
                    manager.shutdown();
                }
                assertSubscriptionEmpty(client, topic, subscription);
            }
        }
    }

    @Test
    @Timeout(90)
    void testPartitionedTopicHonorsNativeBatchCountLimit() throws Exception {
        String topic = uniqueName("partitioned-batch");
        String subscription = uniqueName("partitioned-batch-reader");
        var result = PULSAR.execInContainer("/pulsar/bin/pulsar-admin",
                                            "topics",
                                            "create-partitioned-topic",
                                            canonicalTopic(topic),
                                            "--partitions",
                                            "2");
        assertThat(result.getStderr(), result.getExitCode(), is(0));
        String yaml = batchYaml(topic, subscription, """
                batch-receive-max-messages: 2
                receive-timeout: PT30S
                """);
        try (PulsarClient client = newClient()) {
            seedBacklog(client, topic + "-partition-0", subscription, List.of("a", "b", "c"));
            seedBacklog(client, topic + "-partition-1", subscription, List.of("d", "e", "f"));
            ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, BatchReceiver.class);
            try {
                BatchReceiver receiver = manager.registry().get(BatchReceiver.class);
                manager.registry().get(MessagingRuntime.class);
                List<MessageBatch<String>> batches = receiveBatches(receiver, 6);
                assertThat(batches.stream().map(MessageBatch::size).toList(), is(List.of(2, 2, 2)));
                assertThat(batchPayloads(batches).stream().sorted().toList(), is(List.of("a", "b", "c", "d", "e", "f")));
            } finally {
                manager.shutdown();
            }
            assertSubscriptionEmpty(client, topic, subscription);
        }
    }

    @Test
    @Timeout(90)
    void testSparseBatchFlushesOnTimeoutAndIdleReceivesDoNotDispatchEmptyBatches() throws Exception {
        String topic = uniqueName("timeout-batch");
        String subscription = uniqueName("timeout-batch-reader");
        String yaml = batchYaml(topic, subscription, """
                batch-receive-max-messages: 10
                receive-timeout: PT0.1S
                """);
        try (PulsarClient client = newClient();
                Producer<String> producer = client.newProducer(Schema.STRING).topic(topic).enableBatching(false).create()) {
            ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, BatchReceiver.class);
            try {
                BatchReceiver receiver = manager.registry().get(BatchReceiver.class);
                manager.registry().get(MessagingRuntime.class);
                assertThat(receiver.awaitBatch(NO_MESSAGE_TIMEOUT), nullValue());

                producer.send("sparse");
                MessageBatch<String> batch = receiver.awaitBatch(WAIT_TIMEOUT);
                assertThat(batch, notNullValue());
                assertThat(batch.messages().stream().map(Message::entity).toList(), is(List.of("sparse")));
                assertThat(receiver.awaitBatch(NO_MESSAGE_TIMEOUT), nullValue());

                producer.send("after idle");
                MessageBatch<String> afterIdle = receiver.awaitBatch(WAIT_TIMEOUT);
                assertThat(afterIdle, notNullValue());
                assertThat(afterIdle.messages().stream().map(Message::entity).toList(), is(List.of("after idle")));
            } finally {
                manager.shutdown();
            }
            assertSubscriptionEmpty(client, topic, subscription);
        }
    }

    @Test
    @Timeout(90)
    void testPartialBatchFailureSettlesSuccessfulSourceIndexesAcrossRestart() throws Exception {
        List<String> payloads = List.of("first", "poison", "last");
        try (PulsarClient client = newClient()) {
            for (boolean producerBatching : List.of(false, true)) {
                String topic = uniqueName("partial-batch");
                String subscription = uniqueName("partial-batch-reader");
                String yaml = batchYaml(topic, subscription, """
                        batch-receive-max-messages: 3
                        batch-index-acknowledgment-enabled: true
                        receive-timeout: PT30S
                        failure:
                          retry:
                            calls: 1
                          on-exhausted: FAIL
                        """);
                seedBacklog(client, topic, subscription, payloads, producerBatching);
                ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, PartialBatchReceiver.class);
                try {
                    PartialBatchReceiver receiver = manager.registry().get(PartialBatchReceiver.class);
                    manager.registry().get(MessagingRuntime.class);
                    MessageBatch<String> batch = receiver.awaitBatch(WAIT_TIMEOUT);
                    assertThat(batch, notNullValue());
                    assertThat(batch.messages().stream().map(Message::entity).toList(), is(payloads));
                } finally {
                    shutdownAfterExpectedSourceFailure(manager);
                }
                try (Consumer<String> consumer = stringConsumer(client, topic, subscription)) {
                    assertThat(receiveOne(consumer).getValue(), is("poison"));
                    assertThat("successful indexes are individually acknowledged; producer batching=" + producerBatching,
                               consumer.receive((int) NO_MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), nullValue());
                }
            }
        }
    }

    @Test
    @Timeout(90)
    void testForcedShutdownRedeliversEntireUnsettledNativeBatch() throws Exception {
        String topic = uniqueName("shutdown-batch");
        String subscription = uniqueName("shutdown-batch-reader");
        List<String> payloads = List.of("first", "second", "third");
        String yaml = batchYaml(topic, subscription, """
                batch-receive-max-messages: 3
                receive-timeout: PT30S
                """).replace("messaging:\n", "messaging:\n  shutdown-timeout: PT0.5S\n");
        try (PulsarClient client = newClient()) {
            seedBacklog(client, topic, subscription, payloads);
            ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, BlockingBatchReceiver.class);
            BlockingBatchReceiver receiver = manager.registry().get(BlockingBatchReceiver.class);
            try {
                manager.registry().get(MessagingRuntime.class);
                MessageBatch<String> batch = receiver.awaitBatch(WAIT_TIMEOUT);
                assertThat(batch, notNullValue());
                assertThat(batch.messages().stream().map(Message::entity).toList(), is(payloads));
            } finally {
                try {
                    shutdownAfterExpectedSourceFailure(manager);
                } finally {
                    receiver.release();
                }
            }
            try (Consumer<String> consumer = stringConsumer(client, topic, subscription)) {
                assertThat(receive(consumer, payloads.size()).stream()
                                   .map(org.apache.pulsar.client.api.Message::getValue)
                                   .toList(), is(payloads));
            }
        }
    }

    @Test
    @Timeout(90)
    void testGracefulShutdownRedeliversPartialBatchWaitingForNativeReceive() throws Exception {
        String topic = uniqueName("pending-batch");
        String subscription = uniqueName("pending-batch-reader");
        List<String> payloads = List.of("first", "second");
        String yaml = batchYaml(topic, subscription, """
                batch-receive-max-messages: 3
                receive-timeout: PT30S
                """);
        try (PulsarClient client = newClient()) {
            seedBacklog(client, topic, subscription, payloads);
            ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, BatchReceiver.class);
            BatchReceiver receiver = manager.registry().get(BatchReceiver.class);
            try {
                manager.registry().get(MessagingRuntime.class);
                assertThat("partial receive is still waiting for the third message",
                           receiver.awaitBatch(NO_MESSAGE_TIMEOUT), nullValue());
            } finally {
                manager.shutdown();
            }
            assertThat("shutdown must not dispatch the interrupted partial receive",
                       receiver.awaitBatch(Duration.ZERO), nullValue());
            try (Consumer<String> consumer = stringConsumer(client, topic, subscription)) {
                assertThat(receive(consumer, payloads.size()).stream()
                                   .map(org.apache.pulsar.client.api.Message::getValue)
                                   .toList(), is(payloads));
            }
        }
    }

    @Test
    @Timeout(90)
    void testDropMappingFailureRetainsValidSiblingsInSameNativeBatch() throws Exception {
        String topic = uniqueName("drop-batch");
        String subscription = uniqueName("drop-batch-reader");
        String yaml = batchYaml(topic, subscription, """
                batch-receive-max-messages: 3
                receive-timeout: PT30S
                max-message-bytes: 1
                failure:
                  retry:
                    calls: 1
                  on-exhausted: DROP
                """);
        try (PulsarClient client = newClient()) {
            seedBacklog(client, topic, subscription, List.of("a", "oversized", "b"));
            ServiceRegistryManager manager = PulsarScenarioRegistry.create(yaml, BatchReceiver.class);
            try {
                BatchReceiver receiver = manager.registry().get(BatchReceiver.class);
                manager.registry().get(MessagingRuntime.class);
                assertThat(batchPayloads(receiveBatches(receiver, 2)), is(List.of("a", "b")));
                assertThat(receiver.awaitBatch(NO_MESSAGE_TIMEOUT), nullValue());
            } finally {
                manager.shutdown();
            }
            assertSubscriptionEmpty(client, topic, subscription);
        }
    }

    private static String batchYaml(String topic, String subscription, String options) {
        return """
                messaging:
                  connector:
                    test-pulsar:
                      type: helidon-pulsar
                      service-url: "%s"
                  incoming:
                    %s:
                      connector: test-pulsar
                      topic: "%s"
                      schema: STRING
                      subscription-name: "%s"
                      subscription-initial-position: EARLIEST
                """.formatted(PULSAR.getPulsarBrokerUrl(), PulsarMessagingTypes.BATCH_INCOMING_CHANNEL, topic, subscription)
                + options.indent(6);
    }

    private static void seedBacklog(PulsarClient client,
                                    String topic,
                                    String subscription,
                                    List<String> payloads) throws Exception {
        seedBacklog(client, topic, subscription, payloads, false);
    }

    private static void seedBacklog(PulsarClient client,
                                    String topic,
                                    String subscription,
                                    List<String> payloads,
                                    boolean producerBatching) throws Exception {
        try (Consumer<String> consumer = stringConsumer(client, topic, subscription)) {
            // Retain every source message before the application consumer starts.
            assertThat(consumer.isConnected(), is(true));
        }
        try (Producer<String> producer = client.newProducer(Schema.STRING)
                .topic(topic)
                .enableBatching(producerBatching)
                .batchingMaxPublishDelay(30, TimeUnit.SECONDS)
                .batchingMaxMessages(payloads.size())
                .create()) {
            List<CompletableFuture<MessageId>> sends = new ArrayList<>();
            for (String payload : payloads) {
                sends.add(producer.newMessage().property("sequence", payload).value(payload).sendAsync());
            }
            producer.flush();
            for (CompletableFuture<MessageId> send : sends) {
                send.get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private static List<MessageBatch<String>> receiveBatches(BatchReceiver receiver, int expectedCount) throws Exception {
        List<MessageBatch<String>> batches = new ArrayList<>();
        int received = 0;
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (received < expectedCount) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            MessageBatch<String> batch = receiver.awaitBatch(Duration.ofNanos(remaining));
            if (batch == null) {
                break;
            }
            batches.add(batch);
            received += batch.size();
        }
        assertThat("received message count across native batches", received, is(expectedCount));
        return List.copyOf(batches);
    }

    private static List<String> batchPayloads(List<MessageBatch<String>> batches) {
        return batches.stream().flatMap(batch -> batch.messages().stream()).map(Message::entity).toList();
    }

    private static void assertSubscriptionEmpty(PulsarClient client, String topic, String subscription) throws Exception {
        try (Consumer<String> consumer = stringConsumer(client, topic, subscription)) {
            assertThat("acknowledged source messages are not redelivered after restart",
                       consumer.receive((int) NO_MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), nullValue());
        }
    }

    private static ServiceRegistryManager outgoingRegistryManager(String topic) {
        return PulsarScenarioRegistry.create("""
                messaging:
                  connector:
                    test-pulsar:
                      type: helidon-pulsar
                      service-url: "%2$s"
                  outgoing:
                    %1$s:
                      connector: test-pulsar
                      topic: "%3$s"
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
                    messaging:
                      connector:
                        test-pulsar:
                          type: helidon-pulsar
                          service-url: "%2$s"
                      incoming:
                        %1$s:
                          connector: test-pulsar
                          topic: "%3$s"
                          schema: STRING
                          subscription-name: "%4$s"
                          subscription-initial-position: EARLIEST
                          batch-index-acknowledgment-enabled: true
                          receive-timeout: PT0.1S
                          negative-ack-redelivery-delay: PT0.1S
                          failure:
                            retry:
                              delay: PT0.01S
                              calls: 1
                            on-exhausted: FAIL
                    """.formatted(channel,
                                   PULSAR.getPulsarBrokerUrl(),
                                   topic,
                                   subscription);
        }
        return """
                messaging:
                  connector:
                    test-pulsar:
                      type: helidon-pulsar
                      service-url: "%2$s"
                  incoming:
                    %1$s:
                      connector: test-pulsar
                      topic: "%3$s"
                      schema: STRING
                      subscription-name: "%4$s"
                      subscription-initial-position: EARLIEST
                      receive-timeout: PT0.1S
                """.formatted(channel,
                               PULSAR.getPulsarBrokerUrl(),
                               topic,
                               subscription);
    }

    private static String failedMappingYaml(String sourceTopic,
                                            String deadLetterTopic,
                                            String sourceSubscription) {
        return """
                messaging:
                  connector:
                    test-pulsar:
                      type: helidon-pulsar
                      service-url: "%2$s"
                  incoming:
                    %1$s:
                      connector: test-pulsar
                      topic: "%3$s"
                      schema: STRING
                      max-message-bytes: 1
                      subscription-name: "%4$s"
                      subscription-initial-position: EARLIEST
                      receive-timeout: PT0.1S
                      failure:
                        retry:
                          calls: 1
                        on-exhausted: DEAD_LETTER
                        dead-letter:
                          channel: %5$s
                  outgoing:
                    %6$s:
                      connector: test-pulsar
                      topic: "%8$s"
                      schema: STRING
                """.formatted(PulsarMessagingTypes.FAILED_MAPPING_INCOMING_CHANNEL,
                               PULSAR.getPulsarBrokerUrl(),
                               sourceTopic,
                               sourceSubscription,
                               PulsarMessagingTypes.FAILED_MAPPING_DEAD_LETTER_CHANNEL,
                               PulsarMessagingTypes.FAILED_MAPPING_DEAD_LETTER_CHANNEL,
                               PULSAR.getPulsarBrokerUrl(),
                               deadLetterTopic);
    }

    private static PulsarClient newClient() throws Exception {
        return PulsarClient.builder()
                .serviceUrl(PULSAR.getPulsarBrokerUrl())
                .build();
    }

    private static Consumer<String> stringConsumer(PulsarClient client,
                                                   String topic,
                                                   String subscription) throws Exception {
        return consumer(client, Schema.STRING, topic, subscription);
    }

    private static <T> Consumer<T> consumer(PulsarClient client,
                                            Schema<T> schema,
                                            String topic,
                                            String subscription) throws Exception {
        return client.newConsumer(schema)
                .topic(topic)
                .subscriptionName(subscription)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();
    }

    private static <T> org.apache.pulsar.client.api.Message<T> receiveOne(Consumer<T> consumer) throws Exception {
        org.apache.pulsar.client.api.Message<T> message = consumer.receive((int) WAIT_TIMEOUT.toMillis(),
                                                                           TimeUnit.MILLISECONDS);
        assertThat(message, notNullValue());
        consumer.acknowledge(message);
        return message;
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

    private static void assertFailedMappingDeadLetter(org.apache.pulsar.client.api.Message<String> message,
                                                      String expectedKey,
                                                      String expectedKind,
                                                      String sourceTopic) {
        assertThat(message.getValue(), nullValue());
        assertThat(message.getKey(), is(expectedKey));
        assertThat(message.getProperty("kind"), is(expectedKind));
        assertThat(message.getProperty(DeadLetterMessage.SOURCE_CHANNEL_HEADER),
                   is(PulsarMessagingTypes.FAILED_MAPPING_INCOMING_CHANNEL));
        assertThat(message.getProperties().containsKey(DeadLetterMessage.FAILURE_TYPE_METADATA), is(false));
        assertThat(message.getProperties().containsKey(DeadLetterMessage.FAILURE_MESSAGE_METADATA), is(false));
        assertThat(message.getProperties().containsKey(LEGACY_FAILURE_TYPE_HEADER), is(false));
        assertThat(message.getProperties().containsKey(LEGACY_FAILURE_MESSAGE_HEADER), is(false));
        assertThat(message.getProperty(PulsarConnector.DLQ_ORIGINAL_TOPIC_HEADER),
                   is(canonicalTopic(sourceTopic)));
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

    private record SchemaRoundTrip<T>(PulsarSchemaType type, Schema<T> schema, T payload, T expected) {
        private void assertPayload(T actual) {
            if (expected instanceof byte[] expectedBytes && actual instanceof byte[] actualBytes) {
                assertThat(actualBytes, is(expectedBytes));
            } else if (expected instanceof ByteBuffer expectedBuffer && actual instanceof ByteBuffer actualBuffer) {
                assertThat(bytes(actualBuffer), is(bytes(expectedBuffer)));
            } else {
                assertThat(actual, is(expected));
            }
        }

        private static byte[] bytes(ByteBuffer value) {
            ByteBuffer source = value.duplicate();
            source.position(0);
            byte[] result = new byte[source.remaining()];
            source.get(result);
            return result;
        }
    }
}
