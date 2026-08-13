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

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.extensions.messaging.MessagingException;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;

final class JmsConnectionSupport {
    private final JmsConnectorConfig config;
    private final JmsConnectionFactoryResolver resolver;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition activityChanged = lock.newCondition();
    private final Set<ConnectionAttempt> attempts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<SetupAttempt<?>> setupAttempts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CloseAttempt> activeCloses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<AutoCloseable, CloseAttempt> activeCloseAttempts = new IdentityHashMap<>();
    private final AtomicLong closeSequence = new AtomicLong();
    private Throwable closeFailure;
    private boolean closeRequested;

    JmsConnectionSupport(JmsConnectorConfig config, JmsConnectionFactoryResolver resolver) {
        this.config = Objects.requireNonNull(config);
        this.resolver = Objects.requireNonNull(resolver);
    }

    ConnectionHandle createConnection() throws JMSException {
        ConnectionAttempt attempt = new ConnectionAttempt();
        lock.lock();
        try {
            if (closeRequested) {
                throw new IllegalStateException("JMS connector is closed for channel " + config.channel());
            }
            attempts.add(attempt);
        } finally {
            lock.unlock();
        }
        attempt.start();
        return attempt.await();
    }

    <T> T executeSetup(String operation, SetupOperation<T> setup) throws JMSException {
        SetupAttempt<T> attempt = new SetupAttempt<>(operation, setup);
        lock.lock();
        try {
            if (closeRequested) {
                throw new IllegalStateException("JMS connector is closed for channel " + config.channel());
            }
            setupAttempts.add(attempt);
        } finally {
            lock.unlock();
        }
        attempt.start();
        return attempt.await();
    }

    void forceClose() {
        List<ConnectionAttempt> currentAttempts;
        List<SetupAttempt<?>> currentSetupAttempts;
        lock.lock();
        try {
            closeRequested = true;
            currentAttempts = List.copyOf(attempts);
            currentSetupAttempts = List.copyOf(setupAttempts);
        } finally {
            lock.unlock();
        }
        currentAttempts.forEach(ConnectionAttempt::abandon);
        currentSetupAttempts.forEach(SetupAttempt::abandon);
    }

    void closeAsync(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        CloseAttempt attempt;
        lock.lock();
        try {
            attempt = activeCloseAttempts.get(resource);
            if (attempt == null) {
                attempt = new CloseAttempt(resource);
                activeCloseAttempts.put(resource, attempt);
                activeCloses.add(attempt);
            }
        } finally {
            lock.unlock();
        }
        attempt.start();
    }

    void closeAndAwait(AutoCloseable resource, long deadline) {
        if (resource == null) {
            return;
        }
        CloseAttempt attempt;
        lock.lock();
        try {
            attempt = activeCloseAttempts.get(resource);
            if (attempt == null) {
                attempt = new CloseAttempt(resource);
                activeCloseAttempts.put(resource, attempt);
                activeCloses.add(attempt);
            }
        } finally {
            lock.unlock();
        }
        attempt.start();
        attempt.await(deadline);
    }

