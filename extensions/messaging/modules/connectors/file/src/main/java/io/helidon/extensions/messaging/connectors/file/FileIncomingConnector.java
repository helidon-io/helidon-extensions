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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.helidon.extensions.messaging.ConnectorSource;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.service.registry.Service;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * File incoming connector.
 * <p>
 * Within an active source lifetime, a complete appended-line batch remains pending until delivery succeeds or the
 * portable failure policy settles it. The connector does not advance its in-memory file offset or deliver later
 * appends while a batch is pending. A retry retains the captured batch and may duplicate earlier downstream work; a
 * settled result advances past it.
 * <p>
 * The file offset is not persisted. A replacement source starts at the file's current end and does not recover an
 * unsettled batch from an earlier source lifetime.
 */
@Service.Singleton
public class FileIncomingConnector implements IncomingConnector<FileConnectorConfig> {
    private static final long CLOSE_CHECK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public String connectorName() {
        return FileOutgoingConnector.CONNECTOR;
    }

    @Override
    public ConnectorSource createSource(FileConnectorConfig config, ConnectorSourceContext context) {
        return new FileSource(config, context, closed);
    }

    @Override
    @Service.PreDestroy
    public void close() {
        closed.set(true);
    }

    record FileSource(FileConnectorConfig config,
                      ConnectorSourceContext context,
                      AtomicBoolean closed,
                      WatchRegistrationListener watchRegistrationListener) implements ConnectorSource {
        FileSource(FileConnectorConfig config,
                   ConnectorSourceContext context,
                   AtomicBoolean closed) {
            this(config, context, closed, new WatchRegistrationListener() {
            });
        }

        @Override
        public void run() {
            try {
                tailFile();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("File incoming connector failed", e);
            } catch (IOException e) {
                throw new MessagingException("File incoming connector failed", e);
            }
        }

        private void tailFile() throws IOException, InterruptedException {
            Path path = config.path().toAbsolutePath().normalize();
            Path directory = path.getParent();
            Files.createDirectories(directory);
            try (var ignored = Files.newOutputStream(path, CREATE, APPEND)) {
                // Create the file without truncating it.
            }

            FileCursor cursor = currentEndCursor(path);
            try (WatchService watcher = path.getFileSystem().newWatchService()) {
                watchRegistrationListener.beforeRegistration(path);
                directory.register(watcher, ENTRY_CREATE, ENTRY_MODIFY);
                watchRegistrationListener.afterRegistration();
                // Reconcile changes made after the initial snapshot but before watcher registration.
                cursor = emitAppendedLines(path, cursor);
                watchRegistrationListener.afterReconciliation();
                while (!closed.get()) {
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
            return emitAppendedLines(path, currentCursor(path, offset)).offset();
        }

        private FileCursor emitAppendedLines(Path path, FileCursor cursor) throws IOException, InterruptedException {
            while (!closed.get()) {
                AppendedDelivery delivery = readAppendedLines(path, cursor);
                if (delivery.messages().isEmpty()) {
                    break;
                }
                if (!emitUntilSuccessful(delivery.messages())) {
                    break;
                }
                cursor = delivery.nextCursor();
            }
            return cursor;
        }

        private AppendedDelivery readAppendedLines(Path path, FileCursor cursor) throws IOException {
            FileSnapshot snapshot = readSnapshot(path);
            int offset = cursor.validFor(snapshot) ? cursor.offset() : 0;
            String content = decode(snapshot.bytes(), offset);
            String lineSeparator = config.lineSeparator();
            List<Message<String>> messages = new ArrayList<>();
            int nextOffset = offset;
            int consumedCharacters = 0;
            int index;
            while ((index = content.indexOf(lineSeparator, consumedCharacters)) >= 0) {
                messages.add(Message.create(content.substring(consumedCharacters, index)));
                int nextCharacter = index + lineSeparator.length();
                nextOffset += content.substring(consumedCharacters, nextCharacter)
                        .getBytes(StandardCharsets.UTF_8)
                        .length;
                consumedCharacters = nextCharacter;
            }
            return new AppendedDelivery(List.copyOf(messages), snapshot.cursor(nextOffset));
        }

        private FileCursor currentCursor(Path path, int offset) throws IOException {
            FileSnapshot snapshot = readSnapshot(path);
            int currentOffset = offset >= 0 && offset <= snapshot.bytes().length ? offset : 0;
            return snapshot.cursor(currentOffset);
        }

        private FileCursor currentEndCursor(Path path) throws IOException {
            FileSnapshot snapshot = readSnapshot(path);
            return snapshot.cursor(snapshot.bytes().length);
        }

        private FileSnapshot readSnapshot(Path path) throws IOException {
            for (int attempt = 0; attempt < 3; attempt++) {
                FileIdentity before = fileIdentity(path);
                byte[] bytes = Files.readAllBytes(path);
                FileIdentity after = fileIdentity(path);
                if (before.sameFile(after)) {
                    return new FileSnapshot(bytes, after);
                }
            }
            throw new IOException("File was repeatedly replaced while reading " + path);
        }

        private String decode(byte[] bytes, int offset) throws IOException {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer input = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
            CharBuffer output = CharBuffer.allocate(bytes.length - offset);
            var result = decoder.decode(input, output, false);
            if (result.isError()) {
                result.throwException();
            }
            output.flip();
            return output.toString();
        }

        private FileIdentity fileIdentity(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new FileIdentity(attributes.fileKey(), attributes.creationTime());
        }

        private boolean emitUntilSuccessful(List<Message<String>> messages) throws InterruptedException {
            int failedAttempt = 0;
            while (!closed.get()) {
                try {
                    context.emitBatch(messages);
                    return true;
                } catch (RuntimeException failure) {
                    if (closed.get()) {
                        return false;
                    }
                    failedAttempt++;
                    ConnectorSourceContext.FailureResult result =
                            context.handleFailure(messages, failedAttempt, failure);
                    if (result == ConnectorSourceContext.FailureResult.RETRY) {
                        if (!awaitRetryDelay()) {
                            return false;
                        }
                    } else if (result == ConnectorSourceContext.FailureResult.SETTLED) {
                        return true;
                    } else {
                        throw new MessagingException("Unsupported file failure result: " + result);
                    }
                }
            }
            return false;
        }

        private boolean awaitRetryDelay() throws InterruptedException {
            long remainingNanos = TimeUnit.NANOSECONDS.convert(context.failurePolicy().retryDelay());
            while (!closed.get() && remainingNanos > 0) {
                long waitNanos = Math.min(remainingNanos, CLOSE_CHECK_INTERVAL_NANOS);
                long beforeWait = System.nanoTime();
                TimeUnit.NANOSECONDS.sleep(waitNanos);
                remainingNanos -= Math.max(1, System.nanoTime() - beforeWait);
            }
            return !closed.get();
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

    private record AppendedDelivery(List<Message<String>> messages, FileCursor nextCursor) {
    }

    private record FileSnapshot(byte[] bytes, FileIdentity identity) {
        private FileCursor cursor(int offset) {
            return new FileCursor(offset, identity, digest(bytes, offset));
        }
    }

    private record FileCursor(int offset, FileIdentity identity, byte[] prefixDigest) {
        private boolean validFor(FileSnapshot snapshot) {
            return offset >= 0
                    && offset <= snapshot.bytes().length
                    && identity.sameFile(snapshot.identity())
                    && MessageDigest.isEqual(prefixDigest, digest(snapshot.bytes(), offset));
        }
    }

    private record FileIdentity(Object fileKey, FileTime creationTime) {
        private boolean sameFile(FileIdentity other) {
            if (fileKey != null && other.fileKey != null) {
                return fileKey.equals(other.fileKey);
            }
            return Objects.equals(creationTime, other.creationTime);
        }
    }

    private static byte[] digest(byte[] content, int endIndex) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(content, 0, endIndex);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
