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

package io.helidon.extensions.messaging.tests.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.extensions.messaging.ConnectorSink;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingChannel;
import io.helidon.extensions.messaging.MessagingRuntime;
import io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorConfig;
import io.helidon.extensions.messaging.connectors.kafka.KafkaOutgoingConnector;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.FailOnceIncomingReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.OutgoingSender;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.ReceivedMessage;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class KafkaConnectorIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);
    private static final Duration ADMIN_POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.3.1");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    @Test
    @Timeout(value = 60)
    void testDirectKafkaSinkPublishesPayloadMessageAndBatch() throws Exception {
        String topic = uniqueName("sink");
        createTopic(topic);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector();

        try {
            ConnectorSink sink = connector.createSink(outgoingConnectorConfig(topic));
            sink.send("sink payload");
            sink.send(Message.builder("sink message")
                              .header("trace-id", "Příliš žluťoučký")
                              .build());
            sink.sendBatch(List.of(Message.builder("sink batch first")
                                           .header("trace-id", "sink-batch-1")
                                           .build(),
                                   Message.builder("sink batch second")
                                           .header("trace-id", "sink-batch-2")
                                           .build()));

            assertRecords(awaitRecords(topic, 4),
                          List.of(ExpectedRecord.create("sink payload"),
                                  ExpectedRecord.create("sink message", "Příliš žluťoučký"),
                                  ExpectedRecord.create("sink batch first", "sink-batch-1"),
                                  ExpectedRecord.create("sink batch second", "sink-batch-2")));
        } finally {
            connector.close();
        }
    }

    @Test
    @Timeout(value = 60)
    void testImperativeChannelPublishesPayloadMessageAndBatch() throws Exception {
        String topic = uniqueName("channel");
        createTopic(topic);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector();

        try {
            ConnectorSink sink = connector.createSink(outgoingConnectorConfig(topic));
            MessagingChannel<String> channel = MessagingChannel.<String>builder()
                    .payloadType(String.class)
                    .addOutgoingConnector(sink)
                    .build();

            channel.emit("channel payload");
            channel.emit(Message.builder("channel message")
                                 .header("trace-id", "channel-single")
                                 .build());
            channel.emitBatch(List.of(Message.builder("channel batch first")
                                              .header("trace-id", "channel-batch-1")
                                              .build(),
                                      Message.builder("channel batch second")
                                              .header("trace-id", "channel-batch-2")
                                              .build()));

            assertRecords(awaitRecords(topic, 4),
                          List.of(ExpectedRecord.create("channel payload"),
                                  ExpectedRecord.create("channel message", "channel-single"),
                                  ExpectedRecord.create("channel batch first", "channel-batch-1"),
                                  ExpectedRecord.create("channel batch second", "channel-batch-2")));
        } finally {
            connector.close();
        }
    }

    @Test
    @Timeout(value = 60)
    void testConfiguredRuntimePublishesMessageAndBatch() throws Exception {
        String topic = uniqueName("runtime");
        createTopic(topic);
        ServiceRegistryManager manager = outgoingRegistryManager(topic);

        try {
            MessagingRuntime runtime = manager.registry().get(MessagingRuntime.class);
            runtime.emit(KafkaMessagingTypes.OUTGOING_CHANNEL,
                         Message.builder("runtime message")
                                 .header("trace-id", "runtime-single")
                                 .build());
            runtime.emitBatch(KafkaMessagingTypes.OUTGOING_CHANNEL,
                              List.of(Message.builder("runtime batch first")
                                              .header("trace-id", "runtime-batch-1")
                                              .build(),
                                      Message.builder("runtime batch second")
                                              .header("trace-id", "runtime-batch-2")
                                              .build()));

            assertRecords(awaitRecords(topic, 3),
                          List.of(ExpectedRecord.create("runtime message", "runtime-single"),
                                  ExpectedRecord.create("runtime batch first", "runtime-batch-1"),
                                  ExpectedRecord.create("runtime batch second", "runtime-batch-2")));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testConfiguredEmitterPublishesPayloadMessageAndBatch() throws Exception {
        String topic = uniqueName("emitter");
        createTopic(topic);
        ServiceRegistryManager manager = outgoingRegistryManager(topic);

        try {
            OutgoingSender sender = manager.registry().get(OutgoingSender.class);
            sender.send("emitter payload");
            sender.send(Message.builder("emitter message")
                                .header("trace-id", "emitter-single")
                                .build());
            sender.sendBatch(List.of(Message.builder("emitter batch first")
                                             .header("trace-id", "emitter-batch-1")
                                             .build(),
                                     Message.builder("emitter batch second")
                                             .header("trace-id", "emitter-batch-2")
                                             .build()));

            assertRecords(awaitRecords(topic, 4),
                          List.of(ExpectedRecord.create("emitter payload"),
                                  ExpectedRecord.create("emitter message", "emitter-single"),
                                  ExpectedRecord.create("emitter batch first", "emitter-batch-1"),
                                  ExpectedRecord.create("emitter batch second", "emitter-batch-2")));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testConfiguredIncomingConnectorDispatchesBatchToEveryReceiverVariantAndCommits() throws Exception {
        String topic = uniqueName("incoming");
        String group = uniqueName("group");
        createTopic(topic);
        sendRecords(topic,
                    List.of(Message.builder("incoming first")
                                    .header("trace-id", "Příliš žluťoučký")
                                    .build(),
                            Message.builder("incoming second")
                                    .header("trace-id", "incoming-2")
                                    .build()));
        ServiceRegistryManager manager = incomingRegistryManager(topic, group);

        try {
            ServiceRegistry registry = manager.registry();
            IncomingReceiver receiver = registry.get(IncomingReceiver.class);
            registry.get(MessagingRuntime.class);

            String firstPayload = receiver.awaitPayload(WAIT_TIMEOUT);
            String secondPayload = receiver.awaitPayload(WAIT_TIMEOUT);
            Message<String> firstMessage = receiver.awaitMessage(WAIT_TIMEOUT);
            Message<String> secondMessage = receiver.awaitMessage(WAIT_TIMEOUT);
            ReceivedMessage firstAnnotated = receiver.awaitAnnotated(WAIT_TIMEOUT);
            ReceivedMessage secondAnnotated = receiver.awaitAnnotated(WAIT_TIMEOUT);
            List<Message<String>> batch = receiver.awaitBatch(WAIT_TIMEOUT);

            assertThat("first payload", firstPayload, notNullValue());
            assertThat("second payload", secondPayload, notNullValue());
            assertThat("first message", firstMessage, notNullValue());
            assertThat("second message", secondMessage, notNullValue());
            assertThat("first annotated message", firstAnnotated, notNullValue());
            assertThat("second annotated message", secondAnnotated, notNullValue());
            assertThat("batch", batch, notNullValue());

            assertThat(List.of(firstPayload, secondPayload), is(List.of("incoming first", "incoming second")));
            assertMessages(List.of(firstMessage, secondMessage), List.of("incoming first", "incoming second"));
            assertThat(firstMessage.header("trace-id").orElseThrow(), is("Příliš žluťoučký"));
            assertThat(secondMessage.header("trace-id").orElseThrow(), is("incoming-2"));
            List<ReceivedMessage> annotated = List.of(firstAnnotated, secondAnnotated);
            assertThat(annotated.stream().map(ReceivedMessage::entity).toList(),
                       is(List.of("incoming first", "incoming second")));
            assertThat(annotated.stream().map(ReceivedMessage::traceId).toList(),
                       is(List.of("Příliš žluťoučký", "incoming-2")));
            assertMessages(annotated.stream().map(ReceivedMessage::message).toList(),
                           List.of("incoming first", "incoming second"));
            assertMessages(batch, List.of("incoming first", "incoming second"));
            assertThat(batch.get(0).header("trace-id").orElseThrow(), is("Příliš žluťoučký"));
            assertThat(batch.get(1).header("trace-id").orElseThrow(), is("incoming-2"));
            awaitCommittedOffset(group, topic, 2L);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testConfiguredIncomingConnectorRedeliversAfterHandlerFailureAndCommitsAfterSuccess() throws Exception {
        String topic = uniqueName("redelivery");
        String group = uniqueName("group");
        createTopic(topic);
        sendRecords(topic,
                    List.of(Message.builder("redelivered message")
                                    .header("trace-id", "redelivery-trace")
                                    .build()));
        ServiceRegistryManager manager = redeliveryRegistryManager(topic, group);
        ServiceRegistry registry = manager.registry();
        FailOnceIncomingReceiver receiver = registry.get(FailOnceIncomingReceiver.class);

        try {
            registry.get(MessagingRuntime.class);

            Message<String> firstDelivery = receiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat("first delivery", firstDelivery, notNullValue());
            assertThat("second attempt started", receiver.awaitSecondAttempt(WAIT_TIMEOUT), is(true));
            Message<String> secondDelivery = receiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat("second delivery", secondDelivery, notNullValue());

            assertMessages(List.of(firstDelivery, secondDelivery),
                           List.of("redelivered message", "redelivered message"));
            assertThat(firstDelivery.header("trace-id").orElseThrow(), is("redelivery-trace"));
            assertThat(secondDelivery.header("trace-id").orElseThrow(), is("redelivery-trace"));
            assertThat("attempt count while second attempt is blocked", receiver.attemptCount(), is(2));
            assertNoCommittedOffset(group, topic);

            receiver.allowSecondAttemptToSucceed();

            awaitCommittedOffset(group, topic, 1L);
            assertThat("attempt count after successful redelivery", receiver.attemptCount(), is(2));
        } finally {
            receiver.allowSecondAttemptToSucceed();
            manager.shutdown();
        }
    }

    private static KafkaConnectorConfig outgoingConnectorConfig(String topic) {
        String yaml = """
                direction: OUTGOING
                channel: %s
                connector: kafka
                bootstrap.servers: "%s"
                topic: "%s"
                """.formatted(KafkaMessagingTypes.OUTGOING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic);
        return KafkaConnectorConfig.create(Config.just(yaml, MediaTypes.APPLICATION_YAML));
    }

    private static ServiceRegistryManager outgoingRegistryManager(String topic) {
        return registryManager("""
                helidon:
                  messaging:
                    outgoing:
                      %s:
                        connector: kafka
                        bootstrap.servers: "%s"
                        topic: "%s"
                """.formatted(KafkaMessagingTypes.OUTGOING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic));
    }

    private static ServiceRegistryManager incomingRegistryManager(String topic, String group) {
        return registryManager("""
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: kafka
                        bootstrap.servers: "%s"
                        topic: "%s"
                        group.id: "%s"
                        auto.offset.reset: earliest
                        poll.timeout: PT0.1S
                        properties:
                          max.poll.records: "2"
                """.formatted(KafkaMessagingTypes.INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group));
    }

    private static ServiceRegistryManager redeliveryRegistryManager(String topic, String group) {
        return registryManager("""
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: kafka
                        bootstrap.servers: "%s"
                        topic: "%s"
                        group.id: "%s"
                        auto.offset.reset: earliest
                        poll.timeout: PT0.1S
                        redelivery.delay: PT0.05S
                        properties:
                          max.poll.records: "1"
                """.formatted(KafkaMessagingTypes.REDELIVERY_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group));
    }

    private static ServiceRegistryManager registryManager(String yaml) {
        Config config = Config.just(yaml, MediaTypes.APPLICATION_YAML);
        ServiceRegistryConfig registryConfig = ServiceRegistryConfig.builder()
                .putContractInstance(Config.class, config)
                .build();
        return ServiceRegistryManager.create(registryConfig);
    }

    private static void createTopic(String topic) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                               KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all()
                    .get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private static void sendRecords(String topic, List<Message<String>> messages) throws Exception {
        Map<String, Object> properties = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                                KAFKA.getBootstrapServers());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties,
                                                                          new StringSerializer(),
                                                                          new StringSerializer())) {
            List<Future<RecordMetadata>> results = new ArrayList<>(messages.size());
            for (Message<String> message : messages) {
                ProducerRecord<String, String> record = new ProducerRecord<>(topic, message.entity());
                message.headers().forEach((name, value) -> record.headers()
                        .add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8))));
                results.add(producer.send(record));
            }
            for (Future<RecordMetadata> result : results) {
                result.get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    private static List<ConsumerRecord<String, String>> awaitRecords(String topic, int expectedCount) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, uniqueName("reader"));
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        List<ConsumerRecord<String, String>> result = new ArrayList<>(expectedCount);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties,
                                                                          new StringDeserializer(),
                                                                          new StringDeserializer())) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
            while (result.size() < expectedCount && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, String> record : records) {
                    result.add(record);
                }
            }
        }
        if (result.size() != expectedCount) {
            throw new AssertionError("Expected " + expectedCount + " Kafka records on topic " + topic
                                             + " but received " + result.size());
        }
        return List.copyOf(result);
    }

    private static void assertRecords(List<ConsumerRecord<String, String>> records,
                                      List<ExpectedRecord> expectedRecords) {
        assertThat(records.size(), is(expectedRecords.size()));
        for (int i = 0; i < expectedRecords.size(); i++) {
            ConsumerRecord<String, String> record = records.get(i);
            ExpectedRecord expected = expectedRecords.get(i);
            assertThat(record.partition(), is(0));
            assertThat(record.offset(), is((long) i));
            assertThat(record.value(), is(expected.entity()));
            assertThat(header(record, "trace-id"), is(expected.traceId()));
        }
    }

    private static void assertMessages(List<Message<String>> messages, List<String> expectedEntities) {
        assertThat(messages.stream().map(Message::entity).toList(), is(expectedEntities));
    }

    private static Optional<String> header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null
                ? Optional.empty()
                : Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }

    private static void awaitCommittedOffset(String group, String topic, long expectedOffset) throws Exception {
        TopicPartition partition = new TopicPartition(topic, 0);
        OffsetAndMetadata committed = null;
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                               KAFKA.getBootstrapServers()))) {
            while (System.nanoTime() < deadline) {
                try {
                    committed = admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(ADMIN_POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            .get(partition);
                    if (committed != null && committed.offset() == expectedOffset) {
                        return;
                    }
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof GroupIdNotFoundException)) {
                        throw e;
                    }
                } catch (TimeoutException ignored) {
                    // Retry until the overall wait timeout expires.
                }
                Thread.sleep(50);
            }
        }
        assertThat("committed offset", committed, notNullValue());
        assertThat("committed offset", committed.offset(), is(expectedOffset));
    }

    private static void assertNoCommittedOffset(String group, String topic) throws Exception {
        TopicPartition partition = new TopicPartition(topic, 0);
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                               KAFKA.getBootstrapServers()))) {
            try {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata()
                        .get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .get(partition);
                assertThat("offset committed before handler success", committed, nullValue());
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof GroupIdNotFoundException)) {
                    throw e;
                }
            }
        }
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record ExpectedRecord(String entity, Optional<String> traceId) {
        private static ExpectedRecord create(String entity) {
            return new ExpectedRecord(entity, Optional.empty());
        }

        private static ExpectedRecord create(String entity, String traceId) {
            return new ExpectedRecord(entity, Optional.of(traceId));
        }
    }
}
