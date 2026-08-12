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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.extensions.messaging.BatchDeliveryException;
import io.helidon.extensions.messaging.BatchItemStatus;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorDelivery;
import io.helidon.extensions.messaging.ConnectorDeliveryReservation;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.IncomingConnectorContext;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.MessagingRejectedException;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.WakeupException;

/**
 * Kafka incoming connector.
 * <p>
 * Each consumer poll is one retained delivery batch. Its exact immutable messages and opaque batch identity remain in
 * flight while the runtime dispatches it and applies its delivery policy. The consumer owner thread keeps polling with
 * all assigned partitions paused. Normal runtime completion settles and commits the complete poll. If runtime delivery
 * terminates with a structured failure, each partition advances only through its contiguous successful prefix;
 * successful records behind an unresolved offset remain eligible for redelivery.
 * <p>
 * The connector caps Kafka's {@code max.poll.records} with the channel delivery limit. Before every normal poll, the
 * source reserves that message capacity; while the reservation is unavailable, assigned partitions remain paused and
 * only heartbeat-maintenance polls run. A defensive post-poll check rejects a client result that exceeds the reserved
 * message count without committing it. Kafka fetch and record byte limits remain transport-specific client properties;
 * runtime admission does not bound transient Kafka-client or deserializer memory.
 */
final class KafkaIncomingConnector {
    private static final System.Logger LOGGER = System.getLogger(KafkaIncomingConnector.class.getName());
    private static final Duration MAX_MAINTENANCE_POLL_TIMEOUT = Duration.ofMillis(100);
    private static final Duration DEFAULT_COMMIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_COMMIT_RETRY_BACKOFF = Duration.ofMillis(100);

    private final ConsumerFactory consumerFactory;

    KafkaIncomingConnector() {
        this(KafkaConsumer::new);
    }

    KafkaIncomingConnector(ConsumerFactory consumerFactory) {
        this.consumerFactory = Objects.requireNonNull(consumerFactory);
    }

    IncomingConnector createIncomingConnector(KafkaConnectorConfig config) {
        Objects.requireNonNull(config);
        if (config.direction() != ConnectorConfig.Direction.INCOMING) {
            throw new IllegalArgumentException("Kafka connector configuration for channel " + config.channel()
                                                       + " has direction " + config.direction()
                                                       + ", expected " + ConnectorConfig.Direction.INCOMING);
        }
        return new IncomingKafkaConnector(config);
    }

    @FunctionalInterface
    interface ConsumerFactory {
        Consumer<Object, Object> create(Map<String, Object> properties);
    }

    private final class IncomingKafkaConnector implements IncomingConnector {
        private final KafkaConnectorConfig config;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean runStarted = new AtomicBoolean();
        private final AtomicBoolean runFinished = new AtomicBoolean();
        private final AtomicReference<Thread> sourceOwner = new AtomicReference<>();
        private final AtomicReference<Thread> startupOwner = new AtomicReference<>();
        private final AtomicReference<Consumer<Object, Object>> activeConsumer = new AtomicReference<>();
        private final AtomicReference<ActiveDelivery> activeDelivery = new AtomicReference<>();
        private final AtomicReference<Thread> commitInitiationOwner = new AtomicReference<>();
        private final AtomicReference<RuntimeException> connectorCloseFailure = new AtomicReference<>();
        private final CountDownLatch acquisitionStopSignal = new CountDownLatch(1);
        private final CountDownLatch runCompletion = new CountDownLatch(1);
        private final ReentrantLock commitLock = new ReentrantLock();
        private final ReentrantLock deliveryLock = new ReentrantLock();
        private final Condition deliveryStateChanged = deliveryLock.newCondition();
        private final ReentrantLock consumerCloseLock = new ReentrantLock();
        private final Duration maintenancePollTimeout;
        private final Duration commitTimeout;
        private final Duration commitRetryBackoff;
        private volatile IncomingConnectorContext context;
        private boolean deliveryStarting;

        private IncomingKafkaConnector(KafkaConnectorConfig config) {
            this.config = config;
            this.maintenancePollTimeout = maintenancePollTimeout(config);
            this.commitTimeout = durationProperty(config,
                                                   ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                                                   DEFAULT_COMMIT_TIMEOUT);
            this.commitRetryBackoff = durationProperty(config,
                                                        ConsumerConfig.RETRY_BACKOFF_MS_CONFIG,
                                                        DEFAULT_COMMIT_RETRY_BACKOFF);
        }

