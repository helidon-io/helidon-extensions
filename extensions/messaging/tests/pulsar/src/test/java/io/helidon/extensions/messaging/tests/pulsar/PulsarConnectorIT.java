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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarConnectorConfig;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarConnectorProvider;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarMessage;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarSchemaType;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.AutoIncomingReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.AutoOutgoingSender;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.FailedMappingReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.FailOnceReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.IncomingReceiver;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.JsonOutgoingSender;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.JsonSchemaProvider;
import io.helidon.extensions.messaging.tests.pulsar.PulsarMessagingTypes.OutgoingSender;
import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingRuntime;
import io.helidon.messaging.spi.ConnectorConfig;
import io.helidon.messaging.spi.ConnectorDirection;
import io.helidon.messaging.spi.OutgoingConnector;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.apache.pulsar.client.api.Consumer;
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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
                        helidon:
                          messaging:
                            outgoing:
                              %s:
                                connector: helidon-pulsar
                                service-url: "%s"
                                topic: "%s"
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
        Config config = Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("direction", ConnectorDirection.OUTGOING.name()),
                Map.entry(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, uniqueName("schema-out")),
                Map.entry(ConnectorConfig.CONNECTOR_ATTRIBUTE, PulsarConnectorProvider.CONNECTOR_TYPE),
                Map.entry(PulsarConnectorConfig.SERVICE_URL_PROPERTY, PULSAR.getPulsarBrokerUrl()),
                Map.entry(PulsarConnectorConfig.TOPIC_PROPERTY, topic),
                Map.entry(PulsarConnectorConfig.SCHEMA_PROPERTY, schemaCase.type().name()))));

        try (OutgoingConnector connector = new PulsarConnectorProvider().createOutgoingConnector(config)) {
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
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-pulsar
                        service-url: "%s"
                        topic: "%s"
                        schema: AUTO
                        subscription-name: "%s"
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
                    helidon:
                      messaging:
                        outgoing:
                          %s:
                            connector: helidon-pulsar
                            service-url: "%s"
                            topic: "%s"
                            schema-provider: %s
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
        String yaml = failedMappingYaml(sourceTopic, deadLetterTopic, sourceSubscription);

        try (PulsarClient client = newClient();
                Producer<String> producer = client.newProducer(Schema.STRING).topic(sourceTopic).create();
                Consumer<String> deadLetterConsumer = stringConsumer(client,
                                                                     deadLetterTopic,
                                                                     uniqueName("failed-mapping-dlq-reader"))) {
            ServiceRegistryManager firstManager = PulsarScenarioRegistry.create(yaml, FailedMappingReceiver.class);
            try {
                FailedMappingReceiver receiver = firstManager.registry().get(FailedMappingReceiver.class);
                firstManager.registry().get(MessagingRuntime.class);

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

                org.apache.pulsar.client.api.Message<String> nullDeadLetter = receiveOne(deadLetterConsumer);
                org.apache.pulsar.client.api.Message<String> oversizedDeadLetter = receiveOne(deadLetterConsumer);
                assertFailedMappingDeadLetter(nullDeadLetter, "null-key", "null", sourceTopic);
                assertFailedMappingDeadLetter(oversizedDeadLetter,
                                              "oversized-key",
                                              "oversized",
                                              sourceTopic);
                assertThat("failed mappings must not reach the application handler",
                           receiver.awaitMessage(NO_MESSAGE_TIMEOUT),
                           nullValue());
            } finally {
                firstManager.shutdown();
            }

            ServiceRegistryManager secondManager = PulsarScenarioRegistry.create(yaml, FailedMappingReceiver.class);
            try {
                FailedMappingReceiver receiver = secondManager.registry().get(FailedMappingReceiver.class);
                secondManager.registry().get(MessagingRuntime.class);

                assertThat("settled mapping failures must not be dead-lettered again after restart",
                           deadLetterConsumer.receive((int) NO_MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                           nullValue());
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

    private static String failedMappingYaml(String sourceTopic,
                                            String deadLetterTopic,
                                            String sourceSubscription) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-pulsar
                        service-url: "%s"
                        topic: "%s"
                        schema: STRING
                        max-message-bytes: 1
                        subscription-name: "%s"
                        subscription-initial-position: EARLIEST
                        receive-timeout: PT0.1S
                        failure:
                          retry:
                            max-attempts: 1
                          on-exhausted: DEAD_LETTER
                          dead-letter:
                            channel: %s
                    outgoing:
                      %s:
                        connector: helidon-pulsar
                        service-url: "%s"
                        topic: "%s"
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
        assertThat(message.getProperty(PulsarConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER),
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
                assertArrayEquals(expectedBytes, actualBytes);
            } else if (expected instanceof ByteBuffer expectedBuffer && actual instanceof ByteBuffer actualBuffer) {
                assertArrayEquals(bytes(expectedBuffer), bytes(actualBuffer));
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
