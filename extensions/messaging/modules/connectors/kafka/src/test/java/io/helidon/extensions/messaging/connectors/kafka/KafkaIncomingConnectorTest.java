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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import io.helidon.extensions.messaging.BatchDeliveryException;
import io.helidon.extensions.messaging.BatchItemOutcome;
import io.helidon.extensions.messaging.BatchItemStatus;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorDelivery;
import io.helidon.extensions.messaging.ConnectorDeliveryReservation;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.IncomingConnectorContext;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageBatch;
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
    void testConnectorType() {
        KafkaConnectorProvider provider = new KafkaConnectorProvider();

        assertThat(provider.connectorType(), is(KafkaConnectorProvider.CONNECTOR_TYPE));
    }

    @Test
    void testConnectorCreationAndDrainBeforeRunAreResourceFree() {
        AtomicInteger consumerCreations = new AtomicInteger();
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> {
            consumerCreations.incrementAndGet();
            return trackingConsumer();
        });

        IncomingConnector incoming = connector.createIncomingConnector(config());

        assertThat(consumerCreations.get(), is(0));
        incoming.drain();
        incoming.close();
        assertThat(consumerCreations.get(), is(0));
    }

    @Test
    @Timeout(value = 5)
    void testForceCloseInterruptsBlockedConsumerCreation() throws Exception {
        CountDownLatch consumerCreationStarted = new CountDownLatch(1);
        CountDownLatch releaseConsumerCreation = new CountDownLatch(1);
        CountDownLatch consumerCreationInterrupted = new CountDownLatch(1);
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> {
            consumerCreationStarted.countDown();
            try {
                releaseConsumerCreation.await();
            } catch (InterruptedException e) {
                consumerCreationInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Consumer creation was interrupted", e);
            }
            return trackingConsumer();
        });
        IncomingConnector incoming = connector.createIncomingConnector(config());
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>());
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> incoming.run(context));
        assertThat(consumerCreationStarted.await(1, TimeUnit.SECONDS), is(true));

        AtomicReference<Throwable> forceFailure = new AtomicReference<>();
        CountDownLatch forceReturned = new CountDownLatch(1);
        Thread forceThread = Thread.ofVirtual().start(() -> {
            try {
                incoming.forceClose();
            } catch (Throwable throwable) {
                forceFailure.set(throwable);
            } finally {
                forceReturned.countDown();
            }
        });

        try {
            assertThat("force close must return without waiting for consumer creation",
                       forceReturned.await(1, TimeUnit.SECONDS),
                       is(true));
            assertThat(consumerCreationInterrupted.await(1, TimeUnit.SECONDS), is(true));
            assertThat(releaseConsumerCreation.getCount(), is(1L));
        } finally {
            releaseConsumerCreation.countDown();
        }
        forceThread.join(TimeUnit.SECONDS.toMillis(1));
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(forceThread.isAlive(), is(false));
        assertThat(forceFailure.get(), nullValue());
        assertThat(sourceThread.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        incoming.close();
    }

    @Test
    @Timeout(value = 5)
    void testForceCloseInterruptsRunningGate() throws Exception {
        TrackingMockConsumer consumer = trackingConsumer();
        CountDownLatch runningGateStarted = new CountDownLatch(1);
        CountDownLatch runningGateInterrupted = new CountDownLatch(1);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public boolean awaitRunning() {
                runningGateStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                    return true;
                } catch (InterruptedException e) {
                    runningGateInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        IncomingConnector incoming = connector.createIncomingConnector(config());
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> incoming.run(context));
        assertThat(runningGateStarted.await(1, TimeUnit.SECONDS), is(true));

        incoming.forceClose();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(runningGateInterrupted.getCount(), is(0L));
        assertThat(sourceThread.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        assertThat(consumer.pollCount(), is(0));
        assertThat(consumer.closed(), is(true));
        incoming.close();
    }

    @Test
    @Timeout(value = 5)
    void testDrainLetsRunFinishOffsetCommitAndConsumerClose() throws Exception {
        CountDownLatch commitStarted = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        TrackingMockConsumer consumer = new TrackingMockConsumer() {
            @Override
            public void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets,
                                    OffsetCommitCallback callback) {
                commitStarted.countDown();
                try {
                    releaseCommit.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Commit wait was interrupted", e);
                }
                super.commitAsync(offsets, callback);
            }
        };
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        IncomingConnector incoming = connector.createIncomingConnector(config());
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>());
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> incoming.run(context));

        assertThat(commitStarted.await(5, TimeUnit.SECONDS), is(true));
        incoming.drain();
        try {
            awaitWaiting(sourceThread);
            assertThat(sourceThread.isAlive(), is(true));
            assertThat(consumer.commitCount(), is(0));
        } finally {
            releaseCommit.countDown();
        }
        sourceThread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        assertThat(consumer.commitCount(), is(1));
        assertThat(consumer.closed(), is(true));
        incoming.close();
    }

    @Test
    @Timeout(value = 5)
    void testForceCloseDoesNotWaitForBlockedCommitInitiation() throws Exception {
        CountDownLatch commitStarted = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch commitInterrupted = new CountDownLatch(1);
        TrackingMockConsumer consumer = new TrackingMockConsumer() {
            @Override
            public void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets,
                                    OffsetCommitCallback callback) {
                commitStarted.countDown();
                try {
                    releaseCommit.await();
                } catch (InterruptedException e) {
                    commitInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Commit initiation was interrupted", e);
                }
                super.commitAsync(offsets, callback);
            }
        };
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        IncomingConnector incoming = connector.createIncomingConnector(config());
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>());
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> incoming.run(context));
        assertThat(commitStarted.await(1, TimeUnit.SECONDS), is(true));

        AtomicReference<Throwable> forceFailure = new AtomicReference<>();
        CountDownLatch forceReturned = new CountDownLatch(1);
        Thread forceThread = Thread.ofVirtual().start(() -> {
            try {
                incoming.forceClose();
            } catch (Throwable throwable) {
                forceFailure.set(throwable);
            } finally {
                forceReturned.countDown();
            }
        });

        try {
            assertThat("force close must return without waiting for commit initiation",
                       forceReturned.await(1, TimeUnit.SECONDS),
                       is(true));
            assertThat(commitInterrupted.await(1, TimeUnit.SECONDS), is(true));
            assertThat(releaseCommit.getCount(), is(1L));
        } finally {
            releaseCommit.countDown();
        }
        forceThread.join(TimeUnit.SECONDS.toMillis(1));
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(forceThread.isAlive(), is(false));
        assertThat(forceFailure.get(), nullValue());
        assertThat(sourceThread.isAlive(), is(false));
        assertThat(sourceFailure.get(), nullValue());
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
        incoming.close();
    }

    @Test
    @Timeout(value = 5)
    void testSourceDoesNotPollBeforeContextAllowsRunning() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        RunningGateContext context = new RunningGateContext(new ArrayList<>());
        AtomicReference<IncomingConnectorHarness> connectorRef = new AtomicReference<>();
        consumer.afterCommit(() -> connectorRef.get().close());
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        connectorRef.set(connector);
        IncomingConnector source = connector.createIncomingConnector(config());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> source.run(context));

        assertThat(context.awaitGateReady(), is(true));

        assertThat(consumer.pollCount(), is(0));
        assertThat(context.messages(), is(List.of()));

        context.startRunning();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(context.messages().stream().map(Message::entity).toList(), is(List.of("first")));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    @Timeout(value = 5)
    void testSourceCanDrainBeforeContextAllowsRunning() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        RunningGateContext context = new RunningGateContext(new ArrayList<>());
        IncomingConnector source = connector.createIncomingConnector(config());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> source.run(context));

        assertThat(context.awaitGateReady(), is(true));
        source.drain();
        context.cancel();
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>());
        IncomingConnector source = connector.createIncomingConnector(
                config(Map.of("max.poll.interval.ms", "60000")));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> source.run(context));

        assertThat(consumer.awaitPoll(), is(true));

        source.drain();
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>());
        IncomingConnector source = connector.createIncomingConnector(config());
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> source.run(context));

        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(consumer.pollCount(), is(0));
        assertThat(thread.isAlive(), is(false));
        assertThat(sourceFailure.get(), instanceOf(MessagingException.class));
        assertThat(sourceFailure.get().getCause(), sameInstance(metadataFailure));
    }

    @Test
    void testDispatchesPollAsBatchWithUtf8HeadersThenCommits() {
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
        AtomicReference<IncomingConnectorHarness> connectorRef = new AtomicReference<>();
        consumer.afterCommit(() -> {
            events.add("commit");
            connectorRef.get().close();
        });
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        connectorRef.set(connector);

        connector.createIncomingConnector(config()).run(context);

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
    void testKeepsPollingWhileRuntimeDeliveryIsBlockedAndCommitsOnlyAfterSettlement() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch allowSettlement = new CountDownLatch(1);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public String channel() {
                return "audit";
            }

            @Override
            protected void processBatch(MessageBatch<?> batch) {
                deliveryStarted.countDown();
                try {
                    allowSettlement.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException("Runtime delivery interrupted", e);
                }
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        consumer.afterCommit(connector::close);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> connector.createIncomingConnector(config()).run(context));

        try {
            assertThat(deliveryStarted.await(5, TimeUnit.SECONDS), is(true));
            int pollsAtDeliveryStart = consumer.pollCount();
            assertThat("consumer owner must poll while downstream settlement is blocked",
                       consumer.awaitPollCount(pollsAtDeliveryStart + 1),
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
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public String channel() {
                return "audit";
            }

            @Override
            protected void processBatch(MessageBatch<?> batch) {
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> connector.createIncomingConnector(config()).run(context));

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
    void testStaleInterruptedDeliveryIsCancelledWithoutCommit() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch partitionRevoked = new CountDownLatch(1);
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch handlerAborted = new CountDownLatch(1);
        CountDownLatch blockHandler = new CountDownLatch(1);
        AtomicInteger dispatchAttempts = new AtomicInteger();
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public String channel() {
                return "audit";
            }

            @Override
            protected void processBatch(MessageBatch<?> batch) {
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
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> connector.createIncomingConnector(config()).run(context));

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
            assertThat("stale delivery must not restart", dispatchAttempts.get(), is(1));
            assertThat(consumer.commitCount(), is(0));
        } finally {
            blockHandler.countDown();
            connector.close();
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
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
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public String channel() {
                return "audit";
            }

            @Override
            protected void processBatch(MessageBatch<?> batch) {
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
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                ConnectorDeliveryReservation delegate = super.tryReserveDelivery()
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
                        return super.tryStart(batch)
                                .map(deliveryTask -> new TrackingDelivery(deliveryTask, deliveryCloses));
                    }
                });
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> connector.createIncomingConnector(config(Duration.ofMillis(25))).run(context));

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
    void testRetainsEveryPartitionPollAcrossOpaqueRuntimeRetries() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer,
                        record(TOPIC_PARTITION, 4, "first", new RecordHeaders()),
                        record(TOPIC_PARTITION, 5, "second", new RecordHeaders()),
                        record(SECOND_TOPIC_PARTITION, 9, "third", new RecordHeaders()),
                        record(SECOND_TOPIC_PARTITION, 10, "fourth", new RecordHeaders()));
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch completeDelivery = new CountDownLatch(1);
        List<MessageBatch<?>> runtimeAttempts = new ArrayList<>();
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            protected void processBatch(MessageBatch<?> batch) {
                runtimeAttempts.add(batch);
                deliveryStarted.countDown();
                try {
                    completeDelivery.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException("Runtime delivery interrupted", e);
                }
                runtimeAttempts.add(batch);
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        consumer.afterCommit(connector::close);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> connector.createIncomingConnector(config()).run(context));

        try {
            assertThat(deliveryStarted.await(5, TimeUnit.SECONDS), is(true));
            assertThat(runtimeAttempts.getFirst().size(), is(4));
            assertThat(runtimeAttempts.getFirst().messages().stream().allMatch(KafkaMessage.class::isInstance),
                       is(true));
            assertThat(consumer.commitCount(), is(0));
        } finally {
            completeDelivery.countDown();
        }
        sourceThread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(runtimeAttempts.size(), is(2));
        assertThat(runtimeAttempts.get(1), sameInstance(runtimeAttempts.getFirst()));
        assertThat(consumer.committedOffsets().get(TOPIC_PARTITION).offset(), is(6L));
        assertThat(consumer.committedOffsets().get(SECOND_TOPIC_PARTITION).offset(), is(11L));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    @Timeout(value = 5)
    void testUnsettledRuntimeFailureCommitsOnlyContiguousPartitionPrefixes() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer,
                        record(TOPIC_PARTITION, 4, "p0-first", new RecordHeaders(), 14),
                        record(TOPIC_PARTITION, 5, "p0-failed", new RecordHeaders(), 15),
                        record(TOPIC_PARTITION, 6, "p0-later-success", new RecordHeaders(), 16),
                        record(SECOND_TOPIC_PARTITION, 9, "p1-first", new RecordHeaders(), 19),
                        record(SECOND_TOPIC_PARTITION, 10, "p1-failed", new RecordHeaders(), 20));
        IllegalStateException itemFailure = new IllegalStateException("runtime delivery failed");
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            protected void processBatch(MessageBatch<?> batch) {
                List<BatchItemOutcome> outcomes = new ArrayList<>(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    KafkaMessage<?, ?> message = (KafkaMessage<?, ?>) batch.get(i);
                    long offset = message.offset().orElseThrow();
                    outcomes.add(offset == 4 || offset == 6 || offset == 9
                                         ? BatchItemOutcome.succeeded(i)
                                         : BatchItemOutcome.failed(i, itemFailure));
                }
                throw new BatchDeliveryException("Expected runtime terminal failure", batch, outcomes, itemFailure);
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);

        BatchDeliveryException failure = assertThrows(
                BatchDeliveryException.class,
                () -> connector.createIncomingConnector(config()).run(context));

        assertThat(failure.getCause(), sameInstance(itemFailure));
        assertThat(failure.batch().size(), is(5));
        assertThat(consumer.committedOffsets().get(TOPIC_PARTITION).offset(), is(5L));
        assertThat(consumer.committedOffsets().get(SECOND_TOPIC_PARTITION).offset(), is(10L));
        assertThat(consumer.committedOffsets().get(TOPIC_PARTITION).leaderEpoch(), is(Optional.of(14)));
        assertThat(consumer.committedOffsets().get(SECOND_TOPIC_PARTITION).leaderEpoch(), is(Optional.of(19)));
        assertThat(consumer.seekOffsets().get(TOPIC_PARTITION), is(5L));
        assertThat(consumer.seekOffsets().get(SECOND_TOPIC_PARTITION), is(10L));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    @Timeout(value = 5)
    void testPartialSettlementFailureIsSuppressedOnRuntimeDeliveryFailure() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer,
                        record(TOPIC_PARTITION, 4, "succeeded", new RecordHeaders()),
                        record(TOPIC_PARTITION, 5, "failed", new RecordHeaders()));
        IllegalStateException itemFailure = new IllegalStateException("runtime delivery failed");
        IllegalStateException settlementFailure = new IllegalStateException("partial commit failed");
        consumer.failCommit(settlementFailure);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            protected void processBatch(MessageBatch<?> batch) {
                throw new BatchDeliveryException(
                        "Expected runtime terminal failure",
                        batch,
                        List.of(BatchItemOutcome.succeeded(0), BatchItemOutcome.failed(1, itemFailure)),
                        itemFailure);
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);

        BatchDeliveryException failure = assertThrows(
                BatchDeliveryException.class,
                () -> connector.createIncomingConnector(config()).run(context));

        assertThat(failure.getCause(), sameInstance(itemFailure));
        assertThat(failure.getSuppressed().length, is(1));
        assertThat(failure.getSuppressed()[0], sameInstance(settlementFailure));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.seekOffsets().get(TOPIC_PARTITION), is(4L));
    }

    @Test
    @Timeout(value = 5)
    void testRuntimeTerminalFailureAdvancesSucceededPrefixBeforePropagating() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer,
                        record(TOPIC_PARTITION, 4, "handled", new RecordHeaders()),
                        record(TOPIC_PARTITION, 5, "settled", new RecordHeaders()),
                        record(TOPIC_PARTITION, 6, "failed", new RecordHeaders()),
                        record(TOPIC_PARTITION, 7, "not-attempted", new RecordHeaders()));
        IllegalStateException runtimeFailure = new IllegalStateException("terminal handling failed");
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            protected void processBatch(MessageBatch<?> batch) {
                throw new BatchDeliveryException(
                        "Expected runtime terminal failure",
                        batch,
                        List.of(BatchItemOutcome.succeeded(0),
                                BatchItemOutcome.succeeded(1),
                                BatchItemOutcome.failed(2, runtimeFailure),
                                BatchItemOutcome.notAttempted(3)),
                        runtimeFailure);
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);

        BatchDeliveryException failure = assertThrows(
                BatchDeliveryException.class,
                () -> connector.createIncomingConnector(config()).run(context));

        assertThat(failure.getCause(), sameInstance(runtimeFailure));
        assertThat(consumer.committedOffsets().get(TOPIC_PARTITION).offset(), is(6L));
        assertThat(consumer.seekOffsets().get(TOPIC_PARTITION), is(6L));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    void testRuntimeTerminalFailureDoesNotCommitUnresolvedPoll() {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(7, "poison", new RecordHeaders()));
        IllegalStateException processingFailure = new IllegalStateException("runtime delivery failed");
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            protected void processBatch(MessageBatch<?> batch) {
                throw processingFailure;
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);

        BatchDeliveryException failure = assertThrows(
                BatchDeliveryException.class,
                () -> connector.createIncomingConnector(config()).run(context));

        assertThat(failure.getCause(), sameInstance(processingFailure));
        assertThat(failure.outcome(0).status(), is(BatchItemStatus.INDETERMINATE));
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        consumer.afterCommit(connector::close);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            protected void processBatch(MessageBatch<?> batch) {
                dispatches.incrementAndGet();
            }
        };

        connector.createIncomingConnector(config()).run(context);

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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            protected void processBatch(MessageBatch<?> batch) {
                dispatches.incrementAndGet();
            }
        };

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createIncomingConnector(
                                config(Map.of(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "25")))
                        .run(context));

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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            protected void processBatch(MessageBatch<?> batch) {
                dispatches.incrementAndGet();
            }
        };

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createIncomingConnector(config()).run(context));

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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public String channel() {
                return "audit";
            }

            @Override
            protected void processBatch(MessageBatch<?> batch) {
                throw processingFailure;
            }
        };

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> connector.createIncomingConnector(config()).run(context));

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
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                ConnectorDeliveryReservation delegate = super
                        .tryReserveDelivery()
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
                        if (admissionAttempts.incrementAndGet() < 3) {
                            return Optional.empty();
                        }
                        pollsAtAdmission.set(consumer.pollCount());
                        return super.tryStart(batch);
                    }
                });
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        consumer.afterCommit(connector::close);

        connector.createIncomingConnector(config()).run(context);

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
        AtomicInteger admissionAttempts = new AtomicInteger();
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                ConnectorDeliveryReservation delegate = super
                        .tryReserveDelivery()
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
                        if (admissionAttempts.incrementAndGet() == 3) {
                            throw new MessagingRejectedException("audit",
                                                                 MessagingRejectedException.Reason.TIMEOUT);
                        }
                        return Optional.empty();
                    }
                });
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);

        MessagingRejectedException failure = assertThrows(
                MessagingRejectedException.class,
                () -> connector.createIncomingConnector(config()).run(context));

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
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                reservationAttempted.countDown();
                return Optional.empty();
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> connector.createIncomingConnector(config()).run(context));

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
        AtomicInteger reservationAttempts = new AtomicInteger();
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                if (reservationAttempts.incrementAndGet() == 3) {
                    throw new MessagingRejectedException("audit", MessagingRejectedException.Reason.TIMEOUT);
                }
                return Optional.empty();
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);

        MessagingRejectedException failure = assertThrows(
                MessagingRejectedException.class,
                () -> connector.createIncomingConnector(config()).run(context));

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
        AtomicReference<IncomingConnectorHarness> connectorRef = new AtomicReference<>();
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        connectorRef.set(connector);

        connector.createIncomingConnector(config()).run(context);

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
        AtomicReference<IncomingConnectorHarness> connectorRef = new AtomicReference<>();
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                if (reservationAttempts.incrementAndGet() > 1) {
                    connectorRef.get().close();
                    return Optional.empty();
                }
                return Optional.of(unusedReservation(reservationCloses));
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        connectorRef.set(connector);

        connector.createIncomingConnector(config()).run(context);

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
        AtomicReference<IncomingConnectorHarness> connectorRef = new AtomicReference<>();
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        connectorRef.set(connector);

        connector.createIncomingConnector(config()).run(context);

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
        AtomicInteger actualMessages = new AtomicInteger();
        AtomicReference<ConnectorDelivery> trackedLease = new AtomicReference<>();
        consumer.beforeNextPoll(() -> assertThat("normal poll must run inside an open reservation",
                                                  reservationOpen.get(),
                                                  is(true)));
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public String channel() {
                return "audit";
            }

            @Override
            protected void processBatch(MessageBatch<?> batch) {
            }

            @Override
            public int maxDeliveryMessages() {
                return 2;
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                reservedMessages.set(maxDeliveryMessages());
                reservationOpen.set(true);
                ConnectorDeliveryReservation delegate = super.tryReserveDelivery()
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
                        actualMessages.set(batch.size());
                        Optional<ConnectorDelivery> started = super.tryStart(batch);
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        consumer.afterCommit(() -> {
            assertThat("commit callback must run before the retained-delivery lease is released",
                       leaseCloses.get(),
                       is(0));
            connector.close();
        });

        connector.createIncomingConnector(config()).run(context);

        assertThat(trackedLease.get().isDone(), is(true));
        assertThat(reservedMessages.get(), is(2));
        assertThat(actualMessages.get() <= reservedMessages.get(), is(true));
        assertThat(leaseCloses.get(), is(1));
        assertThat(consumer.commitCount(), is(1));
    }

    @Test
    void testPostPollSafetyCheckEnforcesMessageLimitWhenKafkaAcquisitionHintIsExceeded() {
        TrackingMockConsumer messageLimitedConsumer = trackingConsumer();
        scheduleRecords(messageLimitedConsumer,
                        record(0, "first", new RecordHeaders()),
                        record(1, "second", new RecordHeaders()));
        IncomingConnectorContext messageLimitedContext = new RecordingContext(new ArrayList<>()) {
            @Override
            public int maxDeliveryMessages() {
                return 1;
            }
        };
        AtomicReference<Map<String, Object>> messageAcquisitionProperties = new AtomicReference<>();
        IncomingConnectorHarness messageLimitedConnector = new IncomingConnectorHarness(properties -> {
            messageAcquisitionProperties.set(properties);
            return messageLimitedConsumer;
        });

        MessagingRejectedException messageFailure = assertThrows(
                MessagingRejectedException.class,
                () -> messageLimitedConnector.createIncomingConnector(config()).run(messageLimitedContext));

        assertThat(messageFailure.reason(), is(MessagingRejectedException.Reason.OVERSIZED));
        assertThat(messageAcquisitionProperties.get().get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), is(1));
        assertThat(messageLimitedConsumer.commitCount(), is(0));
    }

    @Test
    @Timeout(value = 5)
    void testCloseCancelsActiveRuntimeDeliveryWithoutCommit() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch deliveryCancelled = new CountDownLatch(1);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            protected void processBatch(MessageBatch<?> batch) {
                deliveryStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    deliveryCancelled.countDown();
                    Thread.currentThread().interrupt();
                    throw new MessagingException("Runtime delivery cancelled", e);
                }
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> connector.createIncomingConnector(config()).run(context));

        assertThat(deliveryStarted.await(5, TimeUnit.SECONDS), is(true));
        connector.close();
        sourceThread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(deliveryCancelled.await(5, TimeUnit.SECONDS), is(true));
        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
    }


    @Test
    @Timeout(value = 5)
    void testCloseBeforeCommitInitiationPreventsCommit() throws Exception {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowSettlement = new CountDownLatch(1);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public String channel() {
                return "audit";
            }

            @Override
            protected void processBatch(MessageBatch<?> batch) {
                handlerStarted.countDown();
                try {
                    allowSettlement.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException("Handler interrupted", e);
                }
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> connector.createIncomingConnector(config()).run(context));

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
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public String channel() {
                return "audit";
            }

            @Override
            protected void processBatch(MessageBatch<?> batch) {
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
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                ConnectorDeliveryReservation delegate = super.tryReserveDelivery()
                        .orElseThrow();
                return Optional.of(new ForwardingReservation(delegate) {
                    @Override
                    public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
                        Optional<ConnectorDelivery> admitted = super.tryStart(batch)
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> connector.createIncomingConnector(config(Duration.ofSeconds(1))).run(context));

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
        AtomicReference<IncomingConnectorHarness> connectorRef = new AtomicReference<>();
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>()) {
            @Override
            public String channel() {
                return "audit";
            }

            @Override
            protected void processBatch(MessageBatch<?> batch) {
                connectorRef.get().close();
                closeReturned.countDown();
            }
        };
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        connectorRef.set(connector);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> connector.createIncomingConnector(config()).run(context));

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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>());
        IncomingConnector source = connector.createIncomingConnector(config());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> source.run(context));
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> {
            consumerCreations.incrementAndGet();
            throw new IllegalStateException("consumer creation failed");
        });
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>());
        IncomingConnector source = connector.createIncomingConnector(config());

        assertThrows(MessagingException.class, () -> source.run(context));
        connector.close();

        assertThrows(IllegalStateException.class, () -> source.run(context));
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
        IncomingConnectorHarness connector = new IncomingConnectorHarness(ignored -> consumer);
        IncomingConnectorContext context = new RecordingContext(new ArrayList<>());
        IncomingConnector source = connector.createIncomingConnector(config(Duration.ofMillis(100)));
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(() -> source.run(context));
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
        assertThat(sourceFailure.get().getCause(), sameInstance(closeFailure));
        assertThat(firstCloseFailure.get(), nullValue());
        assertThat(secondCloseFailure.get(), sameInstance(sourceFailure.get()));

        source.close();
        assertThat(closeAttempts.get(), is(2));
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
                .connector(KafkaConnectorProvider.CONNECTOR_TYPE)
                .bootstrapServers("localhost:9092")
                .topic(TOPIC)
                .groupId("audit-test")
                .pollTimeout(Duration.ofMillis(10))
                .closeTimeout(closeTimeout)
                .properties(properties)
                .build();
    }

    private static ConnectorDeliveryReservation unusedReservation(AtomicInteger closes) {
        return new ConnectorDeliveryReservation() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public ConnectorDelivery start(MessageBatch<?> batch) {
                throw new AssertionError("An empty Kafka poll must not start its reservation");
            }

            @Override
            public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
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
        return record(partition, offset, value, headers, Optional.empty());
    }

    private static ConsumerRecord<Object, Object> record(TopicPartition partition,
                                                         long offset,
                                                         Object value,
                                                         Headers headers,
                                                         int leaderEpoch) {
        return record(partition, offset, value, headers, Optional.of(leaderEpoch));
    }

    private static ConsumerRecord<Object, Object> record(TopicPartition partition,
                                                         long offset,
                                                         Object value,
                                                         Headers headers,
                                                         Optional<Integer> leaderEpoch) {
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
                                    leaderEpoch);
    }

    private static class TrackingMockConsumer extends MockConsumer<Object, Object> {
        private final AtomicBoolean wakeupCalled = new AtomicBoolean();
        private final AtomicBoolean blockNextAssignment = new AtomicBoolean();
        private final CountDownLatch assignmentBlocked = new CountDownLatch(1);
        private final CountDownLatch releaseAssignment = new CountDownLatch(1);
        private final List<Integer> pollCountsAtCommitInitiation = new ArrayList<>();
        private final List<Map<TopicPartition, OffsetAndMetadata>> commitOffsets = new ArrayList<>();
        private final Map<TopicPartition, Long> seekOffsets = new LinkedHashMap<>();
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
        public void seek(TopicPartition partition, long offset) {
            super.seek(partition, offset);
            stateLock.lock();
            try {
                seekOffsets.put(partition, offset);
            } finally {
                stateLock.unlock();
            }
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

        private Map<TopicPartition, Long> seekOffsets() {
            stateLock.lock();
            try {
                return Map.copyOf(seekOffsets);
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

    private static class RecordingContext implements IncomingConnectorContext {
        private final List<Message<?>> messages = new ArrayList<>();
        private final List<String> events;

        private RecordingContext(List<String> events) {
            this.events = events;
        }

        @Override
        public String channel() {
            return "audit";
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery() {
            return new RuntimeReservation(this::createDelivery);
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
            return Optional.of(reserveDelivery());
        }

        protected void processBatch(MessageBatch<?> batch) {
            events.add("dispatch");
            this.messages.addAll(batch.messages());
        }

        protected ConnectorDelivery createDelivery(MessageBatch<?> batch) {
            return new RuntimeDelivery(batch, () -> processBatch(batch));
        }

        List<Message<?>> messages() {
            return List.copyOf(messages);
        }
    }

    private static final class RuntimeReservation implements ConnectorDeliveryReservation {
        private final Function<MessageBatch<?>, ConnectorDelivery> deliveryFactory;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private RuntimeReservation(Function<MessageBatch<?>, ConnectorDelivery> deliveryFactory) {
            this.deliveryFactory = deliveryFactory;
        }

        @Override
        public ConnectorDelivery start(MessageBatch<?> batch) {
            if (!terminal.compareAndSet(false, true)) {
                throw new IllegalStateException("Reservation is no longer available");
            }
            return deliveryFactory.apply(batch);
        }

        @Override
        public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
            return Optional.of(start(batch));
        }

        @Override
        public void close() {
            terminal.set(true);
        }
    }

    private static final class RuntimeDelivery implements ConnectorDelivery {
        private final MessageBatch<?> batch;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch completion = new CountDownLatch(1);
        private final Thread owner;

        private RuntimeDelivery(MessageBatch<?> batch, Runnable action) {
            this.batch = batch;
            this.owner = Thread.ofVirtual()
                    .name("test-messaging-delivery")
                    .unstarted(() -> {
                        try {
                            action.run();
                        } catch (BatchDeliveryException e) {
                            failure.set(e);
                        } catch (RuntimeException e) {
                            failure.set(BatchDeliveryException.indeterminate("Runtime delivery", batch, e));
                        } catch (Throwable t) {
                            failure.set(t);
                        } finally {
                            completion.countDown();
                        }
                    });
            owner.start();
        }

        @Override
        public boolean isDone() {
            return completion.getCount() == 0;
        }

        @Override
        public boolean isCurrentThread() {
            return Thread.currentThread() == owner;
        }

        @Override
        public void await() throws InterruptedException {
            completion.await();
            rethrowFailure();
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            if (!completion.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return false;
            }
            rethrowFailure();
            return true;
        }

        @Override
        public void cancel() {
            owner.interrupt();
        }

        @Override
        public void close() {
            if (!isDone()) {
                cancel();
            }
        }

        private void rethrowFailure() {
            Throwable throwable = failure.get();
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            if (throwable != null) {
                throw new AssertionError("Unexpected checked delivery failure", throwable);
            }
        }
    }

    private static final class RunningGateContext extends RecordingContext {
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch running = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private RunningGateContext(List<String> events) {
            super(events);
        }

        @Override
        public boolean awaitRunning() {
            ready.countDown();
            try {
                running.await();
                return !cancelled.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private boolean awaitGateReady() throws InterruptedException {
            return ready.await(1, TimeUnit.SECONDS);
        }

        private void startRunning() {
            running.countDown();
        }

        private void cancel() {
            cancelled.set(true);
            running.countDown();
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
        public ConnectorDelivery start(MessageBatch<?> batch) {
            return delegate.start(batch);
        }

        @Override
        public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
            return delegate.tryStart(batch);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class IncomingConnectorHarness {
        private final KafkaIncomingConnector connector;
        private final AtomicReference<IncomingConnector> incoming = new AtomicReference<>();

        private IncomingConnectorHarness(KafkaIncomingConnector.ConsumerFactory consumerFactory) {
            connector = new KafkaIncomingConnector(consumerFactory);
        }

        private IncomingConnector createIncomingConnector(KafkaConnectorConfig config) {
            IncomingConnector created = connector.createIncomingConnector(config);
            if (!incoming.compareAndSet(null, created)) {
                throw new IllegalStateException("Incoming connector harness supports one connector");
            }
            return created;
        }

        private void close() {
            IncomingConnector current = incoming.get();
            if (current != null) {
                current.close();
            }
        }
    }

}