        @Override
        public void run(IncomingConnectorContext context) {
            Objects.requireNonNull(context);
            if (!runStarted.compareAndSet(false, true)) {
                throw new IllegalStateException("Kafka incoming connector can only be run once");
            }
            this.context = context;
            Thread owner = Thread.currentThread();
            sourceOwner.set(owner);
            startupOwner.set(owner);

            Consumer<Object, Object> consumer = null;
            Throwable primaryFailure = null;
            try {
                if (closed.get()) {
                    return;
                }
                consumer = consumerFactory.create(KafkaConnectorConfigSupport.consumerProperties(
                        config,
                        context.maxDeliveryMessages()));
                activeConsumer.set(consumer);
                if (!closed.get()) {
                    SourceRebalanceListener rebalanceListener = new SourceRebalanceListener(consumer);
                    consumer.subscribe(List.of(config.topic()), rebalanceListener);
                    verifyBrokerReadiness(consumer);
                    boolean running = context.awaitRunning();
                    startupOwner.compareAndSet(owner, null);
                    if (running && !closed.get() && !draining.get()) {
                        consume(consumer, rebalanceListener);
                    }
                }
            } catch (WakeupException e) {
                if (!closed.get() && !draining.get()) {
                    MessagingException failure = new MessagingException("Kafka incoming connector failed", e);
                    primaryFailure = failure;
                    throw failure;
                }
            } catch (RuntimeException e) {
                if (!closed.get()) {
                    RuntimeException failure = e instanceof MessagingException
                            ? e
                            : new MessagingException("Kafka incoming connector failed", e);
                    primaryFailure = failure;
                    throw failure;
                }
            } catch (Error e) {
                primaryFailure = e;
                throw e;
            } finally {
                try {
                    RuntimeException cleanupFailure = null;
                    if (consumer != null) {
                        try {
                            closeOwnedConsumer(consumer);
                        } catch (RuntimeException e) {
                            connectorCloseFailure.compareAndSet(null, e);
                            cleanupFailure = appendCleanupFailure(cleanupFailure, e);
                        }
                    }
                    if (cleanupFailure != null) {
                        if (primaryFailure != null) {
                            if (primaryFailure != cleanupFailure) {
                                primaryFailure.addSuppressed(cleanupFailure);
                            }
                        } else {
                            throw cleanupFailure;
                        }
                    }
                } finally {
                    closed.set(true);
                    runFinished.set(true);
                    startupOwner.compareAndSet(owner, null);
                    sourceOwner.compareAndSet(owner, null);
                    runCompletion.countDown();
                }
            }
        }

        @Override
        public void drain() {
            Consumer<Object, Object> consumer = null;
            deliveryLock.lock();
            try {
                draining.set(true);
                if (activeDelivery.get() == null && !deliveryStarting) {
                    consumer = activeConsumer.get();
                }
            } finally {
                deliveryLock.unlock();
            }
            acquisitionStopSignal.countDown();
            if (consumer != null) {
                consumer.wakeup();
            }
        }

        private void consume(Consumer<Object, Object> consumer, SourceRebalanceListener rebalanceListener) {
            boolean hasPolled = false;
            while (!closed.get() && !draining.get()) {
                ConnectorDeliveryReservation reservation = awaitPollReservation(consumer,
                                                                                 rebalanceListener,
                                                                                 hasPolled);
                if (reservation == null) {
                    return;
                }
                try (reservation) {
                    if (closed.get() || draining.get()) {
                        return;
                    }
                    consumer.resume(consumer.assignment());
                    ConsumerRecords<Object, Object> records = consumer.poll(config.pollTimeout());
                    hasPolled = true;
                    if (draining.get()) {
                        return;
                    }
                    List<Message<Object>> messages = new ArrayList<>();
                    for (ConsumerRecord<Object, Object> record : records) {
                        messages.add(toMessage(record));
                    }
                    if (messages.isEmpty()) {
                        continue;
                    }
                    PendingPoll pendingPoll = PendingPoll.create(records, messages, context);
                    if (!processPoll(consumer,
                                     rebalanceListener,
                                     pendingPoll,
                                     reservation)) {
                        return;
                    }
                }
            }
        }

        private ConnectorDeliveryReservation awaitPollReservation(Consumer<Object, Object> consumer,
                                                                   SourceRebalanceListener rebalanceListener,
                                                                   boolean hasPolled) {
            rebalanceListener.capacityWaiting(true);
            try {
                while (!closed.get() && !draining.get()) {
                    Set<TopicPartition> assignment = consumer.assignment();
                    if (!assignment.isEmpty()) {
                        consumer.pause(assignment);
                    }
                    Optional<ConnectorDeliveryReservation> reservation = context.tryReserveDelivery();
                    if (reservation.isPresent()) {
                        return reservation.get();
                    }
                    if (closed.get()) {
                        return null;
                    }

                    assignment = consumer.assignment();
                    if (assignment.isEmpty() && !hasPolled) {
                        if (!awaitReservationRetry()) {
                            return null;
                        }
                    } else {
                        consumer.pause(assignment);
                        maintenancePoll(consumer);
                    }
                }
                return null;
            } finally {
                rebalanceListener.capacityWaiting(false);
            }
        }

