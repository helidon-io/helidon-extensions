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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.extensions.messaging.BatchAtomicity;
import io.helidon.extensions.messaging.BatchDeliveryException;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.OutgoingEndpoint;

/**
 * File outgoing endpoint implementation.
 * <p>
 * A delivery completes successfully when the connector's {@code Files.writeString(...)} call returns without
 * throwing. A failure after a batch append starts reports every item as indeterminate because an append can fail after
 * writing an unknown prefix of the encoded content. Failures before lock acquisition report every item as not
 * attempted.
 */
final class FileOutgoingConnector {
    private static final ReentrantLock[] WRITE_LOCKS = new ReentrantLock[64];

    static {
        for (int i = 0; i < WRITE_LOCKS.length; i++) {
            WRITE_LOCKS[i] = new ReentrantLock();
        }
    }

    private FileOutgoingConnector() {
    }

    static OutgoingEndpoint createEndpoint(FileConnectorConfig config) {
        return createEndpoint(config, FileOutgoingConnector::write);
    }

    static OutgoingEndpoint createEndpoint(FileConnectorConfig config, FileWriter fileWriter) {
        Path path = config.path().toAbsolutePath().normalize();
        ReentrantLock writeLock = WRITE_LOCKS[Math.floorMod(path.hashCode(), WRITE_LOCKS.length)];
        return new FileEndpoint(config, path, writeLock, fileWriter);
    }

    private static void write(Path path, String content) {
        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path,
                              content,
                              StandardOpenOption.CREATE,
                              StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new MessagingException("File outgoing connector failed", e);
        }
    }

    private static final class FileEndpoint implements OutgoingEndpoint {
        private final FileConnectorConfig config;
        private final Path path;
        private final ReentrantLock writeLock;
        private final FileWriter fileWriter;
        private final ReentrantLock lifecycleLock = new ReentrantLock();
        private final Set<Thread> activeSendThreads = new HashSet<>();
        private State state = State.NEW;

        private FileEndpoint(FileConnectorConfig config,
                             Path path,
                             ReentrantLock writeLock,
                             FileWriter fileWriter) {
            this.config = config;
            this.path = path;
            this.writeLock = writeLock;
            this.fileWriter = fileWriter;
        }

        @Override
        public void start() {
            lifecycleLock.lock();
            try {
                if (state == State.CLOSED) {
                    throw new IllegalStateException("File outgoing endpoint is closed");
                }
                state = State.STARTED;
            } finally {
                lifecycleLock.unlock();
            }
        }

        @Override
        public void flush() {
            lifecycleLock.lock();
            try {
                requireStarted();
                // Files.writeString completes synchronously, so there is no connector buffer to flush.
            } finally {
                lifecycleLock.unlock();
            }
        }

        @Override
        public BatchAtomicity batchAtomicity() {
            return BatchAtomicity.PER_MESSAGE;
        }

        @Override
        public <T> void sendBatch(MessageBatch<T> batch) {
            Objects.requireNonNull(batch);
            Thread sendThread;
            try {
                sendThread = beginSend();
            } catch (RuntimeException failure) {
                throw BatchDeliveryException.notAttempted("File batch delivery", batch, failure);
            }
            try {
                StringBuilder content = new StringBuilder();
                try {
                    for (Message<T> message : batch.messages()) {
                        content.append(message.entity())
                                .append(config.lineSeparator());
                    }
                } catch (RuntimeException failure) {
                    throw BatchDeliveryException.notAttempted("File batch encoding", batch, failure);
                }
                writeBatch(content.toString(), batch);
            } catch (RuntimeException failure) {
                if (failure instanceof BatchDeliveryException batchFailure) {
                    throw batchFailure;
                }
                throw BatchDeliveryException.indeterminate("File batch delivery outcome is indeterminate",
                                                           batch,
                                                           failure);
            } finally {
                endSend(sendThread);
            }
        }

        @Override
        public void forceClose() {
            lifecycleLock.lock();
            try {
                state = State.CLOSED;
                activeSendThreads.forEach(Thread::interrupt);
            } finally {
                lifecycleLock.unlock();
            }
        }

        @Override
        public void close() {
            lifecycleLock.lock();
            try {
                state = State.CLOSED;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private <T> void writeBatch(String content, MessageBatch<T> batch) {
            try {
                writeLock.lockInterruptibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw BatchDeliveryException.notAttempted(
                        "File batch delivery was interrupted before the write started",
                        batch,
                        e);
            }
            try {
                fileWriter.write(path, content);
            } finally {
                writeLock.unlock();
            }
        }

        private Thread beginSend() {
            lifecycleLock.lock();
            try {
                requireStarted();
                Thread sendThread = Thread.currentThread();
                activeSendThreads.add(sendThread);
                return sendThread;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private void endSend(Thread sendThread) {
            lifecycleLock.lock();
            try {
                activeSendThreads.remove(sendThread);
            } finally {
                lifecycleLock.unlock();
            }
        }

        private void requireStarted() {
            if (state == State.NEW) {
                throw new IllegalStateException("File outgoing endpoint has not been started");
            }
            if (state == State.CLOSED) {
                throw new IllegalStateException("File outgoing endpoint is closed");
            }
        }

        private enum State {
            NEW,
            STARTED,
            CLOSED
        }
    }

    @FunctionalInterface
    interface FileWriter {
        void write(Path path, String content);
    }
}
