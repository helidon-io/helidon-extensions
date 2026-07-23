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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorSource;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.Message;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

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
        scheduleRecords(consumer,
                        record(0, "first", new RecordHeaders()
                                .add("null-header", null)
                                .add("trace-id", "old".getBytes(StandardCharsets.UTF_8))
                                .add("trace-id", "Příliš žluťoučký".getBytes(StandardCharsets.UTF_8))),
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
        assertThat(consumer.commitCount(), is(1));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testRewindsEveryPartitionAndRedeliversFailedPollBeforeCommit() {
        TrackingMockConsumer consumer = trackingConsumer();
        ConsumerRecord<Object, Object> first = record(TOPIC_PARTITION, 4, "first", new RecordHeaders());
        ConsumerRecord<Object, Object> second = record(TOPIC_PARTITION, 5, "second", new RecordHeaders());
        ConsumerRecord<Object, Object> third = record(SECOND_TOPIC_PARTITION, 9, "third", new RecordHeaders());
        ConsumerRecord<Object, Object> fourth = record(SECOND_TOPIC_PARTITION, 10, "fourth", new RecordHeaders());
        scheduleRecords(consumer, first, second, third, fourth);
        List<List<String>> attempts = new ArrayList<>();
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
            public <T> void emitBatch(List<Message<T>> messages) {
                attempts.add(messages.stream()
                                     .map(message -> String.valueOf(message.entity()))
                                     .sorted()
                                     .toList());
                if (attempts.size() == 1) {
                    scheduleReplay(consumer, first, second, third, fourth);
                    throw new IllegalStateException("dispatch failed");
                }
            }
        };
        AtomicReference<KafkaIncomingConnector> connectorRef = new AtomicReference<>();
        consumer.afterCommit(() -> connectorRef.get().close());
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        connectorRef.set(connector);

        connector.createSource(config(Duration.ofNanos(1)), context).run();

        List<String> expectedAttempt = List.of("first", "fourth", "second", "third");
        assertThat(attempts, is(List.of(expectedAttempt, expectedAttempt)));
        assertThat(consumer.seeks(), is(Map.of(TOPIC_PARTITION, 4L,
                                              SECOND_TOPIC_PARTITION, 9L)));
        assertThat(consumer.committedOffsets().get(TOPIC_PARTITION).offset(), is(6L));
        assertThat(consumer.committedOffsets().get(SECOND_TOPIC_PARTITION).offset(), is(11L));
        assertThat(consumer.commitCount(), is(1));
        assertThat(consumer.closed(), is(true));
    }

    @Test
    void testCloseWakesRedeliveryDelayWithoutCommitting() throws InterruptedException {
        TrackingMockConsumer consumer = trackingConsumer();
        scheduleRecords(consumer, record(0, "first", new RecordHeaders()));
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
            public <T> void emitBatch(List<Message<T>> messages) {
                throw new IllegalStateException("dispatch failed");
            }
        };
        KafkaIncomingConnector connector = new KafkaIncomingConnector(ignored -> consumer);
        ConnectorSource source = connector.createSource(config(Duration.ofHours(1)), context);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(source);
        assertThat(consumer.awaitRewind(), is(true));

        connector.close();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive(), is(false));
        assertThat(consumer.wakeupCalled(), is(true));
        assertThat(consumer.commitCount(), is(0));
        assertThat(consumer.closed(), is(true));
        assertThat(failure.get(), nullValue());
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
        return config(Duration.ofSeconds(1));
    }

    private static KafkaConnectorConfig config(Duration redeliveryDelay) {
        return KafkaConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel("audit")
                .connector(KafkaOutgoingConnector.CONNECTOR)
                .bootstrapServers("localhost:9092")
                .topic(TOPIC)
                .groupId("audit-test")
                .pollTimeout(Duration.ofMillis(10))
                .redeliveryDelay(redeliveryDelay)
                .closeTimeout(Duration.ofSeconds(1))
                .build();
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

    @SafeVarargs
    private static void scheduleReplay(MockConsumer<Object, Object> consumer,
                                       ConsumerRecord<Object, Object>... records) {
        consumer.schedulePollTask(() -> {
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
        private final Set<TopicPartition> seenSeeks = new HashSet<>();
        private final Map<TopicPartition, Long> seeks = new LinkedHashMap<>();
        private final CountDownLatch rewindCalled = new CountDownLatch(1);
        private final AtomicBoolean wakeupCalled = new AtomicBoolean();
        private int commitCount;
        private Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of();
        private Runnable afterCommit = () -> { };

        private TrackingMockConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public synchronized void seek(TopicPartition partition, long offset) {
            super.seek(partition, offset);
            if (!seenSeeks.add(partition)) {
                seeks.put(partition, offset);
                rewindCalled.countDown();
            }
        }

        @Override
        public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            super.commitSync(offsets);
            committedOffsets = Map.copyOf(offsets);
            commitCount++;
            afterCommit.run();
        }

        @Override
        public void wakeup() {
            wakeupCalled.set(true);
            super.wakeup();
        }

        private synchronized int commitCount() {
            return commitCount;
        }

        private synchronized Map<TopicPartition, Long> seeks() {
            return Map.copyOf(seeks);
        }

        private synchronized Map<TopicPartition, OffsetAndMetadata> committedOffsets() {
            return committedOffsets;
        }

        private synchronized void afterCommit(Runnable afterCommit) {
            this.afterCommit = afterCommit;
        }

        private boolean awaitRewind() throws InterruptedException {
            return rewindCalled.await(5, TimeUnit.SECONDS);
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

    private static final class RecordingContext implements ConnectorSourceContext {
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
        public <T> void emitBatch(List<Message<T>> messages) {
            events.add("dispatch");
            this.messages.addAll(messages);
        }

        private List<Message<?>> messages() {
            return List.copyOf(messages);
        }
    }
}
