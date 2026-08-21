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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.messaging.ConnectorDelivery;
import io.helidon.messaging.ConnectorDeliveryReservation;
import io.helidon.messaging.IncomingConnector;
import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.MessagingRejectedException;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * File incoming connector implementation.
 * <p>
 * Within an active connector lifetime, a complete appended-line batch remains pending until its runtime-owned
 * delivery completes normally. The runtime owns retries and failure disposition. The connector does not advance its
 * in-memory file offset or read later appends while a batch is pending, and leaves the cursor unchanged when runtime
 * delivery fails. Each retained batch is bounded by the incoming context's message-count limit and the connector's
 * {@code max-batch-bytes} configuration. An in-progress line is bounded by {@code max-line-bytes}.
 * <p>
 * The file offset is not persisted. A replacement connector starts at the file's current end and does not recover an
 * unsettled batch from an earlier connector lifetime.
 */
final class FileIncomingConnector {
    private static final int READ_BUFFER_SIZE = 8192;
    private static final int SNAPSHOT_ATTEMPTS = 3;

    private FileIncomingConnector() {
    }

    static IncomingConnector createConnector(FileConnectorConfig config) {
        return FileConnector.managed(Objects.requireNonNull(config));
    }

    static final class FileConnector implements IncomingConnector {
        private final FileConnectorConfig config;
        private final AtomicBoolean closed;
        private final AtomicBoolean runStarted;
        private final Set<Thread> connectorThreads;
        private final ReentrantLock connectorThreadsLock;
        private final WatchRegistrationListener watchRegistrationListener;
        private final FileReadListener fileReadListener;
        private final AtomicBoolean draining;
        private volatile IncomingConnectorContext context;

        private static FileConnector managed(FileConnectorConfig config) {
            return new FileConnector(config,
                                     null,
                                     new AtomicBoolean(),
                                     new AtomicBoolean(),
                                     ConcurrentHashMap.newKeySet(),
                                     new ReentrantLock(),
                                     new WatchRegistrationListener() {
                                     },
                                     path -> {
                                     },
                                     new AtomicBoolean());
        }

        FileConnector(FileConnectorConfig config,
                      IncomingConnectorContext context,
                      AtomicBoolean closed) {
            this(config,
                 context,
                 closed,
                 new AtomicBoolean(),
                 ConcurrentHashMap.newKeySet(),
                 new ReentrantLock(),
                 new WatchRegistrationListener() {
                 },
                 path -> {
                 },
                 new AtomicBoolean());
        }

        FileConnector(FileConnectorConfig config,
                      IncomingConnectorContext context,
                      AtomicBoolean closed,
                      Set<Thread> connectorThreads) {
            this(config,
                 context,
                 closed,
                 new AtomicBoolean(),
                 connectorThreads,
                 new ReentrantLock(),
                 new WatchRegistrationListener() {
                 },
                 path -> {
                 },
                 new AtomicBoolean());
        }

        FileConnector(FileConnectorConfig config,
                      IncomingConnectorContext context,
                      AtomicBoolean closed,
                      WatchRegistrationListener watchRegistrationListener) {
            this(config,
                 context,
                 closed,
                 new AtomicBoolean(),
                 ConcurrentHashMap.newKeySet(),
                 new ReentrantLock(),
                 watchRegistrationListener,
                 path -> {
            },
                 new AtomicBoolean());
        }

        FileConnector(FileConnectorConfig config,
                      IncomingConnectorContext context,
                      AtomicBoolean closed,
                      WatchRegistrationListener watchRegistrationListener,
                      FileReadListener fileReadListener) {
            this(config,
                 context,
                 closed,
                 new AtomicBoolean(),
                 ConcurrentHashMap.newKeySet(),
                 new ReentrantLock(),
                 watchRegistrationListener,
                 fileReadListener,
                 new AtomicBoolean());
        }

        private FileConnector(FileConnectorConfig config,
                              IncomingConnectorContext context,
                              AtomicBoolean closed,
                              AtomicBoolean runStarted,
                              Set<Thread> connectorThreads,
                              ReentrantLock connectorThreadsLock,
                              WatchRegistrationListener watchRegistrationListener,
                              FileReadListener fileReadListener,
                              AtomicBoolean draining) {
            this.config = Objects.requireNonNull(config);
            this.context = context;
            this.closed = Objects.requireNonNull(closed);
            this.runStarted = Objects.requireNonNull(runStarted);
            this.connectorThreads = Objects.requireNonNull(connectorThreads);
            this.connectorThreadsLock = Objects.requireNonNull(connectorThreadsLock);
            this.watchRegistrationListener = Objects.requireNonNull(watchRegistrationListener);
            this.fileReadListener = Objects.requireNonNull(fileReadListener);
            this.draining = Objects.requireNonNull(draining);
        }

