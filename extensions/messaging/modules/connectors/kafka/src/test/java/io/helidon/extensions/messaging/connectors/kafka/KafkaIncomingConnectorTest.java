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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorSource;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.DeadLetterMessage;
import io.helidon.extensions.messaging.FailurePolicy;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
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
    void testCloseBeforeCommitInitiationPreventsCommit() throws InterruptedException {
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

            connector.close();
            assertThat(consumer.commitInitiationCount(), is(0));
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
    @Timeout(value = 5)
    void testCloseReportsNonCooperativeDeliveryUntilItActuallyFinishes() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        AtomicInteger interrupts = new AtomicInteger();
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
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> sourceFailure.set(throwable))
                .start(connector.createSource(config(Duration.ofMillis(25)), context));

        try {
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), is(true));

            MessagingException firstClose = assertThrows(MessagingException.class, connector::close);
            assertThat(firstClose.getMessage().contains("close timed out"), is(true));
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
            releaseHandler.countDown();
        }

        assertThat(handlerFinished.await(5, TimeUnit.SECONDS), is(true));
        connector.close();
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
        private int pollCount;
        private int commitInitiationCount;
        private int commitCount;
        private Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of();
        private Runnable afterCommit = () -> { };
        private boolean suppressNextCommitCallback;
        private RuntimeException nextCommitFailure;
        private RuntimeException commitFailure;
        private RuntimeException closeFailure;

        private TrackingMockConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public ConsumerRecords<Object, Object> poll(Duration timeout) {
            synchronized (this) {
                pollCount++;
                notifyAll();
            }
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
        public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (commitFailure != null) {
                throw commitFailure;
            }
            super.commitSync(offsets);
            committedOffsets = Map.copyOf(offsets);
            commitCount++;
            afterCommit.run();
        }

        @Override
        public synchronized void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets,
                                             OffsetCommitCallback callback) {
            commitInitiationCount++;
            pollCountsAtCommitInitiation.add(pollCount);
            commitOffsets.add(Map.copyOf(offsets));
            if (suppressNextCommitCallback) {
                suppressNextCommitCallback = false;
                return;
            }
            RuntimeException currentFailure = nextCommitFailure;
            nextCommitFailure = null;
            if (currentFailure == null) {
                currentFailure = commitFailure;
            }
            if (currentFailure != null) {
                callback.onComplete(offsets, currentFailure);
                return;
            }
            super.commitAsync(offsets, (committed, failure) -> {
                if (failure == null) {
                    committedOffsets = Map.copyOf(committed);
                    commitCount++;
                }
                callback.onComplete(committed, failure);
                if (failure == null) {
                    afterCommit.run();
                }
            });
        }

        @Override
        public void close(Duration timeout) {
            super.close(timeout);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        @Override
        public void wakeup() {
            wakeupCalled.set(true);
            super.wakeup();
        }

        private synchronized int commitCount() {
            return commitCount;
        }

        private synchronized int commitInitiationCount() {
            return commitInitiationCount;
        }

        private synchronized List<Integer> pollCountsAtCommitInitiation() {
            return List.copyOf(pollCountsAtCommitInitiation);
        }

        private synchronized List<Map<TopicPartition, OffsetAndMetadata>> commitOffsets() {
            return List.copyOf(commitOffsets);
        }

        private synchronized int pollCount() {
            return pollCount;
        }

        private synchronized boolean awaitPollCount(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pollCount < expected) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }

        private synchronized Map<TopicPartition, OffsetAndMetadata> committedOffsets() {
            return committedOffsets;
        }

        private synchronized void afterCommit(Runnable afterCommit) {
            this.afterCommit = afterCommit;
        }

        private synchronized void failCommit(RuntimeException commitFailure) {
            this.commitFailure = commitFailure;
        }

        private synchronized void failNextCommit(RuntimeException commitFailure) {
            this.nextCommitFailure = commitFailure;
        }

        private synchronized void suppressNextCommitCallback() {
            this.suppressNextCommitCallback = true;
        }

        private synchronized void failClose(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
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

    @FunctionalInterface
    private interface FailureHandler {
        ConnectorSourceContext.FailureResult handle(List<? extends Message<?>> messages,
                                                    int failedAttempt,
                                                    RuntimeException failure);
    }
}
