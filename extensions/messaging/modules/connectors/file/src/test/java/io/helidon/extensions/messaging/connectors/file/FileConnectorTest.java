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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorSink;
import io.helidon.extensions.messaging.ConnectorSource;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.FailurePolicy;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingChannel;
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
    void testConnectorName() {
        assertThat(new FileOutgoingConnector().connectorName(), is("file"));
        assertThat(new FileIncomingConnector().connectorName(), is("file"));
    }

    @Test
    void testMissingPathFails() {
        assertThrows(RuntimeException.class,
                     () -> FileConnectorConfig.builder()
                             .direction(ConnectorConfig.Direction.OUTGOING)
                             .channel("audit")
                             .connector(FileOutgoingConnector.CONNECTOR)
                             .build());
    }

    @Test
    void testEmptyLineSeparatorFails(@TempDir Path tempDir) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                                                        () -> config(Path.of("audit.log"), ""));

        assertThat(failure.getMessage(), is("line-separator must not be empty"));

        RuntimeException configFailure = assertThrows(RuntimeException.class,
                                                      () -> FileConnectorConfig.create(
                                                              Config.just(ConfigSources.create(Map.of(
                                                                      "direction", "OUTGOING",
                                                                      ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "audit",
                                                                      ConnectorConfig.CONNECTOR_ATTRIBUTE,
                                                                      FileOutgoingConnector.CONNECTOR,
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
                ConnectorConfig.CONNECTOR_ATTRIBUTE, FileOutgoingConnector.CONNECTOR,
                FileConnectorConfig.PATH_PROPERTY, auditLog.toString(),
                FileConnectorConfig.LINE_SEPARATOR_PROPERTY, "|"))));

        assertThat(config.direction(), is(ConnectorConfig.Direction.OUTGOING));
        assertThat(config.channel(), is("audit"));
        assertThat(config.connector(), is(FileOutgoingConnector.CONNECTOR));
        assertThat(config.path(), is(auditLog));
        assertThat(config.lineSeparator(), is("|"));
    }

    @Test
    void testDefaultLineSeparatorWritesOneMessagePerLine(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = new FileOutgoingConnector().createSink(config(auditLog));

        sink.send(Message.create("first audit event"));
        sink.send(Message.create("second audit event"));

        assertThat(Files.readString(auditLog), is("first audit event\nsecond audit event\n"));
    }

    @Test
    void testCustomLineSeparator(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = new FileOutgoingConnector().createSink(config(auditLog, "|"));

        sink.send(Message.create("first audit event"));
        sink.send(Message.create("second audit event"));

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
    }

    @Test
    void testBatchWritesMessagesInOneConnectorCall(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");
        var sink = new FileOutgoingConnector().createSink(config(auditLog, "|"));

        sink.sendBatch(List.of(Message.create("first audit event"),
                               Message.create("second audit event")));

        assertThat(Files.readString(auditLog), is("first audit event|second audit event|"));
    }

    @Test
    @Timeout(value = 10)
    void testConcurrentLargeWritesRemainFramedAcrossSinks(@TempDir Path tempDir) throws Exception {
        Path auditLog = tempDir.resolve("audit.log");
        FileOutgoingConnector connector = new FileOutgoingConnector();
        int writerCount = 8;
        List<String> expected = new ArrayList<>();
        List<Thread> writers = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < writerCount; i++) {
            Path configuredPath = i % 2 == 0
                    ? auditLog
                    : tempDir.resolve(".").resolve("audit.log");
            ConnectorSink sink = connector.createSink(config(configuredPath));
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

        new FileOutgoingConnector().createSink(config(auditLog))
                .send(Message.create("audit event"));

        assertThat(Files.readString(auditLog), is("audit event\n"));
    }

    @Test
    void testFileConnectorCanBeUsedAsChannelOutput(@TempDir Path tempDir) throws IOException {
        Path auditLog = tempDir.resolve("audit.log");

        MessagingChannel<String> channel = MessagingChannel.<String>builder()
                .payloadType(String.class)
                .addOutgoingConnector(new FileOutgoingConnector().createSink(config(auditLog, "|")))
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
                .addOutgoingConnector(new FileOutgoingConnector().createSink(config(auditLog, "|")))
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

        int offset = source.emitAppendedLines(input, 0);

        assertThat(deliveries, is(List.of(List.of("first"))));
        assertThat(offset, is("first\n".getBytes(StandardCharsets.UTF_8).length));

        Files.write(input,
                    new byte[] {emoji[2], emoji[3], '\n'},
                    StandardOpenOption.APPEND);
        offset = source.emitAppendedLines(input, offset);

        assertThat(deliveries, is(List.of(List.of("first"), List.of("\uD83D\uDE00"))));
        assertThat(offset, is((int) Files.size(input)));
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
        FileIncomingConnector connector = new FileIncomingConnector();
        var source = (FileIncomingConnector.FileSource) connector.createSource(incomingConfig(input), context);
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

        connector.close();
        thread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(thread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(offset.get(), is(0));
    }

    @Test
    @Timeout(value = 5)
    void testServiceRegistryShutdownStopsSource(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServiceRegistryManager manager = ServiceRegistryManager.create();
        Thread sourceThread;
        try {
            IncomingConnector<?> discovered = manager.registry().get(IncomingConnector.class);
            assertThat(discovered, instanceOf(FileIncomingConnector.class));
            FileIncomingConnector connector = (FileIncomingConnector) discovered;
            ConnectorSource source = connector.createSource(incomingConfig(input), new ConnectorSourceContext() {
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
        } finally {
            manager.shutdown();
        }

        sourceThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
    }

    @Test
    @Timeout(value = 5)
    void testWatchServiceDrainsEventsAndDeliversEachAppendOnce(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("events.log");
        Files.writeString(input, "existing content is tailed\n");
        List<List<String>> deliveries = Collections.synchronizedList(new ArrayList<>());
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
        FileIncomingConnector connector = new FileIncomingConnector();
        ConnectorSource source = connector.createSource(incomingConfig(input), context);
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
        connector.close();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(failure.get(), nullValue());
        assertThat(deliveries, is(List.of(List.of("first"), List.of("second"))));
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

    private static FileConnectorConfig config(Path path, String lineSeparator) {
        return FileConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel("audit")
                .connector(FileOutgoingConnector.CONNECTOR)
                .path(path)
                .lineSeparator(lineSeparator)
                .build();
    }

    private static FileConnectorConfig incomingConfig(Path path) {
        return FileConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel("events")
                .connector(FileOutgoingConnector.CONNECTOR)
                .path(path)
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
