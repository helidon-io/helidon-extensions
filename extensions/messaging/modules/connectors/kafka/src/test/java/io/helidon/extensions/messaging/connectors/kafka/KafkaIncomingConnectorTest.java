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

package io.helidon.extensions.messaging.connectors.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorDelivery;
import io.helidon.extensions.messaging.ConnectorDeliveryReservation;
import io.helidon.extensions.messaging.ConnectorSource;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.DeadLetterMessage;
import io.helidon.extensions.messaging.FailurePolicy;
import io.helidon.extensions.messaging.ManagedConnectorSource;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.MessagingRejectedException;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaIncomingConnectorTest {
    private static final String TOPIC = "audit-events";
    private static final TopicPartition TOPIC_PARTITION = new TopicPartition(TOPIC, 0);
    private static final TopicPartition SECOND_TOPIC_PARTITION = new TopicPartition(TOPIC, 1);

    @Test
    void testConnectorName() {
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> trackingConsumer());

        assertThat(connector.connectorName(), is(KafkaOutgoingConnector.CONNECTOR));
    }

    @Test
    @Timeout(value = 5)
    void testGraphManagedSourceDoesNotPollBeforeAdmissionStarts() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        RecordingContext context = new RecordingContext(new ArrayList<>());
        AtomicReference<KafkaIncomingConnector> connectorRef = new AtomicReference<>();
        consumer.afterCommit(() -> connectorRef.get().close());
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        connectorRef.set(connector);
        ManagedConnectorSource source = (ManagedConnectorSource) connector.createSource(config(), context);
        source.prepareForGraph();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(source);

        source.awaitReady(Duration.ofSeconds(1));

        assertThat(consumer.pollCount(), is(0));
        assertThat(context.messages(), is(List.of()));

        source.startAdmission();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(context.messages().stream().map(Message::entity).toList(), is(List.of("first")));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    @Timeout(value = 5)
    void testGraphManagedSourceCanStopBeforeAdmission() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        ManagedConnectorSource source = (ManagedConnectorSource) connector.createSource(
                config(),
                new RecordingContext(new ArrayList<>()));
        source.prepareForGraph();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(source);

        source.awaitReady(Duration.ofSeconds(1));
        source.stopAdmission();
        thread.join(TimeUnit.SECONDS.toMillis(5));
        connector.close();

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(consumer.pollCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testGraphStopWakesIdlePollWithoutForcing() throws InterruptedException {
        BlockingMockConsumer consumer = new BlockingMockConsumer();
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        ManagedConnectorSource source = (ManagedConnectorSource) connector.createSource(
                config(Map.of("max.poll.interval.ms", "60000")),
                new RecordingContext(new ArrayList<>()));
        source.prepareForGraph();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(source);

        source.awaitReady(Duration.ofSeconds(1));
        source.startAdmission();
        assertThat(consumer.awaitPoll(), is(true));

        source.stopAdmission();
        thread.join(TimeUnit.SECONDS.toMillis(5));
        source.close();

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(consumer.wakeupCalled(), is(true));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testGraphReadinessReportsBrokerFailureBeforeAdmission() throws InterruptedException {
        IllegalStateException metadataFailure = new IllegalStateException("metadata unavailable");
        TrackingMockConsumer consumer = new TrackingMockConsumer() {
            @Override
            public List<PartitionInfo> partitionsFor(String topic, Duration timeout) {
                throw metadataFailure;
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        ManagedConnectorSource source = (ManagedConnectorSource) connector.createSource(
                config(),
                new RecordingContext(new ArrayList<>()));
        source.prepareForGraph();
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(source);

        MessagingException failure = assertThrows(MessagingException.class,
                                                  () -> source.awaitReady(Duration.ofSeconds(1)));
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(failure.getCause(), sameInstance(metadataFailure));
        assertThat(consumer.pollCount(), is(0));
        assertThat(thread.isAlive(), is(false));
        assertThat(sourceFailure.get(), sameInstance(failure));
    }

    @Test
    void testEmitsPollAsBatchWithUtf8HeadersThenCommits() {
        TrackingMockConsumer consumer = trackingConsumer();
        byte[] binaryHeader = new byte[] {0x00, (byte) 0xFF};
        RecordHeaders firstHeaders = new RecordHeaders();
        firstHeaders.add("null-header", null)
                .add("trace-id", "old".getBytes(StandardCharsets.UTF_8))
                .add("trace-id", "Příliš žluťoučký".getBytes(StandardCharsets.UTF_8))
                .add("binary", binaryHeader);
        scheduleRecords(consumer,
                        new ConsumerRecord<>(TOPIC,
                                             TOPIC_PARTITION.partition(),
                                             0,
                                             123_456_789L,
                                             TimestampType.CREATE_TIME,
                                             ConsumerRecord.NULL_SIZE,
                                             ConsumerRecord.NULL_SIZE,
                                             "audit-key",
                                             "first",
                                             firstHeaders,
                                             Optional.of(17)),
                        record(1, "second", new RecordHeaders()
                                .add("source", "kafka".getBytes(StandardCharsets.UTF_8))));
        List<String> events = new ArrayList<>();
        RecordingContext context = new RecordingContext(events);
        AtomicReference<KafkaIncomingConnector> connectorRef = new AtomicReference<>();
        consumer.afterCommit(() -> {
            events.add("commit");
            connectorRef.get().close();
        });
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        connectorRef.set(connector);

        connector.createSource(config(), context).run();

        assertThat(events, is(List.of("dispatch", "commit")));
        assertThat(context.messages().stream().map(Message::entity).toList(), is(List.of("first", "second")));
        assertThat(context.messages().get(0).header("null-header").isEmpty(), is(true));
        assertThat(context.messages().get(0).header("trace-id").orElseThrow(), is("Příliš žluťoučký"));
        assertThat(context.messages().get(1).header("source").orElseThrow(), is("kafka"));
        assertThat(context.messages().get(0), instanceOf(KafkaMessage.class));
        KafkaMessage<?, ?> kafkaMessage = (KafkaMessage<?, ?>) context.messages().get(0);
        assertThat(kafkaMessage.key().orElseThrow(), is("audit-key"));
        assertThat(kafkaMessage.topic().orElseThrow(), is(TOPIC));
        assertThat(kafkaMessage.partition().orElseThrow(), is(TOPIC_PARTITION.partition()));
        assertThat(kafkaMessage.offset().orElseThrow(), is(0L));
        assertThat(kafkaMessage.timestamp().orElseThrow(), is(123_456_789L));
        assertThat(kafkaMessage.timestampType().orElseThrow(), is(KafkaMessage.TimestampType.CREATE_TIME));
        assertThat(kafkaMessage.leaderEpoch().orElseThrow(), is(17));
        assertThat(kafkaMessage.kafkaHeaders().stream().map(KafkaMessage.Header::name).toList(),
                   is(List.of("null-header", "trace-id", "trace-id", "binary")));
        assertThat(kafkaMessage.kafkaHeaders().get(0).value(), is(Optional.empty()));
        assertArrayEquals("old".getBytes(StandardCharsets.UTF_8),
                          kafkaMessage.kafkaHeaders().get(1).value().orElseThrow());
        assertArrayEquals("Příliš žluťoučký".getBytes(StandardCharsets.UTF_8),
                          kafkaMessage.kafkaHeaders().get(2).value().orElseThrow());
        assertArrayEquals(new byte[] {0x00, (byte) 0xFF},
                          kafkaMessage.kafkaHeaders().get(3).value().orElseThrow());

        binaryHeader[0] = 0x7F;
        firstHeaders.add("late-header", "late".getBytes(StandardCharsets.UTF_8));
        byte[] exposedValue = kafkaMessage.kafkaHeaders().get(3).value().orElseThrow();
        exposedValue[1] = 0x00;
        assertArrayEquals(new byte[] {0x00, (byte) 0xFF},
                          kafkaMessage.kafkaHeaders().get(3).value().orElseThrow());
        assertThat(kafkaMessage.kafkaHeaders().size(), is(4));
        assertThrows(UnsupportedOperationException.class, () -> kafkaMessage.kafkaHeaders().clear());
        assertThat(consumer.commitCount(), is(1));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testKeepsPollingWhileHandlerIsBlockedAndCommitsOnlyAfterSettlement() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowSettlement = new CountDownLatch(1);
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                handlerStarted.countDown();
                try {
                    allowSettlement.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException("Handler interrupted", e);
                }
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        consumer.afterCommit(connector::close);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(connector.createSource(config(), context));

        try {
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), is(true));
            int pollsAtHandlerStart = consumer.pollCount();
            assertThat("consumer owner must poll while downstream settlement is blocked",
                       consumer.awaitPollCount(pollsAtHandlerStart + 1),
                       is(true));
            assertThat("offsets must not commit before downstream settlement", consumer.commitCount(), is(0));
        } finally {
            allowSettlement.countDown();
        }
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(consumer.commitCount(), is(1));
        assertThat(consumer.committedOffsets().get(TOPIC_PARTITION).offset(), is(1L));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testRevokedPendingPollIsNeverCommitted() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch partitionRevoked = new CountDownLatch(1);
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowHandlerToFinish = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                handlerStarted.countDown();
                try {
                    allowHandlerToFinish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    handlerFinished.countDown();
                }
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(connector.createSource(config(), context));

        try {
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), is(true));
            consumer.schedulePollTask(() -> {
                consumer.updateBeginningOffsets(Map.of(SECOND_TOPIC_PARTITION, 10L));
                consumer.rebalance(List.of(SECOND_TOPIC_PARTITION));
                partitionRevoked.countDown();
            });
            assertThat("maintenance poll must observe the rebalance while delivery is pending",
                       partitionRevoked.await(5, TimeUnit.SECONDS),
                       is(true));
            allowHandlerToFinish.countDown();
            assertThat(handlerFinished.await(5, TimeUnit.SECONDS), is(true));
            int pollsAtHandlerCompletion = consumer.pollCount();
            assertThat("source must continue after abandoning the revoked settlement unit",
                       consumer.awaitPollCount(pollsAtHandlerCompletion + 1),
                       is(true));
            assertThat("offsets from a revoked settlement unit must never commit", consumer.commitCount(), is(0));
            assertThat(consumer.committedOffsets(), is(Map.of()));
        } finally {
            allowHandlerToFinish.countDown();
            connector.close();
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testStaleInterruptedDeliveryBypassesFailurePolicy() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch partitionRevoked = new CountDownLatch(1);
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch handlerAborted = new CountDownLatch(1);
        CountDownLatch blockHandler = new CountDownLatch(1);
        AtomicInteger dispatchAttempts = new AtomicInteger();
        AtomicInteger failurePolicyCalls = new AtomicInteger();
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                dispatchAttempts.incrementAndGet();
                handlerStarted.countDown();
                try {
                    blockHandler.await();
                    throw new AssertionError("Stale delivery handler was not interrupted");
                } catch (InterruptedException e) {
                    handlerAborted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Handler aborted after partition revocation", e);
                }
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                failurePolicyCalls.incrementAndGet();
                return FailureResult.RETRY;
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(connector.createSource(config(), context));

        try {
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), is(true));
            consumer.schedulePollTask(() -> {
                consumer.updateBeginningOffsets(Map.of(SECOND_TOPIC_PARTITION, 10L));
                consumer.rebalance(List.of(SECOND_TOPIC_PARTITION));
                partitionRevoked.countDown();
            });
            assertThat(partitionRevoked.await(5, TimeUnit.SECONDS), is(true));
            assertThat(handlerAborted.await(5, TimeUnit.SECONDS), is(true));
            int pollsAtAbort = consumer.pollCount();
            assertThat(consumer.awaitPollCount(pollsAtAbort + 1), is(true));
            assertThat("stale handler failure must not enter portable failure handling",
                       failurePolicyCalls.get(),
                       is(0));
            assertThat("stale delivery must not be retried", dispatchAttempts.get(), is(1));
            assertThat(consumer.commitCount(), is(0));
        } finally {
            blockHandler.countDown();
            connector.close();
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(failurePolicyCalls.get(), is(0));
        assertThat(dispatchAttempts.get(), is(1));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testNonCooperativeStaleDeliveryFailsInsteadOfPollingAnotherUnit() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        AtomicInteger deliveryCloses = new AtomicInteger();
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                handlerStarted.countDown();
                try {
                    while (releaseHandler.getCount() != 0) {
                        try {
                            releaseHandler.await();
                        } catch (InterruptedException ignored) {
                            // Deliberately non-cooperative.
                        }
                    }
                } finally {
                    handlerFinished.countDown();
                }
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                ConnectorDeliveryReservation delegate = ConnectorSourceContext.super
                        .tryReserveDelivery(maxMessages, maxAdmissionBytes)
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                                    long admissionBytes,
                                                                    Runnable delivery) {
                        return super.tryStart(messages, admissionBytes, delivery)
                                .map(deliveryTask -> new TrackingDelivery(deliveryTask, deliveryCloses));
                    }
                });
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(connector.createSource(config(Duration.ofMillis(25)), context));

        try {
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), is(true));
            consumer.schedulePollTask(() -> {
                consumer.updateBeginningOffsets(Map.of(SECOND_TOPIC_PARTITION, 10L));
                consumer.rebalance(List.of(SECOND_TOPIC_PARTITION));
            });
            sourceThread.join(TimeUnit.SECONDS.toMillis(5));

            assertThat(sourceThread.isAlive(), is(false));
            assertThat(sourceFailure.get(), instanceOf(MessagingException.class));
            assertThat(sourceFailure.get().getMessage().contains("stale delivery did not stop"), is(true));
            assertThat(handlerFinished.getCount(), is(1L));
            assertThat(deliveryCloses.get(), is(1));
            assertThat(consumer.commitCount(), is(0));
            assertThat(consumer.closed(), is(true));
        } finally {
            releaseHandler.countDown();
        }

        assertThat(handlerFinished.await(5, TimeUnit.SECONDS), is(true));
        connector.close();
        assertThat(deliveryCloses.get(), is(1));
    }

    @Test
    @Timeout(value = 5)
    void testRetainsAndRedeliversEveryPartitionPollBeforeCommit() {
        TrackingMockConsumer consumer = trackingConsumer();
        ConsumerRecord<Object, Object> first = record(TOPIC_PARTITION, 4, "first", new RecordHeaders());
        ConsumerRecord<Object, Object> second = record(TOPIC_PARTITION, 5, "second", new RecordHeaders());
        ConsumerRecord<Object, Object> third = record(SECOND_TOPIC_PARTITION, 9, "third", new RecordHeaders());
        ConsumerRecord<Object, Object> fourth = record(SECOND_TOPIC_PARTITION, 10, "fourth", new RecordHeaders());
        scheduleRecords(consumer, first, second, third, fourth);
        List<List<Message<?>>> attempts = new ArrayList<>();
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                attempts.add(List.copyOf(messages));
                if (attempts.size() == 1) {
                    throw new IllegalStateException("dispatch failed");
                }
            }

            @Override
            public FailurePolicy failurePolicy() {
                return KafkaIncomingConnectorTest.failurePolicy(Duration.ofNanos(1));
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                return FailureResult.RETRY;
            }
        };
        AtomicReference<KafkaIncomingConnector> connectorRef = new AtomicReference<>();
        consumer.afterCommit(() -> connectorRef.get().close());
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        connectorRef.set(connector);

        connector.createSource(config(), context).run();

        List<String> expectedAttempt = List.of("first", "fourth", "second", "third");
        assertThat(attempts.stream()
                           .map(attempt -> attempt.stream()
                                   .map(message -> String.valueOf(message.entity()))
                                   .sorted()
                                   .toList())
                           .toList(),
                   is(List.of(expectedAttempt, expectedAttempt)));
        assertThat(attempts.getFirst().stream().allMatch(KafkaMessage.class::isInstance), is(true));
        for (int i = 0; i < attempts.getFirst().size(); i++) {
            assertThat(attempts.get(1).get(i), sameInstance(attempts.getFirst().get(i)));
        }
        assertThat(consumer.committedOffsets().get(TOPIC_PARTITION).offset(), is(6L));
        assertThat(consumer.committedOffsets().get(SECOND_TOPIC_PARTITION).offset(), is(11L));
        assertThat(consumer.commitCount(), is(1));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testRetryExhaustionFailsWithoutCommit() {
        TrackingMockConsumer consumer = trackingConsumer();
        ConsumerRecord<Object, Object> record = record(7, "poison", new RecordHeaders());
        scheduleRecords(consumer, record);
        AtomicInteger attempts = new AtomicInteger();
        ConnectorSourceContext context = failingContext(failurePolicy(Duration.ofNanos(1)),
                                                        attempts::incrementAndGet,
                                                        (messages, failedAttempt, failure) -> {
                                                            if (failedAttempt < 3) {
                                                                return ConnectorSourceContext.FailureResult.RETRY;
                                                            }
                                                            throw new MessagingException(
                                                                    "retry attempts exhausted after 3 attempt(s)",
                                                                    failure);
                                                        });
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createSource(config(), context)
                        .run());

        assertThat(failure.getMessage().contains("retry attempts exhausted"), is(true));
        assertThat(attempts.get(), is(3));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    void testFailDispositionStopsImmediatelyWithoutCommit() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "poison", new RecordHeaders()));
        AtomicInteger attempts = new AtomicInteger();
        ConnectorSourceContext context = failingContext(FailurePolicy.create(),
                                                        attempts::incrementAndGet,
                                                        (messages, failedAttempt, failure) -> {
                                                            throw new MessagingException(
                                                                    "delivery failed after 1 attempt(s)",
                                                                    failure);
                                                        });
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createSource(config(), context)
                        .run());

        assertThat(failure.getMessage().contains("delivery failed"), is(true));
        assertThat(attempts.get(), is(1));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    void testDropDispositionSettlesAndCommits() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "poison", new RecordHeaders()));
        AtomicInteger attempts = new AtomicInteger();
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        consumer.afterCommit(connector::close);

        connector.createSource(config(),
                               failingContext(FailurePolicy.create(),
                                              attempts::incrementAndGet,
                                              (messages, failedAttempt, failure) ->
                                                      ConnectorSourceContext.FailureResult.SETTLED))
                .run();

        assertThat(attempts.get(), is(1));
        assertThat(consumer.commitCount(), is(1));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testDeadLetterSettlementCompletesBeforeCommit() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        ConsumerRecord<Object, Object> first = record(4,
                                                     "first",
                                                     new RecordHeaders().add(
                                                             "trace-id",
                                                             "abc".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<Object, Object> second = record(5, "second", new RecordHeaders());
        scheduleRecords(consumer, first, second);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch settlementStarted = new CountDownLatch(1);
        CountDownLatch allowSettlement = new CountDownLatch(1);
        AtomicReference<List<DeadLetterMessage<?>>> deadLetters = new AtomicReference<>();
        ConnectorSourceContext context = failingContext(
                failurePolicy(Duration.ofNanos(1)),
                attempts::incrementAndGet,
                (messages, failedAttempt, failure) -> {
                    if (failedAttempt == 1) {
                        return ConnectorSourceContext.FailureResult.RETRY;
                    }
                    List<DeadLetterMessage<?>> wrapped = new ArrayList<>(messages.size());
                    for (Message<?> message : messages) {
                        wrapped.add(DeadLetterMessage.create(message, "audit", failedAttempt, failure));
                    }
                    deadLetters.set(List.copyOf(wrapped));
                    settlementStarted.countDown();
                    try {
                        allowSettlement.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MessagingException("Dead-letter settlement interrupted", e);
                    }
                    return ConnectorSourceContext.FailureResult.SETTLED;
                });
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        consumer.afterCommit(connector::close);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(connector.createSource(config(), context));

        assertThat(settlementStarted.await(5, TimeUnit.SECONDS), is(true));
        assertThat("offsets must not commit before dead-letter settlement", consumer.commitCount(), is(0));
        List<DeadLetterMessage<?>> routed = deadLetters.get();
        assertThat(routed.stream().map(Message::entity).toList(), is(List.of("first", "second")));
        assertThat(routed.stream().map(DeadLetterMessage::attempts).toList(), is(List.of(2, 2)));
        assertThat(routed.getFirst().originalMessage(), instanceOf(KafkaMessage.class));
        assertThat(routed.getFirst().originalMessage().header("trace-id").orElseThrow(), is("abc"));

        allowSettlement.countDown();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(attempts.get(), is(2));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    void testDeadLetterHandlingFailureDoesNotCommit() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "poison", new RecordHeaders()));
        IllegalStateException sendFailure = new IllegalStateException("dlq unavailable");
        AtomicInteger handlingAttempts = new AtomicInteger();
        ConnectorSourceContext context = failingContext(
                FailurePolicy.create(),
                () -> { },
                (messages, failedAttempt, failure) -> {
                    handlingAttempts.incrementAndGet();
                    throw sendFailure;
                });
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createSource(config(), context).run());

        assertThat(failure.getCause(), sameInstance(sendFailure));
        assertThat(handlingAttempts.get(), is(1));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
        connector.close();
    }

    @Test
    void testNullFailureResultDoesNotCommit() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "poison", new RecordHeaders()));
        ConnectorSourceContext context = failingContext(FailurePolicy.create(),
                                                        () -> { },
                                                        (messages, failedAttempt, failure) -> null);
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createSource(config(), context).run());

        assertThat(failure.getCause(), instanceOf(NullPointerException.class));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testRetriableCommitFailurePollsAndRetriesExactOffsets() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(7, "first", new RecordHeaders()));
        consumer.failNextCommit(new RetriableCommitFailedException("coordinator temporarily unavailable"));
        AtomicInteger dispatches = new AtomicInteger();
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        consumer.afterCommit(connector::close);

        connector.createSource(config(),
                               new RecordingContext(new ArrayList<>()) {
                                   @Override
                                   public <T> void emitBatch(List<? extends Message<T>> messages) {
                                       dispatches.incrementAndGet();
                                   }
                               })
                .run();

        assertThat("commit retry must not redeliver the settled handler batch", dispatches.get(), is(1));
        assertThat(consumer.commitInitiationCount(), is(2));
        assertThat(consumer.commitOffsets().stream()
                           .map(offsets -> offsets.get(TOPIC_PARTITION).offset())
                           .toList(),
                   is(List.of(8L, 8L)));
        List<Integer> pollCounts = consumer.pollCountsAtCommitInitiation();
        assertThat(pollCounts.size(), is(2));
        assertThat("consumer owner must poll between retriable commit attempts",
                   pollCounts.get(1) > pollCounts.get(0),
                   is(true));
        assertThat(consumer.commitCount(), is(1));
        assertThat(consumer.committedOffsets().get(TOPIC_PARTITION).offset(), is(8L));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testMissingCommitCallbackTimesOutWhilePolling() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(7, "first", new RecordHeaders()));
        consumer.suppressNextCommitCallback();
        AtomicInteger dispatches = new AtomicInteger();
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createSource(
                                config(Map.of(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "25")),
                                new RecordingContext(new ArrayList<>()) {
                                    @Override
                                    public <T> void emitBatch(List<? extends Message<T>> messages) {
                                        dispatches.incrementAndGet();
                                    }
                                })
                        .run());

        assertThat(failure.getMessage().contains("commit timed out"), is(true));
        assertThat(dispatches.get(), is(1));
        assertThat(consumer.commitInitiationCount(), is(1));
        List<Integer> commitPollCounts = consumer.pollCountsAtCommitInitiation();
        assertThat(commitPollCounts.size(), is(1));
        assertThat("consumer owner must keep polling while awaiting the commit callback",
                   consumer.pollCount() > commitPollCounts.getFirst(),
                   is(true));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.committedOffsets(), is(Map.of()));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    void testCommitFailureLeavesPollUnsettledAndPreservesCause() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        IllegalStateException commitFailure = new IllegalStateException("commit unavailable");
        consumer.failCommit(commitFailure);
        AtomicInteger dispatches = new AtomicInteger();
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createSource(config(),
                                             new RecordingContext(new ArrayList<>()) {
                                                 @Override
                                                 public <T> void emitBatch(List<? extends Message<T>> messages) {
                                                     dispatches.incrementAndGet();
                                                 }
                                             })
                        .run());

        assertThat(failure.getCause(), sameInstance(commitFailure));
        assertThat(dispatches.get(), is(1));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.committedOffsets(), is(Map.of()));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    void testCleanupFailureDoesNotMaskProcessingFailure() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "poison", new RecordHeaders()));
        IllegalStateException processingFailure = new IllegalStateException("handler failed");
        IllegalStateException closeFailure = new IllegalStateException("consumer close failed");
        consumer.failClose(closeFailure);
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                throw processingFailure;
            }
        };

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createSource(config(), context)
                        .run());

        assertThat(failure.getCause(), sameInstance(processingFailure));
        assertThat(failure.getSuppressed().length, is(1));
        assertThat(failure.getSuppressed()[0], instanceOf(MessagingException.class));
        assertThat(failure.getSuppressed()[0].getCause(), sameInstance(closeFailure));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    void testUnavailableAdmissionKeepsMaintenancePolling() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        AtomicInteger admissionAttempts = new AtomicInteger();
        AtomicInteger pollsAtAdmission = new AtomicInteger();
        ConnectorSourceContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                ConnectorDeliveryReservation delegate = super
                        .tryReserveDelivery(maxMessages, maxAdmissionBytes)
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                                    long admissionBytes,
                                                                    Runnable delivery) {
                        if (admissionAttempts.incrementAndGet() < 3) {
                            return Optional.empty();
                        }
                        pollsAtAdmission.set(consumer.pollCount());
                        return super.tryStart(messages, admissionBytes, delivery);
                    }
                });
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        consumer.afterCommit(connector::close);

        connector.createSource(config(), context).run();

        assertThat(admissionAttempts.get(), is(3));
        assertThat("the owner must maintenance-poll after every unavailable admission attempt",
                   pollsAtAdmission.get() >= 3,
                   is(true));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    void testAdmissionTimeoutIsTypedAndDoesNotCommit() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        ConnectorSourceContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<Duration> admissionTimeout() {
                return Optional.of(Duration.ofMillis(25));
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                ConnectorDeliveryReservation delegate = super
                        .tryReserveDelivery(maxMessages, maxAdmissionBytes)
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                                    long admissionBytes,
                                                                    Runnable delivery) {
                        return Optional.empty();
                    }
                });
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);

        MessagingRejectedException failure = assertThrows(
                MessagingRejectedException.class,
                () -> connector.createSource(config(), context).run());

        assertThat(failure.reason(), is(MessagingRejectedException.Reason.TIMEOUT));
        assertThat(failure.channel(), is("audit"));
        assertThat(consumer.pollCount() > 1, is(true));
        assertThat(consumer.commitCount(), is(0));
    }

    @Test
    @Timeout(value = 5)
    void testCloseStopsSourceWithoutPollingWhileReservationIsUnavailable() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch reservationAttempted = new CountDownLatch(1);
        ConnectorSourceContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                reservationAttempted.countDown();
                return Optional.empty();
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(connector.createSource(config(), context));

        assertThat(reservationAttempted.await(5, TimeUnit.SECONDS), is(true));
        assertThat("a source without assignment must not poll or join before reserving capacity",
                   consumer.pollCount(),
                   is(0));
        connector.close();
        sourceThread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    void testReservationTimeoutWithoutAssignmentDoesNotPoll() {
        TrackingMockConsumer consumer = trackingConsumer();
        ConnectorSourceContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<Duration> admissionTimeout() {
                return Optional.of(Duration.ofMillis(25));
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                return Optional.empty();
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);

        MessagingRejectedException failure = assertThrows(
                MessagingRejectedException.class,
                () -> connector.createSource(config(), context).run());

        assertThat(failure.reason(), is(MessagingRejectedException.Reason.TIMEOUT));
        assertThat(consumer.pollCount(), is(0));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    void testPreviouslyPolledConsumerWithoutAssignmentUsesMaintenancePollsWhileReservationIsUnavailable() {
        TrackingMockConsumer consumer = trackingConsumer();
        AtomicInteger reservationAttempts = new AtomicInteger();
        AtomicInteger reservationCloses = new AtomicInteger();
        AtomicReference<KafkaIncomingConnector> connectorRef = new AtomicReference<>();
        ConnectorSourceContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                return switch (reservationAttempts.incrementAndGet()) {
                case 1 -> Optional.of(unusedReservation(reservationCloses));
                case 2 -> Optional.empty();
                default -> {
                    connectorRef.get().close();
                    yield Optional.empty();
                }
                };
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        connectorRef.set(connector);

        connector.createSource(config(), context).run();

        assertThat(reservationAttempts.get(), is(3));
        assertThat(reservationCloses.get(), is(1));
        assertThat(consumer.assignment(), is(Set.of()));
        assertThat("a previously polled consumer must keep polling even when it currently has no assignment",
                   consumer.pollCount(),
                   is(2));
        assertThat(consumer.commitCount(), is(0));
    }

    @Test
    void testEmptyPollClosesUnusedReservation() {
        TrackingMockConsumer consumer = trackingConsumer();
        AtomicInteger reservationAttempts = new AtomicInteger();
        AtomicInteger reservationCloses = new AtomicInteger();
        AtomicReference<KafkaIncomingConnector> connectorRef = new AtomicReference<>();
        ConnectorSourceContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                if (reservationAttempts.incrementAndGet() > 1) {
                    connectorRef.get().close();
                    return Optional.empty();
                }
                return Optional.of(unusedReservation(reservationCloses));
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        connectorRef.set(connector);

        connector.createSource(config(), context).run();

        assertThat(reservationAttempts.get(), is(2));
        assertThat(reservationCloses.get(), is(1));
        assertThat(consumer.pollCount(), is(1));
        assertThat(consumer.commitCount(), is(0));
    }

    @Test
    void testNewAssignmentIsPausedWhilePollReservationIsUnavailable() {
        TrackingMockConsumer consumer = trackingConsumer();
        consumer.schedulePollTask(() -> {
            consumer.rebalance(Set.of(TOPIC_PARTITION));
            consumer.updateBeginningOffsets(Map.of(TOPIC_PARTITION, 0L));
        });
        consumer.schedulePollTask(() -> {
            consumer.rebalance(Set.of(SECOND_TOPIC_PARTITION));
            consumer.updateBeginningOffsets(Map.of(SECOND_TOPIC_PARTITION, 0L));
        });
        AtomicInteger reservationAttempts = new AtomicInteger();
        AtomicBoolean existingAssignmentPausedBeforeAttempt = new AtomicBoolean();
        AtomicReference<Set<TopicPartition>> pausedAfterAssignment = new AtomicReference<>();
        AtomicReference<KafkaIncomingConnector> connectorRef = new AtomicReference<>();
        ConnectorSourceContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                return switch (reservationAttempts.incrementAndGet()) {
                case 1 -> Optional.of(unusedReservation(new AtomicInteger()));
                case 2 -> {
                    existingAssignmentPausedBeforeAttempt.set(consumer.paused().contains(TOPIC_PARTITION));
                    yield Optional.empty();
                }
                default -> {
                    pausedAfterAssignment.set(consumer.paused());
                    connectorRef.get().close();
                    yield Optional.empty();
                }
                };
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        connectorRef.set(connector);

        connector.createSource(config(), context).run();

        assertThat(reservationAttempts.get(), is(3));
        assertThat(consumer.pollCount(), is(2));
        assertThat(existingAssignmentPausedBeforeAttempt.get(), is(true));
        assertThat(pausedAfterAssignment.get().contains(SECOND_TOPIC_PARTITION), is(true));
        assertThat(consumer.commitCount(), is(0));
    }

    @Test
    void testDeliveryLeaseIsReleasedOnlyAfterCommit() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        AtomicInteger leaseCloses = new AtomicInteger();
        AtomicBoolean reservationOpen = new AtomicBoolean();
        AtomicInteger reservedMessages = new AtomicInteger();
        AtomicLong reservedBytes = new AtomicLong();
        AtomicInteger actualMessages = new AtomicInteger();
        AtomicLong actualBytes = new AtomicLong();
        AtomicReference<ConnectorDelivery> trackedLease = new AtomicReference<>();
        consumer.beforeNextPoll(() -> assertThat("normal poll must run inside an open reservation",
                                                  reservationOpen.get(),
                                                  is(true)));
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
            }

            @Override
            public int maxDeliveryMessages() {
                return 2;
            }

            @Override
            public long maxDeliveryBytes() {
                return 128;
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                reservedMessages.set(maxMessages);
                reservedBytes.set(maxAdmissionBytes);
                reservationOpen.set(true);
                ConnectorDeliveryReservation delegate = ConnectorSourceContext.super
                        .tryReserveDelivery(maxMessages, maxAdmissionBytes)
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                                    long admissionBytes,
                                                                    Runnable delivery) {
                        actualMessages.set(messages.size());
                        actualBytes.set(admissionBytes);
                        Optional<ConnectorDelivery> started = super.tryStart(messages, admissionBytes, delivery);
                        if (started.isPresent()) {
                            reservationOpen.set(false);
                        }
                        return started.map(deliveryTask -> {
                                    ConnectorDelivery tracking = new TrackingDelivery(deliveryTask, leaseCloses);
                                    trackedLease.set(tracking);
                                    return tracking;
                                });
                    }

                    @Override
                    public void close() {
                        super.close();
                        reservationOpen.set(false);
                    }
                });
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        consumer.afterCommit(() -> {
            assertThat("commit callback must run before the retained-delivery lease is released",
                       leaseCloses.get(),
                       is(0));
            connector.close();
        });

        connector.createSource(config(), context).run();

        assertThat(trackedLease.get().isDone(), is(true));
        assertThat(reservedMessages.get(), is(2));
        assertThat(reservedBytes.get(), is(128L));
        assertThat(actualMessages.get() <= reservedMessages.get(), is(true));
        assertThat(actualBytes.get() <= reservedBytes.get(), is(true));
        assertThat(leaseCloses.get(), is(1));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    void testPostPollSafetyCheckEnforcesLimitsWhenKafkaAcquisitionHintsAreExceeded() {
        TrackingMockConsumer messageLimitedConsumer = trackingConsumer();
        scheduleRecords(messageLimitedConsumer,
                        record(0, "first", new RecordHeaders()),
                        record(1, "second", new RecordHeaders()));
        ConnectorSourceContext messageLimitedContext = new RecordingContext(new ArrayList<>()) {
            @Override
            public int maxDeliveryMessages() {
                return 1;
            }
        };
        AtomicReference<Map<String, Object>> messageAcquisitionProperties = new AtomicReference<>();
        KafkaIncomingConnector messageLimitedConnector = new KafkaIncomingConnector(properties -> {
            messageAcquisitionProperties.set(properties);
            return messageLimitedConsumer;
        });

        MessagingRejectedException messageFailure = assertThrows(
                MessagingRejectedException.class,
                () -> messageLimitedConnector.createSource(config(), messageLimitedContext).run());

        assertThat(messageFailure.reason(), is(MessagingRejectedException.Reason.OVERSIZED));
        assertThat(messageAcquisitionProperties.get().get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), is(1));
        assertThat(messageLimitedConsumer.commitCount(), is(0));

        TrackingMockConsumer byteLimitedConsumer = trackingConsumer();
        scheduleRecords(byteLimitedConsumer, record(0, "first", new RecordHeaders()));
        ConnectorSourceContext byteLimitedContext = new RecordingContext(new ArrayList<>()) {
            @Override
            public long maxDeliveryBytes() {
                return 1;
            }
        };
        AtomicReference<Map<String, Object>> byteAcquisitionProperties = new AtomicReference<>();
        KafkaIncomingConnector byteLimitedConnector = new KafkaIncomingConnector(properties -> {
            byteAcquisitionProperties.set(properties);
            return byteLimitedConsumer;
        });

        MessagingRejectedException byteFailure = assertThrows(
                MessagingRejectedException.class,
                () -> byteLimitedConnector.createSource(config(), byteLimitedContext).run());

        assertThat(byteFailure.reason(), is(MessagingRejectedException.Reason.OVERSIZED));
        assertThat(byteAcquisitionProperties.get().get(ConsumerConfig.FETCH_MAX_BYTES_CONFIG), is(1));
        assertThat(byteAcquisitionProperties.get().get(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG), is(1));
        assertThat(byteLimitedConsumer.commitCount(), is(0));
    }

    @Test
    void testAdmissionIncludesRetainedPollSettlementMetadata() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        AtomicLong admissionBytes = new AtomicLong();
        ConnectorSourceContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                ConnectorDeliveryReservation delegate = super
                        .tryReserveDelivery(maxMessages, maxAdmissionBytes)
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                                    long deliveryBytes,
                                                                    Runnable delivery) {
                        admissionBytes.set(deliveryBytes);
                        return super.tryStart(messages, deliveryBytes, delivery);
                    }
                });
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        consumer.afterCommit(connector::close);

        connector.createSource(config(), context).run();

        assertThat(admissionBytes.get(), is(76L));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    void testRuntimeMessageEstimateCannotUnderstateSerializedRecord() {
        TrackingMockConsumer consumer = trackingConsumer();
        Object payload = new Object();
        scheduleRecords(consumer, new ConsumerRecord<>(TOPIC,
                                                       TOPIC_PARTITION.partition(),
                                                       0,
                                                       ConsumerRecord.NO_TIMESTAMP,
                                                       TimestampType.NO_TIMESTAMP_TYPE,
                                                       ConsumerRecord.NULL_SIZE,
                                                       10_000,
                                                       null,
                                                       payload,
                                                       new RecordHeaders(),
                                                       Optional.empty()));
        AtomicInteger estimateCalls = new AtomicInteger();
        AtomicLong admissionBytes = new AtomicLong();
        AtomicReference<Message<?>> estimatedMessage = new AtomicReference<>();
        RecordingContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public OptionalLong messageAdmissionBytes(Message<?> message) {
                assertThat(message.admissionBytes().isEmpty(), is(true));
                assertThat(message.entity(), sameInstance(payload));
                estimatedMessage.set(message);
                estimateCalls.incrementAndGet();
                return OptionalLong.of(64);
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                ConnectorDeliveryReservation delegate = super
                        .tryReserveDelivery(maxMessages, maxAdmissionBytes)
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                                    long deliveryBytes,
                                                                    Runnable delivery) {
                        admissionBytes.set(deliveryBytes);
                        return super.tryStart(messages, deliveryBytes, delivery);
                    }
                });
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        consumer.afterCommit(connector::close);

        connector.createSource(config(), context).run();

        assertThat(estimateCalls.get(), is(1));
        assertThat(context.messages().getFirst(), sameInstance(estimatedMessage.get()));
        assertThat(admissionBytes.get(), is(10_071L));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    void testCloseWakesRedeliveryDelayWithoutCommitting() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch retryStarted = new CountDownLatch(1);
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                throw new IllegalStateException("dispatch failed");
            }

            @Override
            public FailurePolicy failurePolicy() {
                return KafkaIncomingConnectorTest.failurePolicy(Duration.ofHours(1));
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                retryStarted.countDown();
                return FailureResult.RETRY;
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        ConnectorSource source = connector.createSource(config(), context);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(source);
        assertThat(retryStarted.await(5, TimeUnit.SECONDS), is(true));
        int pollsAtRetryStart = consumer.pollCount();
        assertThat("consumer owner must poll while retained delivery waits for retry",
                   consumer.awaitPollCount(pollsAtRetryStart + 1),
                   is(true));
        assertThat(consumer.commitCount(), is(0));

        connector.close();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive(), is(false));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
        assertThat(failure.get(), nullValue());
    }

    @Test
    @Timeout(value = 5)
    void testCloseBeforeCommitInitiationPreventsCommit() throws Exception {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowSettlement = new CountDownLatch(1);
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                handlerStarted.countDown();
                try {
                    allowSettlement.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException("Handler interrupted", e);
                }
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(connector.createSource(config(), context));

        try {
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), is(true));
            consumer.blockNextAssignment();
            allowSettlement.countDown();
            assertThat("consumer owner must stop immediately before commit eligibility is checked",
                       consumer.awaitBlockedAssignment(),
                       is(true));

            CompletableFuture<Void> closing = CompletableFuture.runAsync(connector::close);
            long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!consumer.wakeupCalled() && System.nanoTime() < closeDeadline) {
                Thread.onSpinWait();
            }
            assertThat(consumer.wakeupCalled(), is(true));
            assertThat(closing.isDone(), is(false));
            assertThat(consumer.commitInitiationCount(), is(0));
            consumer.releaseBlockedAssignment();
            closing.get(1, TimeUnit.SECONDS);
        } finally {
            allowSettlement.countDown();
            consumer.releaseBlockedAssignment();
            connector.close();
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(consumer.commitInitiationCount(), is(0));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.committedOffsets(), is(Map.of()));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 10)
    void testCloseReportsNonCooperativeDeliveryUntilItActuallyFinishes() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        CountDownLatch deliveryAdmitted = new CountDownLatch(1);
        CountDownLatch releaseDeliveryPublication = new CountDownLatch(1);
        CountDownLatch releaseDeliveryCompletion = new CountDownLatch(1);
        AtomicInteger interrupts = new AtomicInteger();
        AtomicInteger timedAwaits = new AtomicInteger();
        AtomicReference<ConnectorDelivery> trackedDelivery = new AtomicReference<>();
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                handlerStarted.countDown();
                try {
                    while (releaseHandler.getCount() != 0) {
                        try {
                            releaseHandler.await();
                        } catch (InterruptedException e) {
                            interrupts.incrementAndGet();
                        }
                    }
                } finally {
                    handlerFinished.countDown();
                }
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages,
                                                                             long maxAdmissionBytes) {
                ConnectorDeliveryReservation delegate = ConnectorSourceContext.super
                        .tryReserveDelivery(maxMessages, maxAdmissionBytes)
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                                    long admissionBytes,
                                                                    Runnable delivery) {
                        Optional<ConnectorDelivery> admitted = super.tryStart(messages, admissionBytes, delivery)
                                .map(started -> {
                                    ConnectorDelivery completionHolding = new CompletionHoldingDelivery(
                                            started,
                                            releaseDeliveryCompletion);
                                    ConnectorDelivery tracking = new AwaitCountingDelivery(completionHolding, timedAwaits);
                                    trackedDelivery.set(tracking);
                                    return tracking;
                                });
                        deliveryAdmitted.countDown();
                        try {
                            releaseDeliveryPublication.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Delivery publication wait was interrupted", e);
                        }
                        return admitted;
                    }
                });
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(connector.createSource(config(Duration.ofSeconds(1)), context));

        try {
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), is(true));
            assertThat(deliveryAdmitted.await(5, TimeUnit.SECONDS), is(true));

            AtomicReference<Throwable> firstCloseFailure = new AtomicReference<>();
            Thread firstCloseThread = Thread.ofVirtual()
                    .start(() -> captureFailure(connector::close, firstCloseFailure));
            awaitWaiting(firstCloseThread);
            releaseDeliveryPublication.countDown();
            firstCloseThread.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(firstCloseThread.isAlive(), is(false));
            assertThat(firstCloseFailure.get(), instanceOf(MessagingException.class));
            MessagingException firstClose = (MessagingException) firstCloseFailure.get();
            assertThat(firstClose.getMessage().contains("close timed out"), is(true));
            assertThat(firstClose.getMessage().contains("active delivery"), is(true));
            sourceThread.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(sourceThread.isAlive(), is(false));
            assertThat("a timed-out delivery must remain tracked by the closed connector",
                       assertThrows(MessagingException.class, connector::close)
                               .getMessage()
                               .contains("close timed out"),
                       is(true));
            assertThat(handlerFinished.getCount(), is(1L));
            assertThat(interrupts.get() >= 2, is(true));
            assertThat(consumer.commitCount(), is(0));
        } finally {
            releaseDeliveryPublication.countDown();
            releaseHandler.countDown();
        }

        assertThat(handlerFinished.await(5, TimeUnit.SECONDS), is(true));
        try {
            assertThat(trackedDelivery.get().isDone(), is(false));
            MessagingException incompleteDelegateClose = assertThrows(MessagingException.class, connector::close);
            assertThat(incompleteDelegateClose.getMessage().contains("active delivery"), is(true));
        } finally {
            releaseDeliveryCompletion.countDown();
        }
        long deliveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!trackedDelivery.get().isDone() && System.nanoTime() < deliveryDeadline) {
            Thread.onSpinWait();
        }
        assertThat(trackedDelivery.get().isDone(), is(true));
        connector.close();
        int awaitsAfterCleanupClose = timedAwaits.get();
        connector.close();
        assertThat("finished abandoned delivery must remove its source without another close-time await",
                   timedAwaits.get(),
                   is(awaitsAfterCleanupClose));
        assertThat(sourceFailure.get(), nullValue());
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testDeliveryCanCloseConnectorWithoutWaitingForItself() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<KafkaIncomingConnector> connectorRef = new AtomicReference<>();
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                connectorRef.get().close();
                closeReturned.countDown();
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        connectorRef.set(connector);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(connector.createSource(config(), context));

        assertThat(closeReturned.await(5, TimeUnit.SECONDS), is(true));
        sourceThread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
        connector.close();
    }

    @Test
    void testCloseWakesPollingConsumerAndClosesIt() throws InterruptedException {
        BlockingMockConsumer consumer = new BlockingMockConsumer();
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        ConnectorSource source = connector.createSource(config(), new RecordingContext(new ArrayList<>()));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(source);
        assertThat(consumer.awaitPoll(), is(true));

        connector.close();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive(), is(false));
        assertThat(consumer.wakeupCalled(), is(true));
        assertThat(consumer.closed(), is(true));
        assertThat(failure.get(), nullValue());
    }

    @Test
    void testFailedSourceCannotBeRunAgainAfterConnectorClose() {
        AtomicInteger consumerCreations = new AtomicInteger();
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> {
            consumerCreations.incrementAndGet();
            throw new IllegalStateException("consumer creation failed");
        });
        ConnectorSource source = connector.createSource(config(), new RecordingContext(new ArrayList<>()));

        assertThrows(MessagingException.class, source::run);
        connector.close();

        assertThrows(IllegalStateException.class, source::run);
        assertThat(consumerCreations.get(), is(1));
    }

    @Test
    @Timeout(value = 5)
    void testConcurrentCloseSerializesConsumerCloseRetryWithinDeadline() throws Exception {
        CountDownLatch consumerCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseConsumerClose = new CountDownLatch(1);
        CountDownLatch retryCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseRetryClose = new CountDownLatch(1);
        AtomicInteger closeAttempts = new AtomicInteger();
        IllegalStateException closeFailure = new IllegalStateException("consumer close failed");
        TrackingMockConsumer consumer = new TrackingMockConsumer() {
            @Override
            public void close(Duration timeout) {
                int attempt = closeAttempts.incrementAndGet();
                if (attempt == 1) {
                    consumerCloseStarted.countDown();
                    try {
                        releaseConsumerClose.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Consumer close was interrupted", e);
                    }
                    throw closeFailure;
                }
                if (attempt == 2) {
                    retryCloseStarted.countDown();
                    try {
                        releaseRetryClose.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Consumer close retry was interrupted", e);
                    }
                }
                super.close(timeout);
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        ManagedConnectorSource source = (ManagedConnectorSource) connector.createSource(
                config(Duration.ofMillis(100)),
                new RecordingContext(new ArrayList<>()));
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(source);
        assertThat(consumer.awaitPollCount(1), is(true));

        source.forceClose();
        assertThat(consumerCloseStarted.await(1, TimeUnit.SECONDS), is(true));
        releaseConsumerClose.countDown();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));
        assertThat(sourceThread.isAlive(), is(false));

        AtomicReference<Throwable> firstCloseFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondCloseFailure = new AtomicReference<>();
        Thread firstClose = Thread.ofVirtual().start(() -> captureFailure(source::close, firstCloseFailure));
        assertThat(retryCloseStarted.await(1, TimeUnit.SECONDS), is(true));
        Thread secondClose = Thread.ofVirtual().start(() -> captureFailure(source::close, secondCloseFailure));
        try {
            secondClose.join(TimeUnit.SECONDS.toMillis(1));
            assertThat("a concurrent close must honor its deadline while another retry owns the close lock",
                       secondClose.isAlive(),
                       is(false));
            assertThat(firstClose.isAlive(), is(true));
            assertThat(closeAttempts.get(), is(2));
        } finally {
            releaseRetryClose.countDown();
        }

        firstClose.join(TimeUnit.SECONDS.toMillis(1));
        assertThat(firstClose.isAlive(), is(false));
        assertThat(closeAttempts.get(), is(2));
        assertThat(consumer.closed(), is(true));
        assertThat(sourceFailure.get(), instanceOf(MessagingException.class));
        assertThat(firstCloseFailure.get(), instanceOf(MessagingException.class));
        assertThat(firstCloseFailure.get().getCause(), sameInstance(closeFailure));
        assertThat(secondCloseFailure.get(), sameInstance(firstCloseFailure.get()));
    }

    private static void captureFailure(Runnable action, AtomicReference<Throwable> failure) {
        try {
            action.run();
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        Thread.State state;
        do {
            state = thread.getState();
            if (isWaiting(state)) {
                return;
            }
            if (state == Thread.State.TERMINATED) {
                throw new AssertionError("Close task completed instead of waiting");
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Close task did not enter a waiting state; last state was " + state);
    }

    private static boolean isWaiting(Thread.State state) {
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    private static KafkaConnectorConfig config() {
        return config(Map.of());
    }

    private static KafkaConnectorConfig config(Duration closeTimeout) {
        return config(closeTimeout, Map.of());
    }

    private static KafkaConnectorConfig config(Map<String, String> properties) {
        return config(Duration.ofSeconds(1), properties);
    }

    private static KafkaConnectorConfig config(Duration closeTimeout, Map<String, String> properties) {
        return KafkaConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel("audit")
                .connector(KafkaOutgoingConnector.CONNECTOR)
                .bootstrapServers("localhost:9092")
                .topic(TOPIC)
                .groupId("audit-test")
                .pollTimeout(Duration.ofMillis(10))
                .closeTimeout(closeTimeout)
                .properties(properties)
                .build();
    }

    private static FailurePolicy failurePolicy(Duration retryDelay) {
        return FailurePolicy.builder()
                .retryDelay(retryDelay)
                .build();
    }

    private static ConnectorDeliveryReservation unusedReservation(AtomicInteger closes) {
        return new ConnectorDeliveryReservation() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public <T> ConnectorDelivery start(List<? extends Message<T>> messages,
                                               long admissionBytes,
                                               Runnable delivery) {
                throw new AssertionError("An empty Kafka poll must not start its reservation");
            }

            @Override
            public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                            long admissionBytes,
                                                            Runnable delivery) {
                throw new AssertionError("An empty Kafka poll must not start its reservation");
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    closes.incrementAndGet();
                }
            }
        };
    }

    private static ConnectorSourceContext failingContext(FailurePolicy failurePolicy,
                                                         Runnable beforeFailure,
                                                         FailureHandler failureHandler) {
        return new ConnectorSourceContext() {
            @Override
            public FailurePolicy failurePolicy() {
                return failurePolicy;
            }

            @Override
            public String channelName() {
                return "audit";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("Kafka source must emit a poll as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                beforeFailure.run();
                throw new IllegalStateException("dispatch failed");
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                return failureHandler.handle(messages, failedAttempt, failure);
            }
        };
    }

    private static TrackingMockConsumer trackingConsumer() {
        return new TrackingMockConsumer();
    }

    @SafeVarargs
    private static void scheduleRecords(MockConsumer<Object, Object> consumer,
                                        ConsumerRecord<Object, Object>... records) {
        consumer.schedulePollTask(() -> {
            Map<TopicPartition, Long> beginningOffsets = new LinkedHashMap<>();
            for (ConsumerRecord<Object, Object> record : records) {
                TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                beginningOffsets.merge(partition, record.offset(), Math::min);
            }
            consumer.rebalance(beginningOffsets.keySet());
            consumer.updateBeginningOffsets(beginningOffsets);
            for (ConsumerRecord<Object, Object> record : records) {
                consumer.addRecord(record);
            }
        });
    }

    private static ConsumerRecord<Object, Object> record(long offset, Object value, Headers headers) {
        return record(TOPIC_PARTITION, offset, value, headers);
    }

    private static ConsumerRecord<Object, Object> record(TopicPartition partition,
                                                         long offset,
                                                         Object value,
                                                         Headers headers) {
        return new ConsumerRecord<>(TOPIC,
                                    partition.partition(),
                                    offset,
                                    ConsumerRecord.NO_TIMESTAMP,
                                    TimestampType.NO_TIMESTAMP_TYPE,
                                    ConsumerRecord.NULL_SIZE,
                                    ConsumerRecord.NULL_SIZE,
                                    null,
                                    value,
                                    headers,
                                    Optional.empty());
    }

    private static class TrackingMockConsumer extends MockConsumer<Object, Object> {
        private final AtomicBoolean wakeupCalled = new AtomicBoolean();
        private final AtomicBoolean blockNextAssignment = new AtomicBoolean();
        private final CountDownLatch assignmentBlocked = new CountDownLatch(1);
        private final CountDownLatch releaseAssignment = new CountDownLatch(1);
        private final List<Integer> pollCountsAtCommitInitiation = new ArrayList<>();
        private final List<Map<TopicPartition, OffsetAndMetadata>> commitOffsets = new ArrayList<>();
        private final ReentrantLock stateLock = new ReentrantLock();
        private final Condition pollAdvanced = stateLock.newCondition();
        private int pollCount;
        private int commitInitiationCount;
        private int commitCount;
        private Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of();
        private Runnable afterCommit = () -> { };
        private Runnable beforeNextPoll = () -> { };
        private boolean suppressNextCommitCallback;
        private RuntimeException nextCommitFailure;
        private RuntimeException commitFailure;
        private RuntimeException closeFailure;

        private TrackingMockConsumer() {
            super(OffsetResetStrategy.EARLIEST);
            updatePartitions(TOPIC,
                             List.of(new PartitionInfo(TOPIC,
                                                       TOPIC_PARTITION.partition(),
                                                       Node.noNode(),
                                                       new Node[0],
                                                       new Node[0])));
        }

        @Override
        public ConsumerRecords<Object, Object> poll(Duration timeout) {
            Runnable beforePoll;
            stateLock.lock();
            try {
                pollCount++;
                beforePoll = beforeNextPoll;
                beforeNextPoll = () -> { };
                pollAdvanced.signalAll();
            } finally {
                stateLock.unlock();
            }
            beforePoll.run();
            return super.poll(timeout);
        }

        @Override
        public Set<TopicPartition> assignment() {
            if (blockNextAssignment.compareAndSet(true, false)) {
                assignmentBlocked.countDown();
                try {
                    releaseAssignment.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while blocking assignment lookup", e);
                }
            }
            return super.assignment();
        }

        @Override
        public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            Runnable completed = null;
            stateLock.lock();
            try {
                if (commitFailure != null) {
                    throw commitFailure;
                }
                super.commitSync(offsets);
                committedOffsets = Map.copyOf(offsets);
                commitCount++;
                completed = afterCommit;
            } finally {
                stateLock.unlock();
            }
            if (completed != null) {
                completed.run();
            }
        }

        @Override
        public void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets,
                                OffsetCommitCallback callback) {
            RuntimeException currentFailure;
            stateLock.lock();
            try {
                commitInitiationCount++;
                pollCountsAtCommitInitiation.add(pollCount);
                commitOffsets.add(Map.copyOf(offsets));
                if (suppressNextCommitCallback) {
                    suppressNextCommitCallback = false;
                    return;
                }
                currentFailure = nextCommitFailure;
                nextCommitFailure = null;
                if (currentFailure == null) {
                    currentFailure = commitFailure;
                }
            } finally {
                stateLock.unlock();
            }
            if (currentFailure != null) {
                callback.onComplete(offsets, currentFailure);
                return;
            }
            super.commitAsync(offsets, (committed, failure) -> {
                Runnable completed = null;
                stateLock.lock();
                try {
                    if (failure == null) {
                        committedOffsets = Map.copyOf(committed);
                        commitCount++;
                        completed = afterCommit;
                    }
                } finally {
                    stateLock.unlock();
                }
                callback.onComplete(committed, failure);
                if (completed != null) {
                    completed.run();
                }
            });
        }

        @Override
        public void close(Duration timeout) {
            super.close(timeout);
            RuntimeException failure;
            stateLock.lock();
            try {
                failure = closeFailure;
            } finally {
                stateLock.unlock();
            }
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void wakeup() {
            wakeupCalled.set(true);
            super.wakeup();
        }

        private int commitCount() {
            stateLock.lock();
            try {
                return commitCount;
            } finally {
                stateLock.unlock();
            }
        }

        private int commitInitiationCount() {
            stateLock.lock();
            try {
                return commitInitiationCount;
            } finally {
                stateLock.unlock();
            }
        }

        private List<Integer> pollCountsAtCommitInitiation() {
            stateLock.lock();
            try {
                return List.copyOf(pollCountsAtCommitInitiation);
            } finally {
                stateLock.unlock();
            }
        }

        private List<Map<TopicPartition, OffsetAndMetadata>> commitOffsets() {
            stateLock.lock();
            try {
                return List.copyOf(commitOffsets);
            } finally {
                stateLock.unlock();
            }
        }

        private int pollCount() {
            stateLock.lock();
            try {
                return pollCount;
            } finally {
                stateLock.unlock();
            }
        }

        private boolean awaitPollCount(int expected) throws InterruptedException {
            long remaining = TimeUnit.SECONDS.toNanos(5);
            stateLock.lock();
            try {
                while (pollCount < expected) {
                    if (remaining <= 0) {
                        return false;
                    }
                    remaining = pollAdvanced.awaitNanos(remaining);
                }
                return true;
            } finally {
                stateLock.unlock();
            }
        }

        private Map<TopicPartition, OffsetAndMetadata> committedOffsets() {
            stateLock.lock();
            try {
                return committedOffsets;
            } finally {
                stateLock.unlock();
            }
        }

        private void afterCommit(Runnable afterCommit) {
            stateLock.lock();
            try {
                this.afterCommit = afterCommit;
            } finally {
                stateLock.unlock();
            }
        }

        private void beforeNextPoll(Runnable beforeNextPoll) {
            stateLock.lock();
            try {
                this.beforeNextPoll = beforeNextPoll;
            } finally {
                stateLock.unlock();
            }
        }

        private void failCommit(RuntimeException commitFailure) {
            stateLock.lock();
            try {
                this.commitFailure = commitFailure;
            } finally {
                stateLock.unlock();
            }
        }

        private void failNextCommit(RuntimeException commitFailure) {
            stateLock.lock();
            try {
                this.nextCommitFailure = commitFailure;
            } finally {
                stateLock.unlock();
            }
        }

        private void suppressNextCommitCallback() {
            stateLock.lock();
            try {
                this.suppressNextCommitCallback = true;
            } finally {
                stateLock.unlock();
            }
        }

        private void failClose(RuntimeException closeFailure) {
            stateLock.lock();
            try {
                this.closeFailure = closeFailure;
            } finally {
                stateLock.unlock();
            }
        }

        private void blockNextAssignment() {
            blockNextAssignment.set(true);
        }

        private boolean awaitBlockedAssignment() throws InterruptedException {
            return assignmentBlocked.await(5, TimeUnit.SECONDS);
        }

        private void releaseBlockedAssignment() {
            releaseAssignment.countDown();
        }

        private boolean wakeupCalled() {
            return wakeupCalled.get();
        }
    }

    private static final class BlockingMockConsumer extends MockConsumer<Object, Object> {
        private final CountDownLatch pollStarted = new CountDownLatch(1);
        private final CountDownLatch wakeup = new CountDownLatch(1);
        private final AtomicBoolean wakeupCalled = new AtomicBoolean();

        private BlockingMockConsumer() {
            super(OffsetResetStrategy.EARLIEST);
            updatePartitions(TOPIC,
                             List.of(new PartitionInfo(TOPIC,
                                                       TOPIC_PARTITION.partition(),
                                                       Node.noNode(),
                                                       new Node[0],
                                                       new Node[0])));
        }

        @Override
        public ConsumerRecords<Object, Object> poll(Duration timeout) {
            pollStarted.countDown();
            try {
                wakeup.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting wakeup", e);
            }
            throw new WakeupException();
        }

        @Override
        public void wakeup() {
            wakeupCalled.set(true);
            wakeup.countDown();
        }

        private boolean awaitPoll() throws InterruptedException {
            return pollStarted.await(5, TimeUnit.SECONDS);
        }

        private boolean wakeupCalled() {
            return wakeupCalled.get();
        }
    }

    private static class RecordingContext implements ConnectorSourceContext {
        private final List<Message<?>> messages = new ArrayList<>();
        private final List<String> events;

        private RecordingContext(List<String> events) {
            this.events = events;
        }

        @Override
        public String channelName() {
            return "audit";
        }

        @Override
        public <T> void emit(Message<T> message) {
            messages.add(message);
        }

        @Override
        public <T> void emitBatch(List<? extends Message<T>> messages) {
            events.add("dispatch");
            this.messages.addAll(messages);
        }

        private List<Message<?>> messages() {
            return List.copyOf(messages);
        }
    }

    private static final class TrackingDelivery implements ConnectorDelivery {
        private final ConnectorDelivery delegate;
        private final AtomicInteger closes;

        private TrackingDelivery(ConnectorDelivery delegate, AtomicInteger closes) {
            this.delegate = delegate;
            this.closes = closes;
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public boolean isCurrentThread() {
            return delegate.isCurrentThread();
        }

        @Override
        public void await() throws InterruptedException {
            delegate.await();
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            return delegate.await(timeout);
        }

        @Override
        public void cancel() {
            delegate.cancel();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            delegate.close();
        }
    }

    private static final class AwaitCountingDelivery implements ConnectorDelivery {
        private final ConnectorDelivery delegate;
        private final AtomicInteger timedAwaits;

        private AwaitCountingDelivery(ConnectorDelivery delegate, AtomicInteger timedAwaits) {
            this.delegate = delegate;
            this.timedAwaits = timedAwaits;
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public boolean isCurrentThread() {
            return delegate.isCurrentThread();
        }

        @Override
        public void await() throws InterruptedException {
            delegate.await();
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            timedAwaits.incrementAndGet();
            return delegate.await(timeout);
        }

        @Override
        public void cancel() {
            delegate.cancel();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class CompletionHoldingDelivery implements ConnectorDelivery {
        private final ConnectorDelivery delegate;
        private final CountDownLatch completionRelease;

        private CompletionHoldingDelivery(ConnectorDelivery delegate, CountDownLatch completionRelease) {
            this.delegate = delegate;
            this.completionRelease = completionRelease;
        }

        @Override
        public boolean isDone() {
            return delegate.isDone() && completionRelease.getCount() == 0;
        }

        @Override
        public boolean isCurrentThread() {
            return delegate.isCurrentThread();
        }

        @Override
        public void await() throws InterruptedException {
            delegate.await();
            completionRelease.await();
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            long started = System.nanoTime();
            if (!delegate.await(timeout)) {
                return false;
            }
            if (completionRelease.getCount() == 0) {
                return true;
            }
            long remaining = timeout.toNanos() - (System.nanoTime() - started);
            return remaining > 0 && completionRelease.await(remaining, TimeUnit.NANOSECONDS);
        }

        @Override
        public void cancel() {
            delegate.cancel();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static class ForwardingReservation implements ConnectorDeliveryReservation {
        private final ConnectorDeliveryReservation delegate;

        private ForwardingReservation(ConnectorDeliveryReservation delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> ConnectorDelivery start(List<? extends Message<T>> messages,
                                           long admissionBytes,
                                           Runnable delivery) {
            return delegate.start(messages, admissionBytes, delivery);
        }

        @Override
        public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                        long admissionBytes,
                                                        Runnable delivery) {
            return delegate.tryStart(messages, admissionBytes, delivery);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @FunctionalInterface
    private interface FailureHandler {
        ConnectorSourceContext.FailureResult handle(List<? extends Message<?>> messages,
                                                    int failedAttempt,
                                                    RuntimeException failure);
    }
}
