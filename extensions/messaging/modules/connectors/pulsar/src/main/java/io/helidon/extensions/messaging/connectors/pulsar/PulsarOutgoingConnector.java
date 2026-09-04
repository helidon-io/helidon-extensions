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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import io.helidon.messaging.BatchDeliveryException;
import io.helidon.messaging.BatchItemOutcome;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.spi.BatchAtomicity;
import io.helidon.messaging.spi.ConnectorDirection;
import io.helidon.messaging.spi.OutgoingConnector;

import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

final class PulsarOutgoingConnector {
    private final ClientFactory clientFactory;

    PulsarOutgoingConnector() {
        this(PulsarConnectorConfigSupport::createClient);
    }

    PulsarOutgoingConnector(ClientFactory clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory);
    }

    OutgoingConnector createOutgoingConnector(PulsarConnectorConfig config) {
        validateDirection(config);
        return createOutgoingConnector(config,
                                       PulsarSchemaResolver.resolve(config,
                                                                    ConnectorDirection.OUTGOING,
                                                                    List::of));
    }

    OutgoingConnector createOutgoingConnector(PulsarConnectorConfig config,
                                               PulsarSchemaResolver.ResolvedSchema schema) {
        validateDirection(config);
        return new Connector(config, Objects.requireNonNull(schema));
    }

    private static void validateDirection(PulsarConnectorConfig config) {
        Objects.requireNonNull(config);
        if (config.direction() != ConnectorDirection.OUTGOING) {
            throw new IllegalArgumentException("Pulsar connector configuration for channel " + config.channelName()
                                                       + " has direction " + config.direction()
                                                       + ", expected " + ConnectorDirection.OUTGOING);
        }
    }

    @FunctionalInterface
    interface ClientFactory {
        PulsarClient create(PulsarConnectorConfig config) throws PulsarClientException;
    }

    private final class Connector implements OutgoingConnector {
        private final PulsarConnectorConfig config;
        private final PulsarSchemaResolver.ResolvedSchema schema;
        private final ReentrantLock lifecycleLock = new ReentrantLock();
        private final Condition lifecycleChanged = lifecycleLock.newCondition();
        private State state = State.NEW;
        private Resources resources;
        private PulsarClient startingClient;
        private Thread startOwner;
        private boolean closeRequested;
        private int activeSends;
        private Throwable startupFailure;
        private volatile RuntimeException closeFailure;

        private Connector(PulsarConnectorConfig config, PulsarSchemaResolver.ResolvedSchema schema) {
            this.config = config;
            this.schema = schema;
        }

        @Override
        public void start() {
            lifecycleLock.lock();
            try {
                awaitLifecycle(() -> state == State.STARTING, "startup", Long.MAX_VALUE);
                if (closeRequested || state == State.CLOSED || state == State.CLOSING) {
                    throw new IllegalStateException("Pulsar outgoing connector is closed");
                }
                if (state == State.READY) {
                    return;
                }
                if (state == State.FAILED) {
                    throw propagate(startupFailure == null
                                            ? new IllegalStateException("Pulsar outgoing connector startup failed")
                                            : startupFailure);
                }
                state = State.STARTING;
                startOwner = Thread.currentThread();
            } finally {
                lifecycleLock.unlock();
            }

            PulsarClient client = null;
            Producer<Object> producer = null;
            Throwable failure = null;
            try {
                client = clientFactory.create(config);
                lifecycleLock.lock();
                try {
                    startingClient = client;
                    if (closeRequested) {
                        throw new IllegalStateException("Pulsar outgoing connector was closed during startup");
                    }
                } finally {
                    lifecycleLock.unlock();
                }
                producer = PulsarConnectorConfigSupport.producerBuilder(client, config, schema).create();
                if (!producer.isConnected()) {
                    throw new MessagingException("Pulsar producer for topic " + config.topic() + " is not connected");
                }
            } catch (PulsarClientException e) {
                failure = new MessagingException("Cannot start Pulsar producer for topic " + config.topic(), e);
            } catch (RuntimeException | Error e) {
                failure = e;
            }

            if (failure == null) {
                lifecycleLock.lock();
                try {
                    if (closeRequested) {
                        failure = new IllegalStateException("Pulsar outgoing connector was closed during startup");
                    } else {
                        resources = new Resources(client, producer);
                        startingClient = null;
                        startOwner = null;
                        state = State.READY;
                        lifecycleChanged.signalAll();
                        return;
                    }
                } finally {
                    lifecycleLock.unlock();
                }
            }

            RuntimeException cleanupFailure = shutdown(client);
            if (cleanupFailure != null && cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
            lifecycleLock.lock();
            try {
                if (cleanupFailure == null) {
                    startingClient = null;
                    resources = null;
                } else if (producer == null) {
                    startingClient = client;
                } else {
                    startingClient = null;
                    resources = new Resources(client, producer);
                }
                startOwner = null;
                startupFailure = failure;
                state = closeRequested && cleanupFailure == null ? State.CLOSED : State.FAILED;
                lifecycleChanged.signalAll();
            } finally {
                lifecycleLock.unlock();
            }
            throw propagate(failure);
        }

        @Override
        public BatchAtomicity batchAtomicity() {
            return BatchAtomicity.PER_MESSAGE;
        }

        @Override
        public void sendBatch(MessageBatch<?> batch) {
            Objects.requireNonNull(batch);
            Resources current;
            try {
                current = acquireSend();
            } catch (RuntimeException e) {
                throw notAttemptedFailure(batch, e);
            }
            try {
                sendBatch(current.producer(), batch);
            } finally {
                releaseSend();
            }
        }

        @Override
        public void forceClose() {
            Resources current;
            PulsarClient client;
            Thread owner;
            lifecycleLock.lock();
            try {
                closeRequested = true;
                current = resources;
                client = current == null ? startingClient : current.client();
                owner = startOwner;
                state = State.CLOSED;
                lifecycleChanged.signalAll();
            } finally {
                lifecycleLock.unlock();
            }
            if (owner != null && owner != Thread.currentThread()) {
                owner.interrupt();
            }
            RuntimeException failure = shutdown(client);
            lifecycleLock.lock();
            try {
                if (failure == null) {
                    if (resources == current) {
                        resources = null;
                    }
                    if (startingClient == client) {
                        startingClient = null;
                    }
                    state = State.CLOSED;
                } else {
                    state = State.FAILED;
                    recordCloseFailure(failure);
                }
                lifecycleChanged.signalAll();
            } finally {
                lifecycleLock.unlock();
            }
        }

        @Override
        public void close() {
            long deadline = closeDeadline();
            Resources current;
            PulsarClient partialClient;
            Thread owner;
            lifecycleLock.lock();
            try {
                closeRequested = true;
                owner = startOwner;
                if (owner != null && owner != Thread.currentThread()) {
                    owner.interrupt();
                }
                awaitLifecycle(() -> state == State.STARTING || state == State.CLOSING || activeSends != 0,
                               "active Pulsar operation",
                               deadline);
                if (state == State.NEW) {
                    state = State.CLOSED;
                    lifecycleChanged.signalAll();
                    throwCloseFailure();
                    return;
                }
                if (state == State.CLOSED && resources == null) {
                    throwCloseFailure();
                    return;
                }
                current = resources;
                partialClient = startingClient;
                if (current == null && partialClient == null) {
                    state = State.CLOSED;
                    lifecycleChanged.signalAll();
                    throwCloseFailure();
                    return;
                }
                state = State.CLOSING;
            } finally {
                lifecycleLock.unlock();
            }
            if (current == null) {
                RuntimeException failure = shutdown(partialClient);
                lifecycleLock.lock();
                try {
                    if (failure == null && startingClient == partialClient) {
                        startingClient = null;
                    }
                    state = failure == null ? State.CLOSED : State.FAILED;
                    if (failure != null) {
                        recordCloseFailure(failure);
                    }
                    lifecycleChanged.signalAll();
                } finally {
                    lifecycleLock.unlock();
                }
                throwCloseFailure();
                return;
            }
            CleanupResult cleanup = closeResources(current, deadline);
            lifecycleLock.lock();
            try {
                if (cleanup.released() && resources == current) {
                    resources = null;
                }
                state = cleanup.released() ? State.CLOSED : State.FAILED;
                lifecycleChanged.signalAll();
                if (cleanup.failure() != null) {
                    recordCloseFailure(cleanup.failure());
                }
            } finally {
                lifecycleLock.unlock();
            }
            throwCloseFailure();
        }

        private void sendBatch(Producer<Object> producer, MessageBatch<?> batch) {
            List<CompletableFuture<MessageId>> futures = new ArrayList<>(batch.size());
            RuntimeException enqueueFailure = null;
            int enqueueFailureIndex = -1;
            for (int i = 0; i < batch.size(); i++) {
                try {
                    futures.add(PulsarMessageMapper.send(producer, batch.get(i), schema));
                } catch (RuntimeException e) {
                    enqueueFailure = e;
                    enqueueFailureIndex = i;
                    break;
                }
            }

            List<BatchItemOutcome> outcomes = new ArrayList<>(batch.size());
            Throwable primaryFailure = enqueueFailure;
            boolean interrupted = false;
            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<MessageId> future = futures.get(i);
                try {
                    future.get(interrupted ? 0 : config.sendTimeout().toNanos(), TimeUnit.NANOSECONDS);
                    outcomes.add(BatchItemOutcome.succeeded(i));
                } catch (InterruptedException e) {
                    interrupted = true;
                    primaryFailure = firstFailure(primaryFailure, e);
                    outcomes.add(BatchItemOutcome.indeterminate(i, e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    primaryFailure = firstFailure(primaryFailure, cause);
                    outcomes.add(BatchItemOutcome.indeterminate(i, cause));
                } catch (TimeoutException | CancellationException e) {
                    primaryFailure = firstFailure(primaryFailure, e);
                    outcomes.add(BatchItemOutcome.indeterminate(i, e));
                } catch (RuntimeException e) {
                    primaryFailure = firstFailure(primaryFailure, e);
                    outcomes.add(BatchItemOutcome.indeterminate(i, e));
                }
            }
            if (enqueueFailureIndex >= 0) {
                outcomes.add(BatchItemOutcome.failed(enqueueFailureIndex, enqueueFailure));
                for (int i = enqueueFailureIndex + 1; i < batch.size(); i++) {
                    outcomes.add(BatchItemOutcome.notAttempted(i));
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (primaryFailure != null) {
                throw new BatchDeliveryException("Cannot send Pulsar message batch " + batch.id()
                                                         + " to topic " + config.topic(),
                                                 primaryFailure,
                                                 batch,
                                                 outcomes);
            }
        }

        private Resources acquireSend() {
            lifecycleLock.lock();
            try {
                if (state != State.READY || closeRequested || resources == null) {
                    throw new IllegalStateException("Pulsar outgoing connector is not ready");
                }
                activeSends++;
                return resources;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private void releaseSend() {
            lifecycleLock.lock();
            try {
                activeSends--;
                lifecycleChanged.signalAll();
            } finally {
                lifecycleLock.unlock();
            }
        }

        private CleanupResult closeResources(Resources current, long deadline) {
            RuntimeException failure = null;
            boolean producerClosed = false;
            try {
                RuntimeException producerFailure = awaitClose(current.producer().closeAsync(),
                                                               deadline,
                                                               "producer",
                                                               null);
                failure = mergeFailure(failure, producerFailure);
                producerClosed = producerFailure == null;
            } catch (RuntimeException e) {
                failure = new MessagingException("Cannot initiate Pulsar producer close for channel "
                                                         + config.channelName(), e);
            }
            boolean clientClosed = false;
            try {
                RuntimeException clientFailure = awaitClose(current.client().closeAsync(), deadline, "client", null);
                failure = mergeFailure(failure, clientFailure);
                clientClosed = clientFailure == null;
            } catch (RuntimeException e) {
                failure = mergeFailure(failure,
                                       new MessagingException("Cannot initiate Pulsar client close for channel "
                                                                      + config.channelName(), e));
            }
            boolean released = clientClosed || (current.client() == null && producerClosed);
            if (failure != null && !released) {
                RuntimeException shutdownFailure = shutdown(current.client());
                if (shutdownFailure == null) {
                    released = true;
                } else if (shutdownFailure != failure) {
                    failure.addSuppressed(shutdownFailure);
                }
            }
            return new CleanupResult(failure, released);
        }

        private RuntimeException awaitClose(CompletableFuture<Void> future,
                                            long deadline,
                                            String resource,
                                            RuntimeException primary) {
            try {
                long remaining = remainingNanos(deadline);
                future.get(remaining, TimeUnit.NANOSECONDS);
                return primary;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return mergeFailure(primary,
                                    new MessagingException("Pulsar " + resource + " close was interrupted", e));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return mergeFailure(primary,
                                    new MessagingException("Cannot close Pulsar " + resource + " for channel "
                                                                   + config.channelName(), cause));
            } catch (TimeoutException e) {
                return mergeFailure(primary,
                                    new MessagingException("Timed out closing Pulsar " + resource + " for channel "
                                                                   + config.channelName(), e));
            }
        }

        private RuntimeException shutdown(PulsarClient client) {
            if (client == null || client.isClosed()) {
                return null;
            }
            try {
                client.shutdown();
                return null;
            } catch (PulsarClientException e) {
                return new MessagingException("Cannot shut down Pulsar client for channel " + config.channelName(), e);
            }
        }

        private void awaitLifecycle(BooleanSupplier waiting, String operation, long deadline) {
            try {
                while (waiting.getAsBoolean()) {
                    if (deadline == Long.MAX_VALUE) {
                        lifecycleChanged.await();
                    } else {
                        long remaining = remainingNanos(deadline);
                        if (remaining == 0 || !lifecycleChanged.await(remaining, TimeUnit.NANOSECONDS)) {
                            throw new MessagingException("Timed out closing Pulsar connector while waiting for "
                                                                 + operation + " on channel " + config.channelName());
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while waiting for Pulsar " + operation + " on channel "
                                                     + config.channelName(), e);
            }
        }

        private long closeDeadline() {
            long now = System.nanoTime();
            long timeout = config.closeTimeout().toNanos();
            long result = now + timeout;
            return ((now ^ result) & (timeout ^ result)) < 0 ? Long.MAX_VALUE : result;
        }

        private synchronized void recordCloseFailure(RuntimeException failure) {
            if (closeFailure == null) {
                closeFailure = failure;
            } else if (closeFailure != failure) {
                closeFailure.addSuppressed(failure);
            }
        }

        private void throwCloseFailure() {
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private RuntimeException propagate(Throwable failure) {
            if (failure instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return new MessagingException("Pulsar outgoing connector failed", failure);
        }

        private Throwable firstFailure(Throwable current, Throwable additional) {
            if (current == null) {
                return additional;
            }
            if (current != additional) {
                current.addSuppressed(additional);
            }
            return current;
        }

        private BatchDeliveryException notAttemptedFailure(MessageBatch<?> batch, RuntimeException failure) {
            List<BatchItemOutcome> outcomes = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                outcomes.add(BatchItemOutcome.notAttempted(i));
            }
            return new BatchDeliveryException("Pulsar batch delivery failed before attempting the batch",
                                              failure,
                                              batch,
                                              outcomes);
        }

        private RuntimeException mergeFailure(RuntimeException primary, RuntimeException failure) {
            if (failure == null) {
                return primary;
            }
            if (primary == null) {
                return failure;
            }
            if (primary != failure) {
                primary.addSuppressed(failure);
            }
            return primary;
        }

        private long remainingNanos(long deadline) {
            long remaining = deadline - System.nanoTime();
            return remaining <= 0 ? 0 : remaining;
        }
    }

    private record Resources(PulsarClient client, Producer<Object> producer) {
        private Resources {
            Objects.requireNonNull(client);
            Objects.requireNonNull(producer);
        }
    }

    private record CleanupResult(RuntimeException failure, boolean released) {
    }

    private enum State {
        NEW,
        STARTING,
        READY,
        CLOSING,
        FAILED,
        CLOSED
    }
}