    void awaitClose(long deadline) {
        forceClose();
        boolean interrupted = false;
        lock.lock();
        try {
            while (!attempts.isEmpty() || !setupAttempts.isEmpty() || !activeCloses.isEmpty()) {
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (remaining == 0) {
                    interruptActivity();
                    throw new MessagingException("Timed out closing JMS resources for channel " + config.channel());
                }
                try {
                    activityChanged.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    interruptActivity();
                    throw new MessagingException("JMS resource close was interrupted", e);
                }
            }
            throwCloseFailure();
        } finally {
            lock.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void interruptActivity() {
        attempts.forEach(ConnectionAttempt::interrupt);
        setupAttempts.forEach(SetupAttempt::interrupt);
        activeCloses.forEach(CloseAttempt::interrupt);
    }

    private void attemptFinished(ConnectionAttempt attempt) {
        lock.lock();
        try {
            attempts.remove(attempt);
            activityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void setupFinished(SetupAttempt<?> attempt) {
        lock.lock();
        try {
            setupAttempts.remove(attempt);
            activityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void recordCloseFailure(Throwable failure) {
        lock.lock();
        try {
            if (closeFailure == null) {
                closeFailure = failure;
            } else if (closeFailure != failure) {
                closeFailure.addSuppressed(failure);
            }
            activityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void closeFinished(CloseAttempt attempt, Throwable failure) {
        lock.lock();
        try {
            activeCloses.remove(attempt);
            if (activeCloseAttempts.get(attempt.resource) == attempt) {
                activeCloseAttempts.remove(attempt.resource);
            }
            if (failure != null) {
                if (closeFailure == null) {
                    closeFailure = failure;
                } else if (closeFailure != failure) {
                    closeFailure.addSuppressed(failure);
                }
            }
            activityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void throwCloseFailure() {
        if (closeFailure == null) {
            return;
        }
        if (closeFailure instanceof Error error) {
            throw error;
        }
        throw new MessagingException("Cannot close JMS resources for channel " + config.channel(), closeFailure);
    }

    private final class ConnectionAttempt {
        private final CompletableFuture<ConnectionHandle> result = new CompletableFuture<>();
        private final AtomicReference<ConnectionHandle> produced = new AtomicReference<>();
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final Thread worker = Thread.ofVirtual()
                .name("jms-connection-acquisition-" + config.channel())
                .inheritInheritableThreadLocals(false)
                .unstarted(this::acquire);

        private void start() {
            worker.start();
        }

        private ConnectionHandle await() throws JMSException {
            try {
                return result.get();
            } catch (InterruptedException e) {
                abandon();
                Thread.currentThread().interrupt();
                throw new MessagingException("JMS connection acquisition was interrupted for channel "
                                                     + config.channel(), e);
            } catch (ExecutionException e) {
                Throwable failure = e.getCause();
                if (failure instanceof JMSException jmsException) {
                    throw jmsException;
                }
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new MessagingException("Cannot create JMS connection for channel " + config.channel(), failure);
            }
        }

        private void acquire() {
            try {
                ConnectionFactory factory = Objects.requireNonNull(resolver.resolve(config),
                                                                    "JMS connection factory resolver returned null");
                if (abandoned.get()) {
                    return;
                }
                Connection connection = Objects.requireNonNull(create(factory),
                                                                "JMS connection factory returned null");
                ConnectionHandle handle = new ConnectionHandle(connection);
                produced.set(handle);
                if (abandoned.get() || !result.complete(handle)) {
                    closeAsync(handle);
                }
            } catch (Throwable failure) {
                if (failure instanceof JmsResourceCleanupException || failure instanceof Error) {
                    recordCloseFailure(failure);
                }
                result.completeExceptionally(failure);
            } finally {
                attemptFinished(this);
            }
        }

        private Connection create(ConnectionFactory factory) throws JMSException {
            if (config.username().isEmpty()) {
                return factory.createConnection();
            }
            char[] password = config.password().orElseThrow().clone();
            try {
                return factory.createConnection(config.username().orElseThrow(), new String(password));
            } finally {
                Arrays.fill(password, '\0');
            }
        }

        private void abandon() {
            abandoned.set(true);
            result.completeExceptionally(new IllegalStateException("JMS connector is closed for channel "
                                                                           + config.channel()));
            interrupt();
            closeAsync(produced.get());
        }

        private void interrupt() {
            worker.interrupt();
        }
    }

    static final class ConnectionHandle implements AutoCloseable {
        private final Connection connection;
        private final AtomicBoolean closeStarted = new AtomicBoolean();
        private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

        private ConnectionHandle(Connection connection) {
            this.connection = connection;
        }

        Connection connection() {
            return connection;
        }

        @Override
        public void close() throws JMSException {
            if (closeStarted.compareAndSet(false, true)) {
                try {
                    connection.close();
                    closeCompletion.complete(null);
                } catch (JMSException | RuntimeException | Error failure) {
                    closeCompletion.completeExceptionally(failure);
                    throw failure;
                }
                return;
            }
            try {
                closeCompletion.join();
            } catch (CompletionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof JMSException jmsException) {
                    throw jmsException;
                }
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new JMSException("Unexpected JMS connection close failure: " + cause);
            }
        }
    }

    @FunctionalInterface
    interface SetupOperation<T> {
        T execute() throws JMSException;
    }

    private final class SetupAttempt<T> {
        private final String operation;
        private final SetupOperation<T> setup;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final Thread worker;
        private T produced;
        private boolean available;
        private boolean claimed;
        private boolean abandoned;

        private SetupAttempt(String operation, SetupOperation<T> setup) {
            this.operation = Objects.requireNonNull(operation);
            this.setup = Objects.requireNonNull(setup);
            this.worker = Thread.ofVirtual()
                    .name("jms-setup-" + config.channel() + "-" + operation)
                    .inheritInheritableThreadLocals(false)
                    .unstarted(this::execute);
        }

        private void start() {
            worker.start();
        }

        private T await() throws JMSException {
            T value;
            try {
                value = result.get();
            } catch (InterruptedException e) {
                abandon();
                Thread.currentThread().interrupt();
                throw new MessagingException("JMS " + operation + " was interrupted for channel "
                                                     + config.channel(), e);
            } catch (ExecutionException e) {
                throwSetupFailure(e.getCause());
                throw new AssertionError("unreachable");
            }
            synchronized (this) {
                if (abandoned) {
                    throw new IllegalStateException("JMS connector is closed for channel " + config.channel());
                }
                claimed = true;
                return value;
            }
        }

        private void execute() {
            try {
                T value = setup.execute();
                boolean closeLate;
                synchronized (this) {
                    produced = value;
                    available = true;
                    closeLate = abandoned;
                    if (closeLate) {
                        claimed = true;
                    }
                }
                if (closeLate) {
                    closeLate(value);
                } else if (!result.complete(value)) {
                    synchronized (this) {
                        closeLate = abandoned && !claimed;
                    }
                    if (closeLate) {
                        closeLate(value);
                    }
                }
            } catch (Throwable failure) {
                synchronized (this) {
                    if (failure instanceof JmsResourceCleanupException || failure instanceof Error) {
                        recordCloseFailure(failure);
                    }
                }
                result.completeExceptionally(failure);
            } finally {
                setupFinished(this);
            }
        }

        private void abandon() {
            T late = null;
            synchronized (this) {
                abandoned = true;
                if (available && !claimed) {
                    late = produced;
                    claimed = true;
                }
            }
            result.completeExceptionally(new IllegalStateException("JMS connector is closed for channel "
                                                                           + config.channel()));
            interrupt();
            closeLate(late);
        }

        private void closeLate(T value) {
            if (value instanceof AutoCloseable closeable) {
                closeAsync(closeable);
            }
        }

        private void interrupt() {
            worker.interrupt();
        }

        private void throwSetupFailure(Throwable failure) throws JMSException {
            if (failure instanceof JMSException jmsException) {
                throw jmsException;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new MessagingException("Cannot complete JMS " + operation + " for channel "
                                                 + config.channel(), failure);
        }
    }

    private final class CloseAttempt {
        private final AtomicBoolean started = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AutoCloseable resource;
        private final Thread worker;

        private CloseAttempt(AutoCloseable resource) {
            this.resource = resource;
            this.worker = Thread.ofVirtual()
                    .name("jms-resource-close-" + config.channel() + "-" + closeSequence.incrementAndGet())
                    .inheritInheritableThreadLocals(false)
                    .unstarted(this::close);
        }

        private void start() {
            if (started.compareAndSet(false, true)) {
                worker.start();
            }
        }

        private void await(long deadline) {
            boolean interrupted = false;
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (remaining == 0) {
                    throw new TimeoutException();
                }
                completion.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
                worker.interrupt();
                throw new MessagingException("JMS resource close was interrupted", e);
            } catch (TimeoutException e) {
                worker.interrupt();
                throw new MessagingException("Timed out closing JMS resources for channel " + config.channel(), e);
            } catch (ExecutionException e) {
                throw new MessagingException("Cannot close JMS resources for channel " + config.channel(), e.getCause());
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void close() {
            Throwable failure = null;
            try {
                resource.close();
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (failure == null) {
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(failure);
                }
                closeFinished(this, failure);
            }
        }

        private void interrupt() {
            worker.interrupt();
        }
    }
}
