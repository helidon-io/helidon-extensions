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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.DeadLetterMessage;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.OutgoingEndpoint;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaOutgoingConnectorTest {
    private static final String TOPIC = "audit-events";

    @Test
    void testConnectorType() {
        KafkaConnectorProvider provider = new KafkaConnectorProvider();

        assertThat(provider.connectorType(), is(KafkaConnectorProvider.CONNECTOR_TYPE));
    }

    @Test
    void testSendsPayloadTopicAndUtf8Headers() {
        MockProducer<Object, Object> producer = mockProducer(true);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);

        start(connector, config())
                .send(Message.builder("audit event")
                              .header("trace-id", "Příliš žluťoučký")
                              .build());

        assertThat(producer.history().size(), is(1));
        ProducerRecord<Object, Object> record = producer.history().get(0);
        assertThat(record.topic(), is(TOPIC));
        assertThat(record.value(), is("audit event"));
        Header header = record.headers().lastHeader("trace-id");
        assertThat(new String(header.value(), StandardCharsets.UTF_8), is("Příliš žluťoučký"));
    }

    @Test
    void testSendsKafkaMessageKeyAndOrderedNativeHeaders() {
        MockProducer<Object, Object> producer = mockProducer(true);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        byte[] binaryHeader = new byte[] {0x00, (byte) 0xFF};
        KafkaMessage<String, String> message = KafkaMessage.<String, String>builder("audit-key", "audit event")
                .header("trace-id", "first")
                .header("trace-id", "second")
                .rawHeader("binary", binaryHeader)
                .rawHeader("null-header", null)
                .build();
        binaryHeader[0] = 0x7F;

        start(connector, config()).send(message);

        assertThat(producer.history().size(), is(1));
        ProducerRecord<Object, Object> record = producer.history().getFirst();
        assertThat(record.topic(), is(TOPIC));
        assertThat(record.key(), is("audit-key"));
        assertThat(record.value(), is("audit event"));
        Header[] headers = record.headers().toArray();
        assertThat(List.of(headers).stream().map(Header::key).toList(),
                   is(List.of("trace-id", "trace-id", "binary", "null-header")));
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), headers[0].value());
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), headers[1].value());
        assertArrayEquals(new byte[] {0x00, (byte) 0xFF}, headers[2].value());
        assertThat(headers[3].value(), nullValue());
        assertThat(message.header("trace-id").orElseThrow(), is("second"));
        assertThat(message.header("null-header"), is(Optional.empty()));
    }

    @Test
    void testResendsIncomingKafkaMessageToConfiguredTopicWithoutSourcePlacement() {
        MockProducer<Object, Object> producer = mockProducer(true);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        ConsumerRecord<String, String> sourceRecord = new ConsumerRecord<>("source-topic",
                                                                            7,
                                                                            42,
                                                                            987_654_321L,
                                                                            TimestampType.LOG_APPEND_TIME,
                                                                            ConsumerRecord.NULL_SIZE,
                                                                            ConsumerRecord.NULL_SIZE,
                                                                            "source-key",
                                                                            "audit event",
                                                                            new RecordHeaders()
                                                                                    .add("source", new byte[] {0x01}),
                                                                            Optional.of(9));

        start(connector, config()).send(KafkaMessageImpl.create(sourceRecord));

        ProducerRecord<Object, Object> record = producer.history().getFirst();
        assertThat(record.topic(), is(TOPIC));
        assertThat(record.partition(), nullValue());
        assertThat(record.timestamp(), nullValue());
        assertThat(record.key(), is("source-key"));
        assertThat(record.value(), is("audit event"));
        assertArrayEquals(new byte[] {0x01}, record.headers().lastHeader("source").value());
    }

    @Test
    void testSendsDeadLetterKafkaMessageWithNativeAndFailureMetadata() {
        MockProducer<Object, Object> producer = mockProducer(true);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        ConsumerRecord<String, String> sourceRecord = new ConsumerRecord<>(
                "source-topic",
                7,
                42,
                987_654_321L,
                TimestampType.LOG_APPEND_TIME,
                ConsumerRecord.NULL_SIZE,
                ConsumerRecord.NULL_SIZE,
                "source-key",
                "audit event",
                new RecordHeaders()
                        .add("trace-id", "first".getBytes(StandardCharsets.UTF_8))
                        .add("trace-id", "second".getBytes(StandardCharsets.UTF_8))
                        .add("binary", new byte[] {0x00, (byte) 0xFF})
                        .add("null-header", null)
                        .add(DeadLetterMessage.SOURCE_CHANNEL_HEADER,
                             "forged".getBytes(StandardCharsets.UTF_8))
                        .add(KafkaConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER,
                             "forged".getBytes(StandardCharsets.UTF_8))
                        .add(KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_HEADER,
                             "forged".getBytes(StandardCharsets.UTF_8))
                        .add(KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER,
                             "forged".getBytes(StandardCharsets.UTF_8))
                        .add(KafkaConnectorProvider.DLQ_ORIGINAL_LEADER_EPOCH_HEADER,
                             "forged".getBytes(StandardCharsets.UTF_8)),
                Optional.of(9));
        RuntimeException processingFailure = new IllegalStateException("dispatch failed");
        DeadLetterMessage<String> deadLetter = DeadLetterMessage.create(KafkaMessageImpl.create(sourceRecord),
                                                                         "orders-in",
                                                                         3,
                                                                         processingFailure);

        start(connector, config()).send(deadLetter);

        ProducerRecord<Object, Object> record = producer.history().getFirst();
        assertThat(record.topic(), is(TOPIC));
        assertThat(record.partition(), nullValue());
        assertThat(record.timestamp(), nullValue());
        assertThat(record.key(), is("source-key"));
        assertThat(record.value(), is("audit event"));
        assertThat(headerValues(record, "trace-id"), is(List.of("first", "second")));
        assertArrayEquals(new byte[] {0x00, (byte) 0xFF}, record.headers().lastHeader("binary").value());
        assertThat(record.headers().lastHeader("null-header").value(), nullValue());
        assertThat(headerValue(record, DeadLetterMessage.SOURCE_CHANNEL_HEADER), is("orders-in"));
        assertThat(headerValue(record, DeadLetterMessage.ATTEMPTS_HEADER), is("3"));
        assertThat(headerValue(record, DeadLetterMessage.FAILURE_TYPE_HEADER),
                   is(IllegalStateException.class.getName()));
        assertThat(headerValue(record, DeadLetterMessage.FAILURE_MESSAGE_HEADER), is("dispatch failed"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER), is("source-topic"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_PARTITION_HEADER), is("7"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_OFFSET_HEADER), is("42"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_HEADER), is("987654321"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER),
                   is(KafkaMessage.TimestampType.LOG_APPEND_TIME.name()));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_LEADER_EPOCH_HEADER), is("9"));
    }

    @Test
    void testDeadLetterKafkaMessageWithoutSourceMetadataRemovesForgedReservedHeaders() {
        MockProducer<Object, Object> producer = mockProducer(true);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        KafkaMessage<String, String> original = KafkaMessage.<String, String>builder("source-key", "audit event")
                .header(KafkaConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER, "forged")
                .header(KafkaConnectorProvider.DLQ_ORIGINAL_PARTITION_HEADER, "forged")
                .header(KafkaConnectorProvider.DLQ_ORIGINAL_OFFSET_HEADER, "forged")
                .header(KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_HEADER, "forged")
                .header(KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER, "forged")
                .header(KafkaConnectorProvider.DLQ_ORIGINAL_LEADER_EPOCH_HEADER, "forged")
                .build();
        DeadLetterMessage<String> deadLetter = DeadLetterMessage.create(original,
                                                                         "orders-in",
                                                                         1,
                                                                         new IllegalStateException("failed"));

        start(connector, config()).send(deadLetter);

        ProducerRecord<Object, Object> record = producer.history().getFirst();
        assertThat(record.timestamp(), nullValue());
        assertThat(record.headers().lastHeader(KafkaConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER), nullValue());
        assertThat(record.headers().lastHeader(KafkaConnectorProvider.DLQ_ORIGINAL_PARTITION_HEADER), nullValue());
        assertThat(record.headers().lastHeader(KafkaConnectorProvider.DLQ_ORIGINAL_OFFSET_HEADER), nullValue());
        assertThat(record.headers().lastHeader(KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_HEADER), nullValue());
        assertThat(record.headers().lastHeader(KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER), nullValue());
        assertThat(record.headers().lastHeader(KafkaConnectorProvider.DLQ_ORIGINAL_LEADER_EPOCH_HEADER), nullValue());
    }

    @Test
    void testCustomDeadLetterWrapperMergesPortableHeadersAndCanonicalizesMetadata() {
        MockProducer<Object, Object> producer = mockProducer(true);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        ConsumerRecord<String, String> sourceRecord = new ConsumerRecord<>(
                "source-topic",
                7,
                42,
                987_654_321L,
                TimestampType.LOG_APPEND_TIME,
                ConsumerRecord.NULL_SIZE,
                ConsumerRecord.NULL_SIZE,
                "source-key",
                "audit event",
                new RecordHeaders()
                        .add("trace-id", "first".getBytes(StandardCharsets.UTF_8))
                        .add("trace-id", "second".getBytes(StandardCharsets.UTF_8))
                        .add("binary", new byte[] {0x00, (byte) 0xFF})
                        .add("null-header", null),
                Optional.of(9));
        Map<String, String> wrapperHeaders = new LinkedHashMap<>();
        wrapperHeaders.put("wrapper-only", "portable");
        wrapperHeaders.put("trace-id", "wrapper");
        wrapperHeaders.put(DeadLetterMessage.SOURCE_CHANNEL_HEADER, "forged");
        wrapperHeaders.put(DeadLetterMessage.ATTEMPTS_HEADER, "999");
        wrapperHeaders.put(DeadLetterMessage.FAILURE_TYPE_HEADER, "forged");
        wrapperHeaders.put(DeadLetterMessage.FAILURE_MESSAGE_HEADER, "forged");
        wrapperHeaders.put(KafkaConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER, "forged");
        wrapperHeaders.put(KafkaConnectorProvider.DLQ_ORIGINAL_PARTITION_HEADER, "forged");
        wrapperHeaders.put(KafkaConnectorProvider.DLQ_ORIGINAL_OFFSET_HEADER, "forged");
        wrapperHeaders.put(KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_HEADER, "forged");
        wrapperHeaders.put(KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER, "forged");
        wrapperHeaders.put(KafkaConnectorProvider.DLQ_ORIGINAL_LEADER_EPOCH_HEADER, "forged");
        DeadLetterMessage<String> deadLetter = customDeadLetter(KafkaMessageImpl.create(sourceRecord),
                                                                wrapperHeaders);

        start(connector, config()).send(deadLetter);

        ProducerRecord<Object, Object> record = producer.history().getFirst();
        assertThat(record.key(), is("source-key"));
        assertThat(record.value(), is("audit event"));
        assertThat(headerValues(record, "trace-id"), is(List.of("first", "second", "wrapper")));
        assertThat(headerValue(record, "wrapper-only"), is("portable"));
        assertArrayEquals(new byte[] {0x00, (byte) 0xFF}, record.headers().lastHeader("binary").value());
        assertThat(record.headers().lastHeader("null-header").value(), nullValue());
        assertThat(headerValue(record, DeadLetterMessage.SOURCE_CHANNEL_HEADER), is("orders-in"));
        assertThat(headerValue(record, DeadLetterMessage.ATTEMPTS_HEADER), is("4"));
        assertThat(headerValue(record, DeadLetterMessage.FAILURE_TYPE_HEADER),
                   is(IllegalArgumentException.class.getName()));
        assertThat(headerValue(record, DeadLetterMessage.FAILURE_MESSAGE_HEADER), is("custom failure"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER), is("source-topic"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_PARTITION_HEADER), is("7"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_OFFSET_HEADER), is("42"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_HEADER), is("987654321"));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER),
                   is(KafkaMessage.TimestampType.LOG_APPEND_TIME.name()));
        assertThat(headerValue(record, KafkaConnectorProvider.DLQ_ORIGINAL_LEADER_EPOCH_HEADER), is("9"));
    }

    @Test
    void testBatchEnqueuesAllRecordsBeforeWaiting() throws Exception {
        MockProducer<Object, Object> producer = mockProducer(false);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> start(connector, config())
                .sendBatch(List.of(Message.create("first"), Message.create("second"))));

        awaitHistory(producer, 2);
        assertThat("send should wait for broker completion", sending.isDone(), is(false));
        assertThat(producer.completeNext(), is(true));
        assertThat(producer.completeNext(), is(true));
        sending.get(1, TimeUnit.SECONDS);

        assertThat(producer.history().stream().map(record -> (String) record.value()).toList(),
                   is(List.of("first", "second")));
    }

    @Test
    void testProducerFailureIsWrapped() throws Exception {
        MockProducer<Object, Object> producer = mockProducer(false);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        RuntimeException failure = new IllegalStateException("send failed");
        CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> start(connector, config())
                .send(Message.create("audit event")));

        awaitHistory(producer, 1);
        assertThat(producer.errorNext(failure), is(true));
        ExecutionException exception = assertThrows(ExecutionException.class,
                                                    () -> sending.get(1, TimeUnit.SECONDS));
        assertThat(exception.getCause(), instanceOf(MessagingException.class));
        assertThat(exception.getCause().getCause(), sameInstance(failure));
    }

    @Test
    void testSynchronousProducerFailureIsWrapped() {
        MockProducer<Object, Object> producer = mockProducer(true);
        RuntimeException failure = new IllegalStateException("enqueue failed");
        producer.sendException = failure;
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint endpoint = start(connector, config());

        MessagingException exception = assertThrows(
                MessagingException.class,
                () -> endpoint.send(Message.create("audit event")));

        assertThat(exception.getCause(), sameInstance(failure));
        endpoint.close();
    }

    @Test
    void testProducerSendTimeoutIsWrapped() {
        MockProducer<Object, Object> producer = mockProducer(false);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint endpoint = start(connector, config(Duration.ofNanos(1)));

        MessagingException exception = assertThrows(
                MessagingException.class,
                () -> endpoint.send(Message.create("audit event")));

        assertThat(exception.getCause(), instanceOf(TimeoutException.class));
        assertThat(producer.history().size(), is(1));
        endpoint.close();
    }

    @Test
    void testProducerSendInterruptionPreservesInterruptStatus() throws InterruptedException {
        MockProducer<Object, Object> producer = mockProducer(false);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        var sink = start(connector, config(Duration.ofSeconds(5)));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                sink.send(Message.create("audit event"));
            } catch (Throwable t) {
                failure.set(t);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        awaitHistory(producer, 1);

        thread.interrupt();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), instanceOf(MessagingException.class));
        assertThat(failure.get().getCause(), instanceOf(InterruptedException.class));
        assertThat(interrupted.get(), is(true));
        sink.close();
    }

    @Test
    void testEndpointCreationIsResourceFreeAndEndpointsOwnTheirProducers() {
        List<MockProducer<Object, Object>> created = new ArrayList<>();
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> {
            MockProducer<Object, Object> producer = mockProducer(true);
            created.add(producer);
            return producer;
        });
        OutgoingEndpoint first = connector.createOutgoingEndpoint(config());
        OutgoingEndpoint second = connector.createOutgoingEndpoint(config());

        assertThat(created, is(List.of()));

        first.start();
        second.start();
        first.close();
        first.close();

        assertThat(created.size(), is(2));
        assertThat(created.get(0).closed(), is(true));
        assertThat(created.get(1).closed(), is(false));

        second.close();
        assertThat(created.get(1).closed(), is(true));
    }

    @Test
    void testGraphClosesOnlyOwnedSinkAndLeavesSiblingUsable() {
        List<MockProducer<Object, Object>> created = new ArrayList<>();
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> {
            MockProducer<Object, Object> producer = mockProducer(true);
            created.add(producer);
            return producer;
        });
        OutgoingEndpoint first = start(connector, config());
        OutgoingEndpoint second = start(connector, config());

        first.close();
        first.close();

        assertThat(created.get(0).closed(), is(true));
        assertThat(created.get(1).closed(), is(false));
        second.send(Message.create("still available"));
        assertThat(created.get(1).history().size(), is(1));

        second.close();

        assertThat(created.get(1).closed(), is(true));
    }

    @Test
    void testForcedSinkCloseDoesNotUseGracefulTimeout() {
        CloseTrackingProducer producer = new CloseTrackingProducer();
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint sink = start(connector, config());

        sink.forceClose();
        sink.close();

        assertThat(producer.closeTimeout(), is(Duration.ZERO));
    }

    @Test
    void testStartIsRejectedWhileCloseIsInProgress() throws InterruptedException {
        BlockingCloseProducer producer = new BlockingCloseProducer();
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint endpoint = start(connector, config());
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = Thread.ofVirtual().start(() -> captureFailure(endpoint::close, closeFailure));

        assertThat(producer.awaitClose(), is(true));
        try {
            IllegalStateException restartFailure = assertThrows(IllegalStateException.class, endpoint::start);
            assertThat(restartFailure.getMessage(), is("Kafka outgoing endpoint is closed"));
            assertThat(closer.isAlive(), is(true));
        } finally {
            producer.releaseClose();
        }
        closer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(closer.isAlive(), is(false));
        assertThat(closeFailure.get(), nullValue());
        assertThat(producer.closed(), is(true));
    }

    @Test
    void testForceCloseReturnsPromptlyWhileGracefulCloseIsBlocked() throws InterruptedException {
        BlockingCloseProducer producer = new BlockingCloseProducer();
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint endpoint = start(connector, config());
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = Thread.ofVirtual().start(() -> captureFailure(endpoint::close, closeFailure));
        assertThat(producer.awaitClose(), is(true));
        AtomicReference<Throwable> forceFailure = new AtomicReference<>();
        Thread forceCloser = Thread.ofVirtual().start(() -> captureFailure(endpoint::forceClose, forceFailure));

        try {
            forceCloser.join(TimeUnit.SECONDS.toMillis(1));
            assertThat(forceCloser.isAlive(), is(false));
            assertThat(forceFailure.get(), nullValue());
            assertThat(closer.isAlive(), is(true));
            assertThat(producer.awaitCloseInterruption(), is(true));
            assertThat(producer.closeInterruptions(), is(1));
        } finally {
            producer.releaseClose();
        }
        closer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(closer.isAlive(), is(false));
        assertThat(closeFailure.get(), nullValue());
        assertThat(producer.closed(), is(true));
    }

    @Test
    void testSinkRetainsProducerOwnershipAfterCloseFailure() {
        RetryingCloseProducer producer = new RetryingCloseProducer();
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint sink = start(connector, config());

        assertThrows(IllegalStateException.class, sink::close);

        assertThat(producer.closeAttempts(), is(1));
        assertThat(producer.closed(), is(false));
        IllegalStateException restartFailure = assertThrows(IllegalStateException.class, sink::start);
        assertThat(restartFailure.getMessage(), is("Kafka outgoing endpoint is closed"));

        sink.close();

        assertThat(producer.closeAttempts(), is(2));
        assertThat(producer.closed(), is(true));
    }

    @Test
    void testForceCloseUnblocksReadinessProbeAndPreventsReadyTransition() throws InterruptedException {
        BlockingReadinessProducer producer = new BlockingReadinessProducer();
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint endpoint = connector.createOutgoingEndpoint(config());
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        Thread starter = Thread.ofVirtual().start(() -> captureFailure(endpoint::start, startupFailure));

        assertThat(producer.awaitProbe(), is(true));

        endpoint.forceClose();
        starter.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(starter.isAlive(), is(false));
        assertThat(startupFailure.get(), instanceOf(RuntimeException.class));
        assertThat(producer.closeTimeout(), is(Duration.ZERO));
        MessagingException sendFailure = assertThrows(MessagingException.class,
                                                       () -> endpoint.send(Message.create("not sent")));
        assertThat(sendFailure.getCause(), instanceOf(IllegalStateException.class));
    }

    @Test
    void testReadinessFailureClosesProducerAndPreservesCause() {
        MockProducer<Object, Object> producer = mockProducer(true);
        IllegalStateException readinessFailure = new IllegalStateException("metadata failed");
        producer.partitionsForException = readinessFailure;
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint endpoint = connector.createOutgoingEndpoint(config());

        MessagingException failure = assertThrows(MessagingException.class, endpoint::start);

        assertThat(failure.getCause(), sameInstance(readinessFailure));
        assertThat(producer.closed(), is(true));
        assertThrows(MessagingException.class, endpoint::start);
    }

    @Test
    void testStartupErrorsFinalizeLifecycleAndFailedCleanupCanBeRetried() {
        AssertionError factoryError = new AssertionError("factory failed");
        KafkaOutgoingConnector failingFactory = new KafkaOutgoingConnector(ignored -> {
            throw factoryError;
        });
        OutgoingEndpoint factoryEndpoint = failingFactory.createOutgoingEndpoint(config());

        assertThat(assertThrows(AssertionError.class, factoryEndpoint::start), sameInstance(factoryError));
        factoryEndpoint.close();

        AssertionError readinessError = new AssertionError("metadata failed");
        AssertionError cleanupError = new AssertionError("cleanup failed");
        ErrorOnReadinessAndFirstCloseProducer producer = new ErrorOnReadinessAndFirstCloseProducer(readinessError,
                                                                                                   cleanupError);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint endpoint = connector.createOutgoingEndpoint(config());

        assertThat(assertThrows(AssertionError.class, endpoint::start), sameInstance(cleanupError));
        assertThat(cleanupError.getSuppressed()[0], sameInstance(readinessError));
        assertThat(producer.closeAttempts(), is(1));
        assertThat(producer.closed(), is(false));
        assertThat(assertThrows(AssertionError.class, endpoint::start), sameInstance(cleanupError));

        endpoint.close();
        assertThat(producer.closeAttempts(), is(2));
        assertThat(producer.closed(), is(true));
    }

    @Test
    void testFlushDelegatesToReadyProducer() {
        MockProducer<Object, Object> producer = mockProducer(true);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        OutgoingEndpoint endpoint = start(connector, config());

        endpoint.flush();

        assertThat(producer.flushed(), is(true));
        endpoint.close();
    }

    private static KafkaConnectorConfig config() {
        return config(Duration.ofSeconds(2));
    }

    private static KafkaConnectorConfig config(Duration sendTimeout) {
        return KafkaConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel("audit")
                .connector(KafkaConnectorProvider.CONNECTOR_TYPE)
                .bootstrapServers("localhost:9092")
                .topic(TOPIC)
                .sendTimeout(sendTimeout)
                .closeTimeout(Duration.ofSeconds(1))
                .build();
    }

    private static OutgoingEndpoint start(KafkaOutgoingConnector connector, KafkaConnectorConfig config) {
        OutgoingEndpoint endpoint = connector.createOutgoingEndpoint(config);
        endpoint.start();
        return endpoint;
    }

    private static void captureFailure(Runnable action, AtomicReference<Throwable> failure) {
        try {
            action.run();
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private static String headerValue(ProducerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static List<String> headerValues(ProducerRecord<?, ?> record, String name) {
        List<String> result = new ArrayList<>();
        record.headers().headers(name)
                .forEach(header -> result.add(new String(header.value(), StandardCharsets.UTF_8)));
        return List.copyOf(result);
    }

    private static <T> DeadLetterMessage<T> customDeadLetter(Message<T> originalMessage,
                                                             Map<String, String> additionalHeaders) {
        Map<String, String> headers = new LinkedHashMap<>(originalMessage.headers());
        headers.putAll(additionalHeaders);
        Map<String, String> immutableHeaders = Map.copyOf(headers);
        return new DeadLetterMessage<>() {
            @Override
            public Message<T> originalMessage() {
                return originalMessage;
            }

            @Override
            public String sourceChannel() {
                return "orders-in";
            }

            @Override
            public int attempts() {
                return 4;
            }

            @Override
            public String failureType() {
                return IllegalArgumentException.class.getName();
            }

            @Override
            public String failureMessage() {
                return "custom failure";
            }

            @Override
            public T entity() {
                return originalMessage.entity();
            }

            @Override
            public Map<String, String> headers() {
                return immutableHeaders;
            }
        };
    }

    private static MockProducer<Object, Object> mockProducer(boolean autoComplete) {
        Serializer<Object> serializer = (topic, data) -> data == null
                ? null
                : String.valueOf(data).getBytes(StandardCharsets.UTF_8);
        return new MockProducer<>(cluster(), autoComplete, null, serializer, serializer);
    }

    private static Cluster cluster() {
        Node node = new Node(0, "localhost", 9092);
        PartitionInfo partition = new PartitionInfo(TOPIC,
                                                    0,
                                                    node,
                                                    new Node[] {node},
                                                    new Node[] {node});
        return new Cluster("test-cluster", List.of(node), List.of(partition), Set.of(), Set.of());
    }

    private static void awaitHistory(MockProducer<?, ?> producer, int expectedSize) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (producer.history().size() < expectedSize && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(producer.history().size(), is(expectedSize));
    }

    private static final class CloseTrackingProducer extends MockProducer<Object, Object> {
        private final AtomicReference<Duration> closeTimeout = new AtomicReference<>();

        private CloseTrackingProducer() {
            super(cluster(), true, null, serializer(), serializer());
        }

        @Override
        public void close(Duration timeout) {
            closeTimeout.compareAndSet(null, timeout);
            super.close(timeout);
        }

        private Duration closeTimeout() {
            return closeTimeout.get();
        }

        private static Serializer<Object> serializer() {
            return (topic, data) -> data == null
                    ? null
                    : String.valueOf(data).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class RetryingCloseProducer extends MockProducer<Object, Object> {
        private final AtomicInteger closeAttempts = new AtomicInteger();

        private RetryingCloseProducer() {
            super(cluster(), true, null, CloseTrackingProducer.serializer(), CloseTrackingProducer.serializer());
        }

        @Override
        public void close(Duration timeout) {
            if (closeAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("close failed");
            }
            super.close(timeout);
        }

        private int closeAttempts() {
            return closeAttempts.get();
        }
    }

    private static final class BlockingCloseProducer extends MockProducer<Object, Object> {
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private final CountDownLatch closeInterrupted = new CountDownLatch(1);
        private final AtomicInteger closeInterruptions = new AtomicInteger();

        private BlockingCloseProducer() {
            super(cluster(), true, null, CloseTrackingProducer.serializer(), CloseTrackingProducer.serializer());
        }

        @Override
        public void close(Duration timeout) {
            closeEntered.countDown();
            while (releaseClose.getCount() != 0) {
                try {
                    releaseClose.await();
                } catch (InterruptedException e) {
                    closeInterruptions.incrementAndGet();
                    closeInterrupted.countDown();
                }
            }
            super.close(timeout);
        }

        private boolean awaitClose() throws InterruptedException {
            return closeEntered.await(5, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            releaseClose.countDown();
        }

        private int closeInterruptions() {
            return closeInterruptions.get();
        }

        private boolean awaitCloseInterruption() throws InterruptedException {
            return closeInterrupted.await(5, TimeUnit.SECONDS);
        }
    }

    private static final class BlockingReadinessProducer extends MockProducer<Object, Object> {
        private final CountDownLatch probeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseProbe = new CountDownLatch(1);
        private final AtomicReference<Duration> closeTimeout = new AtomicReference<>();

        private BlockingReadinessProducer() {
            super(cluster(), true, null, CloseTrackingProducer.serializer(), CloseTrackingProducer.serializer());
        }

        @Override
        public List<PartitionInfo> partitionsFor(String topic) {
            probeEntered.countDown();
            try {
                releaseProbe.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Readiness probe was interrupted", e);
            }
            if (closed()) {
                throw new IllegalStateException("Producer closed during readiness probe");
            }
            return super.partitionsFor(topic);
        }

        @Override
        public void close(Duration timeout) {
            closeTimeout.compareAndSet(null, timeout);
            releaseProbe.countDown();
            super.close(timeout);
        }

        private boolean awaitProbe() throws InterruptedException {
            return probeEntered.await(5, TimeUnit.SECONDS);
        }

        private Duration closeTimeout() {
            return closeTimeout.get();
        }
    }

    private static final class ErrorOnReadinessAndFirstCloseProducer extends MockProducer<Object, Object> {
        private final Error readinessError;
        private final Error closeError;
        private final AtomicInteger closeAttempts = new AtomicInteger();

        private ErrorOnReadinessAndFirstCloseProducer(Error readinessError, Error closeError) {
            super(cluster(), true, null, CloseTrackingProducer.serializer(), CloseTrackingProducer.serializer());
            this.readinessError = readinessError;
            this.closeError = closeError;
        }

        @Override
        public List<PartitionInfo> partitionsFor(String topic) {
            throw readinessError;
        }

        @Override
        public void close(Duration timeout) {
            if (closeAttempts.incrementAndGet() == 1) {
                throw closeError;
            }
            super.close(timeout);
        }

        private int closeAttempts() {
            return closeAttempts.get();
        }
    }
}