        private boolean processPoll(Consumer<Object, Object> consumer,
                                    SourceRebalanceListener rebalanceListener,
                                    PendingPoll pendingPoll,
                                    ConnectorDeliveryReservation reservation) {
            rebalanceListener.pending(pendingPoll);
            consumer.pause(consumer.assignment());
            ActiveDelivery deliveryTask = null;
            try {
                deliveryTask = awaitDeliveryAdmission(consumer,
                                                      pendingPoll,
                                                      reservation);
                if (deliveryTask == null) {
                    if (pendingPoll.stale() && !closed.get()) {
                        recoverStalePoll(consumer, pendingPoll);
                        return true;
                    }
                    return false;
                }
                while (!deliveryTask.isDone()) {
                    if (closed.get()) {
                        return false;
                    }
                    if (pendingPoll.stale()) {
                        awaitStaleDeliveryStop(consumer, deliveryTask);
                        if (closed.get()) {
                            return false;
                        }
                        recoverStalePoll(consumer, pendingPoll);
                        return true;
                    }
                    maintenancePoll(consumer);
                }
                if (closed.get()) {
                    return false;
                }

                pendingPoll.invalidateMissing(consumer.assignment());
                if (pendingPoll.stale()) {
                    recoverStalePoll(consumer, pendingPoll);
                    return true;
                }
                try {
                    awaitDeliveryResult(deliveryTask, pendingPoll);
                } catch (BatchDeliveryException e) {
                    try {
                        settlePartialPoll(consumer, pendingPoll);
                    } catch (RuntimeException settlementFailure) {
                        if (settlementFailure != e) {
                            e.addSuppressed(settlementFailure);
                        }
                    }
                    throw e;
                }
                return commitPoll(consumer, pendingPoll);
            } finally {
                rebalanceListener.clear(pendingPoll);
                if (deliveryTask != null) {
                    stopDelivery(deliveryTask);
                }
                if (!closed.get()) {
                    consumer.resume(consumer.assignment());
                }
            }
        }

        private ActiveDelivery awaitDeliveryAdmission(Consumer<Object, Object> consumer,
                                                      PendingPoll pendingPoll,
                                                      ConnectorDeliveryReservation reservation) {
            while (!closed.get() && !draining.get() && !pendingPoll.stale()) {
                ActiveDelivery deliveryTask = tryStartDelivery(pendingPoll, reservation);
                if (deliveryTask != null) {
                    return deliveryTask;
                }
                maintenancePoll(consumer);
            }
            return null;
        }

        private ActiveDelivery tryStartDelivery(PendingPoll pendingPoll,
                                                ConnectorDeliveryReservation reservation) {
            if (closed.get()) {
                return null;
            }
            ActiveDelivery active = new ActiveDelivery();
            deliveryLock.lock();
            try {
                if (closed.get() || draining.get()) {
                    return null;
                }
                deliveryStarting = true;
            } finally {
                deliveryLock.unlock();
            }

            Optional<ConnectorDelivery> admitted;
            try {
                admitted = reservation.tryStart(pendingPoll.batch());
                admitted.ifPresent(active::attach);
            } catch (RuntimeException | Error e) {
                finishDeliveryStart();
                throw e;
            }

            boolean cancel = false;
            boolean duplicate = false;
            boolean published = false;
            deliveryLock.lock();
            try {
                deliveryStarting = false;
                if (admitted.isEmpty()) {
                    return null;
                }
                if (!activeDelivery.compareAndSet(null, active)) {
                    cancel = true;
                    duplicate = true;
                } else {
                    published = true;
                    cancel = closed.get() || pendingPoll.stale();
                }
            } finally {
                deliveryStateChanged.signalAll();
                deliveryLock.unlock();
            }
            if (cancel) {
                cancelAndRelease(active);
                if (published) {
                    active.releaseWhenFinished();
                }
                if (duplicate) {
                    throw new IllegalStateException("Kafka incoming connector already has an active delivery");
                }
                return null;
            }
            return active;
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

        private boolean commitPoll(Consumer<Object, Object> consumer, PendingPoll pendingPoll) {
            return commitOffsets(consumer, pendingPoll, pendingPoll.nextOffsets());
        }

        private boolean commitOffsets(Consumer<Object, Object> consumer,
                                      PendingPoll pendingPoll,
                                      Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (offsets.isEmpty()) {
                return true;
            }
            long commitStarted = System.nanoTime();
            Exception previousCommitFailure = null;
            while (!closed.get()) {
                pendingPoll.invalidateMissing(consumer.assignment());
                if (pendingPoll.stale()) {
                    recoverStalePoll(consumer, pendingPoll);
                    return true;
                }
                if (previousCommitFailure != null && commitTimedOut(commitStarted)) {
                    rethrowCommitFailure(previousCommitFailure);
                }

                AtomicBoolean completed = new AtomicBoolean();
                AtomicReference<Exception> failure = new AtomicReference<>();
                if (closed.get()) {
                    return false;
                }
                commitLock.lock();
                Thread commitOwner = Thread.currentThread();
                commitInitiationOwner.set(commitOwner);
                try {
                    if (closed.get()) {
                        return false;
                    }
                    if (previousCommitFailure != null && commitTimedOut(commitStarted)) {
                        rethrowCommitFailure(previousCommitFailure);
                    }
                    try {
                        consumer.commitAsync(offsets, (committedOffsets, exception) -> {
                            failure.set(exception);
                            completed.set(true);
                        });
                    } catch (RuntimeException e) {
                        failure.set(e);
                        completed.set(true);
                    }
                } finally {
                    commitInitiationOwner.compareAndSet(commitOwner, null);
                    commitLock.unlock();
                }
                while (!completed.get()) {
                    if (closed.get()) {
                        return false;
                    }
                    if (commitTimedOut(commitStarted)) {
                        throw new MessagingException("Kafka incoming connector commit timed out after "
                                                             + commitTimeout);
                    }
                    maintenancePoll(consumer);
                }
                if (closed.get()) {
                    return false;
                }

                Exception commitFailure = failure.get();
                if (commitFailure == null) {
                    return true;
                }
                previousCommitFailure = commitFailure;
                if (pendingPoll.stale()) {
                    recoverStalePoll(consumer, pendingPoll);
                    return true;
                }
                if (!isRetriableCommitFailure(commitFailure)
                        || commitTimedOut(commitStarted)) {
                    rethrowCommitFailure(commitFailure);
                }
                if (!awaitCommitRetry(consumer, pendingPoll, commitStarted)) {
                    return false;
                }
            }
            return false;
        }

        private void settlePartialPoll(Consumer<Object, Object> consumer, PendingPoll pendingPoll) {
            Map<TopicPartition, OffsetAndMetadata> settledOffsets = pendingPoll.contiguousSettledOffsets();
            if (!commitOffsets(consumer, pendingPoll, settledOffsets) || pendingPoll.stale()) {
                return;
            }
            Set<TopicPartition> assignment = consumer.assignment();
            pendingPoll.firstUnsettledOffsets().forEach((partition, offset) -> {
                if (assignment.contains(partition)) {
                    consumer.seek(partition, offset);
                }
            });
        }

        private boolean awaitCommitRetry(Consumer<Object, Object> consumer,
                                         PendingPoll pendingPoll,
                                         long commitStarted) {
            long retryStarted = System.nanoTime();
            do {
                if (closed.get()) {
                    return false;
                }
                if (pendingPoll.stale()) {
                    return true;
                }
                if (commitTimedOut(commitStarted)) {
                    return true;
                }
                maintenancePoll(consumer);
            } while (System.nanoTime() - retryStarted < commitRetryBackoff.toNanos());
            return true;
        }

        private boolean commitTimedOut(long commitStarted) {
            return System.nanoTime() - commitStarted >= commitTimeout.toNanos();
        }

        private boolean isRetriableCommitFailure(Exception failure) {
            return failure instanceof RetriableCommitFailedException
                    || failure instanceof RebalanceInProgressException;
        }

        private void rethrowCommitFailure(Exception failure) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new MessagingException("Kafka incoming connector commit failed", failure);
        }

