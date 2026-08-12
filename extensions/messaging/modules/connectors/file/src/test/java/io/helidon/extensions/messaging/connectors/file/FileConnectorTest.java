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

package io.helidon.extensions.messaging.connectors.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.BatchAtomicity;
import io.helidon.extensions.messaging.BatchDeliveryException;
import io.helidon.extensions.messaging.BatchItemStatus;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorDelivery;
import io.helidon.extensions.messaging.ConnectorDeliveryReservation;
import io.helidon.extensions.messaging.ConnectorProvider;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.IncomingConnectorContext;
import io.helidon.extensions.messaging.IncomingConnectorProvider;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.MessagingChannel;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.MessagingGraph;
import io.helidon.extensions.messaging.MessagingRejectedException;
import io.helidon.extensions.messaging.OutgoingConnector;
import io.helidon.extensions.messaging.OutgoingConnectorProvider;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileConnectorTest {
    @Test
    void testConnectorProviderTypeAndCapabilities() {
        FileConnectorProvider provider = new FileConnectorProvider();

        assertThat(provider.connectorType(), is(FileConnectorProvider.CONNECTOR_TYPE));
        assertThat(provider, instanceOf(IncomingConnectorProvider.class));
        assertThat(provider, instanceOf(OutgoingConnectorProvider.class));
        assertThat(AutoCloseable.class.isAssignableFrom(provider.getClass()), is(false));
    }

    @Test
    void testMissingPathFails() {
        assertThrows(RuntimeException.class,
                     () -> FileConnectorConfig.builder()
                             .direction(ConnectorConfig.Direction.OUTGOING)
                             .channel("audit")
                             .connector(FileConnectorProvider.CONNECTOR_TYPE)
                             .build());
    }

    @Test
    void testEmptyLineSeparatorFails(@TempDir Path tempDir) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                                                        () -> config(Path.of("audit.log"), ""));

        assertThat(failure.getMessage(), is("line-separator must not be empty"));

        RuntimeException configFailure = assertThrows(RuntimeException.class,
                                                      () -> new FileConnectorProvider().createOutgoingConnector(
                                                              Config.just(ConfigSources.create(Map.of(
                                                                      "direction", "OUTGOING",
                                                                      ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "audit",
                                                                      ConnectorConfig.CONNECTOR_ATTRIBUTE,
                                                                      FileConnectorProvider.CONNECTOR_TYPE,
                                                                      FileConnectorConfig.PATH_PROPERTY,
                                                                      tempDir.resolve("audit.log").toString(),
                                                                      FileConnectorConfig.LINE_SEPARATOR_PROPERTY, "")))));
        assertThat(configFailure.getMessage(), is("line-separator must not be empty"));
    }

    @Test
    void testCreateFromConfig(@TempDir Path tempDir) {
        Path auditLog = tempDir.resolve("audit.log");
        FileConnectorConfig config = FileConnectorConfig.create(Config.just(ConfigSources.create(Map.of(
                "direction", "OUTGOING",
                ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "audit",
                ConnectorConfig.CONNECTOR_ATTRIBUTE, FileConnectorProvider.CONNECTOR_TYPE,
                FileConnectorConfig.PATH_PROPERTY, auditLog.toString(),
                FileConnectorConfig.LINE_SEPARATOR_PROPERTY, "|",
                FileConnectorConfig.MAX_LINE_BYTES_PROPERTY, "2048",
                FileConnectorConfig.MAX_BATCH_BYTES_PROPERTY, "4096"))));

        assertThat(config.direction(), is(ConnectorConfig.Direction.OUTGOING));
        assertThat(config.channel(), is("audit"));
        assertThat(config.connector(), is(FileConnectorProvider.CONNECTOR_TYPE));
        assertThat(config.path(), is(auditLog));
        assertThat(config.lineSeparator(), is("|"));
        assertThat(config.maxLineBytes(), is(2048));
        assertThat(config.maxBatchBytes(), is(4096L));
        assertThat(config(auditLog).maxLineBytes(), is(FileConnectorConfig.DEFAULT_MAX_LINE_BYTES));
        assertThat(config(auditLog).maxBatchBytes(), is(FileConnectorConfig.DEFAULT_MAX_BATCH_BYTES));
    }

    @Test
    void testMaxLineBytesMustBePositive(@TempDir Path tempDir) {
        for (String value : List.of("0", "-1")) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                                                    () -> new FileConnectorProvider().createIncomingConnector(
                                                            Config.just(ConfigSources.create(Map.of(
                                                                    "direction", "INCOMING",
                                                                    ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "events",
                                                                    ConnectorConfig.CONNECTOR_ATTRIBUTE,
                                                                    FileConnectorProvider.CONNECTOR_TYPE,
                                                                    FileConnectorConfig.PATH_PROPERTY,
                                                                    tempDir.resolve("events.log").toString(),
                                                                    FileConnectorConfig.MAX_LINE_BYTES_PROPERTY, value)))));
            assertThat(failure.getMessage(), is("max-line-bytes must be greater than zero"));
        }
    }

    @Test
    void testMaxBatchBytesMustBePositive(@TempDir Path tempDir) {
        for (String value : List.of("0", "-1")) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                                                    () -> new FileConnectorProvider().createIncomingConnector(
                                                            Config.just(ConfigSources.create(Map.of(
                                                                    "direction", "INCOMING",
                                                                    ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "events",
                                                                    ConnectorConfig.CONNECTOR_ATTRIBUTE,
                                                                    FileConnectorProvider.CONNECTOR_TYPE,
                                                                    FileConnectorConfig.PATH_PROPERTY,
                                                                    tempDir.resolve("events.log").toString(),
                                                                    FileConnectorConfig.MAX_BATCH_BYTES_PROPERTY, value)))));
            assertThat(failure.getMessage(), is("max-batch-bytes must be greater than zero"));
        }
    }

    @Test
    void testMaxLineBytesMustNotExceedMaxBatchBytes(@TempDir Path tempDir) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> incomingConfig(tempDir.resolve("events.log"), FileConnectorConfig.DEFAULT_LINE_SEPARATOR, 5, 4));

        assertThat(failure.getMessage(), is("max-line-bytes must not exceed max-batch-bytes"));
    }

    @Test
    void testOutgoingConnectorsAreResourceFreeAndLifecycleIndependent(@TempDir Path tempDir) throws IOException {
        Path firstPath = tempDir.resolve("first.log");
        Path secondPath = tempDir.resolve("second.log");
        Path thirdPath = tempDir.resolve("third.log");
        FileConnectorProvider provider = new FileConnectorProvider();
        OutgoingConnector first = provider.createOutgoingConnector(config(firstPath));
        OutgoingConnector second = provider.createOutgoingConnector(config(secondPath));

        assertThat(Files.exists(firstPath), is(false));
        assertThat(Files.exists(secondPath), is(false));
        BatchDeliveryException beforeStart = assertThrows(
                BatchDeliveryException.class,
                () -> first.send(Message.create("before start")));
        assertThat(beforeStart.outcome(0).status(), is(BatchItemStatus.NOT_ATTEMPTED));
        assertThat(beforeStart.getCause(), instanceOf(IllegalStateException.class));

        first.start();
        second.start();
        assertThat(Files.exists(firstPath), is(false));
        assertThat(Files.exists(secondPath), is(false));

        first.close();
        first.close();
        BatchDeliveryException firstClosed = assertThrows(
                BatchDeliveryException.class,
                () -> first.send(Message.create("after close")));
        assertThat(firstClosed.outcome(0).status(), is(BatchItemStatus.NOT_ATTEMPTED));
        assertThat(firstClosed.getCause(), instanceOf(IllegalStateException.class));

        second.send(Message.create("second remains available"));
        assertThat(Files.readString(secondPath), is("second remains available\n"));

        OutgoingConnector third = provider.createOutgoingConnector(config(thirdPath));
        third.start();
        third.send(Message.create("provider remains available"));
        third.close();
        second.close();

        assertThat(Files.readString(thirdPath), is("provider remains available\n"));
    }

    @Test
    @Timeout(value = 5)
    void testForceCloseInterruptsBlockedOutgoingWriteWithoutAffectingSibling(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicBoolean writeInterrupted = new AtomicBoolean();
        AtomicReference<Throwable> sendFailure = new AtomicReference<>();
        AtomicReference<Throwable> forceCloseFailure = new AtomicReference<>();
        OutgoingConnector blocked = FileOutgoingConnector.createConnector(config(auditLog), (path, content) -> {
            writeStarted.countDown();
            try {
                releaseWrite.await();
            } catch (InterruptedException e) {
                writeInterrupted.set(true);
                Thread.currentThread().interrupt();
                throw new MessagingException("Expected interrupted test write", e);
            }
        });
        OutgoingConnector sibling = new FileConnectorProvider().createOutgoingConnector(config(auditLog));
        blocked.start();
        sibling.start();
        Thread sendThread = Thread.ofVirtual().start(() -> {
            try {
                blocked.send(Message.create("blocked"));
            } catch (Throwable t) {
                sendFailure.set(t);
            }
        });
        assertThat(writeStarted.await(1, TimeUnit.SECONDS), is(true));

        Thread forceCloseThread = Thread.ofVirtual().start(() -> {
            try {
                blocked.forceClose();
            } catch (Throwable t) {
                forceCloseFailure.set(t);
            }
        });
        forceCloseThread.join(TimeUnit.SECONDS.toMillis(1));
        boolean forceCloseReturnedPromptly = !forceCloseThread.isAlive();
        releaseWrite.countDown();
        forceCloseThread.join(TimeUnit.SECONDS.toMillis(1));
        sendThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(forceCloseReturnedPromptly, is(true));
        assertThat(forceCloseFailure.get(), nullValue());
        assertThat(writeInterrupted.get(), is(true));
        assertThat(sendThread.isAlive(), is(false));
        assertThat(sendFailure.get(), instanceOf(MessagingException.class));
        BatchDeliveryException blockedClosed = assertThrows(
                BatchDeliveryException.class,
                () -> blocked.send(Message.create("after close")));
        assertThat(blockedClosed.outcome(0).status(), is(BatchItemStatus.NOT_ATTEMPTED));
        assertThat(blockedClosed.getCause(), instanceOf(IllegalStateException.class));

        sibling.send(Message.create("sibling remains available"));
        sibling.close();
        assertThat(Files.readString(auditLog), is("sibling remains available\n"));
    }

    @Test
    @Timeout(value = 5)
    void testGracefulCloseAllowsAdmittedOutgoingWriteToSettle(@TempDir Path tempDir) throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicBoolean writeInterrupted = new AtomicBoolean();
        AtomicReference<String> written = new AtomicReference<>();
        AtomicReference<Throwable> sendFailure = new AtomicReference<>();
        OutgoingConnector connector = FileOutgoingConnector.createConnector(
                config(tempDir.resolve("audit.log")),
                (path, content) -> {
                    writeStarted.countDown();
                    try {
                        releaseWrite.await();
                        written.set(content);
                    } catch (InterruptedException e) {
                        writeInterrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw new MessagingException("Unexpected interrupted test write", e);
                    }
                });
        connector.start();
        Thread sendThread = Thread.ofVirtual().start(() -> {
            try {
                connector.send(Message.create("admitted"));
            } catch (Throwable t) {
                sendFailure.set(t);
            }
        });
        assertThat(writeStarted.await(1, TimeUnit.SECONDS), is(true));

        connector.close();
        releaseWrite.countDown();
        sendThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(sendThread.isAlive(), is(false));
        assertThat(sendFailure.get(), nullValue());
        assertThat(writeInterrupted.get(), is(false));
        assertThat(written.get(), is("admitted\n"));
        BatchDeliveryException closed = assertThrows(
                BatchDeliveryException.class,
                () -> connector.send(Message.create("after close")));
        assertThat(closed.outcome(0).status(), is(BatchItemStatus.NOT_ATTEMPTED));
        assertThat(closed.getCause(), instanceOf(IllegalStateException.class));
    }

    @Test
    @Timeout(value = 5)
    void testIncomingConnectorsAreResourceFreeAndLifecycleIndependent(@TempDir Path tempDir) throws Exception {
        Path firstPath = tempDir.resolve("first.log");
        Path secondPath = tempDir.resolve("second.log");
        FileConnectorProvider provider = new FileConnectorProvider();
        CountDownLatch secondDelivered = new CountDownLatch(1);
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        IncomingConnectorContext firstContext = incomingContext(ignored -> { }, firstReady);
        IncomingConnectorContext secondContext = incomingContext(ignored -> secondDelivered.countDown(), secondReady);
        IncomingConnector first = provider.createIncomingConnector(incomingConfig(firstPath));
        IncomingConnector second = provider.createIncomingConnector(incomingConfig(secondPath));

        assertThat(Files.exists(firstPath), is(false));
        assertThat(Files.exists(secondPath), is(false));

        Thread firstThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> firstFailure.set(throwable))
                .start(() -> first.run(firstContext));
        Thread secondThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> secondFailure.set(throwable))
                .start(() -> second.run(secondContext));
        assertThat(firstReady.await(1, TimeUnit.SECONDS), is(true));
        assertThat(secondReady.await(1, TimeUnit.SECONDS), is(true));

        first.close();
        firstThread.join(TimeUnit.SECONDS.toMillis(1));
        assertThat(firstThread.isAlive(), is(false));
        assertThat(secondThread.isAlive(), is(true));

        append(secondPath, "second remains available\n");
        assertThat(secondDelivered.await(1, TimeUnit.SECONDS), is(true));

        second.drain();
        secondThread.join(TimeUnit.SECONDS.toMillis(1));
        second.close();
        IncomingConnector third = provider.createIncomingConnector(incomingConfig(tempDir.resolve("third.log")));
        third.close();

        assertThat(secondThread.isAlive(), is(false));
        assertThat(firstFailure.get(), nullValue());
        assertThat(secondFailure.get(), nullValue());
    }

    @Test
    void testDefaultLineSeparatorWritesOneMessagePerLine(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = outgoingConnector(config(auditLog));

        sink.send(Message.create("first audit event"));
        sink.send(Message.create("second audit event"));

        assertThat(Files.readString(auditLog), is("first audit event\nsecond audit event\n"));
    }

    @Test
    void testCustomLineSeparator(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = outgoingConnector(config(auditLog, "|"));

        sink.send(Message.create("first audit event"));
        sink.send(Message.create("second audit event"));

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
    }

    @Test
    void testBatchWritesMessagesInOneConnectorCall(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = outgoingConnector(config(auditLog, "|"));

        sink.sendBatch(MessageBatch.create(List.of(Message.create("first audit event"),
                                                   Message.create("second audit event"))));

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
        assertThat(sink.batchAtomicity(), is(BatchAtomicity.PER_MESSAGE));
    }

    @Test
    void testBatchWriteFailureReportsEveryOutcomeAsIndeterminate(@TempDir Path tempDir) {
        RuntimeException expectedFailure = new MessagingException("expected write failure");
        OutgoingConnector sink = FileOutgoingConnector.createConnector(config(tempDir.resolve("audit.log")),
                                                                     (path, content) -> {
                                                                         throw expectedFailure;
                                                                     });
        sink.start();
        MessageBatch<String> batch = MessageBatch.<String>builder()
                .id("file-batch")
                .add(Message.create("first audit event"))
                .add(Message.create("second audit event"))
                .build();

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> sink.sendBatch(batch));

        assertThat(failure.batch(), sameInstance(batch));
        assertThat(failure.getCause(), sameInstance(expectedFailure));
        assertThat(failure.outcomes().stream().map(outcome -> outcome.status()).toList(),
                   is(List.of(BatchItemStatus.INDETERMINATE, BatchItemStatus.INDETERMINATE)));
        assertThat(failure.outcomes().stream().map(outcome -> outcome.index()).toList(), is(List.of(0, 1)));
    }

    @Test
    @Timeout(value = 5)
    void testBatchInterruptedBeforeWriteReportsEveryItemNotAttempted(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        OutgoingConnector lockOwner = FileOutgoingConnector.createConnector(config(auditLog), (path, content) -> {
            writeStarted.countDown();
            try {
                releaseWrite.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Unexpected interrupted lock owner", e);
            }
        });
        OutgoingConnector waiting = FileOutgoingConnector.createConnector(config(auditLog));
        lockOwner.start();
        waiting.start();
        Thread lockOwnerThread = Thread.ofVirtual().start(() -> lockOwner.send(Message.create("owner")));
        assertThat(writeStarted.await(1, TimeUnit.SECONDS), is(true));

        CountDownLatch readyForLock = new CountDownLatch(1);
        Object first = new Object() {
            @Override
            public String toString() {
                readyForLock.countDown();
                return "first";
            }
        };
        MessageBatch<Object> batch = MessageBatch.create(List.of(Message.create(first), Message.create("second")));
        AtomicReference<Throwable> sendFailure = new AtomicReference<>();
        Thread waitingThread = Thread.ofVirtual().start(() -> {
            try {
                waiting.sendBatch(batch);
            } catch (Throwable t) {
                sendFailure.set(t);
            }
        });
        assertThat(readyForLock.await(1, TimeUnit.SECONDS), is(true));
        waiting.forceClose();
        waitingThread.join(TimeUnit.SECONDS.toMillis(1));
        releaseWrite.countDown();
        lockOwnerThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(waitingThread.isAlive(), is(false));
        assertThat(sendFailure.get(), instanceOf(BatchDeliveryException.class));
        BatchDeliveryException failure = (BatchDeliveryException) sendFailure.get();
        assertThat(failure.batch(), sameInstance(batch));
        assertThat(failure.outcomes().stream().map(outcome -> outcome.status()).toList(),
                   is(List.of(BatchItemStatus.NOT_ATTEMPTED, BatchItemStatus.NOT_ATTEMPTED)));
    }

    @Test
    void testBatchLifecycleFailureReportsEveryItemNotAttempted(@TempDir Path tempDir) {
        OutgoingConnector sink = FileOutgoingConnector.createConnector(config(tempDir.resolve("audit.log")));
        MessageBatch<String> batch = MessageBatch.create(List.of(Message.create("first"), Message.create("second")));

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class, () -> sink.sendBatch(batch));

        assertThat(failure.batch(), sameInstance(batch));
        assertThat(failure.getCause(), instanceOf(IllegalStateException.class));
        assertThat(failure.outcomes().stream().map(outcome -> outcome.status()).toList(),
                   is(List.of(BatchItemStatus.NOT_ATTEMPTED, BatchItemStatus.NOT_ATTEMPTED)));
    }

    @Test
    void testBatchEncodingFailureReportsEveryItemNotAttempted(@TempDir Path tempDir) {
        RuntimeException expectedFailure = new RuntimeException("expected encoding failure");
        Object invalidPayload = new Object() {
            @Override
            public String toString() {
                throw expectedFailure;
            }
        };
        Path auditLog = tempDir.resolve("audit.log");
        OutgoingConnector sink = outgoingConnector(config(auditLog));
        MessageBatch<Object> batch = MessageBatch.create(List.of(Message.create("first"),
                                                                 Message.create(invalidPayload)));

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class, () -> sink.sendBatch(batch));

        assertThat(failure.batch(), sameInstance(batch));
        assertThat(failure.getCause(), sameInstance(expectedFailure));
        assertThat(failure.outcomes().stream().map(outcome -> outcome.status()).toList(),
                   is(List.of(BatchItemStatus.NOT_ATTEMPTED, BatchItemStatus.NOT_ATTEMPTED)));
        assertThat(Files.exists(auditLog), is(false));
    }

    @Test
    @Timeout(value = 10)
    void testConcurrentLargeWritesRemainFramedAcrossSinks(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        FileConnectorProvider provider = new FileConnectorProvider();
        int writerCount = 8;
        List<String> expected = new ArrayList<>();
        List<Thread> writers = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < writerCount; i++) {
            Path configuredPath = i % 2 == 0
                    ? auditLog
                    : tempDir.resolve(".").resolve("audit.log");
            OutgoingConnector sink = provider.createOutgoingConnector(config(configuredPath));
            sink.start();
            String payload = "writer-" + i + ":" + String.valueOf((char) ('a' + i)).repeat(256 * 1024);
            expected.add(payload);
            writers.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    sink.send(Message.create(payload));
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }

        start.countDown();
        for (Thread writer : writers) {
            writer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(writer.isAlive(), is(false));
        }

        assertThat(failure.get(), nullValue());
        List<String> actual = Files.readAllLines(auditLog);
        Collections.sort(expected);
        Collections.sort(actual);
        assertThat(actual, is(expected));
    }

    @Test
    void testParentDirectoriesAreCreated(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("logs").resolve("audit.log");

        outgoingConnector(config(auditLog))
                .send(Message.create("audit event"));

        assertThat(Files.readString(auditLog), is("audit event\n"));
    }

    @Test
    void testFileConnectorCanBeUsedAsChannelOutput(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");

        MessagingGraph.Builder builder = MessagingGraph.builder();
        MessagingChannel<String> channel = builder.channel("file-output", String.class);
        builder.outgoingConnector(channel, outgoingConnector(config(auditLog, "|")));

        try (MessagingGraph graph = builder.build()) {
            graph.start();
            graph.emitter(channel).emit("first audit event");
            graph.emitter(channel).emitMessage(Message.builder("second audit event")
                                                       .header("key", "value")
                                                       .build());

            assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
        }
    }

    @Test
    void testFileConnectorCanBeUsedAsChannelBatchOutput(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");

        MessagingGraph.Builder builder = MessagingGraph.builder();
        MessagingChannel<String> channel = builder.channel("file-batch-output", String.class);
        builder.outgoingConnector(channel, outgoingConnector(config(auditLog, "|")));

        try (MessagingGraph graph = builder.build()) {
            graph.start();
            graph.emitter(channel).emitBatch(MessageBatch.create(List.of(Message.create("first audit event"),
                                                                         Message.builder("second audit event")
                                                                                 .header("key", "value")
                                                                                 .build())));

            assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
        }
    }

    @Test
    void testRuntimeCompletionAdvancesBeforeLaterLinesAreRead(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\nsecond\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicInteger delivery = new AtomicInteger();
        IncomingConnectorContext context = new TrackingReservationContext(10, messages -> {
            deliveries.add(entities(messages));
            if (delivery.getAndIncrement() == 0) {
                append(input, "third\n");
            }
        });
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries, is(List.of(List.of("first", "second"), List.of("third"))));
        assertThat(offset, is(Math.toIntExact(Files.size(input))));
    }

    @Test
    void testFailedRuntimeDeliveryLeavesBatchAvailableForRedelivery(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\nsecond\n");
        RuntimeException expected = new IllegalStateException("expected runtime delivery failure");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        IncomingConnectorContext context = new TrackingReservationContext(10, messages -> {
            deliveries.add(entities(messages));
            if (fail.getAndSet(false)) {
                throw expected;
            }
        });
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        RuntimeException failure = assertThrows(RuntimeException.class, () -> source.emitAppendedLines(input, 0));
        int offset = source.emitAppendedLines(input, 0);

        assertThat(failure, sameInstance(expected));
        assertThat(deliveries,
                   is(List.of(List.of("first", "second"), List.of("first", "second"))));
        assertThat(offset, is(Math.toIntExact(Files.size(input))));
    }

    @Test
    void testSuccessfulRuntimeDeliveryRevalidatesSameLengthReplacement(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String original = "old-one\nold-two\n";
        String replacement = "new-one\nnew-two\n";
        Files.writeString(input, original);
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean replaced = new AtomicBoolean();
        IncomingConnectorContext context = new TrackingReservationContext(10, messages -> {
            deliveries.add(entities(messages));
            if (replaced.compareAndSet(false, true)) {
                replace(input, replacement);
            }
        });
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries, is(List.of(List.of("old-one", "old-two"), List.of("new-one", "new-two"))));
        assertThat(offset, is(replacement.length()));
    }

    @Test
    void testSuccessfulRuntimeDeliveryRevalidatesLargerReplacement(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String replacement = "replacement-first\nreplacement-second\n";
        Files.writeString(input, "old\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean replaced = new AtomicBoolean();
        IncomingConnectorContext context = new TrackingReservationContext(10, messages -> {
            deliveries.add(entities(messages));
            if (replaced.compareAndSet(false, true)) {
                replace(input, replacement);
            }
        });
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries,
                   is(List.of(List.of("old"), List.of("replacement-first", "replacement-second"))));
        assertThat(offset, is(replacement.length()));
    }


    @Test
    void testIncompleteTrailingUtf8SequenceWaitsForRemainingBytes(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        byte[] emoji = "\uD83D\uDE00".getBytes(StandardCharsets.UTF_8);
        Files.writeString(input, "one\n");
        Files.write(input, Arrays.copyOf(emoji, 2), StandardOpenOption.APPEND);
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = incomingContext(messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input, emoji.length),
                                                          context,
                                                          new AtomicBoolean());
        FileIncomingConnector.FileCursor cursor = source.currentCursor(input, 0);

        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of("one"))));
        assertThat(cursor.offset(), is((long) "one\n".getBytes(StandardCharsets.UTF_8).length));

        Files.write(input,
                    new byte[] {emoji[2], emoji[3], '\n'},
                    StandardOpenOption.APPEND);
        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of("one"), List.of("\uD83D\uDE00"))));
        assertThat(cursor.offset(), is(Files.size(input)));
    }

    @Test
    void testIncomingLinesAreChunkedByMessageLimit(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\nsecond\nthird\nfourth\nfifth\n");
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(2, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries,
                   is(List.of(List.of("first", "second"),
                              List.of("third", "fourth"),
                              List.of("fifth"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testUnreadTailRewriteDoesNotReplayCommittedLine(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "good\nx\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean rewritten = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(1, messages -> {
            deliveries.add(entities(messages));
            if (rewritten.compareAndSet(false, true)) {
                try {
                    Files.writeString(input, "good\ny\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input),
                                                          context,
                                                          new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(rewritten.get(), is(true));
        assertThat(deliveries, is(List.of(List.of("good"), List.of("y"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testIncompleteTailCorrectionDoesNotReplayCommittedLine(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "good\n");
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(10, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input),
                                                          context,
                                                          new AtomicBoolean());
        FileIncomingConnector.FileCursor cursor = source.currentCursor(input, 0);

        cursor = source.emitAppendedLines(input, cursor);
        append(input, "old");
        cursor = source.emitAppendedLines(input, cursor);
        Files.writeString(input, "good\nnew\n");
        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of("good"), List.of("new"))));
        assertThat(cursor.offset(), is(Files.size(input)));
    }

    @Test
    void testShorterIncompleteTailCorrectionDoesNotReplayCommittedLine(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "good\nlong-tail");
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(10, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input),
                                                          context,
                                                          new AtomicBoolean());
        FileIncomingConnector.FileCursor cursor = source.currentCursor(input, 0);

        cursor = source.emitAppendedLines(input, cursor);
        assertThat(deliveries, is(List.of(List.of("good"))));
        assertThat(cursor.offset(), is((long) "good\n".getBytes(StandardCharsets.UTF_8).length));

        Files.writeString(input, "good\nx\n");
        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of("good"), List.of("x"))));
        assertThat(cursor.offset(), is(Files.size(input)));
    }

    @Test
    void testChunkReadStartsAfterPendingReservation(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\n");
        TrackingReservationContext context = new TrackingReservationContext(4, ignored -> {
        });
        AtomicBoolean readObserved = new AtomicBoolean();
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                path -> {
                    assertThat(context.hasOpenReservation(), is(true));
                    readObserved.set(true);
                });

        source.emitAppendedLines(input, 0);

        assertThat(readObserved.get(), is(true));
        assertThat(context.reservations().getFirst().reservedMessages(), is(4));
    }

    @Test
    void testEmptyChunkReadReleasesPendingReservation(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "");
        TrackingReservationContext context = new TrackingReservationContext(4, ignored -> {
        });
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(offset, is(0));
        assertThat(context.reservations().size(), is(1));
        TrackingReservation reservation = context.reservations().getFirst();
        assertThat(reservation.started(), is(false));
        assertThat(reservation.closed(), is(true));
    }

    @Test
    void testActualChunkShrinksPendingReservationBeforeStart(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "one\n");
        TrackingReservationContext context = new TrackingReservationContext(10, ignored -> {
        });
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(offset, is((int) Files.size(input)));
        TrackingReservation started = context.reservations().getFirst();
        assertThat(started.reservedMessages(), is(10));
        assertThat(started.actualMessages(), is(1));
        assertThat(started.delivery().closed(), is(true));
        TrackingReservation empty = context.reservations().get(1);
        assertThat(empty.started(), is(false));
        assertThat(empty.closed(), is(true));
    }

    @Test
    void testStartedDeliveryLeaseIsRetainedUntilRuntimeCompletion(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "one\n");
        AtomicReference<TrackingReservationContext> contextRef = new AtomicReference<>();
        AtomicBoolean leaseRetainedDuringDelivery = new AtomicBoolean();
        TrackingReservationContext context = new TrackingReservationContext(10, ignored -> {
            TrackingDelivery delivery = contextRef.get().reservations().getFirst().delivery();
            leaseRetainedDuringDelivery.set(delivery != null && !delivery.closed());
        });
        contextRef.set(context);
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(offset, is((int) Files.size(input)));
        assertThat(leaseRetainedDuringDelivery.get(), is(true));
        assertThat(context.reservations().getFirst().delivery().closed(), is(true));
    }

    @Test
    void testBoundedChunksDetectFileReplacement(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String original = "old-one\nold-two\n";
        String replacement = "new-one\nnew-two\n";
        assertThat(replacement.length(), is(original.length()));
        Files.writeString(input, original);
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean replaced = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(1, messages -> {
            deliveries.add(entities(messages));
            if (replaced.compareAndSet(false, true)) {
                replace(input, replacement);
            }
        });
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries,
                   is(List.of(List.of("old-one"),
                              List.of("new-one"),
                              List.of("new-two"))));
        assertThat(offset, is(replacement.length()));
    }

    @Test
    void testBoundedChunksDetectTruncation(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "old-one\nold-two\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean truncated = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(1, messages -> {
            deliveries.add(entities(messages));
            if (truncated.compareAndSet(false, true)) {
                try {
                    Files.writeString(input, "new\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries, is(List.of(List.of("old-one"), List.of("new"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testSameFileRewriteDuringReadRetriesWithoutMixedContent(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String originalPayload = "old-" + "a".repeat(16_384);
        String replacementPayload = "new-" + "b".repeat(16_384);
        String replacement = replacementPayload + "\n";
        Files.writeString(input, originalPayload + "\n");
        var lastModified = Files.getLastModifiedTime(input);
        assertThat(replacement.length(), is((int) Files.size(input)));
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean rewritten = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(2, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterRead(Path path) throws IOException {
                if (rewritten.compareAndSet(false, true)) {
                    Files.writeString(path, replacement);
                    Files.setLastModifiedTime(path, lastModified);
                }
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);

        int offset = source.emitAppendedLines(input, 0);

        assertThat(rewritten.get(), is(true));
        assertThat(deliveries, is(List.of(List.of(replacementPayload))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testSameFileRewriteAndAppendDuringFinalValidationRetriesWithRestoredTimestamp(@TempDir Path tempDir)
            throws Exception {
        int guardBytes = 8_192;
        String originalPayload = "a".repeat(guardBytes * 2);
        String replacementPayload = "b" + originalPayload.substring(1);
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, originalPayload + "\n");
        FileTime originalTime = Files.getLastModifiedTime(input);
        long framedBytes = Files.size(input);
        long finalValidationStart = framedBytes * 2 + guardBytes;
        List<List<String>> deliveries = new ArrayList<>();
        AtomicLong validationBytes = new AtomicLong();
        AtomicBoolean rewritten = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(2, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterValidationRead(Path path, int bytes) throws IOException {
                if (validationBytes.addAndGet(bytes) > finalValidationStart
                        && rewritten.compareAndSet(false, true)) {
                    Files.write(path, new byte[] {'b'}, StandardOpenOption.WRITE);
                    append(path, "later\n");
                    Files.setLastModifiedTime(path, originalTime);
                }
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);

        int offset = source.emitAppendedLines(input, 0);

        assertThat(rewritten.get(), is(true));
        assertThat(deliveries, is(List.of(List.of(replacementPayload, "later"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testContinuousAppendDuringValidationDoesNotExhaustSnapshotAttempts(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "initial\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicInteger validationReads = new AtomicInteger();
        AtomicInteger appends = new AtomicInteger();
        IncomingConnectorContext context = boundedContext(1, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
                validationReads.set(0);
            }

            @Override
            public void afterValidationRead(Path path, int bytes) {
                if (validationReads.incrementAndGet() == 2 && appends.get() < 3) {
                    int append = appends.incrementAndGet();
                    FileConnectorTest.append(path, "tail-" + append + "\n");
                }
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);

        int offset = source.emitAppendedLines(input, 0);

        assertThat(appends.get(), is(3));
        assertThat(deliveries,
                   is(List.of(List.of("initial"),
                              List.of("tail-1"),
                              List.of("tail-2"),
                              List.of("tail-3"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testBoundedChunksDetectRewriteOfPreviouslyCommittedWindow(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String originalFirst = "old-" + "a".repeat(9_000);
        String replacementFirst = "new-" + "b".repeat(9_000);
        String second = "second-" + "c".repeat(9_000);
        assertThat(replacementFirst.length(), is(originalFirst.length()));
        Files.writeString(input, originalFirst + "\n" + second + "\n");
        long secondOffset = (originalFirst + "\n").getBytes(StandardCharsets.UTF_8).length;
        var lastModified = Files.getLastModifiedTime(input);
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean rewritten = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(1, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterRead(Path path, long offset, int bytes) throws IOException {
                if (offset >= secondOffset && rewritten.compareAndSet(false, true)) {
                    Files.write(path,
                                (replacementFirst + "\n").getBytes(StandardCharsets.UTF_8),
                                StandardOpenOption.WRITE);
                    Files.setLastModifiedTime(path, lastModified);
                }
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);

        int offset = source.emitAppendedLines(input, 0);

        assertThat(rewritten.get(), is(true));
        assertThat(deliveries,
                   is(List.of(List.of(originalFirst), List.of(replacementFirst), List.of(second))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testCurrentCursorRetriesSameSizeRewriteWithRestoredTimestamp(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String originalFirst = "old-first";
        String originalSecond = "old-second";
        String replacementFirst = "new-first";
        String replacementSecond = "new-second";
        String original = originalFirst + "\n" + originalSecond + "\n";
        String replacement = replacementFirst + "\n" + replacementSecond + "\n";
        assertThat(replacement.length(), is(original.length()));
        Files.writeString(input, original);
        var lastModified = Files.getLastModifiedTime(input);
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean rewritten = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(2, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterCursorRead(Path path) throws IOException {
                if (rewritten.compareAndSet(false, true)) {
                    Files.writeString(path, replacement);
                    Files.setLastModifiedTime(path, lastModified);
                }
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);

        int offset = source.emitAppendedLines(input, originalFirst.length() + 1);

        assertThat(rewritten.get(), is(true));
        assertThat(deliveries, is(List.of(List.of(replacementSecond))));
        assertThat(offset, is(replacement.length()));
    }

    @Test
    void testCurrentCursorReadsOnlyBoundedGuard(@TempDir Path tempDir) throws Exception {
        int guardBytes = 8_192;
        Path input = tempDir.resolve("events.log");
        Files.write(input, new byte[guardBytes * 4]);
        AtomicLong cursorBytes = new AtomicLong();
        AtomicLong validationBytes = new AtomicLong();
        IncomingConnectorContext context = boundedContext(1, messages -> {
        });
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterCursorRead(Path path, long offset, int bytes) {
                cursorBytes.addAndGet(bytes);
            }

            @Override
            public void afterValidationRead(Path path, int bytes) {
                validationBytes.addAndGet(bytes);
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);

        FileIncomingConnector.FileCursor cursor =
                source.currentCursor(input, Math.toIntExact(Files.size(input)));

        assertThat(cursor.offset(), is(Files.size(input)));
        assertThat(cursorBytes.get(), is((long) guardBytes));
        assertThat(validationBytes.get(), is((long) guardBytes));
    }

    @Test
    void testCommittedCursorGuardRemainsBoundedAfterLargeDelivery(@TempDir Path tempDir) throws Exception {
        int guardBytes = 8_192;
        String payload = "x".repeat(guardBytes * 4);
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, payload + "\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicLong validationBytes = new AtomicLong();
        IncomingConnectorContext context = boundedContext(1, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterValidationRead(Path path, int bytes) {
                validationBytes.addAndGet(bytes);
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);
        FileIncomingConnector.FileCursor cursor = source.currentCursor(input, 0);

        cursor = source.emitAppendedLines(input, cursor);
        validationBytes.set(0);
        append(input, "y");
        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of(payload))));
        assertThat(cursor.offset(), is((long) payload.getBytes(StandardCharsets.UTF_8).length + 1));
        assertThat(validationBytes.get() <= guardBytes * 2L + 1, is(true));
    }

    @Test
    void testTinyDeliveryPreservesPriorCommittedGuardWindow(@TempDir Path tempDir) throws Exception {
        int guardBytes = 8_192;
        String originalPayload = "a".repeat(guardBytes * 2);
        int rewrittenOffset = originalPayload.length() - 100;
        String rewrittenPayload = originalPayload.substring(0, rewrittenOffset)
                + "b"
                + originalPayload.substring(rewrittenOffset + 1);
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, originalPayload + "\n");
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(10, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input),
                                                          context,
                                                          new AtomicBoolean());
        FileIncomingConnector.FileCursor cursor = source.currentCursor(input, 0);

        cursor = source.emitAppendedLines(input, cursor);
        append(input, "tiny\n");
        cursor = source.emitAppendedLines(input, cursor);

        FileTime lastModified = Files.getLastModifiedTime(input);
        try (FileChannel channel = FileChannel.open(input, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {'b'}), rewrittenOffset);
        }
        Files.setLastModifiedTime(input, lastModified);
        append(input, "next\n");
        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries,
                   is(List.of(List.of(originalPayload),
                              List.of("tiny"),
                              List.of(rewrittenPayload, "tiny", "next"))));
        assertThat(cursor.offset(), is(Files.size(input)));
    }

    @Test
    void testCompletedIncrementalLineRevalidatesAllPreviouslyScannedBytes(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "old");
        var lastModified = Files.getLastModifiedTime(input);
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(1, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input),
                                                          context,
                                                          new AtomicBoolean());
        FileIncomingConnector.FileCursor cursor = source.currentCursor(input, 0);

        cursor = source.emitAppendedLines(input, cursor);
        append(input, "x");
        cursor = source.emitAppendedLines(input, cursor);
        Files.write(input, new byte[] {'n'}, StandardOpenOption.WRITE);
        Files.setLastModifiedTime(input, lastModified);
        append(input, "\n");
        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of("nldx"))));
        assertThat(cursor.offset(), is(Files.size(input)));
    }

    @Test
    void testSameFileTruncationDuringReadRetriesFromBeginning(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "old-" + "a".repeat(16_384) + "\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean truncated = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(2, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterRead(Path path) throws IOException {
                if (truncated.compareAndSet(false, true)) {
                    Files.writeString(path, "new\n");
                }
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);

        int offset = source.emitAppendedLines(input, 0);

        assertThat(truncated.get(), is(true));
        assertThat(deliveries, is(List.of(List.of("new"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testAppendDuringReadPreservesAppendOnlyContent(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String initialPayload = "initial-" + "a".repeat(16_384);
        Files.writeString(input, initialPayload + "\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean appended = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(2, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterRead(Path path) {
                if (appended.compareAndSet(false, true)) {
                    append(path, "appended\n");
                }
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);

        int offset = source.emitAppendedLines(input, 0);

        assertThat(appended.get(), is(true));
        assertThat(deliveries, is(List.of(List.of(initialPayload, "appended"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testIncomingLinesAreChunkedByAggregateUtf8PayloadBytes(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "one\n\u00E9\u00E9\nx\n");
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(10, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input, FileConnectorConfig.DEFAULT_LINE_SEPARATOR, 4, 5),
                context,
                new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries, is(List.of(List.of("one"), List.of("\u00E9\u00E9", "x"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testIncompleteLineReceivesFreshAggregateBudgetAfterPriorDelivery(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "aa\nbb");
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(10, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input, FileConnectorConfig.DEFAULT_LINE_SEPARATOR, 4, 4),
                context,
                new AtomicBoolean());
        FileIncomingConnector.FileCursor cursor = source.currentCursor(input, 0);

        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of("aa"))));
        assertThat(cursor.offset(), is(3L));

        append(input, "\ncc\n");
        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of("aa"), List.of("bb", "cc"))));
        assertThat(cursor.offset(), is(Files.size(input)));
    }

    @Test
    void testOverBudgetTailRewriteDoesNotReplayCommittedLine(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "aa\nbbb\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean rewritten = new AtomicBoolean();
        IncomingConnectorContext context = boundedContext(10, messages -> {
            deliveries.add(entities(messages));
            if (rewritten.compareAndSet(false, true)) {
                try {
                    Files.writeString(input, "aa\nccc\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input, FileConnectorConfig.DEFAULT_LINE_SEPARATOR, 3, 4),
                context,
                new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(rewritten.get(), is(true));
        assertThat(deliveries, is(List.of(List.of("aa"), List.of("ccc"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testUtf8MultiByteSeparatorCanCrossReadBufferBoundary(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String payload = "a".repeat(8191);
        String separator = "\u2028";
        Files.writeString(input, payload + separator);
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(1, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input,
                                                                         separator,
                                                                         payload.length(),
                                                                         payload.length()),
                                                          context,
                                                          new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries, is(List.of(List.of(payload))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testUnterminatedUtf8SeparatorBytePrefixWaitsForRemainingByte(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String payload = "value";
        byte[] separator = "\u2028".getBytes(StandardCharsets.UTF_8);
        byte[] initial = Arrays.copyOf(payload.getBytes(StandardCharsets.UTF_8),
                                      payload.getBytes(StandardCharsets.UTF_8).length + 2);
        System.arraycopy(separator, 0, initial, payload.getBytes(StandardCharsets.UTF_8).length, 2);
        Files.write(input, initial);
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(1, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input,
                                                                         "\u2028",
                                                                         payload.length(),
                                                                         payload.length()),
                                                          context,
                                                          new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(offset, is(0));
        assertThat(deliveries, is(List.of()));

        Files.write(input, new byte[] {separator[2]}, StandardOpenOption.APPEND);
        offset = source.emitAppendedLines(input, offset);

        assertThat(deliveries, is(List.of(List.of(payload))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testUnterminatedMultiByteSeparatorPrefixDoesNotCountAsPayload(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "xaba");
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(1, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input, "abab", 1, 1),
                                                          context,
                                                          new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(offset, is(0));
        assertThat(deliveries, is(List.of()));

        append(input, "b");
        offset = source.emitAppendedLines(input, offset);

        assertThat(deliveries, is(List.of(List.of("x"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testOversizedUnterminatedLineIsRejectedAfterFirstRead(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "x".repeat(32_768));
        List<List<String>> deliveries = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        IncomingConnectorContext context = boundedContext(10, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterRead(Path path) {
                reads.incrementAndGet();
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input, 4),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);
        MessagingRejectedException failure =
                assertThrows(MessagingRejectedException.class, () -> source.emitAppendedLines(input, 0));

        assertThat(failure.channel(), is("events"));
        assertThat(failure.reason(), is(MessagingRejectedException.Reason.OVERSIZED));
        assertThat(failure.getMessage(),
                   is("File line at byte offset 0 exceeds max-line-bytes 4"));
        assertThat(reads.get(), is(1));
        assertThat(deliveries, is(List.of()));
    }

    @Test
    void testMaxLineBytesCountsUtf8Bytes(@TempDir Path tempDir) throws Exception {
        Path accepted = tempDir.resolve("accepted.log");
        Files.writeString(accepted, "\u00E9\n");
        List<List<String>> deliveries = new ArrayList<>();
        var acceptedSource = new FileIncomingConnector.FileConnector(
                incomingConfig(accepted, 2),
                boundedContext(1, messages -> deliveries.add(entities(messages))),
                new AtomicBoolean());

        int offset = acceptedSource.emitAppendedLines(accepted, 0);

        assertThat(deliveries, is(List.of(List.of("\u00E9"))));
        assertThat(offset, is((int) Files.size(accepted)));

        Path rejected = tempDir.resolve("rejected.log");
        Files.writeString(rejected, "\u00E9\n");
        var rejectedSource = new FileIncomingConnector.FileConnector(
                incomingConfig(rejected, 1),
                boundedContext(1, ignored -> { }),
                new AtomicBoolean());

        MessagingRejectedException failure = assertThrows(
                MessagingRejectedException.class,
                () -> rejectedSource.emitAppendedLines(rejected, 0));

        assertThat(failure.reason(), is(MessagingRejectedException.Reason.OVERSIZED));
        assertThat(failure.getMessage(), is("File line at byte offset 0 exceeds max-line-bytes 1"));
    }

    @Test
    void testCapacityFullBatchIsDeliveredBeforeMalformedNextLine(@TempDir Path tempDir) throws Exception {
        byte[] first = "good\n".getBytes(StandardCharsets.UTF_8);
        byte[] content = Arrays.copyOf(first, first.length + 2);
        content[first.length] = (byte) 0xFF;
        content[first.length + 1] = '\n';

        Path input = tempDir.resolve("events.log");
        Files.write(input, content);
        List<List<String>> deliveries = new ArrayList<>();
        IncomingConnectorContext context = boundedContext(2, messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input, FileConnectorConfig.DEFAULT_LINE_SEPARATOR, 4, 4),
                context,
                new AtomicBoolean());

        assertThrows(IOException.class, () -> source.emitAppendedLines(input, 0));

        assertThat(deliveries, is(List.of(List.of("good"))));
    }

    @Test
    void testIncrementalIncompleteLineScansAndValidatesOnlyBoundedWindows(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.write(input, new byte[0]);
        List<List<String>> deliveries = new ArrayList<>();
        AtomicLong scannedBytes = new AtomicLong();
        AtomicLong validatedBytes = new AtomicLong();
        IncomingConnectorContext context = boundedContext(1, messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterRead(Path path, long offset, int bytes) {
                scannedBytes.addAndGet(bytes);
            }

            @Override
            public void afterValidationRead(Path path, int bytes) {
                validatedBytes.addAndGet(bytes);
            }
        };
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                readListener);
        FileIncomingConnector.FileCursor cursor = source.currentCursor(input, 0);
        scannedBytes.set(0);
        validatedBytes.set(0);

        String chunk = "abcd";
        int chunks = 64;
        for (int i = 0; i < chunks; i++) {
            append(input, chunk);
            cursor = source.emitAppendedLines(input, cursor);
            assertThat(cursor.offset(), is(0L));
        }
        append(input, "\n");
        cursor = source.emitAppendedLines(input, cursor);

        long fileBytes = Files.size(input);
        assertThat(deliveries, is(List.of(List.of(chunk.repeat(chunks)))));
        assertThat(cursor.offset(), is(fileBytes));
        assertThat(scannedBytes.get(), is(fileBytes));
        assertThat(validatedBytes.get() <= fileBytes * 6, is(true));
    }

    @Test
    @Timeout(value = 5)
    void testAppendBetweenInitialSnapshotAndWatchRegistrationIsReconciled(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "existing\n");
        List<List<String>> deliveries = new ArrayList<>();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean registered = new AtomicBoolean();
        IncomingConnectorContext context = incomingContext(messages -> {
            assertThat(registered.get(), is(true));
            deliveries.add(entities(messages));
        });
        var registrationListener = new FileIncomingConnector.WatchRegistrationListener() {
            @Override
            public void beforeRegistration(Path path) {
                append(path, "during-registration\n");
            }

            @Override
            public void afterRegistration() {
                registered.set(true);
            }

            @Override
            public void afterReconciliation() {
                closed.set(true);
            }
        };
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input),
                                                          context,
                                                          closed,
                                                          registrationListener);

        source.run(context);

        assertThat(deliveries, is(List.of(List.of("during-registration"))));
    }

    @Test
    @Timeout(value = 5)
    void testCloseCancelsRuntimeDeliveryBeforeCursorAdvances(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\n");
        CountDownLatch awaitingDelivery = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        ConnectorDelivery blockingDelivery = new ConnectorDelivery() {
            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isCurrentThread() {
                return false;
            }

            @Override
            public void await() throws InterruptedException {
                awaitingDelivery.countDown();
                new CountDownLatch(1).await();
            }

            @Override
            public boolean await(Duration timeout) throws InterruptedException {
                await();
                return false;
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public void close() {
            }
        };
        IncomingConnectorContext context = new IncomingConnectorContext() {
            @Override
            public String channel() {
                return "events";
            }

            @Override
            public int maxDeliveryMessages() {
                return 1;
            }

            @Override
            public ConnectorDeliveryReservation reserveDelivery() {
                return new ConnectorDeliveryReservation() {
                    @Override
                    public ConnectorDelivery start(MessageBatch<?> batch) {
                        return blockingDelivery;
                    }

                    @Override
                    public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
                        return Optional.of(blockingDelivery);
                    }

                    @Override
                    public void close() {
                    }
                };
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                return Optional.of(reserveDelivery());
            }
        };
        Set<Thread> sourceThreads = ConcurrentHashMap.newKeySet();
        var source = new FileIncomingConnector.FileConnector(incomingConfig(input),
                                                              context,
                                                              new AtomicBoolean(),
                                                              sourceThreads);
        AtomicInteger offset = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            sourceThreads.add(Thread.currentThread());
            try {
                offset.set(source.emitAppendedLines(input, 0));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                sourceThreads.remove(Thread.currentThread());
            }
        });
        assertThat(awaitingDelivery.await(1, TimeUnit.SECONDS), is(true));

        source.close();
        thread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(cancelled.get(), is(true));
        assertThat(offset.get(), is(-1));
    }


    @Test
    @Timeout(value = 5)
    void testConnectorCloseInterruptsReservationWait(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "");
        CountDownLatch reservationWait = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        IncomingConnectorContext context = new TrackingReservationContext(1, ignored -> {
        }) {
            @Override
            public ConnectorDeliveryReservation reserveDelivery() {
                reservationWait.countDown();
                try {
                    neverReleased.await();
                    throw new AssertionError("Reservation wait should be interrupted");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MessagingRejectedException("events",
                                                          MessagingRejectedException.Reason.CANCELLED,
                                                          "Reservation wait interrupted",
                                                          e);
                }
            }
        };
        FileConnectorProvider provider = new FileConnectorProvider();
        IncomingConnector source = provider.createIncomingConnector(incomingConfig(input));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedOnExit = new AtomicBoolean();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> {
                    source.run(context);
                    interruptedOnExit.set(Thread.currentThread().isInterrupted());
                });
        assertThat(reservationWait.await(1, TimeUnit.SECONDS), is(true));

        source.close();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(interruptedOnExit.get(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testConnectorCloseInterruptsDeliveryStartWaitAndClosesReservation(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "");
        CountDownLatch initialReadFinished = new CountDownLatch(1);
        CountDownLatch startWait = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicInteger reservationCount = new AtomicInteger();
        AtomicBoolean blockedReservationClosed = new AtomicBoolean();
        IncomingConnectorContext context = new TrackingReservationContext(1, ignored -> {
        }) {
            @Override
            public ConnectorDeliveryReservation reserveDelivery() {
                if (reservationCount.incrementAndGet() == 1) {
                    return new ConnectorDeliveryReservation() {
                        @Override
                        public ConnectorDelivery start(MessageBatch<?> messages) {
                            throw new AssertionError("Initial empty read must not start a delivery");
                        }

                        @Override
                        public Optional<ConnectorDelivery> tryStart(MessageBatch<?> messages) {
                            throw new AssertionError("Initial empty read must not start a delivery");
                        }

                        @Override
                        public void close() {
                            initialReadFinished.countDown();
                        }
                    };
                }
                return new ConnectorDeliveryReservation() {
                    @Override
                    public ConnectorDelivery start(MessageBatch<?> messages) {
                        startWait.countDown();
                        try {
                            neverReleased.await();
                            throw new AssertionError("Delivery start wait should be interrupted");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new MessagingRejectedException("events",
                                                                  MessagingRejectedException.Reason.CANCELLED,
                                                                  "Delivery start wait interrupted",
                                                                  e);
                        }
                    }

                    @Override
                    public Optional<ConnectorDelivery> tryStart(MessageBatch<?> messages) {
                        return Optional.of(start(messages));
                    }

                    @Override
                    public void close() {
                        blockedReservationClosed.set(true);
                    }
                };
            }
        };
        FileConnectorProvider provider = new FileConnectorProvider();
        IncomingConnector source = provider.createIncomingConnector(incomingConfig(input));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedOnExit = new AtomicBoolean();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> {
                    source.run(context);
                    interruptedOnExit.set(Thread.currentThread().isInterrupted());
                });
        assertThat(initialReadFinished.await(1, TimeUnit.SECONDS), is(true));
        append(input, "first\n");
        assertThat(startWait.await(1, TimeUnit.SECONDS), is(true));

        source.close();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(interruptedOnExit.get(), is(true));
        assertThat(blockedReservationClosed.get(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testRuntimeShutdownDuringFileReadIsNormalCancellation(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "");
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        TrackingReservationContext context = new TrackingReservationContext(1, ignored -> {
        });
        var source = new FileIncomingConnector.FileConnector(
                incomingConfig(input),
                context,
                new AtomicBoolean(),
                new FileIncomingConnector.WatchRegistrationListener() {
                },
                path -> {
                    readStarted.countDown();
                    try {
                        neverReleased.await();
                        throw new AssertionError("File read should be interrupted");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ClosedByInterruptException();
                    }
                });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedOnExit = new AtomicBoolean();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> {
                    source.run(context);
                    interruptedOnExit.set(Thread.currentThread().isInterrupted());
                });
        assertThat(readStarted.await(1, TimeUnit.SECONDS), is(true));

        sourceThread.interrupt();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(interruptedOnExit.get(), is(true));
        assertThat(context.reservations().getFirst().closed(), is(true));
    }

    @Test
    @Timeout(value = 5)
    void testServiceRegistryDiscoversProviderWithoutOwningConnectorLifecycle(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        IncomingConnector source = null;
        Thread sourceThread = null;
        try {
            ConnectorProvider discovered = manager.registry().get(ConnectorProvider.class);
            assertThat(discovered, instanceOf(FileConnectorProvider.class));
            assertThat(manager.registry().get(IncomingConnectorProvider.class), sameInstance(discovered));
            assertThat(manager.registry().get(OutgoingConnectorProvider.class), sameInstance(discovered));
            IncomingConnectorProvider provider = (IncomingConnectorProvider) discovered;
            IncomingConnectorContext context = incomingContext(ignored -> {
                throw new AssertionError("No existing file content should be emitted");
            });
            IncomingConnector createdSource = provider.createIncomingConnector(incomingConfigSource(input));
            source = createdSource;
            sourceThread = Thread.ofVirtual()
                    .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                    .start(() -> createdSource.run(context));
            awaitFile(input);
            manager.shutdown();
            assertThat(sourceThread.isAlive(), is(true));
        } finally {
            if (source != null) {
                source.close();
            }
            manager.shutdown();
        }

        if (sourceThread != null) {
            sourceThread.join(TimeUnit.SECONDS.toMillis(1));
        }

        assertThat(sourceThread == null || sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
    }

    @Test
    @Timeout(value = 5)
    void testWatchServiceDrainsEventsAndDeliversEachAppendOnce(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "existing content is tailed\n");
        List<List<String>> deliveries = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(2);
        IncomingConnectorContext context = incomingContext(messages -> {
            deliveries.add(entities(messages));
            delivered.countDown();
        });
        FileConnectorProvider provider = new FileConnectorProvider();
        IncomingConnector source = provider.createIncomingConnector(incomingConfig(input));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> source.run(context));
        Thread.sleep(200);

        append(input, "first\n");
        awaitDeliveryCount(deliveries, 1);
        Thread.sleep(200);
        assertThat(deliveries, is(List.of(List.of("first"))));

        append(input, "second\n");
        assertThat(delivered.await(1, TimeUnit.SECONDS), is(true));
        source.close();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(deliveries, is(List.of(List.of("first"), List.of("second"))));
    }

    @Test
    @Timeout(value = 5)
    void testSourceReconcilesAppendOnlyAfterRuntimeStarts(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "existing content is tailed\n");
        List<List<String>> deliveries = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        IncomingConnectorContext context = new IncomingConnectorContext() {
            @Override
            public String channel() {
                return "events";
            }

            @Override
            public boolean awaitRunning() {
                ready.countDown();
                try {
                    running.await();
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override
            public ConnectorDeliveryReservation reserveDelivery() {
                return reservation(maxDeliveryMessages(), messages -> {
                    deliveries.add(entities(messages));
                    delivered.countDown();
                });
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                return Optional.of(reserveDelivery());
            }
        };
        FileConnectorProvider provider = new FileConnectorProvider();
        IncomingConnector source = provider.createIncomingConnector(incomingConfig(input));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> source.run(context));
        assertThat(ready.await(1, TimeUnit.SECONDS), is(true));

        append(input, "first\n");
        assertThat(delivered.await(200, TimeUnit.MILLISECONDS), is(false));

        running.countDown();
        assertThat(delivered.await(1, TimeUnit.SECONDS), is(true));
        source.drain();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));
        source.close();

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(deliveries, is(List.of(List.of("first"))));
    }

    @Test
    @Timeout(value = 5)
    void testCompletedSourceCannotBeRunAgain(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "");
        FileConnectorProvider provider = new FileConnectorProvider();
        CountDownLatch ready = new CountDownLatch(1);
        IncomingConnectorContext context = new TrackingReservationContext(1, ignored -> {
        }) {
            @Override
            public boolean awaitRunning() {
                ready.countDown();
                return true;
            }
        };
        IncomingConnector source = provider.createIncomingConnector(incomingConfig(input));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> source.run(context));
        assertThat(ready.await(1, TimeUnit.SECONDS), is(true));
        source.drain();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));
        source.close();

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThrows(IllegalStateException.class, () -> source.run(context));
    }

    @Test
    void testWatchKeyIsDrainedAndInvalidRegistrationFails() throws IOException {
        Path target = Path.of("events.log");
        TestWatchKey valid = new TestWatchKey(
                List.of(new TestWatchEvent(target), new TestWatchEvent(Path.of("unrelated.log"))),
                true);

        assertThat(FileIncomingConnector.FileConnector.consumeWatchKey(valid, target), is(true));
        assertThat(valid.pollCount(), is(1));
        assertThat(valid.resetCount(), is(1));

        TestWatchKey invalid = new TestWatchKey(List.of(), false);
        IOException failure = assertThrows(IOException.class,
                                           () -> FileIncomingConnector.FileConnector.consumeWatchKey(invalid, target));
        assertThat(failure.getMessage(), is("File watch registration is no longer valid for events.log"));
        assertThat(invalid.pollCount(), is(1));
        assertThat(invalid.resetCount(), is(1));
    }

    private static FileConnectorConfig config(Path path) {
        return config(path, FileConnectorConfig.DEFAULT_LINE_SEPARATOR);
    }

    private static OutgoingConnector outgoingConnector(FileConnectorConfig config) {
        OutgoingConnector connector = new FileConnectorProvider().createOutgoingConnector(config);
        connector.start();
        return connector;
    }

    private static FileConnectorConfig config(Path path, String lineSeparator) {
        return FileConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel("audit")
                .connector(FileConnectorProvider.CONNECTOR_TYPE)
                .path(path)
                .lineSeparator(lineSeparator)
                .build();
    }

    private static FileConnectorConfig incomingConfig(Path path) {
        return incomingConfig(path, FileConnectorConfig.DEFAULT_LINE_SEPARATOR);
    }

    private static Config incomingConfigSource(Path path) {
        return Config.just(ConfigSources.create(Map.of(
                "direction", "INCOMING",
                ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "events",
                ConnectorConfig.CONNECTOR_ATTRIBUTE, FileConnectorProvider.CONNECTOR_TYPE,
                FileConnectorConfig.PATH_PROPERTY, path.toString())));
    }

    private static FileConnectorConfig incomingConfig(Path path, int maxLineBytes) {
        return incomingConfig(path, FileConnectorConfig.DEFAULT_LINE_SEPARATOR, maxLineBytes);
    }

    private static FileConnectorConfig incomingConfig(Path path, String lineSeparator) {
        return incomingConfig(path, lineSeparator, FileConnectorConfig.DEFAULT_MAX_LINE_BYTES);
    }

    private static FileConnectorConfig incomingConfig(Path path, String lineSeparator, int maxLineBytes) {
        return incomingConfig(path,
                              lineSeparator,
                              maxLineBytes,
                              FileConnectorConfig.DEFAULT_MAX_BATCH_BYTES);
    }

    private static FileConnectorConfig incomingConfig(Path path,
                                                      String lineSeparator,
                                                      int maxLineBytes,
                                                      long maxBatchBytes) {
        return FileConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel("events")
                .connector(FileConnectorProvider.CONNECTOR_TYPE)
                .path(path)
                .lineSeparator(lineSeparator)
                .maxLineBytes(maxLineBytes)
                .maxBatchBytes(maxBatchBytes)
                .build();
    }

    private static void append(Path path, String content) {
        try {
            Files.writeString(path, content, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void replace(Path path, String content) {
        Path replacement;
        try {
            replacement = Files.createTempFile(path.getParent(), "replacement-", ".tmp");
            Files.writeString(replacement, content);
            Files.move(replacement, path, REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static IncomingConnectorContext incomingContext(BatchConsumer consumer) {
        return incomingContext(consumer, null);
    }

    private static IncomingConnectorContext incomingContext(BatchConsumer consumer, CountDownLatch ready) {
        return new IncomingConnectorContext() {
            @Override
            public String channel() {
                return "events";
            }

            @Override
            public boolean awaitRunning() {
                if (ready != null) {
                    ready.countDown();
                }
                return true;
            }

            @Override
            public ConnectorDeliveryReservation reserveDelivery() {
                return reservation(maxDeliveryMessages(), consumer);
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                return Optional.of(reserveDelivery());
            }
        };
    }

    private static IncomingConnectorContext boundedContext(int maxMessages, BatchConsumer consumer) {
        return new IncomingConnectorContext() {
            @Override
            public String channel() {
                return "events";
            }

            @Override
            public int maxDeliveryMessages() {
                return maxMessages;
            }

            @Override
            public ConnectorDeliveryReservation reserveDelivery() {
                return reservation(maxMessages, consumer);
            }

            @Override
            public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
                return Optional.of(reserveDelivery());
            }
        };
    }

    private static ConnectorDeliveryReservation reservation(int maxMessages, BatchConsumer consumer) {
        return new TrackingReservation(maxMessages, consumer);
    }

    private static List<String> entities(MessageBatch<?> messages) {
        return messages.messages().stream()
                .map(message -> String.valueOf(message.entity()))
                .toList();
    }

    private static void awaitFile(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(Files.exists(path), is(true));
    }

    private static void awaitDeliveryCount(List<?> deliveries, int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (deliveries.size() < expectedCount && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(deliveries.size(), is(expectedCount));
    }

    private static class TrackingReservationContext implements IncomingConnectorContext {
        private final int maxMessages;
        private final BatchConsumer consumer;
        private final List<TrackingReservation> reservations = new ArrayList<>();

        private TrackingReservationContext(int maxMessages, BatchConsumer consumer) {
            this.maxMessages = maxMessages;
            this.consumer = consumer;
        }

        @Override
        public String channel() {
            return "events";
        }

        @Override
        public int maxDeliveryMessages() {
            return maxMessages;
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery() {
            TrackingReservation reservation = new TrackingReservation(maxMessages, consumer);
            reservations.add(reservation);
            return reservation;
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
            return Optional.of(reserveDelivery());
        }

        private boolean hasOpenReservation() {
            return reservations.stream().anyMatch(TrackingReservation::open);
        }

        protected List<TrackingReservation> reservations() {
            return reservations;
        }
    }

    private static final class TrackingReservation implements ConnectorDeliveryReservation {
        private final int reservedMessages;
        private final BatchConsumer consumer;
        private boolean started;
        private boolean closed;
        private int actualMessages;
        private TrackingDelivery delivery;

        private TrackingReservation(int reservedMessages) {
            this(reservedMessages, ignored -> { });
        }

        private TrackingReservation(int reservedMessages, BatchConsumer consumer) {
            this.reservedMessages = reservedMessages;
            this.consumer = consumer;
        }

        @Override
        public ConnectorDelivery start(MessageBatch<?> messages) {
            if (!open()) {
                throw new IllegalStateException("Reservation is not open");
            }
            if (messages.size() > reservedMessages) {
                throw new IllegalArgumentException("Actual delivery exceeds reservation");
            }
            started = true;
            actualMessages = messages.size();
            delivery = new TrackingDelivery(() -> consumer.accept(messages));
            delivery.run();
            return delivery;
        }

        @Override
        public Optional<ConnectorDelivery> tryStart(MessageBatch<?> messages) {
            return Optional.of(start(messages));
        }

        @Override
        public void close() {
            if (!started) {
                closed = true;
            }
        }

        private boolean open() {
            return !started && !closed;
        }

        private int reservedMessages() {
            return reservedMessages;
        }

        private boolean started() {
            return started;
        }

        private boolean closed() {
            return closed;
        }

        private int actualMessages() {
            return actualMessages;
        }

        private TrackingDelivery delivery() {
            return delivery;
        }
    }

    private static final class TrackingDelivery implements ConnectorDelivery {
        private final Runnable action;
        private RuntimeException failure;
        private boolean done;
        private boolean closed;
        private Thread executionThread;

        private TrackingDelivery(Runnable action) {
            this.action = action;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public boolean isCurrentThread() {
            return Thread.currentThread() == executionThread;
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
            closed = true;
        }

        private void run() {
            executionThread = Thread.currentThread();
            try {
                action.run();
            } catch (RuntimeException e) {
                failure = e;
            } finally {
                executionThread = null;
                done = true;
            }
        }

        private boolean closed() {
            return closed;
        }
    }

    private static final class TestWatchKey implements WatchKey {
        private final List<WatchEvent<?>> events;
        private final boolean resetResult;
        private final AtomicInteger pollCount = new AtomicInteger();
        private final AtomicInteger resetCount = new AtomicInteger();

        private TestWatchKey(List<WatchEvent<?>> events, boolean resetResult) {
            this.events = events;
            this.resetResult = resetResult;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public List<WatchEvent<?>> pollEvents() {
            pollCount.incrementAndGet();
            return events;
        }

        @Override
        public boolean reset() {
            resetCount.incrementAndGet();
            return resetResult;
        }

        @Override
        public void cancel() {
        }

        @Override
        public Watchable watchable() {
            return Path.of(".");
        }

        private int pollCount() {
            return pollCount.get();
        }

        private int resetCount() {
            return resetCount.get();
        }
    }

    private record TestWatchEvent(Path context) implements WatchEvent<Path> {
        @Override
        public Kind<Path> kind() {
            return StandardWatchEventKinds.ENTRY_MODIFY;
        }

        @Override
        public int count() {
            return 1;
        }
    }

    @FunctionalInterface
    private interface BatchConsumer {
        void accept(MessageBatch<?> messages);
    }
}
