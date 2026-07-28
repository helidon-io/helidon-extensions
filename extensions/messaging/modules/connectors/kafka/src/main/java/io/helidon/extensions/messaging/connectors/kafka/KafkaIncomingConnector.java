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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.extensions.messaging.ConnectorSource;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.service.registry.Service;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.WakeupException;

/**
 * Kafka incoming connector.
 * <p>
 * Each consumer poll is one settlement unit. Its exact immutable message batch remains in flight while the consumer
 * owner thread continues polling with all assigned partitions paused. Next offsets are committed only after dispatch
 * succeeds, or after the portable failure policy explicitly settles the failure. Retrying a poll can duplicate work
 * completed before another required output failed.
 */
@Service.Singleton
public class KafkaIncomingConnector implements IncomingConnector<KafkaConnectorConfig> {
    private static final System.Logger LOGGER = System.getLogger(KafkaIncomingConnector.class.getName());
    private static final Duration MAX_MAINTENANCE_POLL_TIMEOUT = Duration.ofMillis(100);
    private static final Duration DEFAULT_COMMIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_COMMIT_RETRY_BACKOFF = Duration.ofMillis(100);

    private final ConsumerFactory consumerFactory;
    private final Set<KafkaSource> sources = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Create a Kafka incoming connector.
     */
    @Service.Inject
    public KafkaIncomingConnector() {
        this(KafkaConsumer::new);
    }

    KafkaIncomingConnector(ConsumerFactory consumerFactory) {
        this.consumerFactory = Objects.requireNonNull(consumerFactory);
    }

    @Override
    public String connectorName() {
        return KafkaOutgoingConnector.CONNECTOR;
    }

    @Override
    public ConnectorSource createSource(KafkaConnectorConfig config, ConnectorSourceContext context) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(context);
        if (closed.get()) {
            throw new IllegalStateException("Kafka incoming connector is closed");
        }