        private void maintenancePoll(Consumer<Object, Object> consumer) {
            maintenancePoll(consumer, maintenancePollTimeout);
        }

        private void maintenancePoll(Consumer<Object, Object> consumer, Duration timeout) {
            ConsumerRecords<Object, Object> records = consumer.poll(timeout);
            for (TopicPartition partition : records.partitions()) {
                if (consumer.assignment().contains(partition)) {
                    consumer.seek(partition, records.records(partition).getFirst().offset());
                }
            }
        }

        private void recoverStalePoll(Consumer<Object, Object> consumer, PendingPoll pendingPoll) {
            Set<TopicPartition> assignment = consumer.assignment();
            for (Map.Entry<TopicPartition, Long> entry : pendingPoll.firstOffsets().entrySet()) {
                if (assignment.contains(entry.getKey()) && !pendingPoll.invalidatedPartitions().contains(entry.getKey())) {
                    consumer.seek(entry.getKey(), entry.getValue());
                }
            }
        }

        private void awaitStaleDeliveryStop(Consumer<Object, Object> consumer, ActiveDelivery deliveryTask) {
            deliveryTask.cancel();
            long remainingNanos = config.closeTimeout().toNanos();
            while (!deliveryTask.isDone()) {
                if (closed.get()) {
                    return;
                }
                if (remainingNanos <= 0) {
                    deliveryTask.close();
                    deliveryTask.releaseWhenFinished();
                    throw new MessagingException("Kafka stale delivery did not stop after "
                                                         + config.closeTimeout() + " on channel "
                                                         + context.channel());
                }
                Duration timeout = Duration.ofNanos(Math.min(remainingNanos, maintenancePollTimeout.toNanos()));
                long pollStarted = System.nanoTime();
                maintenancePoll(consumer, timeout);
                remainingNanos -= System.nanoTime() - pollStarted;
            }
        }

        private void awaitDeliveryResult(ActiveDelivery deliveryTask, PendingPoll pendingPoll) {
            try {
                deliveryTask.await();
                pendingPoll.settleAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!closed.get()) {
                    throw new MessagingException("Kafka incoming message processing was interrupted", e);
                }
            } catch (BatchDeliveryException e) {
                RuntimeException aligned = BatchDeliveryException.align(pendingPoll.batch(), e);
                if (!(aligned instanceof BatchDeliveryException batchFailure)) {
                    throw aligned;
                }
                pendingPoll.settleSucceeded(batchFailure);
                throw batchFailure;
            }
        }

