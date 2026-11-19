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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import io.helidon.extensions.messaging.connectors.pulsar.PulsarConnectorConfigSupport.IncomingSettings;
import io.helidon.messaging.BatchDeliveryException;
import io.helidon.messaging.BatchItemOutcome;
import io.helidon.messaging.BatchItemStatus;
import io.helidon.messaging.ConnectorDelivery;
import io.helidon.messaging.ConnectorDeliveryReservation;
import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.MessagingRejectedException;
import io.helidon.messaging.spi.IncomingChannel;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

final class PulsarIncomingChannel {
    private static final Duration RESERVATION_RETRY_DELAY = Duration.ofMillis(100);

    private final ClientFactory clientFactory;

    PulsarIncomingChannel() {
        this(config -> PulsarConnectorConfigSupport.createClient(config.serviceUrl(), config.clientProperties()));
    }

    PulsarIncomingChannel(ClientFactory clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory);
    }

    IncomingChannel createIncomingChannel(PulsarConnectorConfig connectorConfig, PulsarIncomingConfig config) {
        IncomingSettings settings = PulsarConnectorConfigSupport.incoming(Objects.requireNonNull(connectorConfig),
                                                                           Objects.requireNonNull(config));
        return new Connector(settings,
                             PulsarSchemaResolver.resolve(settings.channelName(),
                                                          settings.schema(),
                                                          settings.schemaProvider(),
                                                          true,
                                                          connectorConfig::schemaProviders));
    }

    IncomingChannel createIncomingChannel(PulsarConnectorConfig connectorConfig,
                                       PulsarIncomingConfig config,
                                       PulsarSchemaResolver.ResolvedSchema schema) {
        return new Connector(PulsarConnectorConfigSupport.incoming(Objects.requireNonNull(connectorConfig),
                                                                      Objects.requireNonNull(config)),
                             Objects.requireNonNull(schema));
    }

    @FunctionalInterface
    interface ClientFactory {
        PulsarClient create(IncomingSettings config) throws PulsarClientException;
    }

    private static final class PendingBatch {
        private final List<Message<Object>> nativeMessages;
        private final MessageBatch<Object> batch;
        private final BatchDeliveryException mappingFailure;

        private PendingBatch(List<Message<Object>> nativeMessages,
                             MessageBatch<Object> batch,
                             BatchDeliveryException mappingFailure) {
            this.nativeMessages = nativeMessages;
            this.batch = batch;
            this.mappingFailure = mappingFailure;
        }

        private static PendingBatch create(List<Message<Object>> nativeMessages,
                                           IncomingSettings config,
                                           PulsarSchemaResolver.ResolvedSchema schema) {
            List<PulsarMessage<Object>> messages = new ArrayList<>(nativeMessages.size());
            List<BatchItemOutcome> outcomes = new ArrayList<>(nativeMessages.size());
            RuntimeException primaryFailure = null;
            for (int i = 0; i < nativeMessages.size(); i++) {
                Message<Object> nativeMessage = nativeMessages.get(i);
                try {
                    messages.add(PulsarMessageMapper.fromPulsarMessage(nativeMessage, config, schema));
                    outcomes.add(BatchItemOutcome.notAttempted(i));
                } catch (RuntimeException e) {
                    outcomes.add(BatchItemOutcome.failed(i, e));
                    if (primaryFailure == null) {
                        primaryFailure = e;
                    } else if (primaryFailure != e) {
                        primaryFailure.addSuppressed(e);
                    }
                    try {
                        messages.add(PulsarMessageMapper.metadataOnly(nativeMessage));
                    } catch (RuntimeException metadataFailure) {
                        if (metadataFailure != e) {
                            e.addSuppressed(metadataFailure);
                        }
                        messages.add(PulsarMessageImpl.rejected());
                    }
                }
            }
            MessageBatch<Object> batch = MessageBatch.create(messages);
            BatchDeliveryException mappingFailure = primaryFailure == null ? null
                    : new BatchDeliveryException("Cannot map Pulsar batch on channel " + config.channelName(),
                                                 primaryFailure,
                                                 batch,
                                                 outcomes);
            return new PendingBatch(nativeMessages, batch, mappingFailure);
        }
    }

    private final class Connector implements IncomingChannel {
        private final IncomingSettings config;
        private final PulsarSchemaResolver.ResolvedSchema schema;
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private final AtomicBoolean forceCloseRequested = new AtomicBoolean();
        private final AtomicBoolean runStarted = new AtomicBoolean();
        private final AtomicBoolean runFinished = new AtomicBoolean();
        private final AtomicReference<Thread> sourceOwner = new AtomicReference<>();
        private final AtomicReference<Thread> startupOwner = new AtomicReference<>();
        private final AtomicReference<PulsarClient> activeClient = new AtomicReference<>();
        private final AtomicReference<Consumer<Object>> activeConsumer = new AtomicReference<>();
        private final AtomicReference<ConnectorDelivery> activeDelivery = new AtomicReference<>();
        private final AtomicReference<RuntimeException> connectorCloseFailure = new AtomicReference<>();
        private final AtomicBoolean stopInterruptRequested = new AtomicBoolean();
        private final CountDownLatch acquisitionStopSignal = new CountDownLatch(1);
        private final CountDownLatch runCompletion = new CountDownLatch(1);
        private final ReentrantLock deliveryLock = new ReentrantLock();
        private final ReentrantLock resourceCloseLock = new ReentrantLock();
        private final Condition deliveryStateChanged = deliveryLock.newCondition();
        private volatile IncomingConnectorContext context;
        private boolean deliveryStarting;

        private Connector(IncomingSettings config, PulsarSchemaResolver.ResolvedSchema schema) {
            this.config = config;
            this.schema = schema;
        }

        @Override
        public void run(IncomingConnectorContext context) {
            IncomingConnectorContext runContext = Objects.requireNonNull(context);
            if (!runStarted.compareAndSet(false, true)) {
                throw new IllegalStateException("Pulsar incoming connector can only be run once");
            }
            this.context = runContext;
            Thread owner = Thread.currentThread();
            sourceOwner.set(owner);
            startupOwner.set(owner);
            Throwable primaryFailure = null;
            try {
                if (stopping()) {
                    return;
                }
                if (runContext.maxDeliveryMessages() < 1) {
                    throw new MessagingException("Pulsar delivery message limit must be greater than zero");
                }
                PulsarClient client = clientFactory.create(config);
                activeClient.set(client);
                if (stopping()) {
                    return;
                }
                Consumer<Object> consumer = PulsarConnectorConfigSupport.createConsumer(
                        client,
                        config,
                        schema,
                        runContext.maxDeliveryMessages());
                activeConsumer.set(consumer);
                if (stopping()) {
                    return;
                }
                boolean running = runContext.awaitRunning();
                startupOwner.compareAndSet(owner, null);
                if (running && !stopping()) {
                    consume(consumer);
                }
            } catch (PulsarClientException e) {
                if (!stopping()) {
                    MessagingException failure = new MessagingException("Pulsar incoming connector failed", e);
                    primaryFailure = failure;
                    throw failure;
                }
            } catch (MessagingRejectedException e) {
                if (!isLifecycleCancellation(e)) {
                    primaryFailure = e;
                    throw e;
                }
                if (causedByInterruption(e)) {
                    Thread.currentThread().interrupt();
                }
            } catch (RuntimeException e) {
                if (!stopping() || !isLifecycleRuntime(e)) {
                    primaryFailure = e;
                    throw e;
                }
            } catch (Error e) {
                primaryFailure = e;
                throw e;
            } finally {
                boolean interrupted = Thread.interrupted();
                try {
                    startupOwner.compareAndSet(owner, null);
                    RuntimeException cleanupFailure = closeOwnedResources(forceCloseRequested.get());
                    runFinished.set(true);
                    sourceOwner.compareAndSet(owner, null);
                    runCompletion.countDown();
                    if (cleanupFailure != null) {
                        recordCloseFailure(cleanupFailure);
                        if (primaryFailure != null) {
                            if (primaryFailure != cleanupFailure) {
                                primaryFailure.addSuppressed(cleanupFailure);
                            }
                        } else if (!closeRequested.get() && !forceCloseRequested.get()) {
                            throw cleanupFailure;
                        }
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        @Override
        public void drain() {
            requestStop();
        }

        @Override
        public void forceClose() {
            forceCloseRequested.set(true);
            requestStop();
            ConnectorDelivery delivery = activeDelivery.get();
            if (delivery != null) {
                delivery.cancel();
            }
            Thread owner = startupOwner.get();
            if (owner == null) {
                owner = sourceOwner.get();
            }
            if (owner != null && owner != Thread.currentThread()) {
                owner.interrupt();
            }
            RuntimeException cleanupFailure = closeOwnedResources(true);
            if (cleanupFailure != null) {
                recordCloseFailure(cleanupFailure);
            }
            completeBeforeRun();
        }

        @Override
        public void close() {
            closeRequested.set(true);
            requestStop();
            completeBeforeRun();
            if (sourceOwner.get() == Thread.currentThread()) {
                return;
            }
            long deadline = closeDeadline();
            ConnectorDelivery delivery = awaitDeliveryPublication(deadline);
            if (delivery != null && delivery.isCurrentThread()) {
                return;
            }
            awaitRunCompletion(deadline);
            if (activeClient.get() != null || activeConsumer.get() != null) {
                RuntimeException retryFailure = closeOwnedResources(forceCloseRequested.get());
                if (retryFailure != null) {
                    recordCloseFailure(retryFailure);
                }
            }
            RuntimeException failure = connectorCloseFailure.get();
            if (failure != null) {
                throw failure;
            }
        }

        private static long remainingNanos(long deadline) {
            long remaining = deadline - System.nanoTime();
            return remaining <= 0 ? 0 : remaining;
        }

        private static boolean causedByInterruption(Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof InterruptedException) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private static RuntimeException mergeFailure(RuntimeException primary, RuntimeException failure) {
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

        private void consume(Consumer<Object> consumer) {
            while (!stopping()) {
                ConnectorDeliveryReservation reservation = awaitReservation();
                if (reservation == null) {
                    return;
                }
                try (reservation) {
                    if (stopping()) {
                        return;
                    }
                    List<Message<Object>> nativeMessages = receive(consumer);
                    if (nativeMessages.isEmpty()) {
                        continue;
                    }
                    if (stopping()) {
                        negativeAcknowledge(consumer, nativeMessages, null);
                        return;
                    }
                    int limit = PulsarConnectorConfigSupport.batchReceiveMaxMessages(config, context.maxDeliveryMessages());
                    if (nativeMessages.size() > limit) {
                        MessagingRejectedException failure = new MessagingRejectedException(
                                config.channelName(),
                                MessagingRejectedException.Reason.OVERSIZED,
                                "Pulsar batch contains " + nativeMessages.size() + " messages, exceeding channel "
                                        + config.channelName() + " limit " + limit);
                        negativeAcknowledge(consumer, nativeMessages, failure);
                        throw failure;
                    }
                    deliver(consumer, nativeMessages, reservation);
                }
            }
        }

        private List<Message<Object>> receive(Consumer<Object> consumer) {
            consumer.resume();
            try {
                Messages<Object> received = consumer.batchReceive();
                if (received == null) {
                    if (stopping()) {
                        return List.of();
                    }
                    throw new MessagingException("Pulsar batch receive returned no result on channel " + config.channelName());
                }
                List<Message<Object>> messages = new ArrayList<>();
                received.forEach(messages::add);
                return List.copyOf(messages);
            } catch (PulsarClientException e) {
                if (stopping()) {
                    return List.of();
                }
                throw new MessagingException("Cannot receive Pulsar batch from topic " + config.topic(), e);
            } finally {
                consumer.pause();
            }
        }

        private void deliver(Consumer<Object> consumer,
                             List<Message<Object>> nativeMessages,
                             ConnectorDeliveryReservation reservation) {
            PendingBatch pending;
            ConnectorDelivery delivery;
            try {
                pending = PendingBatch.create(nativeMessages, config, schema);
                delivery = pending.mappingFailure == null
                        ? startDelivery(() -> reservation.start(pending.batch))
                        : startDelivery(() -> reservation.startFailed(pending.batch, pending.mappingFailure));
            } catch (RuntimeException | Error e) {
                negativeAcknowledge(consumer, nativeMessages, e);
                throw e;
            }

            try (delivery) {
                if (!forceCloseRequested.get() && stopInterruptRequested.compareAndSet(true, false)) {
                    Thread.interrupted();
                }
                if (forceCloseRequested.get()) {
                    delivery.cancel();
                }
                RuntimeException processingFailure = null;
                try {
                    delivery.await();
                } catch (RuntimeException e) {
                    if (forceCloseRequested.get() && causedByInterruption(e)) {
                        return;
                    }
                    processingFailure = e;
                } catch (Error e) {
                    negativeAcknowledge(consumer, nativeMessages, e);
                    throw e;
                }
                if (!forceCloseRequested.get()) {
                    settle(consumer, pending, processingFailure);
                }
                if (processingFailure != null) {
                    throw processingFailure;
                }
            } finally {
                activeDelivery.compareAndSet(delivery, null);
                signalDeliveryStateChanged();
            }
        }

        private ConnectorDelivery startDelivery(Supplier<ConnectorDelivery> starter) {
            deliveryLock.lock();
            try {
                if (stopping()) {
                    throw new MessagingRejectedException(
                            config.channelName(),
                            MessagingRejectedException.Reason.CANCELLED,
                            "Pulsar delivery admission was cancelled on channel " + config.channelName());
                }
                deliveryStarting = true;
            } finally {
                deliveryLock.unlock();
            }
            ConnectorDelivery delivery;
            try {
                delivery = starter.get();
            } catch (RuntimeException | Error e) {
                finishDeliveryStart();
                throw e;
            }

            deliveryLock.lock();
            try {
                deliveryStarting = false;
                if (!activeDelivery.compareAndSet(null, delivery)) {
                    delivery.cancel();
                    delivery.close();
                    throw new IllegalStateException("Pulsar incoming connector already has an active delivery");
                }
                if (forceCloseRequested.get()) {
                    delivery.cancel();
                }
                return delivery;
            } finally {
                try {
                    deliveryStateChanged.signalAll();
                } finally {
                    deliveryLock.unlock();
                }
            }
        }

        private void finishDeliveryStart() {
            deliveryLock.lock();
            try {
                deliveryStarting = false;
                deliveryStateChanged.signalAll();
            } finally {
                deliveryLock.unlock();
            }
        }

        private ConnectorDeliveryReservation awaitReservation() {
            while (!stopping()) {
                Optional<ConnectorDeliveryReservation> reservation = context.tryReserveDelivery();
                if (reservation.isPresent()) {
                    return reservation.get();
                }
                try {
                    if (acquisitionStopSignal.await(RESERVATION_RETRY_DELAY.toNanos(), TimeUnit.NANOSECONDS)) {
                        return null;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (stopping()) {
                        return null;
                    }
                    throw new MessagingRejectedException(
                            context.channel(),
                            MessagingRejectedException.Reason.CANCELLED,
                            "Pulsar delivery reservation wait was interrupted on channel " + context.channel(),
                            e);
                }
            }
            return null;
        }

        private void settle(Consumer<Object> consumer, PendingBatch pending, RuntimeException processingFailure) {
            long deadline = System.nanoTime() + config.settlementTimeout().toNanos();
            BatchDeliveryException batchFailure = processingFailure instanceof BatchDeliveryException failure
                    && failure.batch() == pending.batch ? failure : null;
            List<CompletableFuture<Void>> acknowledgements = new ArrayList<>(pending.nativeMessages.size());
            RuntimeException settlementFailure = null;
            for (int i = 0; i < pending.nativeMessages.size(); i++) {
                if (forceCloseRequested.get()) {
                    break;
                }
                Message<Object> message = pending.nativeMessages.get(i);
                boolean succeeded = processingFailure == null
                        || batchFailure != null && batchFailure.outcome(i).status() == BatchItemStatus.SUCCEEDED;
                try {
                    if (succeeded) {
                        acknowledgements.add(consumer.acknowledgeAsync(message));
                    } else {
                        consumer.negativeAcknowledge(message);
                    }
                } catch (RuntimeException e) {
                    settlementFailure = mergeFailure(settlementFailure,
                                                     new MessagingException("Cannot "
                                                             + (succeeded ? "acknowledge" : "negatively acknowledge")
                                                             + " Pulsar message on channel " + config.channelName(), e));
                }
            }
            for (CompletableFuture<Void> acknowledgement : acknowledgements) {
                if (forceCloseRequested.get()) {
                    break;
                }
                try {
                    acknowledgement.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    settlementFailure = mergeFailure(settlementFailure,
                                                     new MessagingException("Pulsar acknowledgement was interrupted on channel "
                                                             + config.channelName(), e));
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    settlementFailure = mergeFailure(settlementFailure,
                                                     new MessagingException("Cannot acknowledge Pulsar message on channel "
                                                             + config.channelName(), cause));
                } catch (TimeoutException e) {
                    settlementFailure = mergeFailure(settlementFailure,
                                                     new MessagingException("Pulsar acknowledgement timed out after "
                                                             + config.settlementTimeout() + " on channel "
                                                             + config.channelName(), e));
                } catch (RuntimeException e) {
                    settlementFailure = mergeFailure(settlementFailure,
                                                     new MessagingException("Cannot acknowledge Pulsar message on channel "
                                                             + config.channelName(), e));
                }
            }
            if (settlementFailure != null) {
                if (processingFailure != null) {
                    processingFailure.addSuppressed(settlementFailure);
                } else {
                    throw settlementFailure;
                }
            }
        }

        private void negativeAcknowledge(Consumer<Object> consumer,
                                         List<Message<Object>> nativeMessages,
                                         Throwable primaryFailure) {
            RuntimeException failure = null;
            for (Message<Object> message : nativeMessages) {
                try {
                    consumer.negativeAcknowledge(message);
                } catch (RuntimeException e) {
                    failure = mergeFailure(failure,
                                           new MessagingException("Cannot negatively acknowledge Pulsar message on channel "
                                                                          + config.channelName(), e));
                }
            }
            if (failure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(failure);
                } else {
                    throw failure;
                }
            }
        }

        private RuntimeException closeOwnedResources(boolean force) {
            boolean interrupted = false;
            resourceCloseLock.lock();
            try {
                interrupted = Thread.interrupted();
                PulsarClient client = activeClient.get();
                Consumer<Object> consumer = activeConsumer.get();
                if (client == null && consumer == null) {
                    return null;
                }
                if (force || forceCloseRequested.get()) {
                    return shutdownOwnedResources(client, consumer);
                }
                long deadline = closeDeadline();
                RuntimeException failure = null;
                boolean consumerClosed = consumer == null;
                if (consumer != null) {
                    try {
                        RuntimeException consumerFailure = awaitClose(consumer.closeAsync(),
                                                                      deadline,
                                                                      "consumer",
                                                                      null);
                        failure = mergeFailure(failure, consumerFailure);
                        consumerClosed = consumerFailure == null;
                    } catch (RuntimeException e) {
                        failure = mergeFailure(failure,
                                               new MessagingException(
                                                       "Cannot initiate Pulsar consumer close for channel "
                                                               + config.channelName(), e));
                    } finally {
                        interrupted |= Thread.interrupted();
                    }
                }
                if (forceCloseRequested.get()) {
                    return mergeFailure(failure, shutdownOwnedResources(client, consumer));
                }
                boolean clientClosed = client == null;
                if (client != null) {
                    try {
                        RuntimeException clientFailure = awaitClose(client.closeAsync(), deadline, "client", null);
                        failure = mergeFailure(failure, clientFailure);
                        clientClosed = clientFailure == null;
                    } catch (RuntimeException e) {
                        failure = mergeFailure(failure,
                                               new MessagingException("Cannot initiate Pulsar client close for channel "
                                                                              + config.channelName(), e));
                    } finally {
                        interrupted |= Thread.interrupted();
                    }
                }
                if (forceCloseRequested.get()) {
                    return mergeFailure(failure, shutdownOwnedResources(client, consumer));
                }
                if (clientClosed) {
                    activeClient.compareAndSet(client, null);
                    activeConsumer.compareAndSet(consumer, null);
                } else if (consumerClosed) {
                    activeConsumer.compareAndSet(consumer, null);
                }
                if (failure != null && client != null && !clientClosed) {
                    RuntimeException shutdownFailure = shutdown(client);
                    if (shutdownFailure == null) {
                        activeClient.compareAndSet(client, null);
                        activeConsumer.compareAndSet(consumer, null);
                    } else if (shutdownFailure != failure) {
                        failure.addSuppressed(shutdownFailure);
                    }
                }
                return failure;
            } finally {
                resourceCloseLock.unlock();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private RuntimeException shutdownOwnedResources(PulsarClient client, Consumer<Object> consumer) {
            RuntimeException failure = shutdown(client);
            if (failure == null) {
                activeClient.compareAndSet(client, null);
                activeConsumer.compareAndSet(consumer, null);
            }
            return failure;
        }

        private RuntimeException awaitClose(CompletableFuture<Void> closeFuture,
                                            long deadline,
                                            String resource,
                                            RuntimeException primary) {
            boolean interrupted = false;
            try {
                while (true) {
                    // The caller switches to shutdown before treating an abandoned wait as a successful close.
                    if (forceCloseRequested.get() && !closeFuture.isDone()) {
                        return primary;
                    }
                    try {
                        closeFuture.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
                        return primary;
                    } catch (InterruptedException _) {
                        // Graceful stop requests must not abort resource cleanup or extend its deadline.
                        interrupted = true;
                        if (!forceCloseRequested.get() && remainingNanos(deadline) == 0 && !closeFuture.isDone()) {
                            throw new TimeoutException();
                        }
                    }
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return mergeFailure(primary,
                                    new MessagingException("Cannot close Pulsar " + resource + " for channel "
                                                                   + config.channelName(), cause));
            } catch (TimeoutException e) {
                return mergeFailure(primary,
                                    new MessagingException("Timed out closing Pulsar " + resource + " for channel "
                                                                   + config.channelName(), e));
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
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

        private void requestStop() {
            draining.set(true);
            acquisitionStopSignal.countDown();
            Consumer<Object> consumer = activeConsumer.get();
            if (consumer != null) {
                consumer.pause();
            }
            Thread owner = startupOwner.get();
            if (owner == null && activeDelivery.get() == null) {
                owner = sourceOwner.get();
            }
            if (owner != null && owner != Thread.currentThread()) {
                stopInterruptRequested.set(true);
                owner.interrupt();
            }
        }

        private boolean stopping() {
            return draining.get() || closeRequested.get() || forceCloseRequested.get();
        }

        private void completeBeforeRun() {
            if (runStarted.compareAndSet(false, true)) {
                runFinished.set(true);
                runCompletion.countDown();
            }
        }

        private ConnectorDelivery awaitDeliveryPublication(long deadline) {
            deliveryLock.lock();
            try {
                while (deliveryStarting) {
                    long remaining = remainingNanos(deadline);
                    if (remaining == 0) {
                        throw new MessagingException("Timed out closing Pulsar connector while waiting for delivery "
                                                             + "admission on channel " + config.channelName());
                    }
                    try {
                        deliveryStateChanged.awaitNanos(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MessagingException("Pulsar connector close was interrupted", e);
                    }
                }
                return activeDelivery.get();
            } finally {
                deliveryLock.unlock();
            }
        }

        private void awaitRunCompletion(long deadline) {
            if (runFinished.get()) {
                return;
            }
            try {
                long remaining = remainingNanos(deadline);
                if (remaining == 0 || !runCompletion.await(remaining, TimeUnit.NANOSECONDS)) {
                    throw new MessagingException("Timed out closing Pulsar incoming connector for channel "
                                                         + config.channelName());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Pulsar incoming connector close was interrupted", e);
            }
        }

        private void signalDeliveryStateChanged() {
            deliveryLock.lock();
            try {
                deliveryStateChanged.signalAll();
            } finally {
                deliveryLock.unlock();
            }
        }

        private boolean isLifecycleCancellation(MessagingRejectedException failure) {
            return stopping()
                    && (failure.reason() == MessagingRejectedException.Reason.CANCELLED
                    || failure.reason() == MessagingRejectedException.Reason.SHUTDOWN);
        }

        private boolean isLifecycleRuntime(RuntimeException failure) {
            if (failure instanceof CancellationException) {
                return true;
            }
            Throwable current = failure;
            while (current != null) {
                if (current instanceof InterruptedException
                        || current instanceof PulsarClientException.AlreadyClosedException) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private long closeDeadline() {
            long now = System.nanoTime();
            long timeout = config.closeTimeout().toNanos();
            long result = now + timeout;
            return ((now ^ result) & (timeout ^ result)) < 0 ? Long.MAX_VALUE : result;
        }

        private void recordCloseFailure(RuntimeException failure) {
            RuntimeException primary = connectorCloseFailure.compareAndExchange(null, failure);
            if (primary != null && primary != failure) {
                primary.addSuppressed(failure);
            }
        }
    }
}
