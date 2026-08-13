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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.extensions.messaging.BatchAtomicity;
import io.helidon.extensions.messaging.BatchDeliveryException;
import io.helidon.extensions.messaging.BatchItemOutcome;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.OutgoingConnector;

import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.JMSRuntimeException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

/**
 * Outgoing JMS connector support.
 */
final class JmsOutgoingConnector {
    private JmsOutgoingConnector() {
    }

    static OutgoingConnector create(JmsConnectorConfig config,
                                    JmsConnectionFactoryResolver connectionFactoryResolver) {
        return new Connector(Objects.requireNonNull(config), Objects.requireNonNull(connectionFactoryResolver));
    }

    private static final class Connector implements OutgoingConnector {
        private final JmsConnectorConfig config;
        private final JmsConnectionSupport connectionSupport;
        private final ReentrantLock operationLock = new ReentrantLock();
        private final ReentrantLock lifecycleLock = new ReentrantLock();
        private State state = State.NEW;
        private Resources resources;
        private Resources connectingResources;
        private Thread operationThread;
        private boolean closeRequested;

        private Connector(JmsConnectorConfig config,
                          JmsConnectionFactoryResolver connectionFactoryResolver) {
            this.config = config;
            this.connectionSupport = new JmsConnectionSupport(config, connectionFactoryResolver);
        }

        @Override
        public void start() {
            try {
                operationLock.lockInterruptibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("JMS outgoing connector startup was interrupted", e);
            }
            try {
                lifecycleLock.lock();
                try {
                    if (state == State.CLOSED || closeRequested) {
                        throw new IllegalStateException("JMS outgoing connector is closed");
                    }
                    if (state == State.READY) {
                        return;
                    }
                    operationThread = Thread.currentThread();
                } finally {
                    lifecycleLock.unlock();
                }

                connectWithRetry();
            } finally {
                lifecycleLock.lock();
                try {
                    operationThread = null;
                } finally {
                    lifecycleLock.unlock();
                }
                operationLock.unlock();
            }
        }

        @Override
        public BatchAtomicity batchAtomicity() {
            return config.transacted() ? BatchAtomicity.ATOMIC : BatchAtomicity.PER_MESSAGE;
        }

        @Override
        public void sendBatch(MessageBatch<?> batch) {
            Objects.requireNonNull(batch);
            try {
                operationLock.lockInterruptibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw BatchDeliveryException.notAttempted("JMS batch delivery", batch, e);
            }
            try {
                lifecycleLock.lock();
                try {
                    requireStarted();
                    operationThread = Thread.currentThread();
                } catch (RuntimeException e) {
                    throw BatchDeliveryException.notAttempted("JMS batch delivery", batch, e);
                } finally {
                    lifecycleLock.unlock();
                }

                Resources current;
                try {
                    current = readyResources();
                } catch (RuntimeException e) {
                    throw BatchDeliveryException.notAttempted("JMS batch delivery", batch, e);
                }
                if (config.transacted()) {
                    sendTransacted(current, batch);
                } else {
                    sendPerMessage(current, batch);
                }
            } finally {
                lifecycleLock.lock();
                try {
                    operationThread = null;
                } finally {
                    lifecycleLock.unlock();
                }
                operationLock.unlock();
            }
        }

        @Override
        public void forceClose() {
            Thread active;
            lifecycleLock.lock();
            try {
                closeRequested = true;
                state = State.CLOSED;
                active = operationThread;
                closeOwnedResources();
            } finally {
                lifecycleLock.unlock();
            }
            connectionSupport.forceClose();
            if (active != null && active != Thread.currentThread()) {
                active.interrupt();
            }
        }