        private void stopDelivery(ActiveDelivery deliveryTask) {
            if (!deliveryTask.isDone()) {
                deliveryTask.cancel();
                deliveryTask.close();
                if (deliveryTask.releaseRequested()) {
                    return;
                }
                boolean stopped = false;
                try {
                    stopped = deliveryTask.await(config.closeTimeout());
                } catch (RuntimeException e) {
                    // Processing is quiescent; its failure belongs to the already-closing connector.
                    stopped = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (stopped) {
                    activeDelivery.compareAndSet(deliveryTask, null);
                } else {
                    deliveryTask.releaseWhenFinished();
                }
                return;
            }
            deliveryTask.close();
            activeDelivery.compareAndSet(deliveryTask, null);
        }

        private void cancelAndRelease(ActiveDelivery deliveryTask) {
            deliveryTask.cancel();
            deliveryTask.close();
        }

        private boolean awaitReservationRetry() {
            try {
                return !acquisitionStopSignal.await(maintenancePollTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (closed.get()) {
                    return false;
                }
                throw new MessagingRejectedException(
                        context.channel(),
                        MessagingRejectedException.Reason.CANCELLED,
                        "Kafka delivery reservation wait was interrupted on channel " + context.channel(),
                        e);
            }
        }

        private RuntimeException appendCleanupFailure(RuntimeException current, RuntimeException additional) {
            if (current == null) {
                return additional;
            }
            current.addSuppressed(additional);
            return current;
        }

        private KafkaMessage<Object, Object> toMessage(ConsumerRecord<Object, Object> record) {
            return KafkaMessageImpl.create(record);
        }

        @Override
        public void forceClose() {
            requestForcedClose();
            completeBeforeRun();
        }

        @Override
        public void close() {
            requestClose();
            completeBeforeRun();
            if (sourceOwner.get() == Thread.currentThread()) {
                return;
            }
            long deadline = closeDeadline();
            ActiveDelivery deliveryTask = awaitDeliveryPublication(deadline);
            if (deliveryTask != null && deliveryTask.isCurrentThread()) {
                return;
            }
            awaitDelivery(deliveryTask, deadline);
            awaitRunCompletion(deadline);
            retryConsumerClose(deadline);
            RuntimeException failure = connectorCloseFailure.get();
            if (failure != null) {
                throw failure;
            }
        }

        private void completeBeforeRun() {
            if (!runStarted.compareAndSet(false, true)) {
                return;
            }
            runFinished.set(true);
            runCompletion.countDown();
        }

        private void requestClose() {
            commitLock.lock();
            try {
                closed.set(true);
            } finally {
                commitLock.unlock();
            }
            signalClose();
        }

        private void requestForcedClose() {
            closed.set(true);
            signalClose();
            Thread owner = commitInitiationOwner.get();
            if (owner == null) {
                owner = startupOwner.get();
            }
            if (owner != null && owner != Thread.currentThread()) {
                owner.interrupt();
            }
        }

        private void signalClose() {
            draining.set(true);
            acquisitionStopSignal.countDown();
            ActiveDelivery deliveryTask;
            deliveryLock.lock();
            try {
                deliveryTask = activeDelivery.get();
            } finally {
                deliveryLock.unlock();
            }
            if (deliveryTask != null) {
                deliveryTask.cancel();
            }
            Consumer<Object, Object> consumer = activeConsumer.get();
            if (consumer != null) {
                consumer.wakeup();
            }
        }

        private ActiveDelivery awaitDeliveryPublication(long deadline) {
            deliveryLock.lock();
            try {
                while (deliveryStarting) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new MessagingException("Kafka incoming connector close timed out after "
                                                             + config.closeTimeout()
                                                             + " while waiting for delivery admission on channel "
                                                             + context.channel());
                    }
                    try {
                        deliveryStateChanged.awaitNanos(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MessagingException(
                                "Interrupted while waiting for Kafka delivery admission on channel "
                                        + context.channel(),
                                e);
                    }
                }
                return activeDelivery.get();
            } finally {
                deliveryLock.unlock();
            }
        }

        private void awaitDelivery(ActiveDelivery deliveryTask, long deadline) {
            if (deliveryTask == null || deliveryTask.isCurrentThread()) {
                return;
            }
            Duration timeout = remainingCloseTime(deadline, "active delivery");
            boolean stopped;
            try {
                stopped = deliveryTask.await(timeout);
            } catch (RuntimeException e) {
                // The delivery is quiescent. Its processing failure belongs to the connector owner thread.
                activeDelivery.compareAndSet(deliveryTask, null);
                return;
            } catch (InterruptedException e) {
                deliveryTask.releaseWhenFinished();
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while waiting for active Kafka delivery on channel "
                                                     + context.channel() + " to finish",
                                             e);
            }
            if (stopped) {
                activeDelivery.compareAndSet(deliveryTask, null);
                return;
            }
            deliveryTask.releaseWhenFinished();
            String message = "Kafka incoming connector close timed out after "
                    + config.closeTimeout() + " while waiting for active delivery on channel "
                    + context.channel() + "; the delivery remains tracked until it finishes";
            LOGGER.log(System.Logger.Level.ERROR, message);
            throw new MessagingException(message);
        }

