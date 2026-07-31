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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorDelivery;
import io.helidon.extensions.messaging.ConnectorDeliveryReservation;
import io.helidon.extensions.messaging.ConnectorProvider;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.FailurePolicy;
import io.helidon.extensions.messaging.IncomingConnectorProvider;
import io.helidon.extensions.messaging.IncomingEndpoint;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingChannel;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.MessagingRejectedException;
import io.helidon.extensions.messaging.OutgoingConnectorProvider;
import io.helidon.extensions.messaging.OutgoingEndpoint;
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
                                                      () -> new FileConnectorProvider().createConfig(
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
        FileConnectorConfig config = new FileConnectorProvider().createConfig(Config.just(ConfigSources.create(Map.of(
                "direction", "OUTGOING",
                ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "audit",
                ConnectorConfig.CONNECTOR_ATTRIBUTE, FileConnectorProvider.CONNECTOR_TYPE,
                FileConnectorConfig.PATH_PROPERTY, auditLog.toString(),
                FileConnectorConfig.LINE_SEPARATOR_PROPERTY, "|"))));

        assertThat(config.direction(), is(ConnectorConfig.Direction.OUTGOING));
        assertThat(config.channel(), is("audit"));
        assertThat(config.connector(), is(FileConnectorProvider.CONNECTOR_TYPE));
        assertThat(config.path(), is(auditLog));
        assertThat(config.lineSeparator(), is("|"));
    }

    @Test
    void testOutgoingEndpointsAreResourceFreeAndLifecycleIndependent(@TempDir Path tempDir) throws IOException {
        Path firstPath = tempDir.resolve("first.log");
        Path secondPath = tempDir.resolve("second.log");
        Path thirdPath = tempDir.resolve("third.log");
        FileConnectorProvider provider = new FileConnectorProvider();
        OutgoingEndpoint first = provider.createOutgoingEndpoint(config(firstPath));
        OutgoingEndpoint second = provider.createOutgoingEndpoint(config(secondPath));

        assertThat(Files.exists(firstPath), is(false));
        assertThat(Files.exists(secondPath), is(false));
        assertThrows(IllegalStateException.class, () -> first.send(Message.create("before start")));

        first.start();
        second.start();
        assertThat(Files.exists(firstPath), is(false));
        assertThat(Files.exists(secondPath), is(false));

        first.close();
        first.close();
        assertThrows(IllegalStateException.class, () -> first.send(Message.create("after close")));

        second.send(Message.create("second remains available"));
        second.flush();
        assertThat(Files.readString(secondPath), is("second remains available\n"));

        OutgoingEndpoint third = provider.createOutgoingEndpoint(config(thirdPath));
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
        OutgoingEndpoint blocked = FileOutgoingConnector.createEndpoint(config(auditLog), (path, content) -> {
            writeStarted.countDown();
            try {
                releaseWrite.await();
            } catch (InterruptedException e) {
                writeInterrupted.set(true);
                Thread.currentThread().interrupt();
                throw new MessagingException("Expected interrupted test write", e);
            }
        });
        OutgoingEndpoint sibling = new FileConnectorProvider().createOutgoingEndpoint(config(auditLog));
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
        assertThrows(IllegalStateException.class, () -> blocked.send(Message.create("after close")));

        sibling.send(Message.create("sibling remains available"));
        sibling.flush();
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
        OutgoingEndpoint endpoint = FileOutgoingConnector.createEndpoint(
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
        endpoint.start();
        Thread sendThread = Thread.ofVirtual().start(() -> {
            try {
                endpoint.send(Message.create("admitted"));
            } catch (Throwable t) {
                sendFailure.set(t);
            }
        });
        assertThat(writeStarted.await(1, TimeUnit.SECONDS), is(true));

        endpoint.close();
        releaseWrite.countDown();
        sendThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(sendThread.isAlive(), is(false));
        assertThat(sendFailure.get(), nullValue());
        assertThat(writeInterrupted.get(), is(false));
        assertThat(written.get(), is("admitted\n"));
        assertThrows(IllegalStateException.class, () -> endpoint.send(Message.create("after close")));
    }

    @Test
    @Timeout(value = 5)
    void testIncomingEndpointsAreResourceFreeAndLifecycleIndependent(@TempDir Path tempDir) throws Exception {
        Path firstPath = tempDir.resolve("first.log");
        Path secondPath = tempDir.resolve("second.log");
        FileConnectorProvider provider = new FileConnectorProvider();
        CountDownLatch secondDelivered = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        IncomingEndpoint first = provider.createIncomingEndpoint(
                incomingConfig(firstPath),
                incomingContext(ignored -> { }));
        IncomingEndpoint second = provider.createIncomingEndpoint(
                incomingConfig(secondPath),
                incomingContext(ignored -> secondDelivered.countDown()));

        assertThat(Files.exists(firstPath), is(false));
        assertThat(Files.exists(secondPath), is(false));

        first.prepareForGraph();
        second.prepareForGraph();
        Thread firstThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> firstFailure.set(throwable))
                .start(first);
        Thread secondThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> secondFailure.set(throwable))
                .start(second);
        first.awaitReady(Duration.ofSeconds(1));
        second.awaitReady(Duration.ofSeconds(1));
        first.startAdmission();
        second.startAdmission();

        first.close();
        firstThread.join(TimeUnit.SECONDS.toMillis(1));
        assertThat(firstThread.isAlive(), is(false));
        assertThat(secondThread.isAlive(), is(true));

        append(secondPath, "second remains available\n");
        assertThat(secondDelivered.await(1, TimeUnit.SECONDS), is(true));

        second.stopAdmission();
        secondThread.join(TimeUnit.SECONDS.toMillis(1));
        second.checkpoint();
        second.close();
        IncomingEndpoint third = provider.createIncomingEndpoint(
                incomingConfig(tempDir.resolve("third.log")),
                incomingContext(ignored -> { }));
        third.close();

        assertThat(secondThread.isAlive(), is(false));
        assertThat(firstFailure.get(), nullValue());
        assertThat(secondFailure.get(), nullValue());
    }

    @Test
    void testDefaultLineSeparatorWritesOneMessagePerLine(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = outgoingEndpoint(config(auditLog));

        sink.send(Message.create("first audit event"));
        sink.send(Message.create("second audit event"));

        assertThat(Files.readString(auditLog), is("first audit event\nsecond audit event\n"));
    }

    @Test
    void testCustomLineSeparator(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = outgoingEndpoint(config(auditLog, "|"));

        sink.send(Message.create("first audit event"));
        sink.send(Message.create("second audit event"));

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
    }

    @Test
    void testBatchWritesMessagesInOneConnectorCall(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = outgoingEndpoint(config(auditLog, "|"));

        sink.sendBatch(List.of(Message.create("first audit event"),
                               Message.create("second audit event")));

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
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
            OutgoingEndpoint sink = provider.createOutgoingEndpoint(config(configuredPath));
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

        outgoingEndpoint(config(auditLog))
                .send(Message.create("audit event"));

        assertThat(Files.readString(auditLog), is("audit event\n"));
    }

    @Test
    void testFileConnectorCanBeUsedAsChannelOutput(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");

        MessagingChannel<String> channel = MessagingChannel.<String>builder()
                .payloadType(String.class)
                .addOutgoingConnector(outgoingEndpoint(config(auditLog, "|")))
                .build();

        channel.emit("first audit event");
        channel.emit(Message.builder("second audit event")
                             .header("key", "value")
                             .build());

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
    }

    @Test
    void testFileConnectorCanBeUsedAsChannelBatchOutput(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");

        MessagingChannel<String> channel = MessagingChannel.<String>builder()
                .payloadType(String.class)
                .addOutgoingConnector(outgoingEndpoint(config(auditLog, "|")))
                .build();

        channel.emitBatch(List.of(Message.create("first audit event"),
                                  Message.builder("second audit event")
                                          .header("key", "value")
                                          .build()));

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
    }

    @Test
    void testFailedAppendIsRedeliveredBeforeLaterLines(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\nsecond\n");
        List<List<String>> attempts = new ArrayList<>();
        List<List<String>> handledBatches = new ArrayList<>();
        List<Integer> failedAttempts = new ArrayList<>();
        List<RuntimeException> handledFailures = new ArrayList<>();
        AtomicInteger attempt = new AtomicInteger();
        RuntimeException expectedFailure = new IllegalStateException("expected downstream failure");
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public FailurePolicy failurePolicy() {
                return FailurePolicy.builder()
                        .retryDelay(Duration.ofMillis(1))
                        .build();
            }

            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                attempts.add(entities(messages));
                int currentAttempt = attempt.getAndIncrement();
                if (currentAttempt == 0) {
                    append(input, "third\n");
                }
                if (currentAttempt < 2) {
                    throw expectedFailure;
                }
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                handledBatches.add(entities(messages));
                failedAttempts.add(failedAttempt);
                handledFailures.add(failure);
                return FailureResult.RETRY;
            }
        };
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(attempts,
                   is(List.of(List.of("first", "second"),
                              List.of("first", "second"),
                              List.of("first", "second"),
                              List.of("third"))));
        assertThat(handledBatches,
                   is(List.of(List.of("first", "second"),
                              List.of("first", "second"))));
        assertThat(failedAttempts, is(List.of(1, 2)));
        assertThat(handledFailures, is(List.of(expectedFailure, expectedFailure)));
        assertThat(offset, is(Files.readString(input).length()));
    }

    @Test
    void testSettledFailureAdvancesPastCapturedBatch(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\nsecond\n");
        List<List<String>> attempts = new ArrayList<>();
        List<List<String>> handledBatches = new ArrayList<>();
        List<Integer> failedAttempts = new ArrayList<>();
        List<RuntimeException> handledFailures = new ArrayList<>();
        AtomicInteger attempt = new AtomicInteger();
        RuntimeException expectedFailure = new IllegalStateException("expected downstream failure");
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                attempts.add(entities(messages));
                if (attempt.getAndIncrement() == 0) {
                    append(input, "third\n");
                    throw expectedFailure;
                }
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                handledBatches.add(entities(messages));
                failedAttempts.add(failedAttempt);
                handledFailures.add(failure);
                return FailureResult.SETTLED;
            }
        };
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(attempts, is(List.of(List.of("first", "second"), List.of("third"))));
        assertThat(handledBatches, is(List.of(List.of("first", "second"))));
        assertThat(failedAttempts, is(List.of(1)));
        assertThat(handledFailures, is(List.of(expectedFailure)));
        assertThat(offset, is(Files.readString(input).length()));
    }

    @Test
    void testRetryDoesNotApplyCapturedOffsetToSameLengthReplacement(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String original = "old-one\nold-two\n";
        String replacement = "new-one\nnew-two\n";
        assertThat(replacement.length(), is(original.length()));
        Files.writeString(input, original);
        List<List<String>> attempts = new ArrayList<>();
        AtomicInteger attempt = new AtomicInteger();
        ConnectorSourceContext context = retryingContext(messages -> {
            attempts.add(entities(messages));
            if (attempt.getAndIncrement() == 0) {
                replace(input, replacement);
                throw new IllegalStateException("expected downstream failure");
            }
        });
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(attempts,
                   is(List.of(List.of("old-one", "old-two"),
                              List.of("old-one", "old-two"),
                              List.of("new-one", "new-two"))));
        assertThat(offset, is(replacement.length()));
    }

    @Test
    void testSettlementDoesNotApplyCapturedOffsetToLargerReplacement(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String original = "old\n";
        String replacement = "replacement-first\nreplacement-second\n";
        assertThat(replacement.length() > original.length(), is(true));
        Files.writeString(input, original);
        List<List<String>> attempts = new ArrayList<>();
        AtomicInteger attempt = new AtomicInteger();
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                attempts.add(entities(messages));
                if (attempt.getAndIncrement() == 0) {
                    replace(input, replacement);
                    throw new IllegalStateException("expected downstream failure");
                }
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                return FailureResult.SETTLED;
            }
        };
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(attempts,
                   is(List.of(List.of("old"),
                              List.of("replacement-first", "replacement-second"))));
        assertThat(offset, is(replacement.length()));
    }

    @Test
    void testIncompleteTrailingUtf8SequenceWaitsForRemainingBytes(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        byte[] emoji = "\uD83D\uDE00".getBytes(StandardCharsets.UTF_8);
        Files.writeString(input, "first\n");
        Files.write(input, Arrays.copyOf(emoji, 2), StandardOpenOption.APPEND);
        List<List<String>> deliveries = new ArrayList<>();
        ConnectorSourceContext context = retryingContext(messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());
        FileIncomingConnector.FileCursor cursor = source.currentCursor(input, 0);

        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of("first"))));
        assertThat(cursor.offset(), is((long) "first\n".getBytes(StandardCharsets.UTF_8).length));

        Files.write(input,
                    new byte[] {emoji[2], emoji[3], '\n'},
                    StandardOpenOption.APPEND);
        cursor = source.emitAppendedLines(input, cursor);

        assertThat(deliveries, is(List.of(List.of("first"), List.of("\uD83D\uDE00"))));
        assertThat(cursor.offset(), is(Files.size(input)));
    }

    @Test
    void testIncomingLinesAreChunkedByMessageLimit(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\nsecond\nthird\nfourth\nfifth\n");
        List<List<String>> deliveries = new ArrayList<>();
        ConnectorSourceContext context = boundedContext(2,
                                                        1024,
                                                        messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

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
        ConnectorSourceContext context = boundedContext(10, 4, messages -> {
            deliveries.add(entities(messages));
            if (rewritten.compareAndSet(false, true)) {
                try {
                    Files.writeString(input, "good\ny\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        var source = new FileIncomingConnector.FileSource(incomingConfig(input),
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
        ConnectorSourceContext context = boundedContext(10,
                                                        32,
                                                        messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input),
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
        ConnectorSourceContext context = boundedContext(10,
                                                        32,
                                                        messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input),
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
        TrackingReservationContext context = new TrackingReservationContext(4, 64, ignored -> {
        });
        AtomicBoolean readObserved = new AtomicBoolean();
        var source = new FileIncomingConnector.FileSource(
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
        assertThat(context.reservations().getFirst().reservedBytes(), is(64L));
    }

    @Test
    void testEmptyChunkReadReleasesPendingReservation(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "");
        TrackingReservationContext context = new TrackingReservationContext(4, 64, ignored -> {
        });
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

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
        TrackingReservationContext context = new TrackingReservationContext(10, 100, ignored -> {
        });
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(offset, is((int) Files.size(input)));
        TrackingReservation started = context.reservations().getFirst();
        assertThat(started.reservedMessages(), is(10));
        assertThat(started.reservedBytes(), is(100L));
        assertThat(started.actualMessages(), is(1));
        assertThat(started.actualBytes(), is(3L));
        assertThat(started.delivery().closed(), is(true));
        TrackingReservation empty = context.reservations().get(1);
        assertThat(empty.started(), is(false));
        assertThat(empty.closed(), is(true));
    }

    @Test
    void testRetryAndSettlementRetainStartedDeliveryLease(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "one\n");
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean leaseRetainedDuringFailureHandling = new AtomicBoolean();
        TrackingReservationContext context = new TrackingReservationContext(10, 100, ignored -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("expected downstream failure");
            }
        }) {
            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                TrackingDelivery delivery = reservations().getFirst().delivery();
                leaseRetainedDuringFailureHandling.set(delivery != null && !delivery.closed());
                return FailureResult.RETRY;
            }
        };
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(offset, is((int) Files.size(input)));
        assertThat(attempts.get(), is(2));
        assertThat(leaseRetainedDuringFailureHandling.get(), is(true));
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
        ConnectorSourceContext context = boundedContext(1, 1024, messages -> {
            deliveries.add(entities(messages));
            if (replaced.compareAndSet(false, true)) {
                replace(input, replacement);
            }
        });
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

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
        ConnectorSourceContext context = boundedContext(1, 1024, messages -> {
            deliveries.add(entities(messages));
            if (truncated.compareAndSet(false, true)) {
                try {
                    Files.writeString(input, "new\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

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
        ConnectorSourceContext context = boundedContext(2,
                                                        100_000,
                                                        messages -> deliveries.add(entities(messages)));
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
        var source = new FileIncomingConnector.FileSource(
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
        ConnectorSourceContext context = boundedContext(2,
                                                        originalPayload.length() + "later".length(),
                                                        messages -> deliveries.add(entities(messages)));
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
        var source = new FileIncomingConnector.FileSource(
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
        ConnectorSourceContext context = boundedContext(1,
                                                        64,
                                                        messages -> deliveries.add(entities(messages)));
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
        var source = new FileIncomingConnector.FileSource(
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
        ConnectorSourceContext context = boundedContext(1,
                                                        20_000,
                                                        messages -> deliveries.add(entities(messages)));
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
        var source = new FileIncomingConnector.FileSource(
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
        ConnectorSourceContext context = boundedContext(2,
                                                        100_000,
                                                        messages -> deliveries.add(entities(messages)));
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
        var source = new FileIncomingConnector.FileSource(
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
        ConnectorSourceContext context = boundedContext(1, 1, messages -> {
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
        var source = new FileIncomingConnector.FileSource(
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
        ConnectorSourceContext context = boundedContext(1,
                                                        payload.length(),
                                                        messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterValidationRead(Path path, int bytes) {
                validationBytes.addAndGet(bytes);
            }
        };
        var source = new FileIncomingConnector.FileSource(
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
        ConnectorSourceContext context = boundedContext(10,
                                                        100_000,
                                                        messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input),
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
        ConnectorSourceContext context = boundedContext(1,
                                                        32,
                                                        messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input),
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
        ConnectorSourceContext context = boundedContext(2,
                                                        100_000,
                                                        messages -> deliveries.add(entities(messages)));
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
        var source = new FileIncomingConnector.FileSource(
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
        ConnectorSourceContext context = boundedContext(2,
                                                        100_000,
                                                        messages -> deliveries.add(entities(messages)));
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
        var source = new FileIncomingConnector.FileSource(
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
    void testIncomingLinesAreChunkedByUtf8ByteLimit(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "one\n\u00E9\u00E9\nx\n");
        List<List<String>> deliveries = new ArrayList<>();
        ConnectorSourceContext context = boundedContext(10,
                                                        5,
                                                        messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries, is(List.of(List.of("one"), List.of("\u00E9\u00E9", "x"))));
        assertThat(offset, is((int) Files.size(input)));
    }

    @Test
    void testUtf8MultiByteSeparatorCanCrossReadBufferBoundary(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        String payload = "a".repeat(8191);
        String separator = "\u2028";
        Files.writeString(input, payload + separator);
        List<List<String>> deliveries = new ArrayList<>();
        ConnectorSourceContext context = boundedContext(1,
                                                        payload.length(),
                                                        messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input, separator),
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
        ConnectorSourceContext context = boundedContext(1,
                                                        payload.length(),
                                                        messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input, "\u2028"),
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
        ConnectorSourceContext context = boundedContext(1,
                                                        1,
                                                        messages -> deliveries.add(entities(messages)));
        var source = new FileIncomingConnector.FileSource(incomingConfig(input, "abab"),
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
        ConnectorSourceContext context = boundedContext(10,
                                                        4,
                                                        messages -> deliveries.add(entities(messages)));
        var readListener = new FileIncomingConnector.FileReadListener() {
            @Override
            public void beforeRead(Path path) {
            }

            @Override
            public void afterRead(Path path) {
                reads.incrementAndGet();
            }
        };
        var source = new FileIncomingConnector.FileSource(
                incomingConfig(input),
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
                   is("File line at byte offset 0 requires 5 admission bytes, exceeding the channel limit of 4"));
        assertThat(reads.get(), is(1));
        assertThat(deliveries, is(List.of()));
    }

    @Test
    void testCapacityFullBatchIsDeliveredBeforeMalformedNextLine(@TempDir Path tempDir) throws Exception {
        byte[] first = "good\n".getBytes(StandardCharsets.UTF_8);
        byte[] content = Arrays.copyOf(first, first.length + 2);
        content[first.length] = (byte) 0xFF;
        content[first.length + 1] = '\n';

        for (int maxBytes : List.of(4, 5)) {
            Path input = tempDir.resolve("events-" + maxBytes + ".log");
            Files.write(input, content);
            List<List<String>> deliveries = new ArrayList<>();
            ConnectorSourceContext context = boundedContext(2,
                                                            maxBytes,
                                                            messages -> deliveries.add(entities(messages)));
            var source = new FileIncomingConnector.FileSource(incomingConfig(input),
                                                              context,
                                                              new AtomicBoolean());

            assertThrows(IOException.class, () -> source.emitAppendedLines(input, 0));

            assertThat(deliveries, is(List.of(List.of("good"))));
        }
    }

    @Test
    void testIncrementalIncompleteLineScansAndValidatesOnlyBoundedWindows(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.write(input, new byte[0]);
        List<List<String>> deliveries = new ArrayList<>();
        AtomicLong scannedBytes = new AtomicLong();
        AtomicLong validatedBytes = new AtomicLong();
        ConnectorSourceContext context = boundedContext(1,
                                                        1_024,
                                                        messages -> deliveries.add(entities(messages)));
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
        var source = new FileIncomingConnector.FileSource(
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
        ConnectorSourceContext context = retryingContext(messages -> {
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
        var source = new FileIncomingConnector.FileSource(incomingConfig(input),
                                                          context,
                                                          closed,
                                                          registrationListener);

        source.run();

        assertThat(deliveries, is(List.of(List.of("during-registration"))));
    }

    @Test
    void testTerminalFailureFromPolicyStopsDelivery(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\nsecond\n");
        List<List<String>> attempts = new ArrayList<>();
        List<Integer> failedAttempts = new ArrayList<>();
        RuntimeException deliveryFailure = new IllegalStateException("expected downstream failure");
        RuntimeException terminalFailure = new IllegalStateException("delivery attempts exhausted", deliveryFailure);
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                attempts.add(entities(messages));
                append(input, "third\n");
                throw deliveryFailure;
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                failedAttempts.add(failedAttempt);
                assertThat(entities(messages), is(List.of("first", "second")));
                assertThat(failure, sameInstance(deliveryFailure));
                throw terminalFailure;
            }
        };
        var source = new FileIncomingConnector.FileSource(incomingConfig(input), context, new AtomicBoolean());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> source.emitAppendedLines(input, 0));

        assertThat(thrown, sameInstance(terminalFailure));
        assertThat(attempts, is(List.of(List.of("first", "second"))));
        assertThat(failedAttempts, is(List.of(1)));
    }

    @Test
    @Timeout(value = 5)
    void testCloseStopsFailedDeliveryWithoutAdvancingOffset(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "first\n");
        CountDownLatch retryScheduled = new CountDownLatch(1);
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public FailurePolicy failurePolicy() {
                return FailurePolicy.builder()
                        .retryDelay(Duration.ofSeconds(30))
                        .build();
            }

            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                throw new IllegalStateException("expected downstream failure");
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                retryScheduled.countDown();
                return FailureResult.RETRY;
            }
        };
        FileConnectorProvider provider = new FileConnectorProvider();
        var source = (FileIncomingConnector.FileSource) provider.createIncomingEndpoint(incomingConfig(input), context);
        AtomicInteger offset = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                offset.set(source.emitAppendedLines(input, 0));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        assertThat(retryScheduled.await(1, TimeUnit.SECONDS), is(true));

        source.close();
        thread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(offset.get(), is(0));
    }

    @Test
    @Timeout(value = 5)
    void testEndpointCloseInterruptsReservationWait(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "");
        CountDownLatch reservationWait = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        ConnectorSourceContext context = new TrackingReservationContext(1, 64, ignored -> {
        }) {
            @Override
            public ConnectorDeliveryReservation reserveDelivery(int maxMessages, long maxAdmissionBytes) {
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
        IncomingEndpoint source = provider.createIncomingEndpoint(incomingConfig(input), context);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedOnExit = new AtomicBoolean();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> {
                    source.run();
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
    void testEndpointCloseInterruptsDeliveryStartWaitAndClosesReservation(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "");
        CountDownLatch initialReadFinished = new CountDownLatch(1);
        CountDownLatch startWait = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicInteger reservationCount = new AtomicInteger();
        AtomicBoolean blockedReservationClosed = new AtomicBoolean();
        ConnectorSourceContext context = new TrackingReservationContext(1, 64, ignored -> {
        }) {
            @Override
            public ConnectorDeliveryReservation reserveDelivery(int maxMessages, long maxAdmissionBytes) {
                if (reservationCount.incrementAndGet() == 1) {
                    return new ConnectorDeliveryReservation() {
                        @Override
                        public <T> ConnectorDelivery start(List<? extends Message<T>> messages,
                                                           long admissionBytes,
                                                           Runnable delivery) {
                            throw new AssertionError("Initial empty read must not start a delivery");
                        }

                        @Override
                        public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                                       long admissionBytes,
                                                                       Runnable delivery) {
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
                    public <T> ConnectorDelivery start(List<? extends Message<T>> messages,
                                                       long admissionBytes,
                                                       Runnable delivery) {
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
                    public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                                   long admissionBytes,
                                                                   Runnable delivery) {
                        return Optional.of(start(messages, admissionBytes, delivery));
                    }

                    @Override
                    public void close() {
                        blockedReservationClosed.set(true);
                    }
                };
            }
        };
        FileConnectorProvider provider = new FileConnectorProvider();
        IncomingEndpoint source = provider.createIncomingEndpoint(incomingConfig(input), context);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedOnExit = new AtomicBoolean();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(() -> {
                    source.run();
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
        TrackingReservationContext context = new TrackingReservationContext(1, 64, ignored -> {
        });
        var source = new FileIncomingConnector.FileSource(
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
                    source.run();
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
    @SuppressWarnings("unchecked")
    void testServiceRegistryDiscoversProviderWithoutOwningEndpointLifecycle(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        IncomingEndpoint source = null;
        Thread sourceThread = null;
        try {
            ConnectorProvider<?> discovered = manager.registry().get(ConnectorProvider.class);
            assertThat(discovered, instanceOf(FileConnectorProvider.class));
            assertThat(manager.registry().get(IncomingConnectorProvider.class), sameInstance(discovered));
            assertThat(manager.registry().get(OutgoingConnectorProvider.class), sameInstance(discovered));
            IncomingConnectorProvider<FileConnectorConfig> provider =
                    (IncomingConnectorProvider<FileConnectorConfig>) discovered;
            source = provider.createIncomingEndpoint(incomingConfig(input), new ConnectorSourceContext() {
                @Override
                public String channelName() {
                    return "events";
                }

                @Override
                public <T> void emit(Message<T> message) {
                    throw new AssertionError("No existing file content should be emitted");
                }
            });
            sourceThread = Thread.ofVirtual()
                    .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                    .start(source);
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
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                deliveries.add(entities(messages));
                delivered.countDown();
            }
        };
        FileConnectorProvider provider = new FileConnectorProvider();
        IncomingEndpoint source = provider.createIncomingEndpoint(incomingConfig(input), context);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(source);
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
    void testGraphManagedSourceReconcilesAppendOnlyAfterAdmissionStarts(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "existing content is tailed\n");
        List<List<String>> deliveries = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        ConnectorSourceContext context = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                deliveries.add(entities(messages));
                delivered.countDown();
            }
        };
        FileConnectorProvider provider = new FileConnectorProvider();
        IncomingEndpoint source = provider.createIncomingEndpoint(incomingConfig(input), context);
        source.prepareForGraph();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(source);
        source.awaitReady(Duration.ofSeconds(1));

        append(input, "first\n");
        assertThat(delivered.await(200, TimeUnit.MILLISECONDS), is(false));

        source.startAdmission();
        assertThat(delivered.await(1, TimeUnit.SECONDS), is(true));
        source.stopAdmission();
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
        IncomingEndpoint source = provider.createIncomingEndpoint(
                incomingConfig(input),
                new TrackingReservationContext(1, 64, ignored -> {
                }));
        source.prepareForGraph();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sourceThread = Thread.ofVirtual()
                .uncaughtExceptionHandler((ignored, throwable) -> failure.set(throwable))
                .start(source);
        source.awaitReady(Duration.ofSeconds(1));
        source.stopAdmission();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));
        source.close();

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThrows(IllegalStateException.class, source::run);
    }

    @Test
    void testWatchKeyIsDrainedAndInvalidRegistrationFails() throws IOException {
        Path target = Path.of("events.log");
        TestWatchKey valid = new TestWatchKey(
                List.of(new TestWatchEvent(target), new TestWatchEvent(Path.of("unrelated.log"))),
                true);

        assertThat(FileIncomingConnector.FileSource.consumeWatchKey(valid, target), is(true));
        assertThat(valid.pollCount(), is(1));
        assertThat(valid.resetCount(), is(1));

        TestWatchKey invalid = new TestWatchKey(List.of(), false);
        IOException failure = assertThrows(IOException.class,
                                           () -> FileIncomingConnector.FileSource.consumeWatchKey(invalid, target));
        assertThat(failure.getMessage(), is("File watch registration is no longer valid for events.log"));
        assertThat(invalid.pollCount(), is(1));
        assertThat(invalid.resetCount(), is(1));
    }

    private static FileConnectorConfig config(Path path) {
        return config(path, FileConnectorConfig.DEFAULT_LINE_SEPARATOR);
    }

    private static OutgoingEndpoint outgoingEndpoint(FileConnectorConfig config) {
        OutgoingEndpoint endpoint = new FileConnectorProvider().createOutgoingEndpoint(config);
        endpoint.start();
        return endpoint;
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

    private static FileConnectorConfig incomingConfig(Path path, String lineSeparator) {
        return FileConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel("events")
                .connector(FileConnectorProvider.CONNECTOR_TYPE)
                .path(path)
                .lineSeparator(lineSeparator)
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

    private static ConnectorSourceContext incomingContext(BatchConsumer consumer) {
        return new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                consumer.accept(messages);
            }
        };
    }

    private static ConnectorSourceContext retryingContext(BatchConsumer consumer) {
        return new ConnectorSourceContext() {
            @Override
            public FailurePolicy failurePolicy() {
                return FailurePolicy.builder()
                        .retryDelay(Duration.ofMillis(1))
                        .build();
            }

            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                consumer.accept(messages);
            }

            @Override
            public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                                   int failedAttempt,
                                                   RuntimeException failure) {
                return FailureResult.RETRY;
            }
        };
    }

    private static ConnectorSourceContext boundedContext(int maxMessages,
                                                         long maxBytes,
                                                         BatchConsumer consumer) {
        return new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "events";
            }

            @Override
            public int maxDeliveryMessages() {
                return maxMessages;
            }

            @Override
            public long maxDeliveryBytes() {
                return maxBytes;
            }

            @Override
            public <T> void emit(Message<T> message) {
                throw new AssertionError("File source must emit appended lines as a batch");
            }

            @Override
            public <T> void emitBatch(List<? extends Message<T>> messages) {
                consumer.accept(messages);
            }
        };
    }

    private static List<String> entities(List<? extends Message<?>> messages) {
        return messages.stream()
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

    private static class TrackingReservationContext implements ConnectorSourceContext {
        private final int maxMessages;
        private final long maxBytes;
        private final BatchConsumer consumer;
        private final List<TrackingReservation> reservations = new ArrayList<>();

        private TrackingReservationContext(int maxMessages, long maxBytes, BatchConsumer consumer) {
            this.maxMessages = maxMessages;
            this.maxBytes = maxBytes;
            this.consumer = consumer;
        }

        @Override
        public String channelName() {
            return "events";
        }

        @Override
        public int maxDeliveryMessages() {
            return maxMessages;
        }

        @Override
        public long maxDeliveryBytes() {
            return maxBytes;
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery(int maxMessages, long maxAdmissionBytes) {
            TrackingReservation reservation = new TrackingReservation(maxMessages, maxAdmissionBytes);
            reservations.add(reservation);
            return reservation;
        }

        @Override
        public <T> void emit(Message<T> message) {
            throw new AssertionError("File source must emit appended lines as a batch");
        }

        @Override
        public <T> void emitBatch(List<? extends Message<T>> messages) {
            consumer.accept(messages);
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
        private final long reservedBytes;
        private boolean started;
        private boolean closed;
        private int actualMessages;
        private long actualBytes;
        private TrackingDelivery delivery;

        private TrackingReservation(int reservedMessages, long reservedBytes) {
            this.reservedMessages = reservedMessages;
            this.reservedBytes = reservedBytes;
        }

        @Override
        public <T> ConnectorDelivery start(List<? extends Message<T>> messages,
                                           long admissionBytes,
                                           Runnable action) {
            if (!open()) {
                throw new IllegalStateException("Reservation is not open");
            }
            if (messages.size() > reservedMessages || admissionBytes > reservedBytes) {
                throw new IllegalArgumentException("Actual delivery exceeds reservation");
            }
            started = true;
            actualMessages = messages.size();
            actualBytes = admissionBytes;
            delivery = new TrackingDelivery(action);
            delivery.run();
            return delivery;
        }

        @Override
        public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                        long admissionBytes,
                                                        Runnable action) {
            return Optional.of(start(messages, admissionBytes, action));
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

        private long reservedBytes() {
            return reservedBytes;
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

        private long actualBytes() {
            return actualBytes;
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
        void accept(List<? extends Message<?>> messages);
    }
}
