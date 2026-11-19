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

package io.helidon.extensions.messaging.connectors.pulsar;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.helidon.messaging.BatchDeliveryException;
import io.helidon.messaging.BatchItemOutcome;
import io.helidon.messaging.BatchItemStatus;
import io.helidon.messaging.ConnectorDelivery;
import io.helidon.messaging.ConnectorDeliveryReservation;
import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.spi.IncomingChannel;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionMode;
import org.apache.pulsar.client.api.schema.GenericObject;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.SchemaType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulsarIncomingChannelTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void forceCloseEscalatesPendingConsumerClose() throws Exception {
        forceCloseDuringCleanup(true, null);
    }

    @Test
    void forceCloseEscalatesPendingClientClose() throws Exception {
        forceCloseDuringCleanup(false, null);
    }

    @Test
    void forceCloseRetainsEarlierConsumerCloseFailure() throws Exception {
        forceCloseDuringCleanup(false, new IllegalStateException("consumer close failed before force close"));
    }

    @Test
    void repeatedDrainWaitsForConsumerAndClientCloseDespiteInterrupts() throws Exception {
        TestContext context = new TestContext(false);
        CompletableFuture<Void> consumerClose = new CompletableFuture<>();
        CompletableFuture<Void> clientClose = new CompletableFuture<>();
        FakeSource source = new FakeSource(null, context.reserved, consumerClose, clientClose);
        source.blockReceive.set(true);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        Thread owner = Thread.ofVirtual().start(() -> {
            run(connector, context, runFailure);
            completion.complete(Thread.currentThread().isInterrupted());
        });

        try {
            assertThat(source.receiveEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.drain();
            assertThat(source.consumerCloseEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.drain();
            assertThrows(TimeoutException.class, () -> completion.get(100, TimeUnit.MILLISECONDS));
            assertThat(source.clientCloseCalls.get(), is(0));

            consumerClose.complete(null);
            assertThat(source.clientCloseEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.drain();
            assertThrows(TimeoutException.class, () -> completion.get(100, TimeUnit.MILLISECONDS));

            clientClose.complete(null);
            assertThat(completion.get(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.close();
            assertThat(runFailure.get(), nullValue());
            assertThat(source.consumerCloseCalls.get(), is(1));
            assertThat(source.clientCloseCalls.get(), is(1));
            assertThat(source.shutdownCalls.get(), is(0));
        } finally {
            consumerClose.complete(null);
            clientClose.complete(null);
            connector.drain();
            owner.join(WAIT.toMillis());
        }
        assertThat(owner.isAlive(), is(false));
    }

    @Test
    void drainReportsExceptionalConsumerCloseAndStillClosesClient() throws Exception {
        TestContext context = new TestContext(false);
        IllegalStateException failure = new IllegalStateException("consumer close failed");
        FakeSource source = new FakeSource(null,
                                           context.reserved,
                                           CompletableFuture.failedFuture(failure),
                                           CompletableFuture.completedFuture(null));
        source.blockReceive.set(true);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        try {
            assertThat(source.receiveEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.drain();
            owner.join(WAIT.toMillis());
            assertThat(owner.isAlive(), is(false));
            assertThat(runFailure.get().getMessage(), containsString("Cannot close Pulsar consumer"));
            assertThat(runFailure.get().getCause(), sameInstance(failure));
            assertThat(assertThrows(MessagingException.class, connector::close), sameInstance(runFailure.get()));
            assertThat(source.consumerCloseCalls.get(), is(1));
            assertThat(source.clientCloseCalls.get(), is(1));
            assertThat(source.shutdownCalls.get(), is(0));
        } finally {
            connector.drain();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void drainTimesOutPendingClientCloseAndShutsDownClient() throws Exception {
        TestContext context = new TestContext(false);
        CompletableFuture<Void> clientClose = new CompletableFuture<>();
        FakeSource source = new FakeSource(null,
                                           context.reserved,
                                           CompletableFuture.completedFuture(null),
                                           clientClose);
        source.blockReceive.set(true);
        PulsarIncomingConfig config = configBuilder(PulsarSchemaType.STRING, 1024)
                .closeTimeout(Duration.ofMillis(100))
                .build();
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config);
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        try {
            assertThat(source.receiveEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.drain();
            owner.join(WAIT.toMillis());
            assertThat(owner.isAlive(), is(false));
            assertThat(runFailure.get().getMessage(), containsString("Timed out closing Pulsar client"));
            assertThat(runFailure.get().getCause() instanceof TimeoutException, is(true));
            assertThat(assertThrows(MessagingException.class, connector::close), sameInstance(runFailure.get()));
            assertThat(source.consumerCloseCalls.get(), is(1));
            assertThat(source.clientCloseCalls.get(), is(1));
            assertThat(source.shutdownCalls.get(), is(1));
        } finally {
            clientClose.complete(null);
            connector.drain();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void reservesBeforeReceiveAndAcknowledgesOnlyAfterRuntimeCompletion() throws Exception {
        TestContext context = new TestContext(false, true);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage("payload", 7), context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(context.deliveryEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        assertThat(source.acks.get(), is(0));
        context.allowDelivery.countDown();
        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        assertThat(owner.isAlive(), is(false));
        assertThat(runFailure.get(), nullValue());
        assertThat(source.receivedAfterReservation.get(), is(true));
        assertThat(context.awaitRunningCalls.get(), is(1));
        assertThat(context.started.get(), is(1));
        assertThat(context.failedStarts.get(), is(0));
        assertThat(source.acks.get(), is(1));
        assertThat(source.negativeAcks.get(), is(0));
        assertThat(source.durable.get(), is(true));
        assertThat(source.startedPaused.get(), is(true));
        assertThat(source.ackReceipts.get(), is(true));
        assertThat(source.batchIndexAcknowledgment.get(), is(false));
        assertThat(source.closed.get(), is(true));
    }

    @Test
    void oversizedMappingFailureUsesStartFailedThenAcknowledgesTerminalPolicy() throws Exception {
        TestContext context = new TestContext(false);
        AtomicInteger dataReads = new AtomicInteger();
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage("oversized",
                                                                           9,
                                                                           Map.of("trace-id", "pulsar-trace"),
                                                                           () -> {
                                                                               dataReads.incrementAndGet();
                                                                               return new byte[9];
                                                                           }),
                                           context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config(1));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        assertThat(runFailure.get(), nullValue());
        assertThat(context.started.get(), is(0));
        assertThat(context.failedStarts.get(), is(1));
        assertThat(source.acks.get(), is(1));
        assertThat(source.negativeAcks.get(), is(0));
        assertThat(dataReads.get(), is(0));
        assertThat(mappingFailure(context, 0).getMessage(), containsString("exceeds max-message-bytes 1"));
        PulsarMessage<?> rejected = (PulsarMessage<?>) context.failedBatch.get().get(0);
        assertThat(((PulsarMessageImpl<?>) rejected).entityAvailable(), is(false));
        assertThrows(MessagingException.class, rejected::entity);
        assertThat(rejected.header("trace-id").orElseThrow(), is("pulsar-trace"));
    }

    @Test
    void nullPayloadAndRawDataUseStartFailedThenAcknowledgeTerminalPolicy() throws Exception {
        TestContext context = new TestContext(false);
        AtomicInteger dataReads = new AtomicInteger();
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage(null,
                                                                           0,
                                                                           Map.of("trace-id", "null-trace"),
                                                                           () -> {
                                                                               dataReads.incrementAndGet();
                                                                               return null;
                                                                           }),
                                           context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        assertThat(runFailure.get(), nullValue());
        assertThat(context.started.get(), is(0));
        assertThat(context.failedStarts.get(), is(1));
        assertThat(source.acks.get(), is(1));
        assertThat(source.negativeAcks.get(), is(0));
        assertThat(dataReads.get(), is(0));
        assertThat(mappingFailure(context, 0).getMessage(), is("Pulsar message payload is null"));
        PulsarMessage<?> rejected = (PulsarMessage<?>) context.failedBatch.get().get(0);
        assertThat(((PulsarMessageImpl<?>) rejected).entityAvailable(), is(false));
        assertThrows(MessagingException.class, rejected::entity);
        assertThat(rejected.header("trace-id").orElseThrow(), is("null-trace"));
    }

    @Test
    void metadataSnapshotFailureStillUsesStartFailedWithBodylessFallback() throws Exception {
        TestContext context = new TestContext(false);
        RuntimeException metadataFailure = new IllegalStateException("metadata unavailable");
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage(null,
                                                                           0,
                                                                           () -> {
                                                                               throw metadataFailure;
                                                                           },
                                                                           () -> {
                                                                               throw new AssertionError(
                                                                                       "Raw data must not be read");
                                                                           }),
                                           context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        assertThat(runFailure.get(), nullValue());
        assertThat(context.failedStarts.get(), is(1));
        assertThat(source.acks.get(), is(1));
        assertThat(source.negativeAcks.get(), is(0));
        assertThat(mappingFailure(context, 0).getSuppressed(), is(new Throwable[] {metadataFailure}));
        PulsarMessage<?> rejected = (PulsarMessage<?>) context.failedBatch.get().get(0);
        assertThat(((PulsarMessageImpl<?>) rejected).entityAvailable(), is(false));
        assertThrows(MessagingException.class, rejected::entity);
        assertThat(rejected.headers().entries().isEmpty(), is(true));
        assertThat(rejected.topic().isEmpty(), is(true));
    }

    @Test
    void failedRuntimeDeliveryIsNegativelyAcknowledged() throws Exception {
        TestContext context = new TestContext(true);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage("poison", 6), context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.negativelyAcknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        owner.join(WAIT.toMillis());

        assertThat(owner.isAlive(), is(false));
        assertThat(runFailure.get() instanceof IllegalStateException, is(true));
        assertThat(source.acks.get(), is(0));
        assertThat(source.negativeAcks.get(), is(1));
        assertThat(source.closed.get(), is(true));
    }

    @Test
    void forceCloseInterruptsActiveDeliveryWithoutSettlingTransport() throws Exception {
        TestContext context = new TestContext(false, true);
        FakeSource source = batchSource(context, "first", "second", "third");
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(context.deliveryEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.forceClose();
        owner.join(WAIT.toMillis());

        assertThat(owner.isAlive(), is(false));
        assertThat(runFailure.get(), nullValue());
        assertThat(source.acks.get(), is(0));
        assertThat(source.negativeAcks.get(), is(0));
        assertThat(source.closed.get(), is(true));
    }

    @Test
    void closeInterruptsBlockedReceiveAndForceCloseInterruptsRunningGate() throws Exception {
        TestContext receiveContext = new TestContext(false);
        FakeSource blockedSource = new FakeSource(null, receiveContext.reserved);
        blockedSource.blockReceive.set(true);
        IncomingChannel blockedConnector = new PulsarIncomingChannel(ignored -> blockedSource.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> blockedFailure = new AtomicReference<>();
        Thread blockedOwner = Thread.ofVirtual().start(() -> run(blockedConnector, receiveContext, blockedFailure));
        assertThat(blockedSource.receiveEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));

        blockedConnector.close();
        blockedOwner.join(WAIT.toMillis());

        assertThat(blockedOwner.isAlive(), is(false));
        assertThat(blockedFailure.get(), nullValue());

        BlockingGateContext gateContext = new BlockingGateContext();
        FakeSource gatedSource = new FakeSource(null, gateContext.reserved);
        IncomingChannel gatedConnector = new PulsarIncomingChannel(ignored -> gatedSource.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> gatedFailure = new AtomicReference<>();
        Thread gatedOwner = Thread.ofVirtual().start(() -> run(gatedConnector, gateContext, gatedFailure));
        assertThat(gateContext.entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));

        gatedConnector.forceClose();
        gatedOwner.join(WAIT.toMillis());

        assertThat(gatedOwner.isAlive(), is(false));
        assertThat(gatedFailure.get(), nullValue());
        assertThat(gatedSource.closed.get(), is(true));
    }

    @Test
    void zeroCloseTimeoutAcceptsCompletedConsumerAndClientCloses() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage("payload", 7), context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), configBuilder(PulsarSchemaType.STRING, 1024)
                                                 .closeTimeout(Duration.ZERO)
                                                 .build());
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        assertThat(owner.isAlive(), is(false));
        assertThat(runFailure.get(), nullValue());
        assertThat(source.closed.get(), is(true));
    }

    @Test
    void passesResolvedBuiltInSchemaAndTypedPayloadFromPulsar() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage(42, Integer.BYTES), context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config(PulsarSchemaType.INT32, 1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        PulsarMessage<?> message = deliveredMessage(context);
        assertThat(source.schema.get(), sameInstance(Schema.INT32));
        assertThat(message.entity(), is(42));
        assertThat(message.header("trace-id").orElseThrow(), is("pulsar-trace"));
        assertThat(runFailure.get(), nullValue());
    }

    @Test
    void selectsAutoConsumeSchemaAndPreservesGenericObjectAndMetadata() throws Exception {
        GenericRecord payload = PulsarTestSupport.proxy(GenericRecord.class,
                                                        (ignored, method, args) -> switch (method.getName()) {
                                                        case "getSchemaType" -> SchemaType.INT32;
                                                        case "getNativeObject" -> 42;
                                                        case "getSchemaVersion" -> new byte[] {4, 5};
                                                        default -> PulsarTestSupport.defaultValue(method);
                                                        });
        TestContext context = new TestContext(false);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage(payload, Integer.BYTES), context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config(PulsarSchemaType.AUTO, 1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        PulsarMessage<?> message = deliveredMessage(context);
        assertThat(source.schema.get().getClass().equals(Schema.AUTO_CONSUME().getClass()), is(true));
        assertThat(message.entity(), sameInstance(payload));
        assertThat(((GenericObject) message.entity()).getNativeObject(), is(42));
        assertThat(message.schemaVersion().isPresent(), is(true));
        assertThat(message.header("trace-id").orElseThrow(), is("pulsar-trace"));
        assertThat(runFailure.get(), nullValue());
    }

    @Test
    void passesResolvedCustomKeyValueSchemaAndPayloadFromPulsar() throws Exception {
        Schema<KeyValue<String, Integer>> customSchema = Schema.KeyValue(Schema.STRING, Schema.INT32);
        PulsarIncomingConfig config = configBuilder(PulsarSchemaType.STRING, 1024)
                .schemaProvider("custom-key-value")
                .build();
        PulsarSchemaResolver.ResolvedSchema resolved = PulsarSchemaResolver.resolve(
                config.channelName(),
                config.schema().orElseThrow(),
                config.schemaProvider(),
                true,
                () -> List.of(schemaProvider("custom-key-value", customSchema)));
        KeyValue<String, Integer> payload = new KeyValue<>("order", 7);
        TestContext context = new TestContext(false);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage(payload, 16), context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config, resolved);
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        PulsarMessage<?> message = deliveredMessage(context);
        assertThat(source.schema.get(), sameInstance(customSchema));
        assertThat(message.entity(), sameInstance(payload));
        assertThat(message.header("trace-id").orElseThrow(), is("pulsar-trace"));
        assertThat(runFailure.get(), nullValue());
    }

    @Test
    void byteBufferMappingSnapshotsPayloadWithoutChangingSourceCursor() throws Exception {
        ByteBuffer payload = ByteBuffer.wrap(new byte[] {0, 1, 2, 3, 4, 5});
        payload.position(2);
        payload.limit(5);
        TestContext context = new TestContext(false);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage(payload, 5), context.reserved);
        IncomingChannel connector = new PulsarIncomingChannel(ignored -> source.client())
                .createIncomingChannel(commonConfig(), config(PulsarSchemaType.BYTEBUFFER, 1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        PulsarMessage<?> message = deliveredMessage(context);
        ByteBuffer delivered = (ByteBuffer) message.entity();
        assertThat(source.schema.get(), sameInstance(Schema.BYTEBUFFER));
        assertThat(delivered, not(sameInstance(payload)));
        assertThat(payload.position(), is(2));
        assertThat(payload.limit(), is(5));
        payload.put(0, (byte) 9);
        assertThat(Schema.BYTEBUFFER.encode(delivered), is(new byte[] {0, 1, 2, 3, 4}));
        assertThat(payload.position(), is(2));
        assertThat(payload.limit(), is(5));
        assertThat(runFailure.get(), nullValue());
    }

    @Test
    void receivesOrderedBatchAndRetainsAdmissionUntilEveryAcknowledgmentCompletes() throws Exception {
        TestContext context = new TestContext(false, true);
        FakeSource source = batchSource(context, "first", "second", "third");
        CompletableFuture<Void> pendingAck = new CompletableFuture<>();
        source.ackResults.put("second", pendingAck);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));

        try {
            assertThat(context.deliveryEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            MessageBatch<?> batch = context.deliveredBatch.get();
            assertThat(batch.size(), is(3));
            assertThat(batch.get(0).entity(), is("first"));
            assertThat(batch.get(1).entity(), is("second"));
            assertThat(batch.get(2).entity(), is("third"));
            assertThat(batch.get(1).header("source-id").orElseThrow(), is("second"));
            assertThat(source.receiveCalls.get(), is(1));
            assertThat(source.paused.get(), is(true));
            assertThat(source.acks.get(), is(0));

            context.allowDelivery.countDown();
            assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            assertThat(List.copyOf(source.acknowledgedPayloads), is(List.of("first", "second", "third")));
            assertThat(source.receiveCalls.get(), is(1));
            assertThat(context.deliveryCloses.get(), is(0));
            connector.drain();
            pendingAck.complete(null);
            owner.join(WAIT.toMillis());
            connector.close();
            assertThat(owner.isAlive(), is(false));
            assertThat(failure.get(), nullValue());
            assertThat(context.deliveryCloses.get(), is(1));
            assertThat(source.negativeAcks.get(), is(0));
        } finally {
            pendingAck.complete(null);
            context.allowDelivery.countDown();
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void emptyBatchReleasesReservationWithoutDispatchOrSettlement() throws Exception {
        TestContext context = new TestContext(false, true);
        FakeSource source = batchSource(context, "after-empty");
        source.emptyFirst.set(true);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));

        try {
            assertThat(context.deliveryEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            assertThat(source.receiveCalls.get(), is(2));
            assertThat(context.reservationCloses.get(), is(1));
            assertThat(context.started.get(), is(1));
            assertThat(context.failedStarts.get(), is(0));
            assertThat(source.acks.get(), is(0));
            assertThat(source.negativeAcks.get(), is(0));
            assertThat(deliveredMessage(context).entity(), is("after-empty"));
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
        assertThat(failure.get(), nullValue());
    }

    @Test
    void unavailableReservationKeepsConsumerPausedWithoutAcquiringMessages() throws Exception {
        TestContext context = new TestContext(false);
        context.allowReservation.set(false);
        FakeSource source = batchSource(context, "waiting");
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));

        try {
            assertThat(context.reservationEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            assertThat(source.receiveEntered.await(100, TimeUnit.MILLISECONDS), is(false));
            assertThat(source.receiveCalls.get(), is(0));
            assertThat(source.paused.get(), is(true));
            context.allowReservation.set(true);
            assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.drain();
            owner.join(WAIT.toMillis());
            assertThat(source.receivedAfterReservation.get(), is(true));
            assertThat(failure.get(), nullValue());
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void explicitCountOneStillUsesNativeBatchReceive() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = batchSource(context, "single");
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), configBuilder(PulsarSchemaType.STRING, 1024)
                        .batchReceiveMaxMessages(1)
                        .build());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));

        try {
            assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.drain();
            owner.join(WAIT.toMillis());
            assertThat(deliveredMessage(context).entity(), is("single"));
            assertThat(failure.get(), nullValue());
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void unexpectedNativeBatchCountRejectsEveryAcquiredMessageBeforeMapping() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = batchSource(context, "first", "second", "third");
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), configBuilder(PulsarSchemaType.STRING, 1024)
                        .batchReceiveMaxMessages(2)
                        .build());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));
        owner.join(WAIT.toMillis());

        try {
            assertThat(owner.isAlive(), is(false));
            assertThat(failure.get() instanceof MessagingException, is(true));
            assertThat(context.started.get(), is(0));
            assertThat(context.failedStarts.get(), is(0));
            assertThat(source.acks.get(), is(0));
            assertThat(List.copyOf(source.negativelyAcknowledgedPayloads), is(List.of("first", "second", "third")));
            assertThat(context.reservationCloses.get(), is(1));
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void mixedMappingFailurePreservesValidSiblingsAsNotAttempted() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = batchSource(context, "a", "oversized", "b");
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));

        try {
            assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.drain();
            owner.join(WAIT.toMillis());
            BatchDeliveryException mapping = (BatchDeliveryException) context.mappingFailure.get();
            assertThat(mapping.batch(), sameInstance(context.failedBatch.get()));
            assertThat(mapping.outcome(0).status(), is(BatchItemStatus.NOT_ATTEMPTED));
            assertThat(mapping.outcome(1).status(), is(BatchItemStatus.FAILED));
            assertThat(mapping.outcome(2).status(), is(BatchItemStatus.NOT_ATTEMPTED));
            assertThat(mapping.batch().get(0).entity(), is("a"));
            assertThat(mapping.batch().get(2).entity(), is("b"));
            assertThrows(MessagingException.class, mapping.batch().get(1)::entity);
            assertThat(mapping.batch().get(1).header("source-id").orElseThrow(), is("oversized"));
            assertThat(context.started.get(), is(0));
            assertThat(context.failedStarts.get(), is(1));
            assertThat(source.acks.get(), is(3));
            assertThat(source.negativeAcks.get(), is(0));
            assertThat(failure.get(), nullValue());
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void partialFailureAcknowledgesOnlySuccessfulIndexes() throws Exception {
        TestContext context = new TestContext(false);
        context.deliveryFailure = batch -> new BatchDeliveryException("partial delivery",
                                                                       new IllegalStateException("handler failed"),
                                                                       batch,
                                                                       List.of(BatchItemOutcome.succeeded(0),
                                                                               BatchItemOutcome.failed(1),
                                                                               BatchItemOutcome.notAttempted(2),
                                                                               BatchItemOutcome.indeterminate(3)));
        FakeSource source = batchSource(context, "success", "failed", "deferred", "uncertain");
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));
        owner.join(WAIT.toMillis());

        try {
            assertThat(owner.isAlive(), is(false));
            assertThat(failure.get(), sameInstance(context.terminalFailure.get()));
            assertThat(List.copyOf(source.acknowledgedPayloads), is(List.of("success")));
            assertThat(List.copyOf(source.negativelyAcknowledgedPayloads), is(List.of("failed", "deferred", "uncertain")));
            assertThat(context.deliveryCloses.get(), is(1));
            assertThat(source.receiveCalls.get(), is(1));
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void unstructuredFailureAttemptsEveryNegativeAcknowledgmentAndPreservesPrimaryFailure() throws Exception {
        TestContext context = new TestContext(true);
        FakeSource source = batchSource(context, "first", "second", "third");
        IllegalStateException nackFailure = new IllegalStateException("first nack failed");
        source.nackFailures.put("first", nackFailure);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));
        owner.join(WAIT.toMillis());

        try {
            assertThat(owner.isAlive(), is(false));
            assertThat(failure.get(), sameInstance(context.terminalFailure.get()));
            assertThat(containsFailure(failure.get(), nackFailure), is(true));
            assertThat(List.copyOf(source.negativelyAcknowledgedPayloads), is(List.of("first", "second", "third")));
            assertThat(source.acks.get(), is(0));
            assertThat(context.deliveryCloses.get(), is(1));
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void partialFailureRetainsAcknowledgmentAndNegativeAcknowledgmentFailures() throws Exception {
        TestContext context = new TestContext(false);
        context.deliveryFailure = batch -> new BatchDeliveryException("partial delivery",
                                                                       new IllegalStateException("handler failed"),
                                                                       batch,
                                                                       List.of(BatchItemOutcome.succeeded(0),
                                                                               BatchItemOutcome.failed(1),
                                                                               BatchItemOutcome.succeeded(2),
                                                                               BatchItemOutcome.notAttempted(3)));
        FakeSource source = batchSource(context, "first", "second", "third", "fourth");
        IllegalStateException ackFailure = new IllegalStateException("first receipt failed");
        IllegalStateException nackFailure = new IllegalStateException("second nack failed");
        source.ackResults.put("first", CompletableFuture.failedFuture(ackFailure));
        source.nackFailures.put("second", nackFailure);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));
        owner.join(WAIT.toMillis());

        try {
            assertThat(owner.isAlive(), is(false));
            assertThat(failure.get(), sameInstance(context.terminalFailure.get()));
            assertThat(containsFailure(failure.get(), ackFailure), is(true));
            assertThat(containsFailure(failure.get(), nackFailure), is(true));
            assertThat(List.copyOf(source.acknowledgedPayloads), is(List.of("first", "third")));
            assertThat(List.copyOf(source.negativelyAcknowledgedPayloads), is(List.of("second", "fourth")));
            assertThat(context.deliveryCloses.get(), is(1));
            assertThat(source.receiveCalls.get(), is(1));
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void acknowledgmentFailureDoesNotSkipLaterAcknowledgmentsOrAcquireAnotherBatch() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = batchSource(context, "first", "second", "third");
        IllegalStateException ackFailure = new IllegalStateException("first ack failed");
        source.ackFailures.put("first", ackFailure);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));
        owner.join(WAIT.toMillis());

        try {
            assertThat(owner.isAlive(), is(false));
            assertThat(containsFailure(failure.get(), ackFailure), is(true));
            assertThat(List.copyOf(source.acknowledgedPayloads), is(List.of("first", "second", "third")));
            assertThat(source.receiveCalls.get(), is(1));
            assertThat(context.deliveryCloses.get(), is(1));
            assertThat(source.closed.get(), is(true));
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void settlementDeadlineCoversAllAcknowledgmentsTogether() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = batchSource(context, "first", "second");
        CompletableFuture<Void> firstAck = new CompletableFuture<>();
        CompletableFuture<Void> secondAck = new CompletableFuture<>();
        source.ackResults.put("first", firstAck);
        source.ackResults.put("second", secondAck);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), configBuilder(PulsarSchemaType.STRING, 1024)
                        .settlementTimeout(Duration.ofSeconds(1))
                        .build());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long started = System.nanoTime();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));

        try {
            assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            CompletableFuture.delayedExecutor(750, TimeUnit.MILLISECONDS).execute(() -> firstAck.complete(null));
            owner.join(WAIT.toMillis());
            assertThat(owner.isAlive(), is(false));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), lessThan(1500L));
            assertThat(containsFailureType(failure.get(), TimeoutException.class), is(true));
            assertThat(source.receiveCalls.get(), is(1));
            assertThat(context.deliveryCloses.get(), is(1));
        } finally {
            firstAck.complete(null);
            secondAck.complete(null);
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void drainAfterNativeReceiveReturnsEveryMessageWithoutDispatch() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = batchSource(context, "first", "second", "third");
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        source.beforeReceiveReturn = connector::drain;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));
        owner.join(WAIT.toMillis());

        try {
            assertThat(owner.isAlive(), is(false));
            assertThat(failure.get(), nullValue());
            assertThat(context.started.get(), is(0));
            assertThat(context.failedStarts.get(), is(0));
            assertThat(source.acks.get(), is(0));
            assertThat(List.copyOf(source.negativelyAcknowledgedPayloads), is(List.of("first", "second", "third")));
            assertThat(context.reservationCloses.get(), is(1));
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void forceCloseWhileNativeReceiveCompletesWithNullReleasesReservation() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = new FakeSource(null, context.reserved);
        source.nullReceiveResult.set(true);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        source.beforeReceiveReturn = connector::forceClose;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));
        owner.join(WAIT.toMillis());

        try {
            assertThat(owner.isAlive(), is(false));
            assertThat(failure.get(), nullValue());
            assertThat(source.receiveCalls.get(), is(1));
            assertThat(context.started.get(), is(0));
            assertThat(context.failedStarts.get(), is(0));
            assertThat(context.reservationCloses.get(), is(1));
            assertThat(source.acks.get(), is(0));
            assertThat(source.negativeAcks.get(), is(0));
            assertThat(source.shutdownCalls.get(), is(1));
        } finally {
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    @Test
    void forceCloseDuringAcknowledgmentAbandonsBatchWithoutAnotherReceive() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = batchSource(context, "first", "second");
        CompletableFuture<Void> ack = new CompletableFuture<>();
        source.ackResults.put("first", ack);
        source.ackResults.put("second", ack);
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config(1024));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, failure));

        try {
            assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.forceClose();
            owner.join(WAIT.toMillis());
            assertThat(owner.isAlive(), is(false));
            assertThat(failure.get(), nullValue());
            assertThat(ack.isDone(), is(false));
            assertThat(source.receiveCalls.get(), is(1));
            assertThat(source.negativeAcks.get(), is(0));
            assertThat(context.deliveryCloses.get(), is(1));
            assertThat(source.closed.get(), is(true));
        } finally {
            ack.complete(null);
            connector.forceClose();
            owner.join(WAIT.toMillis());
        }
    }

    private static Throwable mappingFailure(TestContext context, int index) {
        BatchDeliveryException failure = (BatchDeliveryException) context.mappingFailure.get();
        assertThat(failure.batch(), sameInstance(context.failedBatch.get()));
        return failure.outcome(index).failure().orElseThrow();
    }

    private static boolean containsFailure(Throwable actual, Throwable expected) {
        if (actual == null) {
            return false;
        }
        if (actual == expected || containsFailure(actual.getCause(), expected)) {
            return true;
        }
        for (Throwable suppressed : actual.getSuppressed()) {
            if (containsFailure(suppressed, expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsFailureType(Throwable actual, Class<? extends Throwable> expected) {
        if (actual == null) {
            return false;
        }
        if (expected.isInstance(actual) || containsFailureType(actual.getCause(), expected)) {
            return true;
        }
        for (Throwable suppressed : actual.getSuppressed()) {
            if (containsFailureType(suppressed, expected)) {
                return true;
            }
        }
        return false;
    }

    private static FakeSource batchSource(TestContext context, String... payloads) {
        FakeSource source = new FakeSource(null, context.reserved);
        source.acknowledged = new CountDownLatch(payloads.length);
        source.batches.add(List.of(payloads).stream()
                                   .map(payload -> PulsarTestSupport.nativeMessage(payload,
                                                                                  payload.length(),
                                                                                  Map.of("source-id", payload)))
                                   .toList());
        return source;
    }

    private static void forceCloseDuringCleanup(boolean pendingConsumer, RuntimeException consumerFailure) throws Exception {
        TestContext context = new TestContext(false);
        CompletableFuture<Void> consumerClose = new CompletableFuture<>();
        if (!pendingConsumer) {
            if (consumerFailure == null) {
                consumerClose.complete(null);
            } else {
                consumerClose.completeExceptionally(consumerFailure);
            }
        }
        CompletableFuture<Void> clientClose = new CompletableFuture<>();
        FakeSource source = new FakeSource(null, context.reserved, consumerClose, clientClose);
        source.blockReceive.set(true);
        PulsarIncomingConfig config = configBuilder(PulsarSchemaType.STRING, 1024)
                .closeTimeout(Duration.ofSeconds(30))
                .build();
        IncomingChannel connector = new PulsarIncomingChannel(_ -> source.client())
                .createIncomingChannel(commonConfig(), config);
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        CompletableFuture<Void> runCompletion = new CompletableFuture<>();
        CompletableFuture<Void> forceCompletion = new CompletableFuture<>();
        Thread owner = Thread.ofVirtual().start(() -> {
            run(connector, context, runFailure);
            runCompletion.complete(null);
        });
        Thread forceOwner = Thread.ofVirtual().unstarted(() -> {
            try {
                connector.forceClose();
                forceCompletion.complete(null);
            } catch (Throwable e) {
                forceCompletion.completeExceptionally(e);
            }
        });

        try {
            assertThat(source.receiveEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            connector.drain();
            CountDownLatch closeEntered = pendingConsumer ? source.consumerCloseEntered : source.clientCloseEntered;
            assertThat(closeEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            forceOwner.start();
            forceCompletion.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            runCompletion.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(runFailure.get(), nullValue());
            assertThat(clientClose.isDone(), is(false));
            assertThat(consumerClose.isDone(), is(!pendingConsumer));
            if (consumerFailure == null) {
                connector.close();
            } else {
                MessagingException failure = assertThrows(MessagingException.class, connector::close);
                assertThat(failure.getMessage(), containsString("Cannot close Pulsar consumer"));
                assertThat(failure.getCause(), sameInstance(consumerFailure));
                assertThat(failure.getSuppressed().length, is(0));
            }
            assertThat(source.shutdownCalls.get(), is(1));
        } finally {
            consumerClose.complete(null);
            clientClose.complete(null);
            connector.drain();
            owner.join(WAIT.toMillis());
            forceOwner.join(WAIT.toMillis());
        }
        assertThat(owner.isAlive(), is(false));
        assertThat(forceOwner.isAlive(), is(false));
    }

    private static void run(IncomingChannel connector,
                            IncomingConnectorContext context,
                            AtomicReference<Throwable> failure) {
        try {
            connector.run(context);
        } catch (Throwable e) {
            failure.set(e);
        }
    }

    private static PulsarMessage<?> deliveredMessage(TestContext context) {
        MessageBatch<?> batch = context.deliveredBatch.get();
        assertThat(batch == null ? 0 : batch.size(), is(1));
        return (PulsarMessage<?>) batch.get(0);
    }

    private static PulsarConnectorConfig commonConfig() {
        return PulsarConnectorConfig.builder()
                .name("pulsar")
                .serviceUrl("pulsar://localhost:6650")
                .buildPrototype();
    }

    private static PulsarIncomingConfig config(int maxMessageBytes) {
        return config(PulsarSchemaType.STRING, maxMessageBytes);
    }

    private static PulsarIncomingConfig config(PulsarSchemaType schema, int maxMessageBytes) {
        return configBuilder(schema, maxMessageBytes).build();
    }

    private static PulsarIncomingConfig.Builder configBuilder(PulsarSchemaType schema, int maxMessageBytes) {
        return PulsarIncomingConfig.builder()
                .connector("pulsar")
                .channelName("in")
                .topic("persistent://public/default/in")
                .schema(schema)
                .maxMessageBytes(maxMessageBytes);
    }

    private static PulsarSchemaProvider schemaProvider(String name, Schema<?> schema) {
        return new PulsarSchemaProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Schema<?> schema() {
                return schema;
            }
        };
    }

    private static final class TestContext implements IncomingConnectorContext {
        private final boolean failDelivery;
        private final boolean blockDelivery;
        private final AtomicBoolean reserved = new AtomicBoolean();
        private final AtomicInteger awaitRunningCalls = new AtomicInteger();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger failedStarts = new AtomicInteger();
        private final AtomicInteger reservationCloses = new AtomicInteger();
        private final AtomicInteger deliveryCloses = new AtomicInteger();
        private final AtomicBoolean allowReservation = new AtomicBoolean(true);
        private final AtomicReference<MessageBatch<?>> deliveredBatch = new AtomicReference<>();
        private final AtomicReference<MessageBatch<?>> failedBatch = new AtomicReference<>();
        private final AtomicReference<RuntimeException> mappingFailure = new AtomicReference<>();
        private final CountDownLatch deliveryEntered = new CountDownLatch(1);
        private final CountDownLatch allowDelivery = new CountDownLatch(1);
        private final CountDownLatch reservationEntered = new CountDownLatch(1);
        private final AtomicReference<RuntimeException> terminalFailure = new AtomicReference<>();
        private Function<MessageBatch<?>, RuntimeException> deliveryFailure;

        private TestContext(boolean failDelivery) {
            this(failDelivery, false);
        }

        private TestContext(boolean failDelivery, boolean blockDelivery) {
            this.failDelivery = failDelivery;
            this.blockDelivery = blockDelivery;
        }

        @Override
        public String channel() {
            return "in";
        }

        @Override
        public int maxDeliveryMessages() {
            return 4;
        }

        @Override
        public boolean awaitRunning() {
            awaitRunningCalls.incrementAndGet();
            return true;
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery() {
            return reservation();
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
            reservationEntered.countDown();
            if (!allowReservation.get()) {
                return Optional.empty();
            }
            reserved.set(true);
            return Optional.of(reservation());
        }

        private ConnectorDeliveryReservation reservation() {
            return new ConnectorDeliveryReservation() {
                @Override
                public ConnectorDelivery start(MessageBatch<?> batch) {
                    deliveredBatch.set(batch);
                    started.incrementAndGet();
                    return delivery(batch);
                }

                @Override
                public ConnectorDelivery startFailed(MessageBatch<?> batch, RuntimeException failure) {
                    failedBatch.set(batch);
                    mappingFailure.set(failure);
                    failedStarts.incrementAndGet();
                    return delivery(batch);
                }

                @Override
                public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
                    return Optional.of(start(batch));
                }

                @Override
                public void close() {
                    reserved.set(false);
                    reservationCloses.incrementAndGet();
                }
            };
        }

        private ConnectorDelivery delivery(MessageBatch<?> batch) {
            if (blockDelivery) {
                return blockingDelivery();
            }
            RuntimeException failure = deliveryFailure == null
                    ? failDelivery ? new IllegalStateException("expected delivery failure") : null
                    : deliveryFailure.apply(batch);
            terminalFailure.set(failure);
            return new ConnectorDelivery() {
                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public boolean isCurrentThread() {
                    return false;
                }

                @Override
                public void await() {
                    if (failure != null) {
                        throw failure;
                    }
                }

                @Override
                public boolean await(Duration timeout) {
                    await();
                    return true;
                }

                @Override
                public void cancel() {
                }

                @Override
                public void close() {
                    deliveryCloses.incrementAndGet();
                }
            };
        }

        private ConnectorDelivery blockingDelivery() {
            return new ConnectorDelivery() {
                @Override
                public boolean isDone() {
                    return allowDelivery.getCount() == 0;
                }

                @Override
                public boolean isCurrentThread() {
                    return false;
                }

                @Override
                public void await() {
                    deliveryEntered.countDown();
                    try {
                        allowDelivery.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MessagingException("Interrupted while waiting for test delivery", e);
                    }
                }

                @Override
                public boolean await(Duration timeout) {
                    deliveryEntered.countDown();
                    try {
                        return allowDelivery.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MessagingException("Interrupted while waiting for test delivery", e);
                    }
                }

                @Override
                public void cancel() {
                    allowDelivery.countDown();
                }

                @Override
                public void close() {
                    deliveryCloses.incrementAndGet();
                }
            };
        }
    }

    private static final class BlockingGateContext implements IncomingConnectorContext {
        private final AtomicBoolean reserved = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public boolean awaitRunning() {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public String channel() {
            return "in";
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery() {
            throw new AssertionError("No reservation expected before running gate opens");
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
            reserved.set(true);
            throw new AssertionError("No reservation expected before running gate opens");
        }
    }

    private static final class FakeSource {
        private final ConcurrentLinkedQueue<List<Message<Object>>> batches = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Object> acknowledgedPayloads = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Object> negativelyAcknowledgedPayloads = new ConcurrentLinkedQueue<>();
        private final Map<Object, CompletableFuture<Void>> ackResults = new ConcurrentHashMap<>();
        private final Map<Object, RuntimeException> ackFailures = new ConcurrentHashMap<>();
        private final Map<Object, RuntimeException> nackFailures = new ConcurrentHashMap<>();
        private final AtomicBoolean reserved;
        private final CompletableFuture<Void> consumerClose;
        private final CompletableFuture<Void> clientClose;
        private final CountDownLatch consumerCloseEntered = new CountDownLatch(1);
        private final CountDownLatch clientCloseEntered = new CountDownLatch(1);
        private final AtomicInteger consumerCloseCalls = new AtomicInteger();
        private final AtomicInteger clientCloseCalls = new AtomicInteger();
        private final AtomicInteger shutdownCalls = new AtomicInteger();
        private final AtomicBoolean receivedAfterReservation = new AtomicBoolean();
        private final AtomicBoolean durable = new AtomicBoolean();
        private final AtomicBoolean startedPaused = new AtomicBoolean();
        private final AtomicBoolean ackReceipts = new AtomicBoolean();
        private final AtomicBoolean batchIndexAcknowledgment = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger acks = new AtomicInteger();
        private final AtomicInteger negativeAcks = new AtomicInteger();
        private final AtomicInteger receiveCalls = new AtomicInteger();
        private final AtomicBoolean emptyFirst = new AtomicBoolean();
        private final AtomicBoolean nullReceiveResult = new AtomicBoolean();
        private final AtomicBoolean paused = new AtomicBoolean(true);
        private final CountDownLatch negativelyAcknowledged = new CountDownLatch(1);
        private final CountDownLatch receiveEntered = new CountDownLatch(1);
        private final AtomicBoolean blockReceive = new AtomicBoolean();
        private final AtomicReference<Schema<?>> schema = new AtomicReference<>();
        private final Consumer<Object> consumer = consumer();
        private final ConsumerBuilder<Object> consumerBuilder = consumerBuilder();
        private final PulsarClient client = clientProxy();
        private CountDownLatch acknowledged = new CountDownLatch(1);
        private Runnable beforeReceiveReturn = () -> { };

        private FakeSource(Message<Object> message, AtomicBoolean reserved) {
            this(message, reserved, CompletableFuture.completedFuture(null), CompletableFuture.completedFuture(null));
        }

        private FakeSource(Message<Object> message,
                           AtomicBoolean reserved,
                           CompletableFuture<Void> consumerClose,
                           CompletableFuture<Void> clientClose) {
            if (message != null) {
                batches.add(List.of(message));
            }
            this.reserved = reserved;
            this.consumerClose = consumerClose;
            this.clientClose = clientClose;
        }

        private static Messages<Object> messages(List<Message<Object>> messages) {
            return new Messages<>() {
                @Override
                public int size() {
                    return messages.size();
                }

                @Override
                public Iterator<Message<Object>> iterator() {
                    return messages.iterator();
                }
            };
        }

        private PulsarClient client() {
            return client;
        }

        private PulsarClient clientProxy() {
            return PulsarTestSupport.proxy(PulsarClient.class, (ignored, method, args) -> switch (method.getName()) {
            case "newConsumer" -> {
                schema.set((Schema<?>) args[0]);
                yield consumerBuilder;
            }
            case "closeAsync" -> {
                clientCloseCalls.incrementAndGet();
                clientCloseEntered.countDown();
                clientClose.thenRun(() -> closed.set(true));
                yield clientClose;
            }
            case "shutdown" -> {
                shutdownCalls.incrementAndGet();
                closed.set(true);
                yield null;
            }
            case "isClosed" -> closed.get();
            default -> PulsarTestSupport.defaultValue(method);
            });
        }

        @SuppressWarnings("unchecked")
        private ConsumerBuilder<Object> consumerBuilder() {
            final ConsumerBuilder<Object>[] reference = new ConsumerBuilder[1];
            reference[0] = PulsarTestSupport.proxy(ConsumerBuilder.class, (ignored, method, args) -> {
                switch (method.getName()) {
                case "subscriptionMode" -> durable.set(args[0] == SubscriptionMode.Durable);
                case "startPaused" -> startedPaused.set((boolean) args[0]);
                case "isAckReceiptEnabled" -> ackReceipts.set((boolean) args[0]);
                case "enableBatchIndexAcknowledgment" -> batchIndexAcknowledgment.set((boolean) args[0]);
                case "maxTotalReceiverQueueSizeAcrossPartitions" ->
                    throw new AssertionError("Connector must not collapse partition queues to zero");
                case "subscribe" -> {
                    return consumer;
                }
                default -> {
                }
                }
                return reference[0];
            });
            return reference[0];
        }

        @SuppressWarnings("unchecked")
        private Consumer<Object> consumer() {
            return PulsarTestSupport.proxy(Consumer.class, (ignored, method, args) -> switch (method.getName()) {
            case "receive" -> throw new AssertionError("Incoming connector must use native batch receive");
            case "batchReceive" -> {
                receiveCalls.incrementAndGet();
                receiveEntered.countDown();
                if (blockReceive.get()) {
                    new CountDownLatch(1).await();
                }
                receivedAfterReservation.set(reserved.get());
                List<Message<Object>> messages = emptyFirst.compareAndSet(true, false) ? List.of() : batches.poll();
                beforeReceiveReturn.run();
                yield nullReceiveResult.get() ? null : messages(messages == null ? List.of() : messages);
            }
            case "acknowledgeAsync" -> {
                acks.incrementAndGet();
                Object payload = ((Message<?>) args[0]).getValue();
                if (payload != null) {
                    acknowledgedPayloads.add(payload);
                }
                acknowledged.countDown();
                RuntimeException failure = payload == null ? null : ackFailures.get(payload);
                if (failure != null) {
                    throw failure;
                }
                yield payload == null ? CompletableFuture.completedFuture(null)
                        : ackResults.getOrDefault(payload, CompletableFuture.completedFuture(null));
            }
            case "negativeAcknowledge" -> {
                negativeAcks.incrementAndGet();
                Object payload = ((Message<?>) args[0]).getValue();
                if (payload != null) {
                    negativelyAcknowledgedPayloads.add(payload);
                }
                negativelyAcknowledged.countDown();
                RuntimeException failure = payload == null ? null : nackFailures.get(payload);
                if (failure != null) {
                    throw failure;
                }
                yield null;
            }
            case "pause" -> {
                paused.set(true);
                yield null;
            }
            case "resume" -> {
                paused.set(false);
                yield null;
            }
            case "closeAsync" -> {
                consumerCloseCalls.incrementAndGet();
                consumerCloseEntered.countDown();
                yield consumerClose;
            }
            default -> PulsarTestSupport.defaultValue(method);
            });
        }

    }
}
