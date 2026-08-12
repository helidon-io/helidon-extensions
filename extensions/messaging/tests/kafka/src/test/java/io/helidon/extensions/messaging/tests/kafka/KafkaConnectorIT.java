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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.extensions.messaging.DeadLetterMessage;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.MessagingChannel;
import io.helidon.extensions.messaging.MessagingGraph;
import io.helidon.extensions.messaging.MessagingRuntime;
import io.helidon.extensions.messaging.OutgoingConnector;
import io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorConfig;
import io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider;
import io.helidon.extensions.messaging.connectors.kafka.KafkaMessage;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.AlwaysFailIncomingReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.AlwaysFailIncomingReceiver.FailedBatch;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.DropReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.FailingForwardingReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.FailOnceIncomingReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.ForwardingReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingAnnotatedReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingBatchReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingMessageReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingPayloadReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.IncomingReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.KafkaMetadataBatchReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.KafkaMetadataMessageReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.KafkaMetadataReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.NumericReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.NumericSender;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.OutgoingSender;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.PartitionRecord;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.PartitionRetryReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.ReceivedMessage;
import io.helidon.extensions.messaging.tests.kafka.KafkaMessagingTypes.RestartReceiver;
import io.helidon.extensions.messaging.tests.kafka.KafkaTestSerializers.BlockingStringSerializer;
import io.helidon.extensions.messaging.tests.kafka.KafkaTestSerializers.FailingStringSerializer;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
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
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_LEADER_EPOCH_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_OFFSET_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_PARTITION_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
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
        KafkaConnectorProvider provider = new KafkaConnectorProvider();
        OutgoingConnector connector = provider.createOutgoingConnector(outgoingConnectorConfig(topic));

        try {
            connector.start();
            connector.send("sink payload");
            connector.send(Message.builder("sink message")
                                  .header("trace-id", "Příliš žluťoučký")
                                  .build());
            connector.sendBatch(MessageBatch.create(List.of(Message.builder("sink batch first")
                                                                    .header("trace-id", "sink-batch-1")
                                                                    .build(),
                                                            Message.builder("sink batch second")
                                                                    .header("trace-id", "sink-batch-2")
                                                                    .build())));

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
    void testConfiguredEmitterPublishesKafkaMessageKeyAndNativeHeaders() throws Exception {
        String topic = uniqueName("kafka-message-out");
        createTopic(topic);
        ServiceRegistryManager manager = outgoingRegistryManager(topic);

        try {
            OutgoingSender sender = manager.registry().get(OutgoingSender.class);
            KafkaMessage<String, String> message = KafkaMessage.builder("message-key", "kafka message")
                    .rawHeader("duplicate", "first".getBytes(StandardCharsets.UTF_8))
                    .rawHeader("duplicate", "second".getBytes(StandardCharsets.UTF_8))
                    .rawHeader("binary", new byte[] {0, (byte) 0xFF})
                    .rawHeader("null-value", null)
                    .build();
            MessageBatch<String> batch = MessageBatch.create(List.of(
                    KafkaMessage.create("batch-key-1", "kafka batch first"),
                    KafkaMessage.create("batch-key-2", "kafka batch second")));

            sender.send(message);
            sender.sendBatch(batch);

            List<ConsumerRecord<String, String>> records = awaitRecords(topic, 3);
            assertThat(records.stream().map(ConsumerRecord::key).toList(),
                       is(List.of("message-key", "batch-key-1", "batch-key-2")));
            assertThat(records.stream().map(ConsumerRecord::value).toList(),
                       is(List.of("kafka message", "kafka batch first", "kafka batch second")));
            List<Header> headers = new ArrayList<>();
            records.getFirst().headers().forEach(headers::add);
            assertThat(headers.stream().map(Header::key).toList(),
                       is(List.of("duplicate", "duplicate", "binary", "null-value")));
            assertThat(Arrays.equals(headers.get(0).value(), "first".getBytes(StandardCharsets.UTF_8)), is(true));
            assertThat(Arrays.equals(headers.get(1).value(), "second".getBytes(StandardCharsets.UTF_8)), is(true));
            assertThat(Arrays.equals(headers.get(2).value(), new byte[] {0, (byte) 0xFF}), is(true));
            assertThat(headers.get(3).value(), nullValue());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testImperativeChannelPublishesPayloadMessageAndBatch() throws Exception {
        String topic = uniqueName("channel");
        createTopic(topic);
        KafkaConnectorProvider provider = new KafkaConnectorProvider();
        OutgoingConnector connector = provider.createOutgoingConnector(outgoingConnectorConfig(topic));
        MessagingGraph.Builder builder = MessagingGraph.builder();
        MessagingChannel<String> channel = builder.channel("kafka-output", String.class);
        builder.outgoingConnector(channel, connector);

        try (MessagingGraph graph = builder.build()) {
            graph.start();
            graph.emitter(channel).emit("channel payload");
            graph.emitter(channel).emitMessage(Message.builder("channel message")
                                                       .header("trace-id", "channel-single")
                                                       .build());
            graph.emitter(channel).emitBatch(MessageBatch.create(List.of(Message.builder("channel batch first")
                                                                                 .header("trace-id", "channel-batch-1")
                                                                                 .build(),
                                                                         Message.builder("channel batch second")
                                                                                 .header("trace-id", "channel-batch-2")
                                                                                 .build())));

            assertRecords(awaitRecords(topic, 4),
                          List.of(ExpectedRecord.create("channel payload"),
                                  ExpectedRecord.create("channel message", "channel-single"),
                                  ExpectedRecord.create("channel batch first", "channel-batch-1"),
                                  ExpectedRecord.create("channel batch second", "channel-batch-2")));
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
                              MessageBatch.create(List.of(Message.builder("runtime batch first")
                                                                   .header("trace-id", "runtime-batch-1")
                                                                   .build(),
                                                          Message.builder("runtime batch second")
                                                                  .header("trace-id", "runtime-batch-2")
                                                                  .build())));

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
            sender.sendBatch(MessageBatch.create(List.of(Message.builder("emitter batch first")
                                                                  .header("trace-id", "emitter-batch-1")
                                                                  .build(),
                                                          Message.builder("emitter batch second")
                                                                  .header("trace-id", "emitter-batch-2")
                                                                  .build())));

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
    void testConfiguredProcessorCommitsOnlyAfterForwardedKafkaSendCompletes() throws Exception {
        String incomingTopic = uniqueName("forward-in");
        String outgoingTopic = uniqueName("forward-out");
        String group = uniqueName("group");
        createTopic(incomingTopic);
        createTopic(outgoingTopic);
        sendRecords(incomingTopic, List.of(Message.create("source message")));
        BlockingStringSerializer.reset();
        ServiceRegistryManager manager = forwardingRegistryManager(incomingTopic, outgoingTopic, group);

        try {
            ServiceRegistry registry = manager.registry();
            ForwardingReceiver receiver = registry.get(ForwardingReceiver.class);
            registry.get(MessagingRuntime.class);

            assertThat("downstream serializer entered",
                       BlockingStringSerializer.awaitEntered(WAIT_TIMEOUT),
                       is(true));
            Message<String> delivery = receiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat("incoming delivery", delivery, notNullValue());
            assertThat(delivery.entity(), is("source message"));
            assertNoCommittedOffset(group, incomingTopic);
            assertNoRecords(outgoingTopic, Duration.ofMillis(500));

            BlockingStringSerializer.release();

            List<ConsumerRecord<String, String>> forwarded = awaitRecords(outgoingTopic, 1);
            assertRecords(forwarded, List.of(ExpectedRecord.create("forwarded: source message")));
            assertThat(header(forwarded.getFirst(), "processor"),
                       is(Optional.of("kafka-forwarder")));
            awaitCommittedOffset(group, incomingTopic, 1L);
        } finally {
            BlockingStringSerializer.release();
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testConfiguredProcessorSerializationFailureLeavesInputUncommitted() throws Exception {
        String incomingTopic = uniqueName("failing-forward-in");
        String outgoingTopic = uniqueName("failing-forward-out");
        String group = uniqueName("group");
        createTopic(incomingTopic);
        createTopic(outgoingTopic);
        sendRecords(incomingTopic, List.of(Message.create("source message")));
        ServiceRegistryManager manager = failingForwardingRegistryManager(incomingTopic, outgoingTopic, group);

        try {
            ServiceRegistry registry = manager.registry();
            FailingForwardingReceiver receiver = registry.get(FailingForwardingReceiver.class);
            registry.get(MessagingRuntime.class);

            Message<String> first = receiver.awaitDelivery(WAIT_TIMEOUT);
            Message<String> second = receiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat("first delivery", first, notNullValue());
            assertThat("second delivery", second, notNullValue());
            assertThat(first.entity(), is("source message"));
            assertThat(second.entity(), is("source message"));
            assertThat(receiver.attemptCount(), is(2));
            awaitNoActiveConsumerGroupMembers(group);
            assertNoCommittedOffset(group, incomingTopic);
            assertNoRecords(outgoingTopic, Duration.ofMillis(500));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testUncommittedRecordIsRedeliveredAfterRegistryRestartWithSameGroup() throws Exception {
        String topic = uniqueName("restart-redelivery");
        String group = uniqueName("group");
        createTopic(topic);
        sendRecords(topic, List.of(Message.create("restart message")));
        RestartReceiver.failDeliveries();
        ServiceRegistryManager firstManager = restartRegistryManager(topic, group);

        try {
            ServiceRegistry firstRegistry = firstManager.registry();
            RestartReceiver firstReceiver = firstRegistry.get(RestartReceiver.class);
            firstRegistry.get(MessagingRuntime.class);

            KafkaMessage<String, String> firstDelivery = firstReceiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat("delivery before restart", firstDelivery, notNullValue());
            assertThat(firstDelivery.entity(), is("restart message"));
            assertThat(firstDelivery.topic().orElseThrow(), is(topic));
            assertThat(firstDelivery.partition().orElseThrow(), is(0));
            assertThat(firstDelivery.offset().orElseThrow(), is(0L));
            awaitNoActiveConsumerGroupMembers(group);
            assertNoCommittedOffset(group, topic);
        } finally {
            firstManager.shutdown();
            RestartReceiver.succeedDeliveries();
        }

        ServiceRegistryManager secondManager = restartRegistryManager(topic, group);
        try {
            ServiceRegistry secondRegistry = secondManager.registry();
            RestartReceiver secondReceiver = secondRegistry.get(RestartReceiver.class);
            secondRegistry.get(MessagingRuntime.class);

            KafkaMessage<String, String> secondDelivery = secondReceiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat("redelivery after restart", secondDelivery, notNullValue());
            assertThat(secondDelivery.entity(), is("restart message"));
            assertThat(secondDelivery.topic().orElseThrow(), is(topic));
            assertThat(secondDelivery.partition().orElseThrow(), is(0));
            assertThat(secondDelivery.offset().orElseThrow(), is(0L));
            awaitCommittedOffset(group, topic, 1L);
        } finally {
            RestartReceiver.succeedDeliveries();
            secondManager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testTerminalDropCommitsPoisonAndContinuesWithLaterRecord() throws Exception {
        String topic = uniqueName("drop");
        String group = uniqueName("group");
        createTopic(topic);
        sendRecords(topic, List.of(Message.create("poison"), Message.create("after poison")));
        ServiceRegistryManager manager = dropRegistryManager(topic, group);
        ServiceRegistry registry = manager.registry();
        DropReceiver receiver = registry.get(DropReceiver.class);

        try {
            registry.get(MessagingRuntime.class);

            assertThat("terminal poison attempt started",
                       receiver.awaitFinalPoisonAttempt(WAIT_TIMEOUT),
                       is(true));
            assertThat(receiver.poisonAttempts(), is(2));
            assertNoCommittedOffset(group, topic);

            receiver.allowPoisonFailure();

            assertThat("later record delivery started",
                       receiver.awaitSuccessfulDelivery(WAIT_TIMEOUT),
                       is(true));
            assertThat(receiver.successfulEntities(), is(List.of("after poison")));
            assertNoCommittedOffset(group, topic);

            receiver.allowSuccessfulDelivery();

            awaitCommittedOffset(group, topic, 2L);
            assertThat(receiver.poisonAttempts(), is(2));
            assertThat(receiver.successfulEntities(), is(List.of("after poison")));
        } finally {
            receiver.allowPoisonFailure();
            receiver.allowSuccessfulDelivery();
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testActualPollsAreRetriedAsExactBatchesAcrossTwoPartitionTopic() throws Exception {
        String topic = uniqueName("partition-retry");
        String group = uniqueName("group");
        createTopic(topic, 2);
        sendRecords(List.of(
                new ProducerRecord<>(topic, 0, "p0-key-0", "p0-value-0"),
                new ProducerRecord<>(topic, 0, "p0-key-1", "p0-value-1"),
                new ProducerRecord<>(topic, 1, "p1-key-0", "p1-value-0"),
                new ProducerRecord<>(topic, 1, "p1-key-1", "p1-value-1")));
        ServiceRegistryManager manager = partitionRetryRegistryManager(topic, group);

        try {
            ServiceRegistry registry = manager.registry();
            PartitionRetryReceiver receiver = registry.get(PartitionRetryReceiver.class);
            registry.get(MessagingRuntime.class);

            Set<PartitionRecord> successful = receiver.awaitSuccessfulRecords(4, WAIT_TIMEOUT);
            assertThat(successful,
                       is(Set.of(new PartitionRecord(0, 0L, "p0-value-0"),
                                 new PartitionRecord(0, 1L, "p0-value-1"),
                                 new PartitionRecord(1, 0L, "p1-value-0"),
                                 new PartitionRecord(1, 1L, "p1-value-1"))));
            awaitCommittedOffset(group, topic, 0, 2L);
            awaitCommittedOffset(group, topic, 1, 2L);

            List<List<PartitionRecord>> deliveries = receiver.deliveries();
            assertThat("delivery attempt count is paired", deliveries.size() % 2, is(0));
            for (int i = 0; i < deliveries.size(); i += 2) {
                assertThat("retained poll batch on retry", deliveries.get(i + 1), is(deliveries.get(i)));
            }
            List<String> batchIds = receiver.batchIds();
            assertThat("batch identity count", batchIds.size(), is(deliveries.size()));
            for (int i = 0; i < batchIds.size(); i += 2) {
                assertThat("retained poll batch identity on retry", batchIds.get(i + 1), is(batchIds.get(i)));
            }
            assertThat("every retained poll succeeds on its second attempt",
                       receiver.attemptCounts().values().stream().allMatch(attempts -> attempts == 2),
                       is(true));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testConfiguredLongKeyIntegerValueRoundTrip() throws Exception {
        String topic = uniqueName("numeric");
        String group = uniqueName("group");
        createTopic(topic);
        ServiceRegistryManager manager = numericRegistryManager(topic, group);

        try {
            ServiceRegistry registry = manager.registry();
            NumericSender sender = registry.get(NumericSender.class);
            NumericReceiver receiver = registry.get(NumericReceiver.class);
            registry.get(MessagingRuntime.class);

            sender.send(KafkaMessage.create(42L, 1234));

            KafkaMessage<Long, Integer> received = receiver.awaitMessage(WAIT_TIMEOUT);
            assertThat("numeric Kafka message", received, notNullValue());
            assertThat(received.key().orElseThrow(), is(42L));
            assertThat(received.entity(), is(1234));
            assertThat(received.topic().orElseThrow(), is(topic));
            assertThat(received.partition().orElseThrow(), is(0));
            assertThat(received.offset().orElseThrow(), is(0L));
            awaitCommittedOffset(group, topic, 1L);
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
            MessageBatch<String> batch = receiver.awaitBatch(WAIT_TIMEOUT);

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
            assertThat("batch id", batch.id().isBlank(), is(false));
            assertMessages(batch.messages(), List.of("incoming first", "incoming second"));
            assertThat(batch.get(0).header("trace-id").orElseThrow(), is("Příliš žluťoučký"));
            assertThat(batch.get(1).header("trace-id").orElseThrow(), is("incoming-2"));
            awaitCommittedOffset(group, topic, 2L);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testConfiguredIncomingConnectorPreservesKafkaMessageMetadataForSingleAndBatchHandlers() throws Exception {
        String topic = uniqueName("kafka-message-in");
        String group = uniqueName("group");
        long firstTimestamp = 1_753_700_000_001L;
        long secondTimestamp = firstTimestamp + 1;
        createTopic(topic);
        sendRecords(List.of(
                new ProducerRecord<>(topic,
                                     0,
                                     firstTimestamp,
                                     "incoming-key-1",
                                     "metadata first",
                                     new RecordHeaders()
                                             .add("duplicate", "first".getBytes(StandardCharsets.UTF_8))
                                             .add("duplicate", "second".getBytes(StandardCharsets.UTF_8))
                                             .add("binary", new byte[] {0, (byte) 0xFF})
                                             .add("null-value", null)),
                new ProducerRecord<>(topic,
                                     0,
                                     secondTimestamp,
                                     "incoming-key-2",
                                     "metadata second",
                                     new RecordHeaders().add("trace-id",
                                                             "metadata-trace".getBytes(StandardCharsets.UTF_8)))));
        ServiceRegistryManager manager = metadataRegistryManager(topic, group);

        try {
            ServiceRegistry registry = manager.registry();
            KafkaMetadataReceiver receiver = registry.get(KafkaMetadataReceiver.class);
            registry.get(MessagingRuntime.class);

            KafkaMessage<String, String> first = receiver.awaitMessage(WAIT_TIMEOUT);
            KafkaMessage<String, String> second = receiver.awaitMessage(WAIT_TIMEOUT);
            List<KafkaMessage<String, String>> batch = receiver.awaitBatchMessages(2, WAIT_TIMEOUT);

            assertThat("first Kafka message", first, notNullValue());
            assertThat("second Kafka message", second, notNullValue());
            assertThat("Kafka message batch size", batch.size(), is(2));
            assertThat(first.entity(), is("metadata first"));
            assertThat(second.entity(), is("metadata second"));
            assertThat(batch.get(0), sameInstance(first));
            assertThat(batch.get(1), sameInstance(second));
            assertThat(first.key().orElseThrow(), is("incoming-key-1"));
            assertThat(second.key().orElseThrow(), is("incoming-key-2"));
            assertThat(first.topic().orElseThrow(), is(topic));
            assertThat(first.partition().orElseThrow(), is(0));
            assertThat(first.offset().orElseThrow(), is(0L));
            assertThat(second.offset().orElseThrow(), is(1L));
            assertThat(first.timestamp().orElseThrow(), is(firstTimestamp));
            assertThat(second.timestamp().orElseThrow(), is(secondTimestamp));
            assertThat(first.timestampType().isPresent(), is(true));
            assertThat(first.header("duplicate").orElseThrow(), is("second"));
            assertThat(first.header("null-value").isEmpty(), is(true));

            List<KafkaMessage.Header> headers = first.kafkaHeaders();
            assertThat(headers.stream().map(KafkaMessage.Header::name).toList(),
                       is(List.of("duplicate", "duplicate", "binary", "null-value")));
            assertThat(Arrays.equals(headers.get(0).value().orElseThrow(),
                                     "first".getBytes(StandardCharsets.UTF_8)),
                       is(true));
            assertThat(Arrays.equals(headers.get(1).value().orElseThrow(),
                                     "second".getBytes(StandardCharsets.UTF_8)),
                       is(true));
            assertThat(Arrays.equals(headers.get(2).value().orElseThrow(), new byte[] {0, (byte) 0xFF}), is(true));
            assertThat(headers.get(3).value().isEmpty(), is(true));
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

    @Test
    @Timeout(value = 60)
    void testLongHandlerRetryKeepsConsumerGroupStableAndCommitsAfterSettlement() throws Exception {
        Duration maxPollInterval = Duration.ofSeconds(1);
        Duration retryDelay = Duration.ofMillis(1500);
        Duration stabilityWindow = Duration.ofSeconds(3);
        String topic = uniqueName("owner-loop");
        String group = uniqueName("group");
        createTopic(topic);
        sendRecords(topic, List.of(Message.create("retried message")));
        ServiceRegistryManager manager = ownerLoopRegistryManager(topic, group, retryDelay, maxPollInterval);
        ServiceRegistry registry = manager.registry();
        FailOnceIncomingReceiver receiver = registry.get(FailOnceIncomingReceiver.class);

        try {
            registry.get(MessagingRuntime.class);

            Message<String> firstDelivery = receiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat("first delivery", firstDelivery, notNullValue());
            assertThat(firstDelivery.entity(), is("retried message"));
            assertThat("second attempt started", receiver.awaitSecondAttempt(WAIT_TIMEOUT), is(true));
            Message<String> secondDelivery = receiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat("second delivery", secondDelivery, notNullValue());
            assertThat(secondDelivery.entity(), is("retried message"));
            String memberId = awaitStableConsumerGroupMember(group);

            sendRecords(topic, List.of(Message.create("continued message")));
            assertConsumerGroupRemainsStable(group, memberId, stabilityWindow);

            assertThat("follow-up delivery while retry is unsettled",
                       receiver.awaitDelivery(POLL_TIMEOUT),
                       nullValue());
            assertNoCommittedOffset(group, topic);

            receiver.allowSecondAttemptToSucceed();

            Message<String> continuedDelivery = receiver.awaitDelivery(WAIT_TIMEOUT);
            assertThat("continued delivery", continuedDelivery, notNullValue());
            assertThat(continuedDelivery.entity(), is("continued message"));
            awaitCommittedOffset(group, topic, 2L);
            assertThat("consumer group member after continued delivery",
                       awaitStableConsumerGroupMember(group),
                       is(memberId));
        } finally {
            receiver.allowSecondAttemptToSucceed();
            manager.shutdown();
        }
    }

    @Test
    @Timeout(value = 60)
    void testPermanentHandlerFailurePublishesPollToDeadLetterBeforeCommittingOriginalOffsets() throws Exception {
        String topic = uniqueName("dead-letter-source");
        String deadLetterTopic = uniqueName("dead-letter");
        String group = uniqueName("group");
        long firstTimestamp = 1_700_000_000_001L;
        long secondTimestamp = 1_700_000_000_002L;
        createTopic(topic);
        createTopic(deadLetterTopic);
        sendRecords(List.of(producerRecord(topic,
                                           "failed-key-1",
                                           "failed first",
                                           "dead-letter-trace-1",
                                           firstTimestamp),
                            producerRecord(topic,
                                           "failed-key-2",
                                           "failed second",
                                           "dead-letter-trace-2",
                                           secondTimestamp)));
        ServiceRegistryManager manager = deadLetterRegistryManager(topic, deadLetterTopic, group);
        ServiceRegistry registry = manager.registry();
        AlwaysFailIncomingReceiver receiver = registry.get(AlwaysFailIncomingReceiver.class);

        try {
            registry.get(MessagingRuntime.class);

            int settledRecords = 0;
            List<String> failureMessages = new ArrayList<>(2);
            while (settledRecords < 2) {
                FailedBatch failedBatch = receiver.awaitFinalAttempt(WAIT_TIMEOUT);
                assertThat("final delivery attempt", failedBatch, notNullValue());
                assertThat("delivery attempts for poll " + failedBatch.entities(),
                           failedBatch.attempt(),
                           is(3));
                if (settledRecords == 0) {
                    assertNoCommittedOffset(group, topic);
                } else {
                    awaitCommittedOffset(group, topic, settledRecords);
                }
                assertRecordCount(deadLetterTopic, settledRecords, Duration.ofMillis(500));

                String failureMessage = "Expected permanent handler failure for "
                        + failedBatch.entities().getFirst();
                failedBatch.entities().forEach(ignored -> failureMessages.add(failureMessage));
                failedBatch.allowFailure();
                settledRecords += failedBatch.entities().size();

                awaitRecords(deadLetterTopic, settledRecords);
                awaitCommittedOffset(group, topic, settledRecords);
            }

            List<ConsumerRecord<String, String>> deadLetters = awaitRecords(deadLetterTopic, 2);
            assertDeadLetter(deadLetters.get(0),
                             topic,
                             0L,
                             "failed-key-1",
                             "failed first",
                             "dead-letter-trace-1",
                             failureMessages.get(0),
                             firstTimestamp);
            assertDeadLetter(deadLetters.get(1),
                             topic,
                             1L,
                             "failed-key-2",
                             "failed second",
                             "dead-letter-trace-2",
                             failureMessages.get(1),
                             secondTimestamp);
            assertThat("delivery attempts per poll",
                       receiver.attemptCounts().values().stream().allMatch(attempts -> attempts == 3),
                       is(true));
        } finally {
            receiver.allowAllFinalAttemptsToFail();
            manager.shutdown();
        }
    }

    private static KafkaConnectorConfig outgoingConnectorConfig(String topic) {
        String yaml = """
                direction: OUTGOING
                channel-name: %s
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
                               topic),
                               OutgoingSender.class);
    }

    private static ServiceRegistryManager forwardingRegistryManager(String incomingTopic,
                                                                    String outgoingTopic,
                                                                    String group) {
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
                        failure:
                          retry:
                            delay: PT0.05S
                        properties:
                          max.poll.records: "1"
                    outgoing:
                      %s:
                        connector: kafka
                        bootstrap.servers: "%s"
                        topic: "%s"
                        value.serializer: "%s"
                """.formatted(KafkaMessagingTypes.FORWARDING_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               incomingTopic,
                               group,
                               KafkaMessagingTypes.FORWARDING_OUTGOING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               outgoingTopic,
                               BlockingStringSerializer.class.getName()),
                               ForwardingReceiver.class);
    }

    private static ServiceRegistryManager failingForwardingRegistryManager(String incomingTopic,
                                                                           String outgoingTopic,
                                                                           String group) {
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
                        failure:
                          retry:
                            delay: PT0.05S
                            max-attempts: 2
                          on-exhausted: FAIL
                        properties:
                          max.poll.records: "1"
                    outgoing:
                      %s:
                        connector: kafka
                        bootstrap.servers: "%s"
                        topic: "%s"
                        value.serializer: "%s"
                """.formatted(KafkaMessagingTypes.FAILING_FORWARDING_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               incomingTopic,
                               group,
                               KafkaMessagingTypes.FAILING_FORWARDING_OUTGOING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               outgoingTopic,
                               FailingStringSerializer.class.getName()),
                               FailingForwardingReceiver.class);
    }

    private static ServiceRegistryManager restartRegistryManager(String topic, String group) {
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
                        failure:
                          retry:
                            delay: PT0.05S
                            max-attempts: 1
                          on-exhausted: FAIL
                        properties:
                          max.poll.records: "1"
                """.formatted(KafkaMessagingTypes.RESTART_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group),
                               RestartReceiver.class);
    }

    private static ServiceRegistryManager dropRegistryManager(String topic, String group) {
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
                        failure:
                          retry:
                            delay: PT0.05S
                            max-attempts: 2
                          on-exhausted: DROP
                        properties:
                          max.poll.records: "2"
                """.formatted(KafkaMessagingTypes.DROP_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group),
                               DropReceiver.class);
    }

    private static ServiceRegistryManager partitionRetryRegistryManager(String topic, String group) {
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
                        failure:
                          retry:
                            delay: PT0.05S
                        properties:
                          max.poll.records: "4"
                """.formatted(KafkaMessagingTypes.PARTITION_RETRY_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group),
                               PartitionRetryReceiver.class);
    }

    private static ServiceRegistryManager numericRegistryManager(String topic, String group) {
        return registryManager("""
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: kafka
                        bootstrap.servers: "%s"
                        topic: "%s"
                        group.id: "%s"
                        key.deserializer: "%s"
                        value.deserializer: "%s"
                        auto.offset.reset: earliest
                        poll.timeout: PT0.1S
                        properties:
                          max.poll.records: "1"
                    outgoing:
                      %s:
                        connector: kafka
                        bootstrap.servers: "%s"
                        topic: "%s"
                        key.serializer: "%s"
                        value.serializer: "%s"
                """.formatted(KafkaMessagingTypes.NUMERIC_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group,
                               LongDeserializer.class.getName(),
                               IntegerDeserializer.class.getName(),
                               KafkaMessagingTypes.NUMERIC_OUTGOING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               LongSerializer.class.getName(),
                               IntegerSerializer.class.getName()),
                               NumericSender.class,
                               NumericReceiver.class);
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
                               group),
                               IncomingReceiver.class,
                               IncomingPayloadReceiver.class,
                               IncomingMessageReceiver.class,
                               IncomingAnnotatedReceiver.class,
                               IncomingBatchReceiver.class);
    }

    private static ServiceRegistryManager metadataRegistryManager(String topic, String group) {
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
                """.formatted(KafkaMessagingTypes.METADATA_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group),
                               KafkaMetadataReceiver.class,
                               KafkaMetadataMessageReceiver.class,
                               KafkaMetadataBatchReceiver.class);
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
                        failure:
                          retry:
                            delay: PT0.05S
                        properties:
                          max.poll.records: "1"
                """.formatted(KafkaMessagingTypes.REDELIVERY_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group),
                               FailOnceIncomingReceiver.class);
    }

    private static ServiceRegistryManager ownerLoopRegistryManager(String topic,
                                                                   String group,
                                                                   Duration retryDelay,
                                                                   Duration maxPollInterval) {
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
                        failure:
                          retry:
                            delay: %s
                        properties:
                          max.poll.records: "1"
                          max.poll.interval.ms: "%d"
                """.formatted(KafkaMessagingTypes.REDELIVERY_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group,
                               retryDelay,
                               maxPollInterval.toMillis()),
                               FailOnceIncomingReceiver.class);
    }

    private static ServiceRegistryManager deadLetterRegistryManager(String topic,
                                                                    String deadLetterTopic,
                                                                    String group) {
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
                        failure:
                          retry:
                            delay: PT0.05S
                            max-attempts: 3
                          on-exhausted: DEAD_LETTER
                          dead-letter:
                            channel: %s
                        properties:
                          max.poll.records: "2"
                    outgoing:
                      %s:
                        connector: kafka
                        bootstrap.servers: "%s"
                        topic: "%s"
                """.formatted(KafkaMessagingTypes.DEAD_LETTER_INCOMING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               topic,
                               group,
                               KafkaMessagingTypes.DEAD_LETTER_OUTGOING_CHANNEL,
                               KafkaMessagingTypes.DEAD_LETTER_OUTGOING_CHANNEL,
                               KAFKA.getBootstrapServers(),
                               deadLetterTopic),
                               AlwaysFailIncomingReceiver.class);
    }

    private static ServiceRegistryManager registryManager(String yaml, Class<?>... fixtureTypes) {
        return KafkaScenarioRegistry.create(yaml, fixtureTypes);
    }

    private static void createTopic(String topic) throws Exception {
        createTopic(topic, 1);
    }

    private static void createTopic(String topic, int partitions) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                               KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
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

    private static void sendRecords(List<ProducerRecord<String, String>> records) throws Exception {
        Map<String, Object> properties = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                                KAFKA.getBootstrapServers());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties,
                                                                          new StringSerializer(),
                                                                          new StringSerializer())) {
            List<Future<RecordMetadata>> results = new ArrayList<>(records.size());
            for (ProducerRecord<String, String> record : records) {
                results.add(producer.send(record));
            }
            for (Future<RecordMetadata> result : results) {
                result.get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    private static ProducerRecord<String, String> producerRecord(String topic,
                                                                 String key,
                                                                 String value,
                                                                 String traceId,
                                                                 long timestamp) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("trace-id", traceId.getBytes(StandardCharsets.UTF_8)));
        ProducerRecord<String, String> record = new ProducerRecord<>(topic,
                                                                     null,
                                                                     timestamp,
                                                                     key,
                                                                     value,
                                                                     headers);
        return record;
    }

    private static List<ConsumerRecord<String, String>> awaitRecords(String topic, int expectedCount) {
        List<ConsumerRecord<String, String>> result = readRecords(topic, expectedCount, WAIT_TIMEOUT);
        if (result.size() != expectedCount) {
            throw new AssertionError("Expected " + expectedCount + " Kafka records on topic " + topic
                                             + " but received " + result.size());
        }
        return result;
    }

    private static void assertNoRecords(String topic, Duration timeout) {
        assertRecordCount(topic, 0, timeout);
    }

    private static void assertRecordCount(String topic, int expectedCount, Duration timeout) {
        assertThat("record count on topic " + topic,
                   readRecords(topic, expectedCount + 1, timeout).size(),
                   is(expectedCount));
    }

    private static List<ConsumerRecord<String, String>> readRecords(String topic,
                                                                    int expectedCount,
                                                                    Duration timeout) {
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
            long deadline = System.nanoTime() + timeout.toNanos();
            while (result.size() < expectedCount && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, String> record : records) {
                    result.add(record);
                }
            }
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

    private static void assertDeadLetter(ConsumerRecord<String, String> deadLetter,
                                         String originalTopic,
                                         long originalOffset,
                                         String expectedKey,
                                         String expectedValue,
                                         String expectedTraceId,
                                         String expectedFailureMessage,
                                         long originalTimestamp) {
        assertThat(deadLetter.key(), is(expectedKey));
        assertThat(deadLetter.value(), is(expectedValue));
        assertThat("dead-letter record has its own publication timestamp",
                   deadLetter.timestamp() > originalTimestamp,
                   is(true));
        assertThat(header(deadLetter, "trace-id"), is(Optional.of(expectedTraceId)));
        assertThat(header(deadLetter, DLQ_ORIGINAL_TOPIC_HEADER), is(Optional.of(originalTopic)));
        assertThat(header(deadLetter, DLQ_ORIGINAL_PARTITION_HEADER), is(Optional.of("0")));
        assertThat(header(deadLetter, DLQ_ORIGINAL_OFFSET_HEADER), is(Optional.of(Long.toString(originalOffset))));
        assertThat(header(deadLetter, DLQ_ORIGINAL_TIMESTAMP_HEADER),
                   is(Optional.of(Long.toString(originalTimestamp))));
        assertThat(header(deadLetter, DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER),
                   is(Optional.of(KafkaMessage.TimestampType.CREATE_TIME.name())));
        assertThat(header(deadLetter, DLQ_ORIGINAL_LEADER_EPOCH_HEADER), is(Optional.of("0")));
        assertThat(header(deadLetter, DeadLetterMessage.SOURCE_CHANNEL_HEADER),
                   is(Optional.of(KafkaMessagingTypes.DEAD_LETTER_INCOMING_CHANNEL)));
        assertThat(header(deadLetter, DeadLetterMessage.ATTEMPTS_HEADER), is(Optional.of("3")));
        assertThat(header(deadLetter, DeadLetterMessage.FAILURE_TYPE_HEADER),
                   is(Optional.of(IllegalStateException.class.getName())));
        assertThat(header(deadLetter, DeadLetterMessage.FAILURE_MESSAGE_HEADER),
                   is(Optional.of(expectedFailureMessage)));
    }

    private static Optional<String> header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null
                ? Optional.empty()
                : Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }

    private static void awaitCommittedOffset(String group, String topic, long expectedOffset) throws Exception {
        awaitCommittedOffset(group, topic, 0, expectedOffset);
    }

    private static void awaitCommittedOffset(String group,
                                             String topic,
                                             int partitionId,
                                             long expectedOffset) throws Exception {
        TopicPartition partition = new TopicPartition(topic, partitionId);
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
        assertThat("committed offset for " + partition, committed, notNullValue());
        assertThat("committed offset for " + partition, committed.offset(), is(expectedOffset));
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

    private static String awaitStableConsumerGroupMember(String group) throws Exception {
        ConsumerGroupDescription description = null;
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                               KAFKA.getBootstrapServers()))) {
            while (System.nanoTime() < deadline) {
                try {
                    description = admin.describeConsumerGroups(List.of(group))
                            .describedGroups()
                            .get(group)
                            .get(ADMIN_POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    if (description.groupState() == GroupState.STABLE && description.members().size() == 1) {
                        return description.members().iterator().next().consumerId();
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
        assertThat("stable consumer group " + group, description, notNullValue());
        assertThat("consumer group state " + group, description.groupState(), is(GroupState.STABLE));
        assertThat("active members in consumer group " + group, description.members().size(), is(1));
        return description.members().iterator().next().consumerId();
    }

    private static void assertConsumerGroupRemainsStable(String group,
                                                         String expectedMemberId,
                                                         Duration duration) throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        int successfulObservations = 0;
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                               KAFKA.getBootstrapServers()))) {
            while (true) {
                try {
                    ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(group))
                            .describedGroups()
                            .get(group)
                            .get(ADMIN_POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    assertThat("consumer group state while delivery is unsettled",
                               description.groupState(),
                               is(GroupState.STABLE));
                    assertThat("consumer group members while delivery is unsettled",
                               description.members().size(),
                               is(1));
                    assertThat("consumer group member while delivery is unsettled",
                               description.members().iterator().next().consumerId(),
                               is(expectedMemberId));
                    successfulObservations++;
                } catch (TimeoutException ignored) {
                    // Retry transient Admin delays within the fixed stability window.
                }

                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    assertThat("successful consumer group observations while delivery is unsettled",
                               successfulObservations > 0,
                               is(true));
                    return;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100)));
            }
        }
    }

    private static void awaitNoActiveConsumerGroupMembers(String group) throws Exception {
        int activeMembers = -1;
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                               KAFKA.getBootstrapServers()))) {
            while (System.nanoTime() < deadline) {
                try {
                    activeMembers = admin.describeConsumerGroups(List.of(group))
                            .describedGroups()
                            .get(group)
                            .get(ADMIN_POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            .members()
                            .size();
                    if (activeMembers == 0) {
                        return;
                    }
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof GroupIdNotFoundException) {
                        return;
                    }
                    throw e;
                } catch (TimeoutException ignored) {
                    // Retry until the overall wait timeout expires.
                }
                Thread.sleep(50);
            }
        }
        assertThat("active members in consumer group " + group, activeMembers, is(0));
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