        @Override
        public void run(IncomingConnectorContext context) {
            if (!runStarted.compareAndSet(false, true)) {
                throw new IllegalStateException("File incoming connector can only be run once");
            }
            IncomingConnectorContext runContext = Objects.requireNonNull(context);
            IncomingConnectorContext configuredContext = this.context;
            if (configuredContext != null && configuredContext != runContext) {
                throw new IllegalStateException("File incoming connector was run with a different context");
            }
            this.context = runContext;
            Thread connectorThread = Thread.currentThread();
            boolean closedBeforeStartup;
            connectorThreadsLock.lock();
            try {
                closedBeforeStartup = closed.get();
                if (!closedBeforeStartup) {
                    connectorThreads.add(connectorThread);
                }
            } finally {
                connectorThreadsLock.unlock();
            }
            if (closedBeforeStartup) {
                return;
            }
            try {
                try {
                    tailFile();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (MessagingRejectedException e) {
                    if (!isCancellation(e)) {
                        throw e;
                    }
                    if (causedByInterruption(e)) {
                        Thread.currentThread().interrupt();
                    }
                } catch (IOException e) {
                    MessagingException failure = new MessagingException("File incoming connector failed", e);
                    if (Thread.currentThread().isInterrupted() || isInterruptDriven(e)) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    throw failure;
                } catch (RuntimeException | Error e) {
                    throw e;
                }
            } finally {
                closed.set(true);
                connectorThreadsLock.lock();
                try {
                    connectorThreads.remove(connectorThread);
                } finally {
                    connectorThreadsLock.unlock();
                }
            }
        }

        @Override
        public void drain() {
            draining.set(true);
        }

        @Override
        public void forceClose() {
            closed.set(true);
            draining.set(true);
            connectorThreadsLock.lock();
            try {
                connectorThreads.forEach(Thread::interrupt);
            } finally {
                connectorThreadsLock.unlock();
            }
        }

        @Override
        public void close() {
            forceClose();
        }

        private void tailFile() throws IOException, InterruptedException {
            Path configuredPath = config.path().toAbsolutePath().normalize();
            Path directory = configuredPath.getParent();
            Files.createDirectories(directory);
            if (Files.notExists(configuredPath)) {
                try {
                    Files.createFile(configuredPath);
                } catch (FileAlreadyExistsException ignored) {
                    // Another process created the path after the existence check.
                }
            }

            // Watch the resolved target so writes through a symbolic link are observed in the target directory.
            Path path = configuredPath.toRealPath();
            directory = path.getParent();
            FileCursor cursor = currentEndCursor(path);
            try (WatchService watcher = path.getFileSystem().newWatchService()) {
                watchRegistrationListener.beforeRegistration(path);
                directory.register(watcher, ENTRY_CREATE, ENTRY_MODIFY);
                watchRegistrationListener.afterRegistration();
                if (!context.awaitRunning() || closed.get() || draining.get()) {
                    return;
                }
                // Reconcile changes made after the initial snapshot but before watcher registration.
                cursor = emitAppendedLines(path, cursor);
                watchRegistrationListener.afterReconciliation();
                while (!closed.get() && !draining.get()) {
                    WatchKey key = watcher.poll(100, TimeUnit.MILLISECONDS);
                    if (key == null) {
                        continue;
                    }
                    if (consumeWatchKey(key, path.getFileName())) {
                        cursor = emitAppendedLines(path, cursor);
                    }
                }
            }
        }

        static boolean consumeWatchKey(WatchKey key, Path targetFileName) throws IOException {
            boolean changed = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == OVERFLOW || targetFileName.equals(event.context())) {
                    changed = true;
                }
            }
            if (!key.reset()) {
                throw new IOException("File watch registration is no longer valid for " + targetFileName);
            }
            return changed;
        }

        int emitAppendedLines(Path path, int offset) throws IOException, InterruptedException {
            return Math.toIntExact(emitAppendedLines(path, currentCursor(path, offset)).offset());
        }