        private void awaitRunCompletion(long deadline) {
            if (runFinished.get()) {
                return;
            }
            Duration timeout = remainingCloseTime(deadline, "consumer owner");
            try {
                if (runCompletion.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while waiting for Kafka consumer owner on channel "
                                                     + context.channel() + " to finish",
                                             e);
            }
            throw new MessagingException("Kafka incoming connector close timed out after "
                                                 + config.closeTimeout() + " while waiting for consumer owner on channel "
                                                 + context.channel() + " to finish");
        }

        private long closeDeadline() {
            long now = System.nanoTime();
            long timeout = config.closeTimeout().toNanos();
            long result = now + timeout;
            return ((now ^ result) & (timeout ^ result)) < 0 ? Long.MAX_VALUE : result;
        }

        private Duration remainingCloseTime(long deadline, String operation) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new MessagingException("Kafka incoming connector close timed out after "
                                                     + config.closeTimeout() + " while waiting for " + operation
                                                     + " on channel " + context.channel());
            }
            return Duration.ofNanos(remaining);
        }

        private void retryConsumerClose(long deadline) {
            Consumer<Object, Object> consumer = activeConsumer.get();
            if (consumer == null || !runFinished.get()) {
                return;
            }
            RuntimeException previousFailure = connectorCloseFailure.get();
            try {
                closeOwnedConsumer(consumer, deadline);
                connectorCloseFailure.compareAndSet(previousFailure, null);
            } catch (RuntimeException e) {
                connectorCloseFailure.compareAndSet(null, e);
            }
        }

        private void closeOwnedConsumer(Consumer<Object, Object> consumer) {
            consumerCloseLock.lock();
            try {
                if (activeConsumer.get() != consumer) {
                    return;
                }
                closeConsumer(consumer, config.closeTimeout());
                activeConsumer.compareAndSet(consumer, null);
            } finally {
                consumerCloseLock.unlock();
            }
        }

        private void closeOwnedConsumer(Consumer<Object, Object> consumer, long deadline) {
            boolean acquired;
            Duration lockTimeout = remainingCloseTime(deadline, "consumer close");
            try {
                acquired = consumerCloseLock.tryLock(lockTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while waiting to close Kafka consumer on channel "
                                                     + context.channel(),
                                             e);
            }
            if (!acquired) {
                throw new MessagingException("Kafka incoming connector close timed out after "
                                                     + config.closeTimeout()
                                                     + " while waiting for consumer close on channel "
                                                     + context.channel());
            }
            try {
                if (activeConsumer.get() != consumer) {
                    return;
                }
                closeConsumer(consumer, remainingCloseTime(deadline, "consumer close"));
                activeConsumer.compareAndSet(consumer, null);
            } finally {
                consumerCloseLock.unlock();
            }
        }

        private void verifyBrokerReadiness(Consumer<Object, Object> consumer) {
            List<PartitionInfo> partitions = consumer.partitionsFor(config.topic(), commitTimeout);
            if (partitions == null || partitions.isEmpty()) {
                throw new MessagingException("Kafka topic " + config.topic() + " has no available partitions");
            }
            Set<TopicPartition> topicPartitions = new HashSet<>();
            for (PartitionInfo partition : partitions) {
                topicPartitions.add(new TopicPartition(partition.topic(), partition.partition()));
            }
            consumer.committed(topicPartitions, commitTimeout);
        }

        private final class ActiveDelivery implements ConnectorDelivery {
            private final AtomicReference<ConnectorDelivery> delegate = new AtomicReference<>();
            private final AtomicBoolean delegateClosed = new AtomicBoolean();
            private final AtomicBoolean releaseWhenFinished = new AtomicBoolean();
            private final AtomicBoolean completionWatcherStarted = new AtomicBoolean();
            private final AtomicBoolean releasedFromSource = new AtomicBoolean();

            @Override
            public boolean isDone() {
                return delegate().isDone();
            }

            @Override
            public boolean isCurrentThread() {
                return delegate().isCurrentThread();
            }

            @Override
            public void await() throws InterruptedException {
                delegate().await();
            }

            @Override
            public boolean await(Duration timeout) throws InterruptedException {
                return delegate().await(timeout);
            }

            @Override
            public void cancel() {
                delegate().cancel();
            }

            @Override
            public void close() {
                if (delegateClosed.compareAndSet(false, true)) {
                    delegate().close();
                }
            }

            private void attach(ConnectorDelivery delivery) {
                if (!delegate.compareAndSet(null, Objects.requireNonNull(delivery))) {
                    throw new IllegalStateException("Kafka delivery delegate was already attached");
                }
                watchForCompletionIfRequested();
            }

            private void releaseWhenFinished() {
                releaseWhenFinished.set(true);
                watchForCompletionIfRequested();
            }

            private boolean releaseRequested() {
                return releaseWhenFinished.get();
            }

            private void watchForCompletionIfRequested() {
                if (releaseWhenFinished.get()
                        && delegate.get() != null
                        && completionWatcherStarted.compareAndSet(false, true)) {
                    Thread.ofVirtual()
                            .name("helidon-messaging-kafka-delivery-release-" + context.channel())
                            .inheritInheritableThreadLocals(false)
                            .start(this::releaseAfterCompletion);
                }
            }

            private void releaseAfterCompletion() {
                boolean interrupted = false;
                try {
                    while (true) {
                        try {
                            delegate().await();
                            break;
                        } catch (InterruptedException e) {
                            interrupted = true;
                        } catch (RuntimeException | Error e) {
                            // A propagated processing failure also means the delegate has terminated.
                            break;
                        }
                    }
                } finally {
                    if (releasedFromSource.compareAndSet(false, true)) {
                        activeDelivery.compareAndSet(this, null);
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            private ConnectorDelivery delegate() {
                return Objects.requireNonNull(delegate.get(), "Kafka delivery delegate");
            }
        }

        private void closeConsumer(Consumer<Object, Object> consumer, Duration timeout) {
            try {
                consumer.close(timeout);
            } catch (RuntimeException e) {
                throw new MessagingException("Kafka incoming connector close failed", e);
            }
        }

        private final class SourceRebalanceListener implements ConsumerRebalanceListener {
            private final Consumer<Object, Object> consumer;
            private PendingPoll pendingPoll;
            private boolean capacityWaiting;

            private SourceRebalanceListener(Consumer<Object, Object> consumer) {
                this.consumer = consumer;
            }

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                invalidate(partitions);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (capacityWaiting || pendingPoll != null) {
                    consumer.pause(consumer.assignment());
                }
            }

            @Override
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                invalidate(partitions);
            }

            private void pending(PendingPoll pendingPoll) {
                this.pendingPoll = pendingPoll;
            }

            private void clear(PendingPoll pendingPoll) {
                if (this.pendingPoll == pendingPoll) {
                    this.pendingPoll = null;
                }
            }

            private void capacityWaiting(boolean capacityWaiting) {
                this.capacityWaiting = capacityWaiting;
            }

            private void invalidate(Collection<TopicPartition> partitions) {
                if (pendingPoll != null && pendingPoll.invalidate(partitions)) {
                    ActiveDelivery deliveryTask;
                    deliveryLock.lock();
                    try {
                        deliveryTask = activeDelivery.get();
                    } finally {
                        deliveryLock.unlock();
                    }
                    if (deliveryTask != null) {
                        deliveryTask.cancel();
                    }
                }
            }
        }
    }

    private static final class PendingPoll {
        private final MessageBatch<Object> batch;
        private final Map<TopicPartition, OffsetAndMetadata> nextOffsets;
        private final Map<TopicPartition, Long> firstOffsets;
        private final Map<TopicPartition, List<IndexedOffset>> indexedOffsets;
        private final AtomicIntegerArray settled;
        private final Set<TopicPartition> invalidatedPartitions = new HashSet<>();
        private final AtomicBoolean stale = new AtomicBoolean();

        private PendingPoll(MessageBatch<Object> batch,
                            Map<TopicPartition, OffsetAndMetadata> nextOffsets,
                            Map<TopicPartition, Long> firstOffsets,
                            Map<TopicPartition, List<IndexedOffset>> indexedOffsets) {
            this.batch = batch;
            this.nextOffsets = nextOffsets;
            this.firstOffsets = firstOffsets;
            this.indexedOffsets = indexedOffsets;
            this.settled = new AtomicIntegerArray(batch.size());
        }

        private static PendingPoll create(ConsumerRecords<Object, Object> records,
                                          List<Message<Object>> messages,
                                          IncomingConnectorContext context) {
            String channel = context.channel();
            int maxDeliveryMessages = context.maxDeliveryMessages();
            if (messages.size() > maxDeliveryMessages) {
                throw new MessagingRejectedException(
                        channel,
                        MessagingRejectedException.Reason.OVERSIZED,
                        "Kafka poll contains " + messages.size() + " messages, exceeding channel "
                                + channel + " limit " + maxDeliveryMessages);
            }
            Map<TopicPartition, Long> firstOffsets = new LinkedHashMap<>();
            for (TopicPartition partition : records.partitions()) {
                firstOffsets.put(partition, records.records(partition).getFirst().offset());
            }
            Map<TopicPartition, List<IndexedOffset>> indexedOffsets = new LinkedHashMap<>();
            int batchIndex = 0;
            for (ConsumerRecord<Object, Object> record : records) {
                TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                indexedOffsets.computeIfAbsent(partition, ignored -> new ArrayList<>())
                        .add(new IndexedOffset(batchIndex++, record.offset(), record.leaderEpoch()));
            }
            if (batchIndex != messages.size()) {
                throw new IllegalStateException("Kafka poll record count does not match its message count");
            }
            Map<TopicPartition, OffsetAndMetadata> nextOffsets = Map.copyOf(records.nextOffsets());
            Map<TopicPartition, Long> immutableFirstOffsets = Map.copyOf(firstOffsets);
            MessageBatch<Object> batch = MessageBatch.<Object>builder()
                    .messages(messages)
                    .build();
            Map<TopicPartition, List<IndexedOffset>> immutableIndexedOffsets = new LinkedHashMap<>();
            indexedOffsets.forEach((partition, offsets) -> immutableIndexedOffsets.put(partition,
                                                                                       List.copyOf(offsets)));
            return new PendingPoll(batch,
                                   nextOffsets,
                                   immutableFirstOffsets,
                                   Map.copyOf(immutableIndexedOffsets));
        }

        private MessageBatch<Object> batch() {
            return batch;
        }

        private Map<TopicPartition, OffsetAndMetadata> nextOffsets() {
            return nextOffsets;
        }

        private Map<TopicPartition, Long> firstOffsets() {
            return firstOffsets;
        }

        private Set<TopicPartition> invalidatedPartitions() {
            return invalidatedPartitions;
        }

        private void settle(int originalIndex) {
            settled.set(originalIndex, 1);
        }

        private void settleAll() {
            for (int i = 0; i < batch.size(); i++) {
                settle(i);
            }
        }

        private void settleSucceeded(BatchDeliveryException failure) {
            for (int i = 0; i < batch.size(); i++) {
                if (failure.outcome(i).status() == BatchItemStatus.SUCCEEDED) {
                    settle(i);
                }
            }
        }

        private Map<TopicPartition, OffsetAndMetadata> contiguousSettledOffsets() {
            Map<TopicPartition, OffsetAndMetadata> result = new LinkedHashMap<>();
            indexedOffsets.forEach((partition, offsets) -> {
                int settledCount = 0;
                while (settledCount < offsets.size()
                        && settled.get(offsets.get(settledCount).batchIndex()) != 0) {
                    settledCount++;
                }
                if (settledCount == 0) {
                    return;
                }
                if (settledCount == offsets.size()) {
                    result.put(partition, nextOffsets.get(partition));
                } else {
                    IndexedOffset lastSettled = offsets.get(settledCount - 1);
                    OffsetAndMetadata pollNextOffset = nextOffsets.get(partition);
                    String metadata = pollNextOffset == null ? "" : pollNextOffset.metadata();
                    result.put(partition,
                               new OffsetAndMetadata(offsets.get(settledCount).offset(),
                                                     lastSettled.leaderEpoch(),
                                                     metadata));
                }
            });
            return Map.copyOf(result);
        }

        private Map<TopicPartition, Long> firstUnsettledOffsets() {
            Map<TopicPartition, Long> result = new LinkedHashMap<>();
            indexedOffsets.forEach((partition, offsets) -> {
                for (IndexedOffset offset : offsets) {
                    if (settled.get(offset.batchIndex()) == 0) {
                        result.put(partition, offset.offset());
                        break;
                    }
                }
            });
            return Map.copyOf(result);
        }

        private boolean stale() {
            return stale.get();
        }

        private boolean invalidate(Collection<TopicPartition> partitions) {
            boolean changed = false;
            for (TopicPartition partition : partitions) {
                if (firstOffsets.containsKey(partition)) {
                    changed |= invalidatedPartitions.add(partition);
                }
            }
            if (changed) {
                stale.set(true);
            }
            return changed;
        }

        private void invalidateMissing(Set<TopicPartition> assignment) {
            for (TopicPartition partition : firstOffsets.keySet()) {
                if (!assignment.contains(partition)) {
                    invalidatedPartitions.add(partition);
                    stale.set(true);
                }
            }
        }

        private record IndexedOffset(int batchIndex, long offset, Optional<Integer> leaderEpoch) {
            private IndexedOffset {
                Objects.requireNonNull(leaderEpoch);
            }
        }
    }

    private static Duration maintenancePollTimeout(KafkaConnectorConfig config) {
        String configured = config.properties().get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
        if (configured == null) {
            return MAX_MAINTENANCE_POLL_TIMEOUT;
        }
        long maxPollIntervalMillis = Long.parseLong(configured);
        long timeoutMillis = Math.max(1, Math.min(MAX_MAINTENANCE_POLL_TIMEOUT.toMillis(),
                                                 maxPollIntervalMillis / 3));
        return Duration.ofMillis(timeoutMillis);
    }

    private static Duration durationProperty(KafkaConnectorConfig config,
                                             String property,
                                             Duration defaultValue) {
        String configured = config.properties().get(property);
        return configured == null ? defaultValue : Duration.ofMillis(Long.parseLong(configured));
    }
}