        KafkaSource source = new KafkaSource(config, context);
        sources.add(source);
        if (closed.get() && sources.remove(source)) {
            source.close();
            throw new IllegalStateException("Kafka incoming connector is closed");
        }
        return source;
    }

    @Override
    @Service.PreDestroy
    public void close() {
        closed.set(true);
        RuntimeException closeFailure = null;
        for (KafkaSource source : List.copyOf(sources)) {
            try {
                source.close();
            } catch (RuntimeException e) {
                if (closeFailure == null) {
                    closeFailure = e;
                } else {
                    closeFailure.addSuppressed(e);
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    @FunctionalInterface
    interface ConsumerFactory {
        Consumer<Object, Object> create(Map<String, Object> properties);
    }

    private final class KafkaSource implements ConnectorSource {
        private final KafkaConnectorConfig config;
        private final ConnectorSourceContext context;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean runFinished = new AtomicBoolean();
        private final AtomicReference<Consumer<Object, Object>> activeConsumer = new AtomicReference<>();
        private final AtomicReference<DeliveryTask> activeDelivery = new AtomicReference<>();
        private final CountDownLatch closeSignal = new CountDownLatch(1);
        private final Object commitGate = new Object();
        private final Object deliveryGate = new Object();
        private final Duration maintenancePollTimeout;
        private final Duration commitTimeout;
        private final Duration commitRetryBackoff;

        private KafkaSource(KafkaConnectorConfig config, ConnectorSourceContext context) {
            this.config = config;
            this.context = context;
            this.maintenancePollTimeout = maintenancePollTimeout(config);
            this.commitTimeout = durationProperty(config,
                                                   ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                                                   DEFAULT_COMMIT_TIMEOUT);
            this.commitRetryBackoff = durationProperty(config,
                                                        ConsumerConfig.RETRY_BACKOFF_MS_CONFIG,
                                                        DEFAULT_COMMIT_RETRY_BACKOFF);
        }

        @Override
        public void run() {
            if (closed.get()) {
                sources.remove(this);
                return;
            }

            Consumer<Object, Object> consumer = null;
            Throwable primaryFailure = null;
            try {
                consumer = consumerFactory.create(KafkaConnectorConfigSupport.consumerProperties(config));
                activeConsumer.set(consumer);
                if (!closed.get()) {
                    consume(consumer);
                }
            } catch (WakeupException e) {
                if (!closed.get()) {
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
                RuntimeException cleanupFailure = null;
                if (consumer != null) {
                    activeConsumer.compareAndSet(consumer, null);
                    try {
                        closeConsumer(consumer);
                    } catch (RuntimeException e) {
                        cleanupFailure = appendCleanupFailure(cleanupFailure, e);
                    }
                }
                runFinished.set(true);
                removeIfQuiescent();
                if (cleanupFailure != null) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }

        private void consume(Consumer<Object, Object> consumer) {
            SourceRebalanceListener rebalanceListener = new SourceRebalanceListener(consumer);
            consumer.subscribe(List.of(config.topic()), rebalanceListener);
            while (!closed.get()) {
                ConsumerRecords<Object, Object> records = consumer.poll(config.pollTimeout());
                List<Message<Object>> messages = new ArrayList<>();
                for (ConsumerRecord<Object, Object> record : records) {
                    messages.add(toMessage(record));
                }
                if (messages.isEmpty()) {
                    continue;
                }
                PendingPoll pendingPoll = PendingPoll.create(records, List.copyOf(messages));
                if (!processPoll(consumer, rebalanceListener, pendingPoll)) {
                    return;
                }
            }
        }

        private boolean processPoll(Consumer<Object, Object> consumer,
                                    SourceRebalanceListener rebalanceListener,
                                    PendingPoll pendingPoll) {
            rebalanceListener.pending(pendingPoll);
            consumer.pause(consumer.assignment());
            DeliveryTask deliveryTask = startDelivery(pendingPoll);
            if (deliveryTask == null) {
                rebalanceListener.clear(pendingPoll);
                return false;
            }
            try {
                while (!deliveryTask.completion().isDone()) {
                    if (closed.get()) {
                        return false;
                    }
                    maintenancePoll(consumer);
                }
                if (closed.get()) {
                    return false;
                }

                rethrowDeliveryFailure(deliveryTask);
                pendingPoll.invalidateMissing(consumer.assignment());
                if (pendingPoll.stale()) {
                    recoverStalePoll(consumer, pendingPoll);
                    return true;
                }
                return commitPoll(consumer, pendingPoll);
            } finally {
                rebalanceListener.clear(pendingPoll);
                stopDelivery(deliveryTask);
                if (!closed.get()) {
                    consumer.resume(consumer.assignment());
                }
            }
        }

        private DeliveryTask startDelivery(PendingPoll pendingPoll) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            Thread thread = Thread.ofVirtual()
                    .name("helidon-messaging-kafka-delivery-" + context.channelName())
                    .inheritInheritableThreadLocals(false)
                    .unstarted(() -> runDelivery(pendingPoll, completion));
            DeliveryTask deliveryTask = new DeliveryTask(thread, completion);
            synchronized (deliveryGate) {
                if (closed.get()) {
                    return null;
                }
                if (!activeDelivery.compareAndSet(null, deliveryTask)) {
                    throw new IllegalStateException("Kafka source already has an active delivery");
                }
                thread.start();
            }
            return deliveryTask;
        }

        private void runDelivery(PendingPoll pendingPoll, CompletableFuture<Void> completion) {
            try {
                deliver(pendingPoll);
                completion.complete(null);
            } catch (Throwable t) {
                completion.completeExceptionally(t);
            } finally {
                DeliveryTask deliveryTask = activeDelivery.get();
                if (deliveryTask != null && deliveryTask.thread() == Thread.currentThread()) {
                    activeDelivery.compareAndSet(deliveryTask, null);
                }
                removeIfQuiescent();
            }
        }

        private void deliver(PendingPoll pendingPoll) {
            int attempts = 0;
            while (!closed.get() && !pendingPoll.stale()) {
                try {
                    context.emitBatch(pendingPoll.messages());
                } catch (RuntimeException e) {
                    if (closed.get() || pendingPoll.stale()) {
                        return;
                    }
                    attempts++;
                    ConnectorSourceContext.FailureResult result;
                    try {
                        result = Objects.requireNonNull(
                                context.handleFailure(pendingPoll.messages(), attempts, e),
                                "Connector source failure result");
                    } catch (RuntimeException failureHandlingFailure) {
                        if (closed.get() || pendingPoll.stale()) {
                            return;
                        }
                        throw failureHandlingFailure;
                    }
                    if (closed.get() || pendingPoll.stale()) {
                        return;
                    }
                    switch (result) {
                    case RETRY:
                        try {
                            if (!prepareRedelivery(pendingPoll.messages().size(), attempts, e)) {
                                return;
                            }
                        } catch (RuntimeException redeliveryFailure) {
                            if (closed.get() || pendingPoll.stale()) {
                                return;
                            }
                            throw redeliveryFailure;
                        }
                        if (pendingPoll.stale()) {
                            return;
                        }
                        continue;
                    case SETTLED:
                        return;
                    default:
                        throw new IllegalStateException("Unsupported connector source failure result: " + result);
                    }
                }
                return;
            }
        }

        private boolean commitPoll(Consumer<Object, Object> consumer, PendingPoll pendingPoll) {
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
                synchronized (commitGate) {
                    if (closed.get()) {
                        return false;
                    }
                    if (previousCommitFailure != null && commitTimedOut(commitStarted)) {
                        rethrowCommitFailure(previousCommitFailure);
                    }
                    try {
                        consumer.commitAsync(pendingPoll.nextOffsets(), (offsets, exception) -> {
                            failure.set(exception);
                            completed.set(true);
                        });
                    } catch (RuntimeException e) {
                        failure.set(e);
                        completed.set(true);
                    }
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
            ConsumerRecords<Object, Object> records = consumer.poll(maintenancePollTimeout);
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

        private void rethrowDeliveryFailure(DeliveryTask deliveryTask) {
            try {
                deliveryTask.completion().join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new MessagingException("Kafka incoming message processing failed", cause);
            }
        }

        private void stopDelivery(DeliveryTask deliveryTask) {
            if (!deliveryTask.completion().isDone()) {
                deliveryTask.thread().interrupt();
            } else {
                activeDelivery.compareAndSet(deliveryTask, null);
            }
            removeIfQuiescent();
        }

        private boolean prepareRedelivery(int recordCount,
                                          int attempts,
                                          RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                       "Kafka incoming message processing failed on attempt "
                               + attempts + "; redelivering retained poll of " + recordCount + " record(s)",
                       failure);
            return awaitRedeliveryDelay();
        }

        private RuntimeException appendCleanupFailure(RuntimeException current, RuntimeException additional) {
            if (current == null) {
                return additional;
            }
            current.addSuppressed(additional);
            return current;
        }

        private boolean awaitRedeliveryDelay() {
            try {
                long delayNanos = TimeUnit.NANOSECONDS.convert(context.failurePolicy().retryDelay());
                return !closeSignal.await(delayNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (closed.get()) {
                    return false;
                }
                throw new MessagingException("Kafka incoming connector redelivery wait interrupted", e);
            }
        }

        private KafkaMessage<Object, Object> toMessage(ConsumerRecord<Object, Object> record) {
            return KafkaMessageImpl.create(record);
        }

        private void close() {
            synchronized (commitGate) {
                closed.set(true);
            }
            closeSignal.countDown();
            DeliveryTask deliveryTask;
            synchronized (deliveryGate) {
                deliveryTask = activeDelivery.get();
            }
            if (deliveryTask != null) {
                deliveryTask.thread().interrupt();
            }
            Consumer<Object, Object> consumer = activeConsumer.get();
            if (consumer != null) {
                consumer.wakeup();
            }
            awaitDelivery(deliveryTask);
        }

        private void awaitDelivery(DeliveryTask deliveryTask) {
            if (deliveryTask == null || deliveryTask.thread() == Thread.currentThread()) {
                return;
            }
            try {
                deliveryTask.completion().get(TimeUnit.NANOSECONDS.convert(config.closeTimeout()),
                                              TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                // The delivery is quiescent. Its processing failure belongs to the source owner thread.
            } catch (TimeoutException e) {
                String message = "Kafka incoming connector close timed out after "
                        + config.closeTimeout() + " while waiting for active delivery on channel "
                        + context.channelName() + "; the delivery remains tracked until it finishes";
                LOGGER.log(System.Logger.Level.ERROR, message);
                throw new MessagingException(message, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while waiting for active Kafka delivery on channel "
                                                     + context.channelName() + " to finish",
                                             e);
            }
        }

        private void removeIfQuiescent() {
            if (runFinished.get() && activeDelivery.get() == null) {
                sources.remove(this);
            }
        }

        private void closeConsumer(Consumer<Object, Object> consumer) {
            try {
                consumer.close(config.closeTimeout());
            } catch (RuntimeException e) {
                if (!closed.get()) {
                    throw new MessagingException("Kafka incoming connector close failed", e);
                }
            }
        }

        private final class SourceRebalanceListener implements ConsumerRebalanceListener {
            private final Consumer<Object, Object> consumer;
            private PendingPoll pendingPoll;

            private SourceRebalanceListener(Consumer<Object, Object> consumer) {
                this.consumer = consumer;
            }

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                invalidate(partitions);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (pendingPoll != null) {
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

            private void invalidate(Collection<TopicPartition> partitions) {
                if (pendingPoll != null && pendingPoll.invalidate(partitions)) {
                    DeliveryTask deliveryTask = activeDelivery.get();
                    if (deliveryTask != null) {
                        deliveryTask.thread().interrupt();
                    }
                }
            }
        }
    }

    private record DeliveryTask(Thread thread, CompletableFuture<Void> completion) {
    }

    private static final class PendingPoll {
        private final List<Message<Object>> messages;
        private final Map<TopicPartition, OffsetAndMetadata> nextOffsets;
        private final Map<TopicPartition, Long> firstOffsets;
        private final Set<TopicPartition> invalidatedPartitions = new HashSet<>();
        private final AtomicBoolean stale = new AtomicBoolean();

        private PendingPoll(List<Message<Object>> messages,
                            Map<TopicPartition, OffsetAndMetadata> nextOffsets,
                            Map<TopicPartition, Long> firstOffsets) {
            this.messages = messages;
            this.nextOffsets = nextOffsets;
            this.firstOffsets = firstOffsets;
        }

        private static PendingPoll create(ConsumerRecords<Object, Object> records,
                                          List<Message<Object>> messages) {
            Map<TopicPartition, Long> firstOffsets = new LinkedHashMap<>();
            for (TopicPartition partition : records.partitions()) {
                firstOffsets.put(partition, records.records(partition).getFirst().offset());
            }
            return new PendingPoll(messages, Map.copyOf(records.nextOffsets()), Map.copyOf(firstOffsets));
        }

        private List<Message<Object>> messages() {
            return messages;
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