        FileCursor emitAppendedLines(Path path, FileCursor cursor) throws IOException, InterruptedException {
            FileReadLimits limits = new FileReadLimits(context.maxDeliveryMessages(),
                                                       config.maxLineBytes(),
                                                       config.maxBatchBytes());
            if (limits.maxMessages() <= 0) {
                throw new MessagingException("File delivery message limit must be greater than zero");
            }
            while (!closed.get() && !draining.get()) {
                try (ConnectorDeliveryReservation reservation =
                             context.reserveDelivery()) {
                    if (closed.get() || draining.get()) {
                        break;
                    }
                    AppendedDelivery delivery = readAppendedLines(path, cursor, limits);
                    if (delivery.messages().isEmpty()) {
                        cursor = delivery.nextCursor();
                        break;
                    }
                    if (closed.get()) {
                        break;
                    }

                    MessageBatch<String> batch = MessageBatch.<String>builder()
                            .messages(delivery.messages())
                            .build();
                    try (ConnectorDelivery deliveryLease = reservation.start(batch)) {
                        awaitDelivery(deliveryLease);
                        cursor = delivery.nextCursor();
                    }
                }
            }
            return cursor;
        }

        private AppendedDelivery readAppendedLines(Path path,
                                                   FileCursor cursor,
                                                   FileReadLimits limits) throws IOException {
            for (int attempt = 0; attempt < SNAPSHOT_ATTEMPTS; attempt++) {
                fileReadListener.beforeRead(path);
                FileState before = fileState(path);
                try (FileChannel channel = FileChannel.open(path, READ)) {
                    FileState opened = fileState(path);
                    if (!before.identity().sameFile(opened.identity())) {
                        continue;
                    }

                    CursorState cursorState = validateCursor(path, channel, cursor, opened);
                    FileReadTracker readTracker = new FileReadTracker(cursorState.scanOffset());
                    AppendedDelivery delivery;
                    try {
                        delivery = readDelivery(path,
                                                channel,
                                                cursorState,
                                                readTracker,
                                                opened,
                                                limits);
                    } catch (IOException | RuntimeException e) {
                        if (e instanceof FileSnapshotChangedException) {
                            cursor = new FileCursor(cursor.offset(),
                                                    cursor.state(),
                                                    cursor.guard(),
                                                    null);
                            continue;
                        }
                        FileReadSnapshot readSnapshot = readTracker.snapshot();
                        FileState after = fileState(path);
                        boolean snapshotValid = opened.identity().sameFile(after.identity())
                                && validateReadSnapshot(path, channel, cursorState.guard())
                                && validateReadSnapshot(path, channel, readSnapshot);
                        FileState validatedState = fileState(path);
                        if (!snapshotValid
                                || !opened.identity().sameFile(validatedState.identity())
                                || validatedState.size() < readSnapshot.endOffset()) {
                            continue;
                        }
                        throw e;
                    }
                    FileState after = fileState(path);
                    AppendedDelivery validated = validateDeliverySnapshot(path, channel, after, delivery);
                    if (validated != null) {
                        return validated;
                    }
                }
            }
            throw new IOException("File was repeatedly replaced while reading " + path);
        }

        private AppendedDelivery validateDeliverySnapshot(Path path,
                                                          FileChannel channel,
                                                          FileState state,
                                                          AppendedDelivery delivery) throws IOException {
            FileCursor cursor = delivery.nextCursor();
            FileReadSnapshot readSnapshot = delivery.readSnapshot();
            FileReadSnapshot acceptedSnapshot = delivery.acceptedSnapshot();
            FileReadSnapshot acceptedGuard = delivery.acceptedGuard();
            if (!cursor.state().identity().sameFile(state.identity())
                    || cursor.offset() > channel.size()
                    || readSnapshot.endOffset() > channel.size()
                    || acceptedSnapshot.endOffset() > channel.size()
                    || acceptedGuard.endOffset() > channel.size()) {
                return null;
            }

            if (!validateDeliverySnapshots(path, channel, delivery)) {
                return null;
            }
            FileState validatedState = fileState(path);
            if (!state.identity().sameFile(validatedState.identity())
                    || validatedState.size() < state.size()) {
                return null;
            }
            if (validatedState.size() == state.size()) {
                if (!state.lastModifiedTime().equals(validatedState.lastModifiedTime())) {
                    return null;
                }
            } else {
                /*
                 * Growth may be a legitimate concurrent append. Revalidate every captured range once after observing
                 * it, so a rewrite hidden behind that growth cannot commit stale content. Further growth is accepted
                 * after this pass to preserve liveness for a continuously appended file.
                 */
                if (!validateDeliverySnapshots(path, channel, delivery)) {
                    return null;
                }
                FileState growthValidatedState = fileState(path);
                if (!validatedState.identity().sameFile(growthValidatedState.identity())
                        || growthValidatedState.size() < validatedState.size()
                        || (growthValidatedState.size() == validatedState.size()
                        && !validatedState.lastModifiedTime().equals(growthValidatedState.lastModifiedTime()))) {
                    return null;
                }
                validatedState = growthValidatedState;
            }
            // Unaccepted bytes may be corrected in place and must not become part of the committed cursor guard.
            FileReadSnapshot nextGuard = acceptedSnapshot.isEmpty()
                    ? delivery.priorGuard()
                    : acceptedGuard;
            return new AppendedDelivery(delivery.messages(),
                                        new FileCursor(cursor.offset(),
                                                       validatedState,
                                                       nextGuard,
                                                       cursor.incompleteLineCopy()),
                                        readSnapshot,
                                        delivery.priorGuard(),
                                        acceptedSnapshot,
                                        acceptedGuard);
        }