        @Override
        public void close() {
            long deadline = deadline(config.closeTimeout());
            Thread active;
            lifecycleLock.lock();
            try {
                closeRequested = true;
                state = State.CLOSED;
                active = operationThread;
                closeOwnedResources();
            } finally {
                lifecycleLock.unlock();
            }
            connectionSupport.forceClose();
            if (active != null && active != Thread.currentThread()) {
                active.interrupt();
            }

            boolean interrupted = false;
            try {
                if (!operationLock.tryLock(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                    throw new MessagingException("Timed out closing JMS outgoing connector for channel "
                                                         + config.channel());
                }
            } catch (InterruptedException e) {
                interrupted = true;
                throw new MessagingException("JMS outgoing connector close was interrupted", e);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                lifecycleLock.lock();
                try {
                    closeOwnedResources();
                } finally {
                    lifecycleLock.unlock();
                }
                connectionSupport.awaitClose(deadline);
            } finally {
                operationLock.unlock();
            }
        }

        private Resources readyResources() {
            Resources stale = null;
            lifecycleLock.lock();
            try {
                if (resources != null && !resources.broken) {
                    return resources;
                }
                stale = resources;
                resources = null;
            } finally {
                lifecycleLock.unlock();
            }
            closeForReconnect(stale);
            return connectWithRetry();
        }

        private Resources connectWithRetry() {
            Duration delay = config.reconnectInitialDelay();
            Throwable lastFailure = null;
            while (true) {
                requireOpenForReconnect(lastFailure);
                try {
                    Resources created = connect();
                    if (promoteResources(created)) {
                        return created;
                    }
                    disposeConnecting(created);
                    lastFailure = new JMSException("JMS connection failed before it became ready");
                } catch (JmsResourceCleanupException e) {
                    throw e;
                } catch (JMSException | RuntimeException e) {
                    lastFailure = e;
                }
                requireOpenForReconnect(lastFailure);
                sleepBeforeReconnect(jitter(delay), lastFailure);
                delay = doubleDelay(delay, config.reconnectMaxDelay());
            }
        }

        private Resources connect() throws JMSException {
            Resources created = null;
            CleanupBudget cleanupBudget = new CleanupBudget(config.closeTimeout());
            try {
                JmsConnectionSupport.ConnectionHandle connectionHandle = connectionSupport.createConnection();
                Connection connection = connectionHandle.connection();
                created = new Resources(connectionHandle);
                if (!publishConnecting(created)) {
                    throw new IllegalStateException("JMS outgoing connector is closed");
                }
                if (config.clientId().isPresent()) {
                    connectionSupport.executeSetup("client ID setup", () -> {
                        connection.setClientID(config.clientId().orElseThrow());
                        return null;
                    });
                }
                Resources connecting = created;
                connectionSupport.executeSetup("exception listener setup", () -> {
                    connection.setExceptionListener(failure -> {
                        connecting.broken = true;
                        connectionSupport.closeAsync(connecting);
                    });
                    return null;
                });
                Session session = connectionSupport.executeSetup(
                        "session creation",
                        () -> connection.createSession(config.transacted(),
                                                       config.transacted()
                                                               ? Session.SESSION_TRANSACTED
                                                               : Session.AUTO_ACKNOWLEDGE));
                if (!publishSession(created, session)) {
                    throw rejectedResource("session", session, cleanupBudget.deadline());
                }
                Destination destination = connectionSupport.executeSetup(
                        "destination resolution",
                        () -> JmsResourceResolver.resolveDestination(session, config));
                requireConnecting(created);
                MessageProducer producer = connectionSupport.executeSetup(
                        "producer creation",
                        () -> session.createProducer(destination));
                if (!publishProducer(created, producer)) {
                    throw rejectedResource("producer", producer, cleanupBudget.deadline());
                }
                connectionSupport.executeSetup("connection start", () -> {
                    connection.start();
                    return null;
                });
                requireConnecting(created);
                return created;
            } catch (JMSException | RuntimeException e) {
                try {
                    disposeConnecting(created, cleanupBudget.deadline());
                } catch (JmsResourceCleanupException cleanupFailure) {
                    if (cleanupFailure != e) {
                        cleanupFailure.addSuppressed(e);
                    }
                    throw cleanupFailure;
                }
                throw e;
            } catch (Error e) {
                try {
                    disposeConnecting(created, cleanupBudget.deadline());
                } catch (JmsResourceCleanupException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
        }

        private boolean publishConnecting(Resources created) {
            lifecycleLock.lock();
            try {
                if (connectingResources != null && connectingResources != created) {
                    throw new IllegalStateException("JMS outgoing connector already owns connecting resources");
                }
                connectingResources = created;
                if (closeRequested) {
                    closeResourcesAsync(created);
                    return false;
                }
                return true;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private boolean publishSession(Resources created, Session session) {
            lifecycleLock.lock();
            try {
                if (closeRequested || connectingResources != created || created.broken) {
                    return false;
                }
                created.session = session;
                return true;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private boolean publishProducer(Resources created, MessageProducer producer) {
            lifecycleLock.lock();
            try {
                if (closeRequested || connectingResources != created || created.broken) {
                    return false;
                }
                created.producer = producer;
                return true;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private void requireConnecting(Resources created) throws JMSException {
            lifecycleLock.lock();
            try {
                if (closeRequested || connectingResources != created) {
                    throw new IllegalStateException("JMS outgoing connector is closed");
                }
                if (created.broken) {
                    throw new JMSException("JMS connection failed during setup for channel " + config.channel());
                }
            } finally {
                lifecycleLock.unlock();
            }
        }

        private boolean promoteResources(Resources created) {
            lifecycleLock.lock();
            try {
                if (closeRequested || connectingResources != created || created.broken) {
                    return false;
                }
                connectingResources = null;
                resources = created;
                state = State.READY;
                return true;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private void disposeConnecting(Resources created) {
            disposeConnecting(created, deadline(config.closeTimeout()));
        }

        private void disposeConnecting(Resources created, long cleanupDeadline) {
            if (created == null) {
                return;
            }
            boolean closeInBackground = false;
            lifecycleLock.lock();
            try {
                if (connectingResources == created) {
                    if (closeRequested) {
                        closeResourcesAsync(created);
                        closeInBackground = true;
                    } else {
                        connectingResources = null;
                    }
                }
            } finally {
                lifecycleLock.unlock();
            }
            if (!closeInBackground) {
                closeForReconnect(created, cleanupDeadline);
            }
        }

        private void closeForReconnect(Resources stale) {
            closeForReconnect(stale, deadline(config.closeTimeout()));
        }

        private void closeForReconnect(Resources stale, long cleanupDeadline) {
            if (stale == null) {
                return;
            }
            try {
                connectionSupport.closeAndAwait(stale, cleanupDeadline);
            } catch (RuntimeException failure) {
                throw cleanupFailure("Cannot clean up stale JMS resources for channel " + config.channel(), failure);
            }
        }

        private void sendPerMessage(Resources current, MessageBatch<?> batch) {
            List<BatchItemOutcome> outcomes = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                jakarta.jms.Message message;
                try {
                    message = JmsMessageMapper.toJmsMessage(current.session,
                                                            batch.get(i),
                                                            config.allowObjectMessages());
                } catch (JMSException | JMSRuntimeException e) {
                    current.broken = true;
                    outcomes.add(BatchItemOutcome.failed(i, e));
                    addNotAttempted(outcomes, i + 1, batch.size());
                    throw new BatchDeliveryException("Cannot map JMS message batch " + batch.id()
                                                             + " for channel " + config.channel(),
                                                     batch,
                                                     outcomes,
                                                     e);
                } catch (RuntimeException e) {
                    if (causedByJmsFailure(e)) {
                        current.broken = true;
                    }
                    outcomes.add(BatchItemOutcome.failed(i, e));
                    addNotAttempted(outcomes, i + 1, batch.size());
                    throw new BatchDeliveryException("Cannot map JMS message batch " + batch.id()
                                                             + " for channel " + config.channel(),
                                                     batch,
                                                     outcomes,
                                                     e);
                }
                try {
                    current.producer.send(message);
                    outcomes.add(BatchItemOutcome.succeeded(i));
                } catch (JMSException | JMSRuntimeException e) {
                    current.broken = true;
                    outcomes.add(BatchItemOutcome.indeterminate(i, e));
                    addNotAttempted(outcomes, i + 1, batch.size());
                    throw new BatchDeliveryException("Cannot send JMS message batch " + batch.id()
                                                             + " to channel " + config.channel(),
                                                     batch,
                                                     outcomes,
                                                     e);
                } catch (RuntimeException e) {
                    current.broken = true;
                    outcomes.add(BatchItemOutcome.indeterminate(i, e));
                    addNotAttempted(outcomes, i + 1, batch.size());
                    throw new BatchDeliveryException("Cannot send JMS message batch " + batch.id()
                                                             + " to channel " + config.channel(),
                                                     batch,
                                                     outcomes,
                                                     e);
                }
            }
        }

        private void sendTransacted(Resources current, MessageBatch<?> batch) {
            List<jakarta.jms.Message> messages = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                try {
                    messages.add(JmsMessageMapper.toJmsMessage(current.session,
                                                               batch.get(i),
                                                               config.allowObjectMessages()));
                } catch (JMSException | JMSRuntimeException e) {
                    current.broken = true;
                    throw mappingFailure(batch, i, e);
                } catch (RuntimeException e) {
                    if (causedByJmsFailure(e)) {
                        current.broken = true;
                    }
                    throw mappingFailure(batch, i, e);
                }
            }

            try {
                for (jakarta.jms.Message message : messages) {
                    current.producer.send(message);
                }
            } catch (JMSException | JMSRuntimeException e) {
                current.broken = true;
                throw rollbackAfterSendFailure(current, batch, e);
            } catch (RuntimeException e) {
                current.broken = true;
                throw rollbackAfterSendFailure(current, batch, e);
            }

            try {
                current.session.commit();
            } catch (JMSException | JMSRuntimeException e) {
                current.broken = true;
                rollbackBestEffort(current, e);
                throw BatchDeliveryException.indeterminate("JMS transactional batch commit", batch, e);
            } catch (RuntimeException e) {
                current.broken = true;
                rollbackBestEffort(current, e);
                throw BatchDeliveryException.indeterminate("JMS transactional batch commit", batch, e);
            }
        }

        private BatchDeliveryException rollbackAfterSendFailure(Resources current,
                                                                 MessageBatch<?> batch,
                                                                 Throwable failure) {
            try {
                current.session.rollback();
                return atomicFailed(batch, failure);
            } catch (JMSException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                return BatchDeliveryException.indeterminate("JMS transactional batch delivery", batch, failure);
            }
        }

        private void rollbackBestEffort(Resources current, Throwable failure) {
            try {
                current.session.rollback();
            } catch (JMSException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }

        private BatchDeliveryException mappingFailure(MessageBatch<?> batch, int failedIndex, Throwable failure) {
            List<BatchItemOutcome> outcomes = new ArrayList<>(batch.size());
            for (int i = 0; i < failedIndex; i++) {
                outcomes.add(BatchItemOutcome.notAttempted(i));
            }
            outcomes.add(BatchItemOutcome.failed(failedIndex, failure));
            addNotAttempted(outcomes, failedIndex + 1, batch.size());
            return new BatchDeliveryException("Cannot map JMS transactional batch " + batch.id()
                                                      + " for channel " + config.channel(),
                                              batch,
                                              outcomes,
                                              failure);
        }

        private BatchDeliveryException atomicFailed(MessageBatch<?> batch, Throwable failure) {
            List<BatchItemOutcome> outcomes = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                outcomes.add(BatchItemOutcome.failed(i, failure));
            }
            return new BatchDeliveryException("JMS transactional batch was rolled back for channel " + config.channel(),
                                              batch,
                                              outcomes,
                                              failure);
        }

        private static void addNotAttempted(List<BatchItemOutcome> outcomes, int firstIndex, int size) {
            for (int i = firstIndex; i < size; i++) {
                outcomes.add(BatchItemOutcome.notAttempted(i));
            }
        }

        private IllegalStateException rejectedResource(String resourceType,
                                                        AutoCloseable resource,
                                                        long cleanupDeadline) {
            IllegalStateException failure = new IllegalStateException("JMS outgoing connector rejected a newly "
                                                                               + "created " + resourceType
                                                                               + " for channel " + config.channel());
            if (closeRequested()) {
                connectionSupport.closeAsync(resource);
                return failure;
            }
            try {
                connectionSupport.closeAndAwait(resource, cleanupDeadline);
            } catch (RuntimeException cleanupFailure) {
                throw cleanupFailure("Cannot close rejected JMS " + resourceType + " for channel "
                                             + config.channel(), cleanupFailure);
            }
            return failure;
        }

        private boolean closeRequested() {
            lifecycleLock.lock();
            try {
                return closeRequested;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private JmsResourceCleanupException cleanupFailure(String message, RuntimeException failure) {
            lifecycleLock.lock();
            try {
                closeRequested = true;
                state = State.CLOSED;
            } finally {
                lifecycleLock.unlock();
            }
            connectionSupport.forceClose();
            return new JmsResourceCleanupException(message, failure);
        }

        private static boolean causedByJmsFailure(Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof JMSException || current instanceof JMSRuntimeException) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private void closeOwnedResources() {
            List<Resources> current = new ArrayList<>(2);
            if (resources != null) {
                current.add(resources);
            }
            if (connectingResources != null && connectingResources != resources) {
                current.add(connectingResources);
            }
            current.forEach(this::closeResourcesAsync);
        }

        private void closeResourcesAsync(Resources resources) {
            connectionSupport.closeAsync(resources);
        }

        private static long deadline(Duration timeout) {
            long now = System.nanoTime();
            long nanos = timeout.toNanos();
            return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
        }

        private static long remainingNanos(long deadline) {
            return Math.max(0, deadline - System.nanoTime());
        }

        private void requireStarted() {
            if (state == State.NEW) {
                throw new IllegalStateException("JMS outgoing connector has not been started");
            }
            if (state == State.CLOSED || closeRequested) {
                throw new IllegalStateException("JMS outgoing connector is closed");
            }
        }

        private void requireOpenForReconnect(Throwable failure) {
            lifecycleLock.lock();
            try {
                if (closeRequested) {
                    IllegalStateException closed = new IllegalStateException("JMS outgoing connector is closed");
                    if (failure != null) {
                        closed.addSuppressed(failure);
                    }
                    throw closed;
                }
            } finally {
                lifecycleLock.unlock();
            }
            if (Thread.currentThread().isInterrupted()) {
                InterruptedException interrupted = new InterruptedException("JMS reconnect was interrupted");
                if (failure != null) {
                    interrupted.addSuppressed(failure);
                }
                Thread.currentThread().interrupt();
                throw new MessagingException("JMS reconnect was interrupted", interrupted);
            }
        }

        private void sleepBeforeReconnect(Duration delay, Throwable failure) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                if (failure != null) {
                    e.addSuppressed(failure);
                }
                Thread.currentThread().interrupt();
                throw new MessagingException("JMS reconnect was interrupted", e);
            }
        }

        private Duration jitter(Duration delay) {
            double variation = config.reconnectJitter();
            if (variation == 0) {
                return delay;
            }
            double multiplier = ThreadLocalRandom.current().nextDouble(1 - variation, 1 + variation);
            long nanos = Math.max(1, (long) Math.min(Long.MAX_VALUE, delay.toNanos() * multiplier));
            return Duration.ofNanos(nanos);
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

        private static void closeResources(Resources resources) {
            if (resources == null) {
                return;
            }
            synchronized (resources) {
                if (resources.closeAttempted) {
                    throwCloseFailure(resources.closeFailure);
                    return;
                }
                resources.closeAttempted = true;
                Throwable failure = close(resources.connectionHandle, null);
                if (failure != null) {
                    // A connection owns its children. Try them directly only if connection cleanup failed.
                    failure = close(resources.producer, failure);
                    failure = close(resources.session, failure);
                }
                resources.closeFailure = normalizeCloseFailure(failure);
                throwCloseFailure(resources.closeFailure);
            }
        }

        private static Throwable normalizeCloseFailure(Throwable failure) {
            if (failure == null || failure instanceof RuntimeException || failure instanceof Error) {
                return failure;
            }
            return new MessagingException("Cannot close JMS resources", failure);
        }

        private static void throwCloseFailure(Throwable failure) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        private static Throwable close(AutoCloseable closeable, Throwable previous) {
            if (closeable == null) {
                return previous;
            }
            try {
                closeable.close();
            } catch (Throwable failure) {
                if (previous == null) {
                    return failure;
                }
                previous.addSuppressed(failure);
            }
            return previous;
        }

        private enum State {
            NEW,
            READY,
            CLOSED
        }

        private static final class CleanupBudget {
            private final Duration timeout;
            private long deadline = Long.MIN_VALUE;

            private CleanupBudget(Duration timeout) {
                this.timeout = timeout;
            }

            private long deadline() {
                if (deadline == Long.MIN_VALUE) {
                    deadline = Connector.deadline(timeout);
                }
                return deadline;
            }
        }

    }

    private static final class Resources implements AutoCloseable {
        private final JmsConnectionSupport.ConnectionHandle connectionHandle;
        private final Connection connection;
        private volatile Session session;
        private volatile MessageProducer producer;
        private volatile boolean broken;
        private volatile boolean closeAttempted;
        private volatile Throwable closeFailure;

        private Resources(JmsConnectionSupport.ConnectionHandle connectionHandle) {
            this.connectionHandle = connectionHandle;
            this.connection = connectionHandle.connection();
        }

        @Override
        public void close() {
            Connector.closeResources(this);
        }
    }

}
