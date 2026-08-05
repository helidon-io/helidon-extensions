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

package io.helidon.extensions.messaging;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Runtime-owned delivery and source task engine.
 */
final class DeliveryEngine implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(DeliveryEngine.class.getName());
    private static final ThreadLocal<DeliveryContext> CURRENT_DELIVERY = new ThreadLocal<>();

    private final Map<String, ChannelDispatcher> dispatchers = new ConcurrentHashMap<>();
    private final Set<Thread> sourceThreads = ConcurrentHashMap.newKeySet();
    private final Set<Thread> dispatchThreads = ConcurrentHashMap.newKeySet();
    private final ReentrantLock sourceThreadsLock = new ReentrantLock();
    private final ReentrantLock dispatchThreadsLock = new ReentrantLock();
    private final List<MessageSizeEstimator> sizeEstimators;
    private final ThreadFactory dispatchThreadFactory;
    private final ThreadFactory cleanupThreadFactory;
    private final ThreadFactory sourceThreadFactory;
    private final Duration shutdownTimeout;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();

    DeliveryEngine(MessagingExecutionConfig defaultConfig, List<MessageSizeEstimator> sizeEstimators) {
        this.shutdownTimeout = Objects.requireNonNull(defaultConfig).shutdownTimeout();
        this.sizeEstimators = List.copyOf(sizeEstimators);
        this.dispatchThreadFactory = virtualThreadFactory("helidon-messaging-dispatch-", "Messaging delivery failed");
        this.cleanupThreadFactory = virtualThreadFactory("helidon-messaging-cleanup-",
                                                         "Messaging reservation cleanup failed");
        this.sourceThreadFactory = virtualThreadFactory("helidon-messaging-source-", "Messaging source failed");
    }

    void registerChannel(String channel, MessagingExecutionConfig config) {
        Objects.requireNonNull(channel);
        Objects.requireNonNull(config);
        if (!accepting.get() || closed.get()) {
            throw rejected(channel,
                           MessagingRejectedException.Reason.SHUTDOWN,
                           "Messaging runtime is shutting down");
        }
        ChannelDispatcher previous = dispatchers.putIfAbsent(channel, new ChannelDispatcher(channel, config));
        if (previous != null) {
            throw new IllegalArgumentException("Messaging channel already registered: " + channel);
        }
    }

    void dispatch(String channel,
                  List<? extends Message<?>> messages,
                  Runnable action) {
        Objects.requireNonNull(messages);
        Objects.requireNonNull(action);
        ChannelDispatcher dispatcher = dispatcher(channel);
        DeliveryContext parent = CURRENT_DELIVERY.get();
        if (parent != null && parent.connectorLease(this, channel)) {
            parent.dispatchWithinLease(messages, action);
            return;
        }
        if (parent != null && parent.path().contains(new DeliveryNode(this, channel))) {
            throw new MessagingException("Cyclic synchronous messaging emission: "
                                                 + String.join(" -> ", parent.pathNames()) + " -> " + channel);
        }
        DeliveryCost cost = deliveryCost(channel, messages);
        AdmissionMode admissionMode = parent == null ? AdmissionMode.WAIT : AdmissionMode.NESTED;
        DeliveryTask task = dispatcher.submit(cost,
                                              parent == null ? List.of() : parent.path(),
                                              false,
                                              admissionMode,
                                              List.of(),
                                              action);
        if (task == null) {
            throw rejected(channel,
                           MessagingRejectedException.Reason.SATURATED,
                           "Nested delivery cannot run immediately on channel " + channel);
        }
        awaitCaller(task);
    }

    ConnectorDelivery submitConnectorDelivery(String channel,
                                               List<? extends Message<?>> messages,
                                               long admissionBytes,
                                               Runnable action) {
        Objects.requireNonNull(messages);
        Objects.requireNonNull(action);
        if (admissionBytes < 0) {
            throw new IllegalArgumentException("admissionBytes must be zero or greater");
        }
        ChannelDispatcher dispatcher = dispatcher(channel);
        DeliveryContext parent = CURRENT_DELIVERY.get();
        if (parent != null) {
            throw new MessagingException("A connector delivery cannot be submitted from messaging dispatch");
        }
        DeliveryCost cost = connectorDeliveryCost(channel, messages, admissionBytes);
        return dispatcher.submit(cost,
                                 List.of(),
                                 true,
                                 AdmissionMode.WAIT,
                                 messages,
                                 action);
    }

    Optional<ConnectorDelivery> trySubmitConnectorDelivery(String channel,
                                                           List<? extends Message<?>> messages,
                                                           long admissionBytes,
                                                           Runnable action) {
        Objects.requireNonNull(messages);
        Objects.requireNonNull(action);
        if (admissionBytes < 0) {
            throw new IllegalArgumentException("admissionBytes must be zero or greater");
        }
        DeliveryContext parent = CURRENT_DELIVERY.get();
        if (parent != null) {
            throw new MessagingException("A connector delivery cannot be submitted from messaging dispatch");
        }
        DeliveryTask task = dispatcher(channel).submit(connectorDeliveryCost(channel, messages, admissionBytes),
                                                       List.of(),
                                                       true,
                                                       AdmissionMode.TRY,
                                                       messages,
                                                       action);
        return Optional.ofNullable(task);
    }

    ConnectorDeliveryReservation reserveConnectorDelivery(String channel,
                                                           int maxMessages,
                                                           long maxAdmissionBytes) {
        rejectConnectorReservationFromDispatch();
        return dispatcher(channel).reserveConnectorDelivery(
                connectorReservationCost(channel, maxMessages, maxAdmissionBytes),
                AdmissionMode.WAIT);
    }

    Optional<ConnectorDeliveryReservation> tryReserveConnectorDelivery(String channel,
                                                                       int maxMessages,
                                                                       long maxAdmissionBytes) {
        rejectConnectorReservationFromDispatch();
        return Optional.ofNullable(dispatcher(channel).reserveConnectorDelivery(
                connectorReservationCost(channel, maxMessages, maxAdmissionBytes),
                AdmissionMode.TRY));
    }

    int maxDeliveryMessages(String channel) {
        MessagingExecutionConfig config = dispatcher(channel).config;
        return Math.min(config.maxInFlightMessages(), config.maxPendingMessages());
    }

    long maxDeliveryBytes(String channel) {
        MessagingExecutionConfig config = dispatcher(channel).config;
        return Math.min(config.maxInFlightBytes(), config.maxPendingBytes());
    }

    Optional<Duration> admissionTimeout(String channel) {
        return dispatcher(channel).config.admissionTimeout();
    }

    void runWithChannelAdmissionLock(String channel, Runnable action) {
        dispatcher(channel).runWithAdmissionLock(action);
    }

    void runWithDispatchThreadRegistryLock(Runnable action) {
        dispatchThreadsLock.lock();
        try {
            action.run();
        } finally {
            dispatchThreadsLock.unlock();
        }
    }

    SourceTask startSource(String name, Runnable source) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(source);
        sourceThreadsLock.lock();
        try {
            if (!accepting.get() || closed.get()) {
                throw rejected(name,
                               MessagingRejectedException.Reason.SHUTDOWN,
                               "Messaging runtime is shutting down");
            }

            SourceTask sourceTask = new SourceTask(name, source);
            Thread thread = sourceThreadFactory.newThread(() -> {
                Throwable failure = null;
                try {
                    source.run();
                } catch (RuntimeException | Error t) {
                    failure = t;
                    throw t;
                } finally {
                    sourceThreads.remove(Thread.currentThread());
                    sourceTask.complete(failure);
                }
            });
            sourceTask.thread(thread);
            sourceThreads.add(thread);
            try {
                thread.start();
            } catch (RuntimeException | Error e) {
                sourceThreads.remove(thread);
                sourceTask.complete(e);
                throw e;
            }
            return sourceTask;
        } finally {
            sourceThreadsLock.unlock();
        }
    }

    Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    void beginDrain() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        dispatchers.values().forEach(ChannelDispatcher::beginDrain);
    }

    boolean awaitDrained(Duration timeout) {
        Objects.requireNonNull(timeout);
        long deadline = saturatedAdd(System.nanoTime(), timeout.toNanos());
        for (ChannelDispatcher dispatcher : dispatchers.values()) {
            if (!dispatcher.awaitDrained(deadline)) {
                return false;
            }
        }
        return awaitSourceTermination(deadline);
    }

    void forceShutdown() {
        accepting.set(false);
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        dispatchers.values().forEach(ChannelDispatcher::close);
        sourceThreadsLock.lock();
        try {
            sourceThreads.forEach(Thread::interrupt);
        } finally {
            sourceThreadsLock.unlock();
        }
    }

    @Override
    public void close() {
        forceShutdown();
        if (!awaitTermination(shutdownTimeout)) {
            int remaining = sourceThreads.size() + dispatchThreads.size();
            LOGGER.log(System.Logger.Level.ERROR,
                       "Messaging shutdown timed out after " + shutdownTimeout
                               + "; " + remaining + " task(s) remain active");
        }
    }

    private ChannelDispatcher dispatcher(String channel) {
        ChannelDispatcher dispatcher = dispatchers.get(channel);
        if (dispatcher == null) {
            throw new MessagingException("Unknown messaging channel " + channel);
        }
        return dispatcher;
    }

    private DeliveryCost deliveryCost(String channel, List<? extends Message<?>> messages) {
        DeliveryBytes bytes;
        try {
            bytes = deliveryBytes(messages);
        } catch (ArithmeticException e) {
            throw rejected(channel,
                           MessagingRejectedException.Reason.OVERSIZED,
                           "Message batch admission byte size exceeds the supported range");
        }
        if (bytes.firstUnknown().isPresent()) {
            Message<?> unknown = bytes.firstUnknown().get();
            throw rejected(channel,
                           MessagingRejectedException.Reason.UNKNOWN_SIZE,
                           "Message " + unknown.getClass().getName()
                                   + " does not declare an admission byte size");
        }
        return new DeliveryCost(messages.size(), bytes.knownBytes());
    }

    private DeliveryCost connectorDeliveryCost(String channel,
                                               List<? extends Message<?>> messages,
                                               long admissionBytes) {
        if (admissionBytes < 0) {
            throw new IllegalArgumentException("admissionBytes must be zero or greater");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Connector delivery must contain at least one message");
        }
        DeliveryBytes declaredMessages;
        try {
            declaredMessages = deliveryBytes(messages);
        } catch (ArithmeticException e) {
            throw rejected(channel,
                           MessagingRejectedException.Reason.OVERSIZED,
                           "Message batch admission byte size exceeds the supported range");
        }
        long effectiveBytes = Math.max(admissionBytes, declaredMessages.knownBytes());
        if (effectiveBytes < 0) {
            throw rejected(channel,
                           MessagingRejectedException.Reason.OVERSIZED,
                           "Message batch admission byte size exceeds the supported range");
        }
        return new DeliveryCost(messages.size(), effectiveBytes);
    }

    private DeliveryCost connectorReservationCost(String channel,
                                                  int maxMessages,
                                                  long maxAdmissionBytes) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than zero");
        }
        if (maxAdmissionBytes < 0) {
            throw new IllegalArgumentException("maxAdmissionBytes must be zero or greater");
        }
        DeliveryCost cost = new DeliveryCost(maxMessages, maxAdmissionBytes);
        ChannelDispatcher dispatcher = dispatcher(channel);
        dispatcher.validateCost(cost);
        dispatcher.validatePendingCost(cost);
        return cost;
    }

    private void rejectConnectorReservationFromDispatch() {
        if (CURRENT_DELIVERY.get() != null) {
            throw new MessagingException("A connector delivery cannot be reserved from messaging dispatch");
        }
    }

    private DeliveryBytes deliveryBytes(List<? extends Message<?>> messages) {
        long bytes = 0;
        Message<?> firstUnknown = null;
        for (Message<?> message : messages) {
            Objects.requireNonNull(message);
            OptionalLong estimate = messageAdmissionBytes(message);
            if (estimate.isEmpty()) {
                if (firstUnknown == null) {
                    firstUnknown = message;
                }
                continue;
            }
            long messageBytes = estimate.getAsLong();
            bytes = Math.addExact(bytes, messageBytes);
        }
        return new DeliveryBytes(bytes, Optional.ofNullable(firstUnknown));
    }

    OptionalLong messageAdmissionBytes(Message<?> message) {
        return messageAdmissionBytes(message, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private OptionalLong messageAdmissionBytes(Message<?> message, Set<Message<?>> path) {
        if (!path.add(message)) {
            throw new IllegalArgumentException("Dead-letter message original-message chain must not be cyclic");
        }
        try {
            OptionalLong result = directMessageAdmissionBytes(message);
            if (message instanceof DeadLetterMessage<?> deadLetterMessage
                    && deadLetterMessage.originalMessage() != message) {
                OptionalLong originalBytes = messageAdmissionBytes(deadLetterMessage.originalMessage(), path);
                if (originalBytes.isPresent()) {
                    long deadLetterBytes = Math.addExact(originalBytes.getAsLong(),
                                                        MessageSizes.headersBytes(message.headers()));
                    if (result.isEmpty() || deadLetterBytes > result.getAsLong()) {
                        result = OptionalLong.of(deadLetterBytes);
                    }
                }
            }
            return result;
        } finally {
            path.remove(message);
        }
    }

    private OptionalLong directMessageAdmissionBytes(Message<?> message) {
        OptionalLong result = Objects.requireNonNull(message.admissionBytes(), "Message admission byte size");
        if (result.isPresent() && result.getAsLong() < 0) {
            throw new IllegalArgumentException("Message admission byte size must be zero or greater");
        }
        for (MessageSizeEstimator estimator : sizeEstimators) {
            OptionalLong estimate = Objects.requireNonNull(estimator.estimate(message),
                                                           "Message size estimator result");
            if (estimate.isPresent()) {
                long value = estimate.getAsLong();
                if (value < 0) {
                    throw new IllegalArgumentException("Message size estimator returned a negative value: "
                                                               + estimator.getClass().getName());
                }
                if (result.isEmpty() || value > result.getAsLong()) {
                    result = OptionalLong.of(value);
                }
            }
        }
        return result;
    }

    private void awaitCaller(DeliveryTask task) {
        try {
            task.await();
        } catch (InterruptedException e) {
            task.cancel(MessagingRejectedException.Reason.CANCELLED,
                        "Messaging delivery caller was interrupted");
            Thread.currentThread().interrupt();
            throw new MessagingRejectedException(task.channel(),
                                                 MessagingRejectedException.Reason.CANCELLED,
                                                 "Interrupted while waiting for messaging delivery on channel "
                                                         + task.channel(),
                                                 e);
        }
    }

    private Thread startDispatch(DeliveryTask task) {
        Thread thread = dispatchThreadFactory.newThread(() -> {
            CURRENT_DELIVERY.set(task.context());
            Throwable failure = null;
            try {
                task.action().run();
            } catch (Throwable t) {
                failure = t;
            } finally {
                CURRENT_DELIVERY.remove();
                try {
                    task.finished(failure);
                } finally {
                    dispatchThreads.remove(Thread.currentThread());
                }
            }
        });
        task.thread(thread);
        dispatchThreads.add(thread);
        try {
            thread.start();
            return thread;
        } catch (RuntimeException | Error e) {
            dispatchThreads.remove(thread);
            task.thread(null);
            throw e;
        }
    }

    boolean startCleanup(Runnable cleanup) {
        dispatchThreadsLock.lock();
        try {
            if (closed.get()) {
                return false;
            }
            Thread thread = cleanupThreadFactory.newThread(() -> {
                try {
                    cleanup.run();
                } finally {
                    dispatchThreads.remove(Thread.currentThread());
                }
            });
            dispatchThreads.add(thread);
            try {
                thread.start();
                return true;
            } catch (RuntimeException | Error e) {
                dispatchThreads.remove(thread);
                throw e;
            }
        } finally {
            dispatchThreadsLock.unlock();
        }
    }

    boolean awaitTermination(Duration timeout) {
        Objects.requireNonNull(timeout);
        long deadline = saturatedAdd(System.nanoTime(), timeout.toNanos());
        List<Thread> tasks = new ArrayList<>(sourceThreads.size() + dispatchThreads.size());
        sourceThreadsLock.lock();
        try {
            tasks.addAll(sourceThreads);
        } finally {
            sourceThreadsLock.unlock();
        }
        dispatchThreadsLock.lock();
        try {
            tasks.addAll(dispatchThreads);
        } finally {
            dispatchThreadsLock.unlock();
        }
        for (Thread task : tasks) {
            if (task == Thread.currentThread()) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                task.join(Duration.ofNanos(remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(System.Logger.Level.WARNING,
                           "Interrupted while waiting for messaging tasks to stop",
                           e);
                return false;
            }
        }
        return sourceThreads.isEmpty() && dispatchThreads.isEmpty();
    }

    private boolean awaitSourceTermination(long deadline) {
        List<Thread> tasks = List.copyOf(sourceThreads);
        for (Thread task : tasks) {
            if (task == Thread.currentThread()) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            try {
                task.join(Duration.ofNanos(remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return sourceThreads.isEmpty()
                || sourceThreads.size() == 1 && sourceThreads.contains(Thread.currentThread());
    }

    private static ThreadFactory virtualThreadFactory(String prefix, String failureMessage) {
        AtomicLong sequence = new AtomicLong();
        return runnable -> Thread.ofVirtual()
                .name(prefix + sequence.incrementAndGet())
                .inheritInheritableThreadLocals(false)
                .uncaughtExceptionHandler((thread, throwable) -> LOGGER.log(System.Logger.Level.ERROR,
                                                                            failureMessage,
                                                                            throwable))
                .unstarted(runnable);
    }

    private static long saturatedAdd(long first, long second) {
        long result = first + second;
        if (((first ^ result) & (second ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private MessagingRejectedException rejected(String channel,
                                                 MessagingRejectedException.Reason reason,
                                                 String message) {
        if (reason == MessagingRejectedException.Reason.SHUTDOWN) {
            return new RuntimeShutdownException(this, channel, message);
        }
        return new MessagingRejectedException(channel, reason, message);
    }

    boolean ownsShutdownRejection(Throwable failure) {
        return failure instanceof RuntimeShutdownException shutdown && shutdown.owner == this;
    }

    private final class ChannelDispatcher {
        private final String channel;
        private final MessagingExecutionConfig config;
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition changed = lock.newCondition();
        private final Semaphore pendingAdmissions;
        private final Deque<Object> admissionOrder = new ArrayDeque<>();
        private final Deque<Object> pendingReservationOrder = new ArrayDeque<>();
        private final Deque<DeliveryTask> queue = new ArrayDeque<>();
        private final Set<DeliveryTask> active = new LinkedHashSet<>();
        private final Set<DeliveryTask> retained = new LinkedHashSet<>();
        private final Set<DeliveryReservation> reservations = new LinkedHashSet<>();
        private long inFlightMessages;
        private long inFlightBytes;
        private long pendingMessages;
        private long pendingBytes;
        private boolean dispatcherClosed;

        private ChannelDispatcher(String channel, MessagingExecutionConfig config) {
            this.channel = channel;
            this.config = config;
            this.pendingAdmissions = new Semaphore(config.maxPendingAdmissions(), true);
        }

        private DeliveryTask submit(DeliveryCost cost,
                                    List<DeliveryNode> parentPath,
                                    boolean connectorLease,
                                    AdmissionMode admissionMode,
                                    List<? extends Message<?>> connectorMessages,
                                    Runnable action) {
            validateCost(cost);
            DeliveryTask task = new DeliveryTask(this,
                                                 cost,
                                                 new DeliveryContext(DeliveryEngine.this,
                                                                     channel,
                                                                     parentPath,
                                                                     connectorLease,
                                                                     connectorMessages),
                                                 connectorLease,
                                                 action);
            if (admissionMode == AdmissionMode.NESTED || admissionMode == AdmissionMode.TRY) {
                if (!lock.tryLock()) {
                    return null;
                }
                try {
                    if (admissionMode == AdmissionMode.NESTED) {
                        rejectIfForced();
                    } else {
                        rejectIfNotAccepting();
                    }
                    boolean admissible = admissionOrder.isEmpty()
                            && (admissionMode == AdmissionMode.NESTED
                                    ? canStartImmediately(cost)
                                    : canAdmit(cost));
                    if (!admissible) {
                        return null;
                    }
                    admit(task);
                    return task;
                } finally {
                    lock.unlock();
                }
            }

            DeliveryTask immediate = tryImmediateAdmission(task);
            if (immediate != null) {
                return immediate;
            }

            Object admissionToken = null;
            boolean pendingReserved = false;
            if (!pendingAdmissions.tryAcquire()) {
                immediate = tryImmediateAdmission(task);
                if (immediate != null) {
                    return immediate;
                }
                throw pendingSaturated("Messaging pending-admission limit reached");
            }
            try {
                // Parking for this mutex would retain the caller's delivery before its byte/message cost is reserved.
                if (!lock.tryLock()) {
                    throw pendingSaturated("Messaging dispatcher is busy");
                }
                try {
                    rejectIfNotAccepting();
                    if (admissionOrder.isEmpty() && canAdmit(cost)) {
                        admit(task);
                        return task;
                    }
                    if (!canReservePending(cost)) {
                        throw pendingSaturated("Messaging pending message or byte limit reached");
                    }
                    reservePending(cost);
                    pendingReserved = true;
                    admissionToken = new Object();
                    admissionOrder.addLast(admissionToken);
                    long remaining = config.admissionTimeout()
                            .map(Duration::toNanos)
                            .orElse(Long.MAX_VALUE);
                    while (true) {
                        rejectIfNotAccepting();
                        if (admissionOrder.peekFirst() == admissionToken && canAdmit(cost)) {
                            admissionOrder.removeFirst();
                            releasePending(cost);
                            pendingReserved = false;
                            admit(task);
                            changed.signalAll();
                            return task;
                        }
                        if (remaining == Long.MAX_VALUE) {
                            changed.await();
                        } else {
                            if (remaining <= 0) {
                                throw rejected(channel,
                                               MessagingRejectedException.Reason.TIMEOUT,
                                               "Messaging admission timed out on channel " + channel);
                            }
                            remaining = changed.awaitNanos(remaining);
                        }
                    }
                } finally {
                    if (admissionToken != null && admissionOrder.remove(admissionToken)) {
                        changed.signalAll();
                    }
                    if (pendingReserved) {
                        releasePending(cost);
                        changed.signalAll();
                    }
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingRejectedException(channel,
                                                     MessagingRejectedException.Reason.CANCELLED,
                                                     "Interrupted while waiting for messaging admission on channel "
                                                             + channel,
                                                     e);
            } finally {
                pendingAdmissions.release();
            }
        }

        private DeliveryTask tryImmediateAdmission(DeliveryTask task) {
            if (!lock.tryLock()) {
                return null;
            }
            try {
                rejectIfNotAccepting();
                if (!admissionOrder.isEmpty() || !canAdmit(task.cost())) {
                    return null;
                }
                admit(task);
                return task;
            } finally {
                lock.unlock();
            }
        }

        private DeliveryReservation reserveConnectorDelivery(DeliveryCost cost, AdmissionMode admissionMode) {
            validatePendingCost(cost);
            if (!pendingAdmissions.tryAcquire()) {
                if (admissionMode == AdmissionMode.TRY) {
                    return null;
                }
                throw pendingSaturated("Messaging pending-admission limit reached");
            }

            boolean transferPermit = false;
            Object reservationToken = null;
            try {
                if (admissionMode == AdmissionMode.TRY) {
                    if (!lock.tryLock()) {
                        return null;
                    }
                } else {
                    // Parking here would retain the requested transport capacity before it is accounted as pending.
                    if (!lock.tryLock()) {
                        throw pendingSaturated("Messaging dispatcher is busy");
                    }
                }
                try {
                    rejectIfNotAccepting();
                    if (admissionMode == AdmissionMode.TRY) {
                        if (!pendingReservationOrder.isEmpty() || !canReservePending(cost)) {
                            return null;
                        }
                        DeliveryReservation result = createReservation(cost, timeoutNanos());
                        transferPermit = true;
                        return result;
                    }

                    reservationToken = new Object();
                    pendingReservationOrder.addLast(reservationToken);
                    long remaining = timeoutNanos();
                    while (true) {
                        rejectIfNotAccepting();
                        if (pendingReservationOrder.peekFirst() == reservationToken
                                && canReservePending(cost)) {
                            pendingReservationOrder.removeFirst();
                            DeliveryReservation result = createReservation(cost, remaining);
                            transferPermit = true;
                            changed.signalAll();
                            return result;
                        }
                        remaining = awaitCapacity(remaining,
                                                  "Messaging delivery reservation timed out on channel " + channel);
                    }
                } finally {
                    if (reservationToken != null && pendingReservationOrder.remove(reservationToken)) {
                        changed.signalAll();
                    }
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingRejectedException(
                        channel,
                        MessagingRejectedException.Reason.CANCELLED,
                        "Interrupted while waiting for messaging delivery reservation on channel " + channel,
                        e);
            } finally {
                if (!transferPermit) {
                    pendingAdmissions.release();
                }
            }
        }

        private DeliveryReservation createReservation(DeliveryCost cost, long remainingCapacityWaitNanos) {
            reservePending(cost);
            DeliveryReservation result = new DeliveryReservation(this, cost, remainingCapacityWaitNanos);
            reservations.add(result);
            return result;
        }

        private DeliveryTask startReservation(DeliveryReservation reservation,
                                              DeliveryCost actualCost,
                                              List<? extends Message<?>> connectorMessages,
                                              Runnable action,
                                              AdmissionMode admissionMode) {
            validateReservationActual(reservation, actualCost);
            DeliveryTask task = new DeliveryTask(this,
                                                 actualCost,
                                                 new DeliveryContext(DeliveryEngine.this,
                                                                     channel,
                                                                     List.of(),
                                                                     true,
                                                                     connectorMessages),
                                                 true,
                                                 action);
            Object admissionToken = null;
            try {
                if (admissionMode == AdmissionMode.TRY) {
                    if (!lock.tryLock()) {
                        return null;
                    }
                } else {
                    lockForReservationStart(reservation);
                }
                try {
                    reservation.requireOpen();
                    rejectIfForced();
                    if (admissionMode == AdmissionMode.TRY) {
                        if (!admissionOrder.isEmpty() || !canAdmit(actualCost)) {
                            return null;
                        }
                        reservation.state.set(ReservationState.STARTING);
                    } else if (!admissionOrder.isEmpty() || !canAdmit(actualCost)) {
                        reservation.state.set(ReservationState.STARTING);
                        admissionToken = new Object();
                        reservation.waitingToken = admissionToken;
                        admissionOrder.addLast(admissionToken);
                        while (true) {
                            reservation.requireStarting();
                            rejectIfForced();
                            if (admissionOrder.peekFirst() == admissionToken && canAdmit(actualCost)) {
                                admissionOrder.removeFirst();
                                reservation.waitingToken = null;
                                break;
                            }
                            reservation.remainingCapacityWaitNanos = awaitCapacity(
                                    reservation.remainingCapacityWaitNanos,
                                    "Messaging delivery reservation start timed out on channel " + channel);
                        }
                    } else {
                        reservation.state.set(ReservationState.STARTING);
                    }

                    reservations.remove(reservation);
                    releasePending(reservation.reservedCost);
                    pendingAdmissions.release();
                    reservation.state.set(ReservationState.STARTED);
                    try {
                        admit(task);
                    } catch (RuntimeException | Error e) {
                        reservation.state.set(ReservationState.CLOSED);
                        changed.signalAll();
                        throw e;
                    }
                    changed.signalAll();
                    return task;
                } finally {
                    if (admissionToken != null && admissionOrder.remove(admissionToken)) {
                        reservation.waitingToken = null;
                        changed.signalAll();
                    }
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                closeReservationWithoutWaiting(reservation, ReservationState.CLOSED);
                Thread.currentThread().interrupt();
                throw new MessagingRejectedException(
                        channel,
                        MessagingRejectedException.Reason.CANCELLED,
                        "Interrupted while waiting to start messaging delivery on channel " + channel,
                        e);
            } catch (MessagingRejectedException e) {
                if (e.reason() == MessagingRejectedException.Reason.TIMEOUT) {
                    closeReservationWithoutWaiting(reservation, ReservationState.CLOSED);
                } else if (e.reason() == MessagingRejectedException.Reason.SHUTDOWN
                        || e.reason() == MessagingRejectedException.Reason.CANCELLED) {
                    closeReservation(reservation,
                                     e.reason() == MessagingRejectedException.Reason.SHUTDOWN
                                             ? ReservationState.SHUTDOWN
                                             : ReservationState.CLOSED);
                }
                throw e;
            }
        }

        private void lockForReservationStart(DeliveryReservation reservation) throws InterruptedException {
            long remaining = reservation.remainingCapacityWaitNanos;
            if (remaining == Long.MAX_VALUE) {
                lock.lockInterruptibly();
                return;
            }

            long started = System.nanoTime();
            boolean acquired = lock.tryLock(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            long elapsed = Math.max(0, System.nanoTime() - started);
            reservation.remainingCapacityWaitNanos = remaining <= elapsed ? 0 : remaining - elapsed;
            if (!acquired) {
                throw rejected(channel,
                               MessagingRejectedException.Reason.TIMEOUT,
                               "Messaging delivery reservation start timed out on channel " + channel);
            }
        }

        private void validateReservationActual(DeliveryReservation reservation, DeliveryCost actualCost) {
            reservation.requireOpen();
            validateCost(actualCost);
            if (actualCost.messages() > reservation.reservedCost.messages()
                    || actualCost.bytes() > reservation.reservedCost.bytes()) {
                closeReservation(reservation, ReservationState.CLOSED);
                throw rejected(channel,
                               MessagingRejectedException.Reason.OVERSIZED,
                               "Connector delivery exceeds its pending reservation on channel " + channel);
            }
        }

        private void closeReservation(DeliveryReservation reservation, ReservationState targetState) {
            if (!reservation.canTransitionToTerminal()) {
                return;
            }
            lock.lock();
            try {
                if (!reservation.transitionToTerminal(targetState)) {
                    return;
                }
                cleanupReservationLocked(reservation);
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void closeReservationWithoutWaiting(DeliveryReservation reservation, ReservationState targetState) {
            if (!reservation.transitionToTerminal(targetState)) {
                return;
            }
            if (lock.tryLock()) {
                try {
                    cleanupReservationLocked(reservation);
                    changed.signalAll();
                } finally {
                    lock.unlock();
                }
                return;
            }

            try {
                startCleanup(() -> cleanupReservation(reservation));
            } catch (RuntimeException | Error e) {
                LOGGER.log(System.Logger.Level.WARNING,
                           "Could not start deferred messaging reservation cleanup; cleaning up synchronously",
                           e);
                cleanupReservation(reservation);
            }
        }

        private void cleanupReservation(DeliveryReservation reservation) {
            lock.lock();
            try {
                cleanupReservationLocked(reservation);
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void cleanupReservationLocked(DeliveryReservation reservation) {
            if (reservation.waitingToken != null && admissionOrder.remove(reservation.waitingToken)) {
                reservation.waitingToken = null;
            }
            if (reservations.remove(reservation)) {
                releasePending(reservation.reservedCost);
                pendingAdmissions.release();
            }
        }

        private long timeoutNanos() {
            return config.admissionTimeout().map(Duration::toNanos).orElse(Long.MAX_VALUE);
        }

        private long awaitCapacity(long remaining, String timeoutMessage) throws InterruptedException {
            if (remaining == Long.MAX_VALUE) {
                changed.await();
                return remaining;
            }
            if (remaining <= 0) {
                throw rejected(channel, MessagingRejectedException.Reason.TIMEOUT, timeoutMessage);
            }
            return changed.awaitNanos(remaining);
        }

        private void admit(DeliveryTask task) {
            retained.add(task);
            reserve(task.cost());
            if (active.size() < config.concurrency() && queue.isEmpty()) {
                active.add(task);
                try {
                    startDispatch(task);
                } catch (RuntimeException | Error e) {
                    active.remove(task);
                    task.executionFinished = true;
                    release(task);
                    changed.signalAll();
                    throw e;
                }
            } else {
                queue.addLast(task);
            }
        }

        private boolean canAdmit(DeliveryCost cost) {
            boolean executionCapacity = active.size() < config.concurrency()
                    || queue.size() < config.queueCapacity();
            return executionCapacity
                    && cost.messages() <= config.maxInFlightMessages() - inFlightMessages
                    && cost.bytes() <= config.maxInFlightBytes() - inFlightBytes;
        }

        private boolean canStartImmediately(DeliveryCost cost) {
            return queue.isEmpty()
                    && active.size() < config.concurrency()
                    && cost.messages() <= config.maxInFlightMessages() - inFlightMessages
                    && cost.bytes() <= config.maxInFlightBytes() - inFlightBytes;
        }

        private boolean canReservePending(DeliveryCost cost) {
            return cost.messages() <= config.maxPendingMessages() - pendingMessages
                    && cost.bytes() <= config.maxPendingBytes() - pendingBytes;
        }

        private void validateCost(DeliveryCost cost) {
            if (cost.messages() > config.maxInFlightMessages()) {
                throw rejected(channel,
                               MessagingRejectedException.Reason.OVERSIZED,
                               "Delivery contains " + cost.messages()
                                       + " messages, exceeding channel " + channel
                                       + " limit " + config.maxInFlightMessages());
            }
            if (cost.bytes() > config.maxInFlightBytes()) {
                throw rejected(channel,
                               MessagingRejectedException.Reason.OVERSIZED,
                               "Delivery contains " + cost.bytes()
                                       + " admission bytes, exceeding channel " + channel
                                       + " limit " + config.maxInFlightBytes());
            }
        }

        private void validatePendingCost(DeliveryCost cost) {
            if (cost.messages() > config.maxPendingMessages()) {
                throw rejected(channel,
                               MessagingRejectedException.Reason.OVERSIZED,
                               "Delivery reservation contains " + cost.messages()
                                       + " messages, exceeding channel " + channel
                                       + " pending limit " + config.maxPendingMessages());
            }
            if (cost.bytes() > config.maxPendingBytes()) {
                throw rejected(channel,
                               MessagingRejectedException.Reason.OVERSIZED,
                               "Delivery reservation contains " + cost.bytes()
                                       + " admission bytes, exceeding channel " + channel
                                       + " pending limit " + config.maxPendingBytes());
            }
        }

        private MessagingRejectedException pendingSaturated(String message) {
            return rejected(channel,
                            MessagingRejectedException.Reason.SATURATED,
                            message + " on channel " + channel);
        }

        private void runWithAdmissionLock(Runnable action) {
            lock.lock();
            try {
                action.run();
            } finally {
                lock.unlock();
            }
        }

        private void reserve(DeliveryCost cost) {
            inFlightMessages += cost.messages();
            inFlightBytes += cost.bytes();
        }

        private void reservePending(DeliveryCost cost) {
            pendingMessages += cost.messages();
            pendingBytes += cost.bytes();
        }

        private void releasePending(DeliveryCost cost) {
            pendingMessages -= cost.messages();
            pendingBytes -= cost.bytes();
        }

        private void release(DeliveryTask task) {
            if (retained.remove(task)) {
                inFlightMessages -= task.cost().messages();
                inFlightBytes -= task.cost().bytes();
            }
        }

        private void finished(DeliveryTask task, Throwable failure) {
            Throwable completionFailure;
            lock.lock();
            try {
                if (!active.remove(task)) {
                    return;
                }
                task.executionFinished = true;
                completionFailure = task.cancellationFailure == null ? failure : task.cancellationFailure;
                if (!task.connectorLease || task.releaseRequested) {
                    release(task);
                }
                while (!closed.get()
                        && !dispatcherClosed
                        && active.size() < config.concurrency()
                        && !queue.isEmpty()) {
                    DeliveryTask next = queue.removeFirst();
                    active.add(next);
                    try {
                        startDispatch(next);
                    } catch (RuntimeException | Error e) {
                        active.remove(next);
                        next.executionFinished = true;
                        release(next);
                        next.complete(e);
                    }
                }
                changed.signalAll();
            } finally {
                lock.unlock();
            }
            task.complete(completionFailure);
        }

        private void cancel(DeliveryTask task,
                            MessagingRejectedException.Reason reason,
                            String message) {
            Thread activeThread = null;
            boolean complete = false;
            lock.lock();
            try {
                if (queue.remove(task)) {
                    task.requestCancellation(reason, message);
                    task.executionFinished = true;
                    if (!task.connectorLease) {
                        release(task);
                    }
                    complete = true;
                    changed.signalAll();
                } else if (active.contains(task)) {
                    task.requestCancellation(reason, message);
                    activeThread = task.thread();
                }
            } finally {
                lock.unlock();
            }
            if (complete) {
                task.complete(rejected(channel, reason, message));
            }
            if (activeThread != null) {
                activeThread.interrupt();
            }
        }

        private void releaseConnector(DeliveryTask task) {
            Thread activeThread = null;
            boolean complete = false;
            lock.lock();
            try {
                task.releaseRequested = true;
                if (queue.remove(task)) {
                    task.requestCancellation(MessagingRejectedException.Reason.CANCELLED,
                                             "Messaging delivery lease was released before processing started");
                    task.executionFinished = true;
                    release(task);
                    complete = true;
                } else if (active.contains(task)) {
                    task.requestCancellation(MessagingRejectedException.Reason.CANCELLED,
                                             "Messaging delivery lease was released before processing completed");
                    activeThread = task.thread();
                } else if (task.executionFinished) {
                    release(task);
                }
                changed.signalAll();
            } finally {
                lock.unlock();
            }
            if (complete) {
                task.complete(rejected(channel,
                                       MessagingRejectedException.Reason.CANCELLED,
                                       "Messaging delivery lease was released before processing started"));
            }
            if (activeThread != null) {
                activeThread.interrupt();
            }
        }

        private void beginDrain() {
            lock.lock();
            try {
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private boolean awaitDrained(long deadline) {
            try {
                lock.lockInterruptibly();
                try {
                    while (!isDrained()) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            return false;
                        }
                        changed.awaitNanos(remaining);
                    }
                    return true;
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private boolean isDrained() {
            return admissionOrder.isEmpty()
                    && pendingReservationOrder.isEmpty()
                    && queue.isEmpty()
                    && active.isEmpty()
                    && retained.isEmpty()
                    && reservations.isEmpty();
        }

        private void close() {
            List<DeliveryTask> queued;
            List<Thread> running;
            lock.lock();
            try {
                dispatcherClosed = true;
                for (DeliveryReservation reservation : List.copyOf(reservations)) {
                    reservation.state.set(ReservationState.SHUTDOWN);
                    if (reservation.waitingToken != null) {
                        admissionOrder.remove(reservation.waitingToken);
                        reservation.waitingToken = null;
                    }
                    releasePending(reservation.reservedCost);
                    pendingAdmissions.release();
                }
                reservations.clear();
                queued = new ArrayList<>(queue);
                queue.clear();
                for (DeliveryTask task : queued) {
                    task.requestCancellation(MessagingRejectedException.Reason.SHUTDOWN,
                                             "Messaging runtime is shutting down");
                    task.executionFinished = true;
                    release(task);
                }
                active.forEach(task -> {
                    task.releaseRequested = true;
                    task.requestCancellation(MessagingRejectedException.Reason.SHUTDOWN,
                                             "Messaging runtime is shutting down");
                });
                List<DeliveryTask> completedLeases = retained.stream()
                        .filter(task -> !active.contains(task) && !queue.contains(task))
                        .toList();
                completedLeases.forEach(this::release);
                running = active.stream().map(DeliveryTask::thread)
                        .filter(Objects::nonNull)
                        .toList();
                changed.signalAll();
            } finally {
                lock.unlock();
            }
            for (DeliveryTask task : queued) {
                task.complete(rejected(channel,
                                       MessagingRejectedException.Reason.SHUTDOWN,
                                       "Messaging runtime is shutting down"));
            }
            running.forEach(Thread::interrupt);
        }

        private void rejectIfNotAccepting() {
            if (!accepting.get()) {
                throw rejected(channel,
                               MessagingRejectedException.Reason.SHUTDOWN,
                               "Messaging runtime is draining");
            }
            rejectIfForced();
        }

        private void rejectIfForced() {
            if (dispatcherClosed || closed.get()) {
                throw rejected(channel,
                               MessagingRejectedException.Reason.SHUTDOWN,
                               "Messaging runtime is shutting down");
            }
        }
    }

    final class SourceTask {
        private final String name;
        private final Runnable source;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile Thread thread;

        private SourceTask(String name, Runnable source) {
            this.name = name;
            this.source = source;
        }

        String name() {
            return name;
        }

        Runnable source() {
            return source;
        }

        Optional<Throwable> failure() {
            return Optional.ofNullable(failure.get());
        }

        void onCompletion(Consumer<Optional<Throwable>> listener) {
            completion.whenComplete((ignored, throwable) -> listener.accept(failure()));
        }

        boolean await(Duration timeout) throws InterruptedException {
            try {
                completion.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                return true;
            } catch (ExecutionException e) {
                return true;
            } catch (TimeoutException e) {
                return false;
            }
        }

        void interrupt() {
            Thread current = thread;
            if (current != null) {
                current.interrupt();
            }
        }

        private void thread(Thread thread) {
            this.thread = thread;
        }

        private void complete(Throwable failure) {
            if (failure == null) {
                completion.complete(null);
            } else {
                this.failure.compareAndSet(null, failure);
                completion.completeExceptionally(failure);
            }
        }
    }

    private static final class RuntimeShutdownException extends MessagingRejectedException {
        private final DeliveryEngine owner;

        private RuntimeShutdownException(DeliveryEngine owner, String channel, String message) {
            super(channel, Reason.SHUTDOWN, message);
            this.owner = owner;
        }
    }

    private final class DeliveryReservation implements ConnectorDeliveryReservation {
        private final ChannelDispatcher dispatcher;
        private final DeliveryCost reservedCost;
        private final AtomicBoolean startClaimed = new AtomicBoolean();
        private final AtomicReference<ReservationState> state = new AtomicReference<>(ReservationState.OPEN);
        private long remainingCapacityWaitNanos;
        private Object waitingToken;

        private DeliveryReservation(ChannelDispatcher dispatcher,
                                    DeliveryCost reservedCost,
                                    long remainingCapacityWaitNanos) {
            this.dispatcher = dispatcher;
            this.reservedCost = reservedCost;
            this.remainingCapacityWaitNanos = remainingCapacityWaitNanos;
        }

        @Override
        public <T> ConnectorDelivery start(List<? extends Message<T>> messages,
                                           long admissionBytes,
                                           Runnable delivery) {
            claimStart();
            try {
                Objects.requireNonNull(messages);
                Objects.requireNonNull(delivery);
                DeliveryCost actualCost = connectorDeliveryCost(dispatcher.channel, messages, admissionBytes);
                return dispatcher.startReservation(this,
                                                   actualCost,
                                                   messages,
                                                   delivery,
                                                   AdmissionMode.WAIT);
            } catch (RuntimeException | Error e) {
                dispatcher.closeReservation(this, ReservationState.CLOSED);
                throw e;
            }
        }

        @Override
        public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                        long admissionBytes,
                                                        Runnable delivery) {
            claimStart();
            try {
                Objects.requireNonNull(messages);
                Objects.requireNonNull(delivery);
                DeliveryCost actualCost = connectorDeliveryCost(dispatcher.channel, messages, admissionBytes);
                DeliveryTask task = dispatcher.startReservation(this,
                                                                actualCost,
                                                                messages,
                                                                delivery,
                                                                AdmissionMode.TRY);
                if (task == null) {
                    startClaimed.set(false);
                }
                return Optional.ofNullable(task);
            } catch (RuntimeException | Error e) {
                dispatcher.closeReservation(this, ReservationState.CLOSED);
                throw e;
            }
        }

        @Override
        public void close() {
            dispatcher.closeReservation(this, ReservationState.CLOSED);
        }

        private void requireOpen() {
            ReservationState current = state.get();
            switch (current) {
            case OPEN:
                return;
            case STARTED:
                throw new IllegalStateException("Connector delivery reservation was already started");
            case STARTING:
                throw new IllegalStateException("Connector delivery reservation is already being started");
            case CLOSED:
                throw rejected(dispatcher.channel,
                               MessagingRejectedException.Reason.CANCELLED,
                               "Connector delivery reservation is closed");
            case SHUTDOWN:
                throw rejected(dispatcher.channel,
                               MessagingRejectedException.Reason.SHUTDOWN,
                               "Messaging runtime is shutting down");
            default:
                throw new IllegalStateException("Unsupported connector delivery reservation state: " + current);
            }
        }

        private void claimStart() {
            requireOpen();
            if (!startClaimed.compareAndSet(false, true)) {
                throw new IllegalStateException("Connector delivery reservation is already being started");
            }
            try {
                requireOpen();
            } catch (RuntimeException | Error e) {
                startClaimed.set(false);
                throw e;
            }
        }

        private void requireStarting() {
            if (state.get() == ReservationState.STARTING) {
                return;
            }
            requireOpen();
        }

        private boolean canTransitionToTerminal() {
            ReservationState current = state.get();
            return current == ReservationState.OPEN || current == ReservationState.STARTING;
        }

        private boolean transitionToTerminal(ReservationState targetState) {
            while (true) {
                ReservationState current = state.get();
                if (current != ReservationState.OPEN && current != ReservationState.STARTING) {
                    return false;
                }
                if (state.compareAndSet(current, targetState)) {
                    return true;
                }
            }
        }
    }

    private final class DeliveryTask implements ConnectorDelivery {
        private final ChannelDispatcher dispatcher;
        private final DeliveryCost cost;
        private final DeliveryContext context;
        private final boolean connectorLease;
        private final Runnable action;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile Thread thread;
        private boolean executionFinished;
        private boolean releaseRequested;
        private MessagingRejectedException cancellationFailure;

        private DeliveryTask(ChannelDispatcher dispatcher,
                             DeliveryCost cost,
                             DeliveryContext context,
                             boolean connectorLease,
                             Runnable action) {
            this.dispatcher = dispatcher;
            this.cost = cost;
            this.context = context;
            this.connectorLease = connectorLease;
            this.action = action;
        }

        @Override
        public boolean isDone() {
            return completion.isDone();
        }

        @Override
        public boolean isCurrentThread() {
            return thread == Thread.currentThread();
        }

        @Override
        public void await() throws InterruptedException {
            try {
                completion.get();
            } catch (ExecutionException e) {
                rethrow(e);
            }
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            try {
                completion.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                return true;
            } catch (ExecutionException e) {
                rethrow(e);
                return true;
            } catch (TimeoutException e) {
                return false;
            }
        }

        @Override
        public void cancel() {
            cancel(MessagingRejectedException.Reason.CANCELLED,
                   "Messaging delivery was cancelled");
        }

        @Override
        public void close() {
            if (!connectorLease) {
                return;
            }
            dispatcher.releaseConnector(this);
        }

        private void cancel(MessagingRejectedException.Reason reason, String message) {
            dispatcher.cancel(this, reason, message);
        }

        private void finished(Throwable failure) {
            dispatcher.finished(this, failure);
        }

        private void complete(Throwable failure) {
            if (failure == null) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(failure);
            }
        }

        private void requestCancellation(MessagingRejectedException.Reason reason, String message) {
            if (cancellationFailure == null) {
                cancellationFailure = rejected(channel(), reason, message);
            }
        }

        private void thread(Thread thread) {
            this.thread = thread;
        }

        private Thread thread() {
            return thread;
        }

        private String channel() {
            return dispatcher.channel;
        }

        private DeliveryCost cost() {
            return cost;
        }

        private DeliveryContext context() {
            return context;
        }

        private Runnable action() {
            return action;
        }

        private void rethrow(ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new MessagingException("Messaging delivery failed on channel " + channel(), cause);
        }
    }

    private static final class DeliveryContext {
        private final DeliveryEngine owner;
        private final String channel;
        private final List<DeliveryNode> path;
        private final boolean connectorLease;
        private final List<Message<?>> retainedMessages;
        private int dispatchDepth;

        private DeliveryContext(DeliveryEngine owner,
                                String channel,
                                List<DeliveryNode> parentPath,
                                boolean connectorLease,
                                List<? extends Message<?>> retainedMessages) {
            this.owner = owner;
            this.channel = channel;
            List<DeliveryNode> path = new ArrayList<>(parentPath.size() + 1);
            path.addAll(parentPath);
            path.add(new DeliveryNode(owner, channel));
            this.path = List.copyOf(path);
            this.connectorLease = connectorLease;
            this.retainedMessages = connectorLease ? List.copyOf(retainedMessages) : List.of();
        }

        private boolean connectorLease(DeliveryEngine targetOwner, String targetChannel) {
            return connectorLease
                    && owner == targetOwner
                    && channel.equals(targetChannel)
                    && dispatchDepth == 0;
        }

        private void dispatchWithinLease(List<? extends Message<?>> messages, Runnable action) {
            if (dispatchDepth != 0) {
                throw new MessagingException("Cyclic synchronous messaging emission: "
                                                     + String.join(" -> ", pathNames()) + " -> " + channel);
            }
            if (!retains(messages)) {
                throw owner.rejected(
                        channel,
                        MessagingRejectedException.Reason.OVERSIZED,
                        "Connector emission is not part of its retained delivery lease on channel " + channel);
            }
            dispatchDepth++;
            try {
                action.run();
            } finally {
                dispatchDepth--;
            }
        }

        private boolean retains(List<? extends Message<?>> messages) {
            if (messages.size() > retainedMessages.size()) {
                return false;
            }
            boolean[] matched = new boolean[retainedMessages.size()];
            for (Message<?> message : messages) {
                boolean found = false;
                for (int i = 0; i < retainedMessages.size(); i++) {
                    if (!matched[i] && retainedMessages.get(i) == message) {
                        matched[i] = true;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }

        private List<DeliveryNode> path() {
            return path;
        }

        private List<String> pathNames() {
            return path.stream().map(DeliveryNode::channel).toList();
        }
    }

    private record DeliveryNode(DeliveryEngine owner, String channel) {
    }

    private record DeliveryCost(long messages, long bytes) {
    }

    private record DeliveryBytes(long knownBytes, Optional<Message<?>> firstUnknown) {
    }

    private enum AdmissionMode {
        WAIT,
        TRY,
        NESTED
    }

    private enum ReservationState {
        OPEN,
        STARTING,
        STARTED,
        CLOSED,
        SHUTDOWN
    }
}