        private boolean validateDeliverySnapshots(Path path,
                                                  FileChannel channel,
                                                  AppendedDelivery delivery) throws IOException {
            return validateReadSnapshot(path, channel, delivery.priorGuard())
                    && validateReadSnapshot(path, channel, delivery.readSnapshot())
                    && validateReadSnapshot(path, channel, delivery.acceptedGuard())
                    && validateReadSnapshot(path, channel, delivery.acceptedSnapshot());
        }

        FileCursor currentCursor(Path path, int offset) throws IOException {
            return currentCursor(path, offset, false);
        }

        private FileCursor currentEndCursor(Path path) throws IOException {
            return currentCursor(path, 0, true);
        }

        private FileCursor currentCursor(Path path, long requestedOffset, boolean currentEnd) throws IOException {
            for (int attempt = 0; attempt < SNAPSHOT_ATTEMPTS; attempt++) {
                FileState before = fileState(path);
                long offset = currentEnd
                        ? before.size()
                        : requestedOffset >= 0 && requestedOffset <= before.size() ? requestedOffset : 0;
                try (FileChannel channel = FileChannel.open(path, READ)) {
                    FileState opened = fileState(path);
                    if (!before.identity().sameFile(opened.identity()) || channel.size() < offset) {
                        continue;
                    }
                    FileReadSnapshot guard = snapshotWindow(path, channel, offset, fileReadListener);
                    fileReadListener.afterCursorRead(path);
                    if (!validateReadSnapshot(path, channel, guard)) {
                        continue;
                    }
                    FileState after = fileState(path);
                    if (opened.identity().sameFile(after.identity()) && after.size() >= offset) {
                        return new FileCursor(offset, after, guard, null);
                    }
                }
            }
            throw new IOException("File was repeatedly replaced while reading " + path);
        }

        private CursorState validateCursor(Path path,
                                           FileChannel channel,
                                           FileCursor cursor,
                                           FileState state) throws IOException {
            if (cursor.offset() < 0
                    || cursor.offset() > channel.size()
                    || !cursor.state().identity().sameFile(state.identity())
                    || !validateReadSnapshot(path, channel, cursor.guard())) {
                channel.position(0);
                return new CursorState(0, emptySnapshot(), null);
            }

            IncompleteLineState incompleteLine = cursor.incompleteLineValid(channel.size())
                    ? cursor.incompleteLineCopy()
                    : null;
            if (incompleteLine != null
                    && channel.size() == incompleteLine.scanOffset()
                    && !validateReadSnapshot(path,
                                             channel,
                                             new FileReadSnapshot(cursor.offset(),
                                                                  incompleteLine.scanOffset(),
                                                                  incompleteLine.lineDigestCopy().digest()))) {
                // No bytes were appended, so the event can only make progress if the incomplete tail was rewritten.
                incompleteLine = null;
            }
            return new CursorState(cursor.offset(),
                                   cursor.guard(),
                                   incompleteLine);
        }

