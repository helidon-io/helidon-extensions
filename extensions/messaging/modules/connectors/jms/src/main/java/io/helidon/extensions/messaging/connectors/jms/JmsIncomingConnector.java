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

package io.helidon.extensions.messaging.connectors.jms;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.extensions.messaging.ConnectorDelivery;
import io.helidon.extensions.messaging.ConnectorDeliveryReservation;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.IncomingConnectorContext;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.MessagingRejectedException;

import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.JMSRuntimeException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.Topic;

/**
 * Incoming JMS connector support.
 */
final class JmsIncomingConnector {
    private JmsIncomingConnector() {
    }

    static IncomingConnector create(JmsConnectorConfig config,
                                    JmsConnectionFactoryResolver connectionFactoryResolver) {
        return new Connector(Objects.requireNonNull(config), Objects.requireNonNull(connectionFactoryResolver));
    }

    static Duration jitter(Duration delay,
                           Duration maximum,
                           double variation,
                           double sample) {
        Objects.requireNonNull(delay);
        Objects.requireNonNull(maximum);
        if (delay.isZero() || delay.isNegative() || maximum.isZero() || maximum.isNegative()) {
            throw new IllegalArgumentException("JMS reconnect delays must be positive");
        }
        if (!(variation >= 0 && variation < 1)) {
            throw new IllegalArgumentException("JMS reconnect jitter must be at least 0 and less than 1");
        }
        if (!(sample >= 0 && sample <= 1)) {
            throw new IllegalArgumentException("JMS reconnect jitter sample must be between 0 and 1");
        }

        Duration cappedDelay = delay.compareTo(maximum) > 0 ? maximum : delay;
        if (variation == 0) {
            return cappedDelay;
        }

        long delayNanos;
        try {
            delayNanos = cappedDelay.toNanos();
        } catch (ArithmeticException e) {
            // Duration can represent a much larger value than the nanosecond-based wait APIs.
            return cappedDelay;
        }
        long maximumNanos = saturatedNanos(maximum);
        double multiplier = (1 - variation) + 2 * variation * sample;
        double randomizedNanos = delayNanos * multiplier;
        long nanos = randomizedNanos >= maximumNanos
                ? maximumNanos
                : Math.max(1, (long) randomizedNanos);
        return Duration.ofNanos(nanos);
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static final class Connector implements IncomingConnector {
        private static final Duration MAX_ADMISSION_RETRY_DELAY = Duration.ofMillis(100);

        private final JmsConnectorConfig config;
        private final JmsConnectionSupport connectionSupport;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private final AtomicBoolean forceCloseRequested = new AtomicBoolean();
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean runStarted = new AtomicBoolean();
        private final AtomicReference<Thread> sourceOwner = new AtomicReference<>();
        private final AtomicReference<Resources> activeResources = new AtomicReference<>();
        private final AtomicReference<ConnectorDelivery> activeDelivery = new AtomicReference<>();
        private final AtomicReference<RuntimeException> connectorCloseFailure = new AtomicReference<>();
        private final AtomicLong requestedCloseDeadline = new AtomicLong(Long.MAX_VALUE);
        private final Set<Resources> ownedResources = ConcurrentHashMap.newKeySet();
        private final CountDownLatch acquisitionStopSignal = new CountDownLatch(1);
        private final CountDownLatch runCompletion = new CountDownLatch(1);
        private final ReentrantLock deliveryLock = new ReentrantLock();
        private final Condition deliveryStateChanged = deliveryLock.newCondition();
        private volatile IncomingConnectorContext context;
        private boolean deliveryStarting;

        private Connector(JmsConnectorConfig config,
                          JmsConnectionFactoryResolver connectionFactoryResolver) {
            this.connectionSupport = new JmsConnectionSupport(config, connectionFactoryResolver);
            this.config = connectionSupport.runtimeConfig();
        }

        @Override
        public void run(IncomingConnectorContext context) {
            IncomingConnectorContext runContext = Objects.requireNonNull(context);
            if (!runStarted.compareAndSet(false, true)) {
                throw new IllegalStateException("JMS incoming connector can only be run once");
            }
            this.context = runContext;
            Thread owner = Thread.currentThread();
            sourceOwner.set(owner);
            Resources resources = null;
            Throwable primaryFailure = null;
            try {
                if (closed.get() || draining.get()) {
                    return;
                }
                if (runContext.maxDeliveryMessages() < 1) {
                    throw new MessagingException("JMS delivery message limit must be greater than zero");
                }
                resources = connectWithRetry(null, false);
                if (resources == null || !runContext.awaitRunning() || closed.get() || draining.get()) {
                    return;
                }
                resources = connectWithRetry(resources, true);
                if (resources == null) {
                    return;
                }

                while (!closed.get() && !draining.get()) {
                    if (resources.broken()) {
                        closeForReconnect(resources);
                        resources = connectWithRetry(null, true);
                        if (resources == null) {
                            return;
                        }
                    }
                    DeliveryResult result = receiveAndDeliver(resources);
                    if (result == DeliveryResult.STOP) {
                        return;
                    }
                    if (result == DeliveryResult.RECONNECT) {
                        closeForReconnect(resources);
                        resources = connectWithRetry(null, true);
                        if (resources == null) {
                            return;
                        }
                    }
                }
            } catch (MessagingRejectedException e) {
                if (!isLifecycleCancellation(e)) {
                    primaryFailure = e;
                    throw e;
                }
                if (causedByInterruption(e)) {
                    Thread.currentThread().interrupt();
                }
            } catch (RuntimeException | Error e) {
                if (!closed.get()) {
                    primaryFailure = e;
                    throw e;
                }
            } finally {
                closed.set(true);
                draining.set(true);
                acquisitionStopSignal.countDown();
                connectionSupport.forceClose();
                ConnectorDelivery delivery = activeDelivery.getAndSet(null);
                if (delivery != null) {
                    delivery.cancel();
                }
                Resources current = activeResources.getAndSet(null);
                RuntimeException cleanupFailure = closeResources(current == null ? resources : current,
                                                                  resourceCleanupDeadline(),
                                                                  !forceCloseRequested.get());
                if (delivery != null) {
                    delivery.close();
                }
                sourceOwner.compareAndSet(owner, null);
                runCompletion.countDown();
                if (cleanupFailure != null) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else if (!closeRequested.get()) {
                        throw cleanupFailure;
                    }
                }
            }
        }

        @Override
        public void drain() {
            draining.set(true);
            acquisitionStopSignal.countDown();
        }

        @Override
        public void forceClose() {
            forceCloseRequested.set(true);
            requestClose();
            completeBeforeRun();
        }

        @Override
        public void close() {
            long closeDeadline = deadline(config.closeTimeout());
            requestedCloseDeadline.accumulateAndGet(closeDeadline, Math::min);
            requestClose();
            completeBeforeRun();
            if (sourceOwner.get() == Thread.currentThread()) {
                return;
            }
            ConnectorDelivery delivery = awaitDeliveryPublication(closeDeadline);
            if (delivery != null && delivery.isCurrentThread()) {
                return;
            }
            boolean interrupted = false;
            try {
                long remaining = remainingNanos(closeDeadline);
                if (remaining == 0 || !runCompletion.await(remaining, TimeUnit.NANOSECONDS)) {
                    throw new MessagingException("Timed out closing JMS incoming connector for channel "
                                                         + config.channel());
                }
            } catch (InterruptedException e) {
                interrupted = true;
                throw new MessagingException("JMS incoming connector close was interrupted", e);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            RuntimeException failure = null;
            try {
                connectionSupport.awaitClose(closeDeadline);
            } catch (RuntimeException e) {
                failure = mergeFailure(failure, e);
            }
            failure = mergeFailure(failure, awaitOwnedResources(closeDeadline));
            RuntimeException runFailure = connectorCloseFailure.get();
            failure = mergeFailure(failure, runFailure);
            if (failure != null) {
                throw failure;
            }
        }

        private DeliveryResult receiveAndDeliver(Resources resources) {
            ConnectorDeliveryReservation reservation = awaitReservation();
            if (reservation == null) {
                return DeliveryResult.STOP;
            }
            try (reservation) {
                if (closed.get() || draining.get()) {
                    return DeliveryResult.STOP;
                }

                jakarta.jms.Message nativeMessage;
                try {
                    nativeMessage = resources.consumer().receive(receiveTimeoutMillis(config.receiveTimeout()));
                } catch (JMSException | JMSRuntimeException e) {
                    resources.broken(e);
                    return closed.get() || draining.get() ? DeliveryResult.STOP : DeliveryResult.RECONNECT;
                }
                if (nativeMessage == null) {
                    return resources.broken() ? DeliveryResult.RECONNECT : DeliveryResult.CONTINUE;
                }
                if (closed.get() || draining.get() || resources.broken()) {
                    abandon(resources, null);
                    return closed.get() || draining.get() ? DeliveryResult.STOP : DeliveryResult.RECONNECT;
                }

                JmsMessage<?> message;
                try {
                    message = JmsMessageMapper.fromJmsMessage(nativeMessage, config.allowObjectMessages());
                } catch (RuntimeException e) {
                    abandon(resources, e);
                    if (resources.broken()) {
                        return DeliveryResult.RECONNECT;
                    }
                    throw e;
                }

                ConnectorDelivery delivery;
                try {
                    delivery = startDelivery(reservation, MessageBatch.create(message));
                } catch (RuntimeException | Error e) {
                    abandon(resources, e);
                    throw e;
                }
                try {
                    if (closed.get()) {
                        delivery.cancel();
                    }
                    try {
                        delivery.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        abandon(resources, e);
                        if (closed.get()) {
                            return DeliveryResult.STOP;
                        }
                        throw new MessagingException("JMS incoming message processing was interrupted", e);
                    } catch (RuntimeException | Error e) {
                        abandon(resources, e);
                        throw e;
                    }

                    if (closed.get()) {
                        abandon(resources, null);
                        return DeliveryResult.STOP;
                    }
                    if (resources.broken()) {
                        abandon(resources, null);
                        return DeliveryResult.RECONNECT;
                    }
                    try {
                        settle(resources, nativeMessage);
                        return DeliveryResult.CONTINUE;
                    } catch (JMSException | JMSRuntimeException e) {
                        resources.broken(e);
                        abandon(resources, e);
                        return DeliveryResult.RECONNECT;
                    }
                } finally {
                    activeDelivery.compareAndSet(delivery, null);
                    signalDeliveryStateChanged();
                    delivery.close();
                }
            }
        }

        private ConnectorDelivery startDelivery(ConnectorDeliveryReservation reservation,
                                                 MessageBatch<?> batch) {
            deliveryLock.lock();
            try {
                if (closed.get() || draining.get()) {
                    throw new MessagingRejectedException(
                            context.channel(),
                            MessagingRejectedException.Reason.CANCELLED,
                            "JMS delivery admission was cancelled on channel " + context.channel());
                }
                deliveryStarting = true;
            } finally {
                deliveryLock.unlock();
            }

            ConnectorDelivery delivery;
            try {
                delivery = reservation.start(batch);
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
                    throw new IllegalStateException("JMS incoming connector already has an active delivery");
                }
                if (closed.get()) {
                    delivery.cancel();
                }
                return delivery;
            } finally {
                deliveryStateChanged.signalAll();
                deliveryLock.unlock();
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

        private void signalDeliveryStateChanged() {
            deliveryLock.lock();
            try {
                deliveryStateChanged.signalAll();
            } finally {
                deliveryLock.unlock();
            }
        }

        private ConnectorDeliveryReservation awaitReservation() {
            Duration retryDelay = min(config.receiveTimeout(), MAX_ADMISSION_RETRY_DELAY);
            while (!closed.get() && !draining.get()) {
                Optional<ConnectorDeliveryReservation> reservation = context.tryReserveDelivery();
                if (reservation.isPresent()) {
                    return reservation.get();
                }
                try {
                    if (acquisitionStopSignal.await(retryDelay.toNanos(), TimeUnit.NANOSECONDS)) {
                        return null;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (closed.get() || draining.get()) {
                        return null;
                    }
                    throw new MessagingRejectedException(
                            context.channel(),
                            MessagingRejectedException.Reason.CANCELLED,
                            "JMS delivery reservation wait was interrupted on channel " + context.channel(),
                            e);
                }
            }
            return null;
        }

        private Resources connectWithRetry(Resources candidate, boolean startConnection) {
            Duration delay = config.reconnectInitialDelay();
            Resources resources = candidate;
            while (!closed.get() && !draining.get()) {
                try {
                    if (resources == null) {
                        resources = connect();
                    }
                    if (startConnection) {
                        start(resources);
                    }
                    if (closed.get() || draining.get()) {
                        closeResources(resources, resourceCleanupDeadline(), false);
                        return null;
                    }
                    return resources;
                } catch (JmsResourceCleanupException e) {
                    if (resources != null) {
                        RuntimeException cleanupFailure = cleanupFailedSetup(resources);
                        resources = null;
                        if (cleanupFailure != null) {
                            cleanupFailure.addSuppressed(e);
                            throw new JmsResourceCleanupException(
                                    "Cannot clean up failed JMS connection setup for channel " + config.channel(),
                                    cleanupFailure);
                        }
                    }
                    throw e;
                } catch (JMSException | RuntimeException e) {
                    if (resources != null) {
                        RuntimeException cleanupFailure = cleanupFailedSetup(resources);
                        resources = null;
                        if (cleanupFailure != null) {
                            cleanupFailure.addSuppressed(e);
                            throw new JmsResourceCleanupException(
                                    "Cannot clean up failed JMS connection setup for channel " + config.channel(),
                                    cleanupFailure);
                        }
                    }
                    if (closed.get() || draining.get()) {
                        return null;
                    }
                    if (!awaitReconnect(jitter(delay))) {
                        return null;
                    }
                    delay = doubleDelay(delay, config.reconnectMaxDelay());
                } catch (Error e) {
                    if (resources != null) {
                        RuntimeException cleanupFailure = cleanupFailedSetup(resources);
                        if (cleanupFailure != null) {
                            e.addSuppressed(cleanupFailure);
                        }
                    }
                    throw e;
                }
            }
            return null;
        }

        private Resources connect() throws JMSException {
            JmsConnectionSupport.ConnectionHandle connectionHandle = connectionSupport.createConnection();
            Connection connection = connectionHandle.connection();

            Resources resources = new Resources(connectionHandle);
            ownedResources.add(resources);
            if (!activeResources.compareAndSet(null, resources)) {
                resources.closeAsync();
                throw new IllegalStateException("JMS incoming connector already owns active resources");
            }
            try {
                if (closed.get() || draining.get()) {
                    resources.closeAsync();
                    return resources;
                }
                if (config.clientId().isPresent()) {
                    connectionSupport.executeSetup("client ID setup", () -> {
                        connection.setClientID(config.clientId().orElseThrow());
                        return null;
                    });
                }
                connectionSupport.executeSetup("exception listener setup", () -> {
                    connection.setExceptionListener(resources::broken);
                    return null;
                });
                Session session = connectionSupport.executeSetup(
                        "session creation",
                        () -> connection.createSession(config.transacted(),
                                                       config.transacted()
                                                               ? Session.SESSION_TRANSACTED
                                                               : Session.CLIENT_ACKNOWLEDGE));
                resources.session(session);
                requireUsable(resources);
                Destination destination = connectionSupport.executeSetup(
                        "destination resolution",
                        () -> JmsResourceResolver.resolveDestination(session, config));
                requireUsable(resources);
                MessageConsumer consumer = connectionSupport.executeSetup(
                        "consumer creation",
                        () -> createConsumer(session, destination));
                resources.consumer(consumer);
                requireUsable(resources);
                return resources;
            } catch (JMSException | RuntimeException e) {
                RuntimeException cleanupFailure = cleanupFailedSetup(resources);
                if (cleanupFailure != null) {
                    cleanupFailure.addSuppressed(e);
                    throw new JmsResourceCleanupException("Cannot clean up failed JMS connection setup for channel "
                                                                  + config.channel(), cleanupFailure);
                }
                throw e;
            } catch (Error e) {
                RuntimeException cleanupFailure = cleanupFailedSetup(resources);
                if (cleanupFailure != null) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
        }

        private void start(Resources resources) throws JMSException {
            requireUsable(resources);
            connectionSupport.executeSetup("connection start", () -> {
                resources.connection().start();
                return null;
            });
            requireUsable(resources);
        }

        private RuntimeException cleanupFailedSetup(Resources resources) {
            activeResources.compareAndSet(resources, null);
            return closeResources(resources,
                                  resourceCleanupDeadline(),
                                  !closed.get() && !draining.get());
        }

        private void requireUsable(Resources resources) throws JMSException {
            if (closed.get() || draining.get()) {
                throw new IllegalStateException("JMS incoming connector is closed");
            }
            if (resources.broken()) {
                throw new JMSException("JMS connection failed during setup for channel " + config.channel());
            }
        }

        private MessageConsumer createConsumer(Session session, Destination destination) throws JMSException {
            String selector = config.messageSelector().orElse(null);
            if (config.durable()) {
                if (!(destination instanceof Topic topic)) {
                    throw new MessagingException("Durable JMS destination is not a Topic for channel "
                                                         + config.channel());
                }
                return session.createDurableConsumer(topic,
                                                     config.subscriptionName().orElseThrow(),
                                                     selector,
                                                     config.noLocal());
            }
            return session.createConsumer(destination, selector, config.noLocal());
        }

        private void closeForReconnect(Resources resources) {
            activeResources.compareAndSet(resources, null);
            RuntimeException failure = closeResources(resources, deadline(config.closeTimeout()), true);
            if (failure != null) {
                throw new JmsResourceCleanupException("Cannot clean up stale JMS resources for channel "
                                                              + config.channel(), failure);
            }
        }

        private void settle(Resources resources, jakarta.jms.Message message) throws JMSException {
            if (config.transacted()) {
                resources.session().commit();
            } else {
                message.acknowledge();
            }
        }

        private void abandon(Resources resources, Throwable primaryFailure) {
            if (closed.get() || resources.broken()) {
                RuntimeException closeFailure = closeResources(resources,
                                                               resourceCleanupDeadline(),
                                                               false);
                if (primaryFailure != null && closeFailure != null) {
                    primaryFailure.addSuppressed(closeFailure);
                }
                return;
            }
            try {
                if (config.transacted()) {
                    resources.session().rollback();
                } else {
                    resources.session().recover();
                }
            } catch (JMSException | JMSRuntimeException e) {
                resources.broken(e);
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(e);
                }
                closeResources(resources, resourceCleanupDeadline(), false);
            }
        }

        private boolean awaitReconnect(Duration delay) {
            try {
                return !acquisitionStopSignal.await(saturatedNanos(delay), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (closed.get() || draining.get()) {
                    return false;
                }
                throw new MessagingException("JMS reconnect was interrupted", e);
            }
        }

        private void requestClose() {
            closeRequested.set(true);
            closed.set(true);
            draining.set(true);
            acquisitionStopSignal.countDown();
            connectionSupport.forceClose();
            ConnectorDelivery delivery = activeDelivery.get();
            if (delivery != null) {
                delivery.cancel();
            }
            Resources resources = activeResources.get();
            if (resources != null) {
                resources.closeAsync();
            }
            Thread owner = sourceOwner.get();
            if (owner != null && owner != Thread.currentThread()) {
                owner.interrupt();
            }
        }

        private void completeBeforeRun() {
            if (runStarted.compareAndSet(false, true)) {
                runCompletion.countDown();
            }
        }

        private ConnectorDelivery awaitDeliveryPublication(long closeDeadline) {
            deliveryLock.lock();
            try {
                while (deliveryStarting) {
                    long remaining = remainingNanos(closeDeadline);
                    if (remaining == 0) {
                        throw new MessagingException("Timed out closing JMS incoming connector while waiting for "
                                                             + "delivery admission on channel " + config.channel());
                    }
                    try {
                        deliveryStateChanged.awaitNanos(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MessagingException("JMS incoming connector close was interrupted", e);
                    }
                }
                return activeDelivery.get();
            } finally {
                deliveryLock.unlock();
            }
        }

        private RuntimeException closeResources(Resources resources,
                                                long closeDeadline,
                                                boolean reportFailure) {
            if (resources == null) {
                return null;
            }
            resources.closeAsync();
            try {
                long remaining = remainingNanos(closeDeadline);
                if (remaining == 0 || !resources.awaitClosed(Duration.ofNanos(remaining))) {
                    return reportFailure
                            ? new MessagingException("Timed out closing JMS resources for channel " + config.channel())
                            : null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return reportFailure ? new MessagingException("JMS resource close was interrupted", e) : null;
            }
            RuntimeException closeFailure = resources.closeException(config.channel());
            ownedResources.remove(resources);
            if (closeFailure == null) {
                return null;
            }
            recordConnectorCloseFailure(closeFailure);
            return reportFailure ? closeFailure : null;
        }

        private RuntimeException awaitOwnedResources(long closeDeadline) {
            RuntimeException primary = null;
            for (Resources resources : List.copyOf(ownedResources)) {
                RuntimeException failure = closeResources(resources, closeDeadline, true);
                if (failure != null) {
                    primary = mergeFailure(primary, failure);
                }
            }
            return primary;
        }

        private long resourceCleanupDeadline() {
            if (forceCloseRequested.get()) {
                return System.nanoTime();
            }
            if (closeRequested.get()) {
                return requestedCloseDeadline.get();
            }
            return deadline(config.closeTimeout());
        }

        private boolean isLifecycleCancellation(MessagingRejectedException failure) {
            return (closed.get() || draining.get())
                    && (failure.reason() == MessagingRejectedException.Reason.CANCELLED
                            || failure.reason() == MessagingRejectedException.Reason.SHUTDOWN);
        }

        private Duration jitter(Duration delay) {
            double variation = config.reconnectJitter();
            double sample = variation == 0 ? 0.5 : ThreadLocalRandom.current().nextDouble();
            return JmsIncomingConnector.jitter(delay, config.reconnectMaxDelay(), variation, sample);
        }

        private static long receiveTimeoutMillis(Duration timeout) {
            long secondsAsMillis;
            try {
                secondsAsMillis = Math.multiplyExact(timeout.getSeconds(), 1000);
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
            long fractionalMillis = (timeout.getNano() + 999_999L) / 1_000_000L;
            return Long.MAX_VALUE - secondsAsMillis < fractionalMillis
                    ? Long.MAX_VALUE
                    : Math.max(1, secondsAsMillis + fractionalMillis);
        }

        private static Duration min(Duration first, Duration second) {
            return first.compareTo(second) <= 0 ? first : second;
        }

        private static long deadline(Duration timeout) {
            long now = System.nanoTime();
            try {
                return Math.addExact(now, timeout.toNanos());
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }

        private static long remainingNanos(long deadline) {
            try {
                return Math.max(0, Math.subtractExact(deadline, System.nanoTime()));
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }

        private static Duration doubleDelay(Duration delay, Duration maximum) {
            if (delay.compareTo(maximum) >= 0) {
                return maximum;
            }
            try {
                Duration doubled = delay.multipliedBy(2);
                return doubled.compareTo(maximum) > 0 ? maximum : doubled;
            } catch (ArithmeticException e) {
                return maximum;
            }
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
            if (failure == null || failure == primary) {
                return primary;
            }
            if (primary == null) {
                return failure;
            }
            primary.addSuppressed(failure);
            return primary;
        }

        private void recordConnectorCloseFailure(RuntimeException failure) {
            RuntimeException primary = connectorCloseFailure.compareAndExchange(null, failure);
            if (primary != null && primary != failure) {
                synchronized (primary) {
                    primary.addSuppressed(failure);
                }
            }
        }

    }

    private static final class Resources {
        private final JmsConnectionSupport.ConnectionHandle connectionHandle;
        private final Connection connection;
        private final AtomicReference<Session> session = new AtomicReference<>();
        private final AtomicReference<MessageConsumer> consumer = new AtomicReference<>();
        private final AtomicBoolean broken = new AtomicBoolean();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        private final AtomicReference<RuntimeException> closeException = new AtomicReference<>();
        private final ReentrantLock ownershipLock = new ReentrantLock();
        private final Condition closeStateChanged = ownershipLock.newCondition();
        private boolean primaryCloseComplete;
        private int pendingLateCloses;

        private Resources(JmsConnectionSupport.ConnectionHandle connectionHandle) {
            this.connectionHandle = Objects.requireNonNull(connectionHandle);
            this.connection = connectionHandle.connection();
        }

        private Session session() {
            return Objects.requireNonNull(session.get(), "JMS session");
        }

        private Connection connection() {
            return connection;
        }

        private void session(Session session) {
            setOwned(this.session, session);
        }

        private MessageConsumer consumer() {
            return Objects.requireNonNull(consumer.get(), "JMS consumer");
        }

        private void consumer(MessageConsumer consumer) {
            setOwned(this.consumer, consumer);
        }

        private boolean broken() {
            return broken.get();
        }

        private void broken(Throwable failure) {
            broken.set(true);
            closeAsync();
        }

        private void closeAsync() {
            if (closing.compareAndSet(false, true)) {
                Thread.ofVirtual()
                        .name("helidon-messaging-jms-resource-close")
                        .inheritInheritableThreadLocals(false)
                        .start(this::close);
            }
        }

        private boolean awaitClosed(Duration timeout) throws InterruptedException {
            long remaining = timeout.toNanos();
            ownershipLock.lockInterruptibly();
            try {
                while (!primaryCloseComplete || pendingLateCloses != 0) {
                    if (remaining <= 0) {
                        return false;
                    }
                    remaining = closeStateChanged.awaitNanos(remaining);
                }
                return true;
            } finally {
                ownershipLock.unlock();
            }
        }

        private Throwable closeFailure() {
            return closeFailure.get();
        }

        private RuntimeException closeException(String channel) {
            Throwable failure = closeFailure();
            if (failure == null) {
                return null;
            }
            RuntimeException created = failure instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new MessagingException("Cannot close JMS resources for channel " + channel, failure);
            RuntimeException existing = closeException.compareAndExchange(null, created);
            return existing == null ? created : existing;
        }

        private <T extends AutoCloseable> void setOwned(AtomicReference<T> reference, T value) {
            Objects.requireNonNull(value);
            ownershipLock.lock();
            try {
                if (closing.get()) {
                    closeLate(value);
                    return;
                }
                if (!reference.compareAndSet(null, value)) {
                    throw new IllegalStateException("JMS resource was already initialized");
                }
            } finally {
                ownershipLock.unlock();
            }
        }

        private void close() {
            Throwable connectionFailure = closeOne(connectionHandle);
            AutoCloseable lateConsumer = null;
            AutoCloseable lateSession = null;
            ownershipLock.lock();
            try {
                if (connectionFailure == null) {
                    // A JMS connection owns its sessions and consumers.
                    consumer.set(null);
                    session.set(null);
                } else {
                    // A failed Connection.close may leave its children live. Fall back to closing them directly.
                    lateConsumer = consumer.getAndSet(null);
                    lateSession = session.getAndSet(null);
                }
            } finally {
                ownershipLock.unlock();
            }
            if (connectionFailure != null) {
                closeOne(lateConsumer);
                closeOne(lateSession);
            }
            ownershipLock.lock();
            try {
                primaryCloseComplete = true;
                closeStateChanged.signalAll();
            } finally {
                ownershipLock.unlock();
            }
        }

        private void closeLate(AutoCloseable closeable) {
            pendingLateCloses++;
            Thread closer;
            try {
                closer = Thread.ofVirtual()
                        .name("helidon-messaging-jms-late-resource-close")
                        .inheritInheritableThreadLocals(false)
                        .unstarted(() -> {
                            try {
                                closeOne(closeable);
                            } finally {
                                ownershipLock.lock();
                                try {
                                    pendingLateCloses--;
                                    closeStateChanged.signalAll();
                                } finally {
                                    ownershipLock.unlock();
                                }
                            }
                        });
                closer.start();
            } catch (RuntimeException | Error failure) {
                recordCloseFailure(failure);
                pendingLateCloses--;
                closeStateChanged.signalAll();
            }
        }

        private void recordCloseFailure(Throwable failure) {
            Throwable previous = closeFailure.compareAndExchange(null, failure);
            if (previous != null && previous != failure) {
                previous.addSuppressed(failure);
            }
        }

        private Throwable closeOne(AutoCloseable closeable) {
            if (closeable == null) {
                return null;
            }
            try {
                closeable.close();
                return null;
            } catch (Throwable failure) {
                recordCloseFailure(failure);
                return failure;
            }
        }
    }

    private enum DeliveryResult {
        CONTINUE,
        RECONNECT,
        STOP
    }
}
