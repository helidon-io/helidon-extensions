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

package io.helidon.extensions.messaging.connectors.aq;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.messaging.MessagingException;

/**
 * Serializes replacement of single-use resource generations. Failed operations are never replayed.
 */
final class AqResourceManager implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(AqResourceManager.class.getName());

    private final AqRuntimeConfig config;
    private final AqResourceResolver resolver;
    private final boolean incoming;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    private AqResources current;
    private AqResources opening;
    private AqResources retiring;
    private Thread recovery;
    private Throwable failure;
    private boolean terminal;
    private boolean started;
    private boolean stopped;
    private boolean closed;

    AqResourceManager(AqRuntimeConfig config, AqResourceResolver resolver, boolean incoming) {
        this.config = Objects.requireNonNull(config);
        this.resolver = Objects.requireNonNull(resolver);
        this.incoming = incoming;
    }

    void open() {
        AqResources resources;
        lock.lock();
        try {
            if (started || stopped || closed) {
                throw new MessagingException("AQ channel cannot be started more than once or after closing");
            }
            started = true;
            resources = new AqResources(config, resolver);
            opening = resources;
        } finally {
            lock.unlock();
        }
        try {
            resources.open(incoming);
            if (!publish(resources)) {
                throw new MessagingException("AQ channel closed during startup");
            }
        } catch (Throwable e) {
            resources.forceClose();
            recordFailure(e, true);
            throw e;
        }
    }

    AqResources awaitReady() {
        return awaitReady(null);
    }

    AqResources awaitReady(Duration timeout) {
        long remaining = timeout == null ? 0 : TimeUnit.NANOSECONDS.convert(timeout);
        lock.lock();
        try {
            while (true) {
                if (stopped || closed) {
                    if (timeout == null) {
                        return null;
                    }
                    throw new MessagingException("AQ channel recovery has stopped");
                }
                if (current != null) {
                    return current;
                }
                if (!started || terminal) {
                    throw new MessagingException("AQ channel connection is unavailable", failure);
                }
                try {
                    if (timeout == null) {
                        changed.await();
                    } else {
                        if (remaining <= 0) {
                            throw new MessagingException("Timed out waiting for AQ connection recovery", failure);
                        }
                        remaining = changed.awaitNanos(remaining);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException("Interrupted waiting for AQ connection recovery", e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    boolean current(AqResources generation) {
        lock.lock();
        try {
            return !stopped && !closed && current == generation;
        } finally {
            lock.unlock();
        }
    }

    boolean reconnect(AqResources failed, Throwable cause) {
        if (!config.reconnect().enabled() || !AqReconnectSupport.recoverable(cause)) {
            return false;
        }
        lock.lock();
        try {
            if (stopped || closed || terminal) {
                return false;
            }
            if (current != failed) {
                return started && (current != null || recovery != null);
            }
            failure = cause;
            current = null;
            retiring = failed;
            failed.forceClose();
            recovery = Thread.ofVirtual().name("aq-reconnect-" + config.channelName()).unstarted(this::recover);
            LOGGER.log(System.Logger.Level.WARNING, "AQ channel {0} lost its provider connection; reconnecting",
                       config.channelName());
            recovery.start();
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    void stopRecovery() {
        lock.lock();
        try {
            stopped = true;
            if (opening != null) {
                opening.forceClose();
            }
            if (recovery != null) {
                recovery.interrupt();
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void forceClose() {
        for (AqResources resources : shutdown()) {
            resources.forceClose();
        }
    }

    @Override
    public void close() {
        List<AqResources> closing = shutdown();
        closing.forEach(AqResources::forceClose);
        MessagingException closeFailure = null;
        for (AqResources resources : closing) {
            try {
                resources.close();
            } catch (MessagingException e) {
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

    private void recover() {
        long delay = config.reconnect().initialDelay().toMillis();
        long maximum = config.reconnect().maxDelay().toMillis();
        try {
            while (cleanupRetired() && pause(delay)) {
                AqResources resources = nextAttempt();
                if (resources == null) {
                    return;
                }
                try {
                    resources.open(incoming);
                    if (publish(resources)) {
                        LOGGER.log(System.Logger.Level.INFO, "AQ channel {0} reconnected", config.channelName());
                        return;
                    }
                    resources.forceClose();
                    return;
                } catch (Throwable e) {
                    resources.forceClose();
                    recordFailure(e, !AqReconnectSupport.recoverable(e));
                    lock.lock();
                    try {
                        retiring = resources;
                        opening = null;
                        if (terminal) {
                            LOGGER.log(System.Logger.Level.ERROR,
                                       "AQ channel {0} connection recovery stopped after a nonrecoverable failure",
                                       config.channelName());
                            return;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
                delay = delay >= maximum / 2 ? maximum : Math.min(maximum, delay * 2);
            }
        } catch (MessagingException e) {
            lock.lock();
            try {
                if (!stopped && !closed) {
                    failure = e;
                    terminal = true;
                    changed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private boolean cleanupRetired() {
        AqResources resources;
        lock.lock();
        try {
            if (stopped || closed) {
                return false;
            }
            resources = retiring;
        } finally {
            lock.unlock();
        }
        if (resources != null) {
            // Do not accumulate initializer threads if a provider ignores interruption or connection timeouts.
            resources.awaitCleanup();
        }
        lock.lock();
        try {
            if (stopped || closed) {
                return false;
            }
            retiring = null;
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean pause(long delay) {
        double jitter = config.reconnect().jitter();
        long millis = jitter == 0
                ? delay
                : (long) (delay * ThreadLocalRandom.current().nextDouble(1 - jitter, 1 + jitter));
        long boundedMillis = Math.max(1, Math.min(millis, config.reconnect().maxDelay().toMillis()));
        long remaining = TimeUnit.MILLISECONDS.toNanos(boundedMillis);
        lock.lock();
        try {
            while (!stopped && !closed && remaining > 0) {
                remaining = changed.awaitNanos(remaining);
            }
            return !stopped && !closed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    private AqResources nextAttempt() {
        lock.lock();
        try {
            if (stopped || closed) {
                return null;
            }
            opening = new AqResources(config, resolver);
            return opening;
        } finally {
            lock.unlock();
        }
    }

    private boolean publish(AqResources resources) {
        lock.lock();
        try {
            if (stopped || closed) {
                resources.forceClose();
                return false;
            }
            current = resources;
            opening = null;
            retiring = null;
            failure = null;
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void recordFailure(Throwable cause, boolean fatal) {
        lock.lock();
        try {
            failure = cause;
            terminal = fatal;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private List<AqResources> shutdown() {
        lock.lock();
        try {
            closed = true;
            stopped = true;
            if (recovery != null) {
                recovery.interrupt();
            }
            changed.signalAll();
            LinkedHashSet<AqResources> resources = new LinkedHashSet<>();
            if (current != null) {
                resources.add(current);
            }
            if (opening != null) {
                resources.add(opening);
            }
            if (retiring != null) {
                resources.add(retiring);
            }
            return List.copyOf(resources);
        } finally {
            lock.unlock();
        }
    }
}