        private AppendedDelivery readDelivery(Path path,
                                              FileChannel channel,
                                              CursorState cursorState,
                                              FileReadTracker readTracker,
                                              FileState state,
                                              FileReadLimits limits) throws IOException {
            int maxMessages = limits.maxMessages();
            byte[] separator = config.lineSeparator().getBytes(StandardCharsets.UTF_8);
            int[] separatorPrefixes = separatorPrefixes(separator);
            IncompleteLineState incompleteLine = cursorState.incompleteLine();
            List<Message<String>> messages = new ArrayList<>(Math.min(maxMessages, 16));
            ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
            long lineBytes = incompleteLine == null ? 0 : incompleteLine.lineBytes();
            long acceptedFileBytes = 0;
            long acceptedPayloadBytes = 0;
            int separatorMatch = incompleteLine == null ? 0 : incompleteLine.separatorMatch();
            boolean rescanTrailingLine = false;
            Utf8Validator utf8 = new Utf8Validator(incompleteLine == null ? null : incompleteLine.utf8State());
            MessageDigest lineDigest = incompleteLine == null
                    ? newDigest()
                    : incompleteLine.lineDigestCopy();
            MessageDigest acceptedDigest = newDigest();
            LineReadContext lineReadContext = new LineReadContext(path, channel);

            channel.position(cursorState.scanOffset());
            readLoop:
            while (messages.size() < maxMessages) {
                readBuffer.clear();
                int read = channel.read(readBuffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                long readOffset = channel.position() - read;
                fileReadListener.afterRead(path, readOffset, read);
                readBuffer.flip();
                while (readBuffer.hasRemaining()) {
                    byte value = readBuffer.get();
                    readTracker.update(value);
                    long nextLineBytes = lineBytes + 1;
                    int nextSeparatorMatch =
                            separatorMatch(separator, separatorPrefixes, separatorMatch, value);
                    if (nextSeparatorMatch != separator.length) {
                        long definitePayloadBytes = nextLineBytes - nextSeparatorMatch;
                        if (definitePayloadBytes > limits.maxLineBytes()
                                || definitePayloadBytes > limits.maxBatchBytes() - acceptedPayloadBytes) {
                            if (messages.isEmpty()) {
                                lineDigest.update(value);
                                validateLineSnapshot(path,
                                                     channel,
                                                     cursorState.offset() + acceptedFileBytes,
                                                     nextLineBytes,
                                                     lineDigest);
                                throw oversizedLine(context.channel(),
                                                    cursorState.offset(),
                                                    limits.maxLineBytes());
                            }
                            rescanTrailingLine = true;
                            break readLoop;
                        }
                        if (!acceptUtf8(lineReadContext,
                                        cursorState.offset() + acceptedFileBytes,
                                        nextLineBytes,
                                        lineDigest,
                                        utf8,
                                        value,
                                        !messages.isEmpty())) {
                            rescanTrailingLine = true;
                            break readLoop;
                        }
                        lineBytes = nextLineBytes;
                        separatorMatch = nextSeparatorMatch;
                        continue;
                    }

                    long payloadBytes = nextLineBytes - separator.length;
                    if (messages.isEmpty()
                            && (payloadBytes > limits.maxLineBytes()
                                    || payloadBytes > limits.maxBatchBytes()
                                    || nextLineBytes > Integer.MAX_VALUE - 8L)) {
                        lineDigest.update(value);
                        validateLineSnapshot(path,
                                             channel,
                                             cursorState.offset() + acceptedFileBytes,
                                             nextLineBytes,
                                             lineDigest);
                        throw oversizedLine(context.channel(),
                                            cursorState.offset() + acceptedFileBytes,
                                            limits.maxLineBytes());
                    }
                    if (payloadBytes > limits.maxLineBytes()
                            || payloadBytes > limits.maxBatchBytes() - acceptedPayloadBytes
                            || nextLineBytes > Integer.MAX_VALUE - 8L) {
                        rescanTrailingLine = true;
                        break readLoop;
                    }

                    if (!acceptUtf8(lineReadContext,
                                    cursorState.offset() + acceptedFileBytes,
                                    nextLineBytes,
                                    lineDigest,
                                    utf8,
                                    value,
                                    !messages.isEmpty())) {
                        rescanTrailingLine = true;
                        break readLoop;
                    }
                    lineBytes = nextLineBytes;
                    separatorMatch = nextSeparatorMatch;
                    utf8.complete();
                    byte[] framedLine = readValidatedLine(path,
                                                         channel,
                                                         cursorState.offset() + acceptedFileBytes,
                                                         lineBytes,
                                                         lineDigest);
                    messages.add(Message.create(decodeCompleteLine(framedLine, Math.toIntExact(payloadBytes))));
                    acceptedDigest.update(framedLine);
                    acceptedFileBytes += lineBytes;
                    acceptedPayloadBytes += payloadBytes;

                    lineBytes = 0;
                    separatorMatch = 0;
                    utf8 = new Utf8Validator(null);
                    lineDigest = newDigest();
                    if (messages.size() == maxMessages || acceptedPayloadBytes == limits.maxBatchBytes()) {
                        break readLoop;
                    }
                }
            }

            long nextOffset = cursorState.offset() + acceptedFileBytes;
            IncompleteLineState nextIncompleteLine = lineBytes == 0 || rescanTrailingLine
                    ? null
                    : new IncompleteLineState(nextOffset + lineBytes,
                                              lineBytes,
                                              separatorMatch,
                                              utf8.state(),
                                              lineDigest);
            FileReadSnapshot acceptedGuard = acceptedFileBytes == 0
                    ? new FileReadSnapshot(nextOffset, nextOffset, newDigest().digest())
                    : snapshotWindow(path, channel, nextOffset, fileReadListener);
            return new AppendedDelivery(List.copyOf(messages),
                                        new FileCursor(nextOffset,
                                                       state,
                                                       cursorState.guard(),
                                                       nextIncompleteLine),
                                        readTracker.snapshot(),
                                        cursorState.guard(),
                                        new FileReadSnapshot(cursorState.offset(),
                                                             nextOffset,
                                                             acceptedDigest.digest()),
                                        acceptedGuard);
        }

        private boolean validateReadSnapshot(Path path,
                                             FileChannel channel,
                                             FileReadSnapshot snapshot) throws IOException {
            return FileIncomingConnector.validateReadSnapshot(channel, snapshot, fileReadListener, path);
        }

        private boolean acceptUtf8(LineReadContext context,
                                   long lineOffset,
                                   long lineBytes,
                                   MessageDigest lineDigest,
                                   Utf8Validator utf8,
                                   byte value,
                                   boolean deferMalformed) throws IOException {
            lineDigest.update(value);
            try {
                utf8.accept(value);
                return true;
            } catch (MalformedInputException e) {
                if (deferMalformed) {
                    return false;
                }
                validateLineSnapshot(context.path(),
                                     context.channel(),
                                     lineOffset,
                                     lineBytes,
                                     lineDigest);
                throw e;
            }
        }

        private byte[] readValidatedLine(Path path,
                                         FileChannel channel,
                                         long lineOffset,
                                         long lineBytes,
                                         MessageDigest lineDigest) throws IOException {
            byte[] result = new byte[Math.toIntExact(lineBytes)];
            MessageDigest validationDigest = newDigest();
            ByteBuffer buffer = ByteBuffer.wrap(result);
            long position = lineOffset;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read < 0) {
                    throw new FileSnapshotChangedException();
                }
                if (read == 0) {
                    continue;
                }
                fileReadListener.afterValidationRead(path, read);
                validationDigest.update(result, Math.toIntExact(position - lineOffset), read);
                position += read;
            }
            if (!MessageDigest.isEqual(copyDigest(lineDigest).digest(), validationDigest.digest())) {
                throw new FileSnapshotChangedException();
            }
            return result;
        }

        private void validateLineSnapshot(Path path,
                                          FileChannel channel,
                                          long lineOffset,
                                          long lineBytes,
                                          MessageDigest lineDigest) throws IOException {
            MessageDigest validationDigest = newDigest();
            ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
            long position = lineOffset;
            long remaining = lineBytes;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer, position);
                if (read < 0) {
                    throw new FileSnapshotChangedException();
                }
                if (read == 0) {
                    continue;
                }
                fileReadListener.afterValidationRead(path, read);
                validationDigest.update(buffer.array(), 0, read);
                position += read;
                remaining -= read;
            }
            if (!MessageDigest.isEqual(copyDigest(lineDigest).digest(), validationDigest.digest())) {
                throw new FileSnapshotChangedException();
            }
        }

        private String decodeCompleteLine(byte[] framedLine, int payloadBytes) throws IOException {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(framedLine, 0, payloadBytes)).toString();
        }

        private FileState fileState(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new FileState(new FileIdentity(attributes.fileKey(), attributes.creationTime()),
                                 attributes.size(),
                                 attributes.lastModifiedTime());
        }

        private void awaitDelivery(ConnectorDelivery delivery) throws InterruptedException {
            try {
                delivery.await();
            } catch (InterruptedException e) {
                delivery.cancel();
                throw e;
            }
        }

    }

    interface WatchRegistrationListener {
        default void beforeRegistration(Path path) throws IOException {
        }

        default void afterRegistration() {
        }

        default void afterReconciliation() {
        }
    }

    interface FileReadListener {
        void beforeRead(Path path) throws IOException;

        default void afterRead(Path path) throws IOException {
        }

        default void afterRead(Path path, long offset, int bytes) throws IOException {
            afterRead(path);
        }

        default void afterValidationRead(Path path, int bytes) throws IOException {
        }

        default void afterCursorRead(Path path, long offset, int bytes) throws IOException {
        }

        default void afterCursorRead(Path path) throws IOException {
        }
    }

    private record AppendedDelivery(List<Message<String>> messages,
                                    FileCursor nextCursor,
                                    FileReadSnapshot readSnapshot,
                                    FileReadSnapshot priorGuard,
                                    FileReadSnapshot acceptedSnapshot,
                                    FileReadSnapshot acceptedGuard) {
    }

    private record FileReadSnapshot(long startOffset, long endOffset, byte[] digest) {
        private FileReadSnapshot {
            digest = digest.clone();
        }

        private boolean isEmpty() {
            return startOffset == endOffset;
        }
    }

    private static final class FileSnapshotChangedException extends IOException {
        private FileSnapshotChangedException() {
            super("File content changed while reading");
        }
    }

    private static final class FileReadTracker {
        private final long startOffset;
        private final MessageDigest digest = newDigest();
        private long offset;

        private FileReadTracker(long offset) {
            this.startOffset = offset;
            this.offset = offset;
        }

        private void update(byte value) {
            digest.update(value);
            offset++;
        }

        private FileReadSnapshot snapshot() {
            return new FileReadSnapshot(startOffset, offset, copyDigest(digest).digest());
        }
    }

    private record LineReadContext(Path path, FileChannel channel) {
    }

    private record FileReadLimits(int maxMessages, int maxLineBytes, long maxBatchBytes) {
    }

    private record CursorState(long offset,
                               FileReadSnapshot guard,
                               IncompleteLineState incompleteLine) {
        private long scanOffset() {
            return incompleteLine == null ? offset : incompleteLine.scanOffset();
        }
    }

    private record IncompleteLineState(long scanOffset,
                                       long lineBytes,
                                       int separatorMatch,
                                       Utf8State utf8State,
                                       MessageDigest lineDigest) {
        /*
         * Only fixed-size parser and digest state crosses file events. The payload remains in the file and is reread
         * once, under a fresh pending reservation, when the separator or terminal oversize condition is reached.
         */
        private IncompleteLineState {
            lineDigest = copyDigest(lineDigest);
        }

        private IncompleteLineState copy() {
            return new IncompleteLineState(scanOffset,
                                           lineBytes,
                                           separatorMatch,
                                           utf8State,
                                           lineDigest);
        }

        private MessageDigest lineDigestCopy() {
            return copyDigest(lineDigest);
        }
    }

    private record Utf8State(int remainingBytes, int codePoint, int minimumCodePoint) {
    }

    private static final class Utf8Validator {
        private int remainingBytes;
        private int codePoint;
        private int minimumCodePoint;

        private Utf8Validator(Utf8State state) {
            if (state != null) {
                remainingBytes = state.remainingBytes();
                codePoint = state.codePoint();
                minimumCodePoint = state.minimumCodePoint();
            }
        }

        private void accept(byte value) throws MalformedInputException {
            int unsigned = value & 0xFF;
            if (remainingBytes == 0) {
                if (unsigned <= 0x7F) {
                    return;
                }
                if (unsigned >= 0xC2 && unsigned <= 0xDF) {
                    start(unsigned & 0x1F, 1, 0x80);
                    return;
                }
                if (unsigned >= 0xE0 && unsigned <= 0xEF) {
                    start(unsigned & 0x0F, 2, 0x800);
                    return;
                }
                if (unsigned >= 0xF0 && unsigned <= 0xF4) {
                    start(unsigned & 0x07, 3, 0x10000);
                    return;
                }
                throw new MalformedInputException(1);
            }

            if ((unsigned & 0xC0) != 0x80) {
                throw new MalformedInputException(1);
            }
            codePoint = (codePoint << 6) | (unsigned & 0x3F);
            remainingBytes--;
            if (remainingBytes == 0
                    && (codePoint < minimumCodePoint
                    || codePoint > Character.MAX_CODE_POINT
                    || (codePoint >= Character.MIN_SURROGATE
                    && codePoint <= Character.MAX_SURROGATE))) {
                throw new MalformedInputException(1);
            }
        }

        private void complete() throws MalformedInputException {
            if (remainingBytes != 0) {
                throw new MalformedInputException(1);
            }
        }

        private Utf8State state() {
            return new Utf8State(remainingBytes, codePoint, minimumCodePoint);
        }

        private void start(int initialCodePoint, int continuationBytes, int minimum) {
            codePoint = initialCodePoint;
            remainingBytes = continuationBytes;
            minimumCodePoint = minimum;
        }
    }

    /*
     * The guard covers the most recently committed bounded byte range and is checked both before and after the next
     * scan. An incomplete line is revalidated from its digest when it becomes complete; correcting that uncommitted
     * tail discards its parser state without invalidating the committed offset.
     *
     * Portable file metadata cannot identify an arbitrary rewrite in an older, unguarded prefix. Detecting that case
     * exactly would require writer cooperation, filesystem change-range/version support, or rereading the full prefix
     * after every append.
     */
    static final class FileCursor {
        private final long offset;
        private final FileState state;
        private final FileReadSnapshot guard;
        private final IncompleteLineState incompleteLine;

        private FileCursor(long offset,
                           FileState state,
                           FileReadSnapshot guard,
                           IncompleteLineState incompleteLine) {
            this.offset = offset;
            this.state = state;
            this.guard = guard;
            this.incompleteLine = incompleteLine;
        }

        long offset() {
            return offset;
        }

        private FileState state() {
            return state;
        }

        private FileReadSnapshot guard() {
            return guard;
        }

        private IncompleteLineState incompleteLineCopy() {
            return incompleteLine == null ? null : incompleteLine.copy();
        }

        private boolean incompleteLineValid(long size) {
            return incompleteLine == null
                    || incompleteLine.scanOffset() >= offset
                    && incompleteLine.scanOffset() <= size
                    && incompleteLine.scanOffset() - offset == incompleteLine.lineBytes();
        }
    }

    private record FileState(FileIdentity identity, long size, FileTime lastModifiedTime) {
    }

    private record FileIdentity(Object fileKey, FileTime creationTime) {
        private boolean sameFile(FileIdentity other) {
            if (fileKey != null && other.fileKey != null) {
                return fileKey.equals(other.fileKey);
            }
            return Objects.equals(creationTime, other.creationTime);
        }
    }

    private static FileReadSnapshot emptySnapshot() {
        return new FileReadSnapshot(0, 0, newDigest().digest());
    }

    private static FileReadSnapshot snapshotWindow(Path path,
                                                   FileChannel channel,
                                                   long endOffset,
                                                   FileReadListener listener) throws IOException {
        long startOffset = Math.max(0, endOffset - READ_BUFFER_SIZE);
        channel.position(startOffset);
        MessageDigest digest = newDigest();
        ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        long remaining = endOffset - startOffset;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer);
            if (read < 0) {
                throw new IOException("File ended while capturing the cursor guard");
            }
            if (read == 0) {
                continue;
            }
            listener.afterCursorRead(path, endOffset - remaining, read);
            digest.update(buffer.array(), 0, read);
            remaining -= read;
        }
        return new FileReadSnapshot(startOffset, endOffset, digest.digest());
    }

    private static boolean validateReadSnapshot(FileChannel channel,
                                                FileReadSnapshot snapshot,
                                                FileReadListener listener,
                                                Path path) throws IOException {
        if (snapshot.startOffset() < 0
                || snapshot.endOffset() < snapshot.startOffset()
                || snapshot.endOffset() > channel.size()) {
            return false;
        }
        channel.position(snapshot.startOffset());
        MessageDigest validationDigest = newDigest();
        ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        long remaining = snapshot.endOffset() - snapshot.startOffset();
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer);
            if (read < 0) {
                return false;
            }
            if (read == 0) {
                continue;
            }
            listener.afterValidationRead(path, read);
            validationDigest.update(buffer.array(), 0, read);
            remaining -= read;
        }
        return MessageDigest.isEqual(snapshot.digest(), validationDigest.digest());
    }

    private static int[] separatorPrefixes(byte[] separator) {
        int[] result = new int[separator.length];
        int matched = 0;
        for (int i = 1; i < separator.length; i++) {
            while (matched > 0 && separator[i] != separator[matched]) {
                matched = result[matched - 1];
            }
            if (separator[i] == separator[matched]) {
                matched++;
            }
            result[i] = matched;
        }
        return result;
    }

    private static int separatorMatch(byte[] separator,
                                      int[] prefixes,
                                      int currentMatch,
                                      byte value) {
        while (currentMatch > 0 && value != separator[currentMatch]) {
            currentMatch = prefixes[currentMatch - 1];
        }
        if (value == separator[currentMatch]) {
            currentMatch++;
        }
        return currentMatch;
    }

    private static MessagingRejectedException oversizedLine(String channel,
                                                             long offset,
                                                             int maxLineBytes) {
        return new MessagingRejectedException(channel,
                                              MessagingRejectedException.Reason.OVERSIZED,
                                              "File line at byte offset " + offset
                                                      + " exceeds max-line-bytes " + maxLineBytes);
    }

    private static boolean isCancellation(MessagingRejectedException rejection) {
        return rejection.reason() == MessagingRejectedException.Reason.SHUTDOWN
                || rejection.reason() == MessagingRejectedException.Reason.CANCELLED;
    }

    private static boolean isInterruptDriven(IOException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ClosedByInterruptException
                    || (current instanceof InterruptedIOException && Thread.currentThread().isInterrupted())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean causedByInterruption(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException
                    || current instanceof ClosedByInterruptException
                    || current instanceof InterruptedIOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static MessageDigest copyDigest(MessageDigest digest) {
        try {
            return (MessageDigest) digest.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-256 message digest cannot be copied", e);
        }
    }
}
