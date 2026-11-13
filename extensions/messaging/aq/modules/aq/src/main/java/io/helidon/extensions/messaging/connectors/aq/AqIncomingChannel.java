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

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.jms.JMSException;
import javax.jms.Message;

import io.helidon.messaging.ConnectorDelivery;
import io.helidon.messaging.ConnectorDeliveryReservation;
import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.spi.IncomingChannel;

/**
 * Serial receive/process/settle loop on the Messaging-owned source thread.
 */
final class AqIncomingChannel implements IncomingChannel {
    private final AqRuntimeConfig config;
    private final AqResourceManager resources;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private final AtomicReference<ConnectorDelivery> activeDelivery = new AtomicReference<>();
    private final CountDownLatch acquisitionStopped = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final ReentrantLock deliveryLock = new ReentrantLock();
    private final Condition deliveryPublished = deliveryLock.newCondition();

    private boolean deliveryStarting;
    private boolean receiving;
    private boolean receiveInterrupted;

    AqIncomingChannel(AqRuntimeConfig config, AqResourceResolver resolver) {
        this.config = Objects.requireNonNull(config);
        this.resources = new AqResourceManager(config, resolver, true);
    }

    @Override
    public void run(IncomingConnectorContext context) {
        Objects.requireNonNull(context);
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("AQ incoming channel can only run once");
        }
        owner.set(Thread.currentThread());
        Throwable failure = null;
        try {
            if (closed.get() || draining.get()) {
                return;
            }
            if (context.maxDeliveryMessages() < 1) {
                throw new MessagingException("AQ incoming channel requires capacity for one message");
            }
            try {
                resources.open();
            } catch (RuntimeException e) {
                if (closed.get() || draining.get()) {
                    return;
                }
                throw e;
            }
            if (!context.awaitRunning() || closed.get() || draining.get()) {
                return;
            }
            AqResources receivingResources = null;
            while (!closed.get() && !draining.get()) {
                AqResources current = resources.awaitReady();
                if (current == null || closed.get() || draining.get()) {
                    break;
                }
                if (current != receivingResources) {
                    try {
                        current.beginReceiving();
                    } catch (JMSException | RuntimeException e) {
                        if (recover(current, e)) {
                            continue;
                        }
                        throw e;
                    }
                    receivingResources = current;
                }
                var available = context.tryReserveDelivery();
                if (available.isEmpty()) {
                    if (acquisitionStopped.await(10, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    continue;
                }
                try (var reservation = available.orElseThrow()) {
                    if (!closed.get() && !draining.get()) {
                        receive(current, reservation);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!closed.get()) {
                failure = new MessagingException("Interrupted AQ incoming channel " + config.channelName(), e);
                throw (MessagingException) failure;
            }
        } catch (JMSException e) {
            if (!closed.get()) {
                failure = new MessagingException("AQ receive or settlement failed on " + config.channelName(), e);
                throw (MessagingException) failure;
            }
        } catch (RuntimeException | Error e) {
            if (!closed.get() || e instanceof Error) {
                failure = e;
                throw e;
            }
        } finally {
            closed.set(true);
            try {
                resources.close();
            } catch (RuntimeException | Error closeFailure) {
                if (failure != null) {
                    failure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            } finally {
                owner.set(null);
                finished.countDown();
            }
        }
    }

    @Override
    public void drain() {
        deliveryLock.lock();
        try {
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            acquisitionStopped.countDown();
            resources.stopRecovery();
            if (receiving) {
                Thread running = owner.get();
                if (!closed.get() && running != null && running != Thread.currentThread() && !running.isInterrupted()) {
                    // AQ's native JDBC dequeue can ignore consumer.close cancellation while blocked in socket I/O.
                    receiveInterrupted = true;
                    running.interrupt();
                }
            }
        } finally {
            deliveryLock.unlock();
        }
    }

    @Override
    public void forceClose() {
        closed.set(true);
        drain();
        resources.forceClose();
        ConnectorDelivery delivery = activeDelivery.get();
        if (delivery != null) {
            delivery.cancel();
        }
        Thread running = owner.get();
        if (running != null && running != Thread.currentThread()) {
            running.interrupt();
        }
    }

    @Override
    public void close() {
        drain();
        if (!started.get()) {
            closed.set(true);
            resources.close();
            return;
        }
        if (owner.get() == Thread.currentThread() || isDeliveryThread()) {
            return;
        }
        try {
            if (!finished.await(config.closeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                forceClose();
                throw new MessagingException("Timed out draining AQ channel " + config.channelName());
            }
            resources.close();
        } catch (InterruptedException e) {
            forceClose();
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while draining AQ channel " + config.channelName(), e);
        }
    }

    private void receive(AqResources current, ConnectorDeliveryReservation reservation) throws JMSException {
        Message nativeMessage;
        deliveryLock.lock();
        try {
            if (closed.get() || draining.get()) {
                return;
            }
            receiving = true;
        } finally {
            deliveryLock.unlock();
        }
        try {
            nativeMessage = current.consumer().receive(config.receiveTimeout().toMillis());
        } catch (JMSException | RuntimeException e) {
            if (draining.get() || recover(current, e)) {
                return;
            }
            throw e;
        } finally {
            deliveryLock.lock();
            try {
                receiving = false;
                if (receiveInterrupted && !closed.get()) {
                    // A message can win the drain race. Its processing and settlement must not inherit this interrupt.
                    Thread.interrupted();
                }
                receiveInterrupted = false;
            } finally {
                deliveryLock.unlock();
            }
        }
        if (nativeMessage == null || closed.get()) {
            return;
        }
        ConnectorDelivery delivery = null;
        boolean abandonRequired = true;
        Throwable failure = null;
        try {
            AqMessage<?> message;
            RuntimeException mappingFailure = null;
            try {
                message = AqMessageMapper.fromNative(nativeMessage, config);
            } catch (RuntimeException e) {
                if (e instanceof AqMappingException && recover(current, e)) {
                    // Snapshotting can still access the provider. Retiring its failed generation abandons this
                    // dequeue without dispatching a partial payload or treating connection loss as a poison message.
                    abandonRequired = false;
                    return;
                }
                mappingFailure = new MessagingException("Cannot map incoming AQ message", e);
                message = AqMessageMapper.metadataOnly(nativeMessage);
            }
            MessageBatch<?> batch = MessageBatch.create(message);
            if (closed.get()) {
                return;
            }
            delivery = startDelivery(reservation, batch, mappingFailure);
            if (closed.get()) {
                delivery.cancel();
            }
            delivery.await();
            if (closed.get()) {
                return;
            }
            try {
                if (config.transacted()) {
                    current.session().commit();
                } else {
                    nativeMessage.acknowledge();
                }
            } catch (JMSException | RuntimeException e) {
                if (!recover(current, e)) {
                    throw e;
                }
                // The old generation is retired. Let Oracle decide whether to redeliver an uncertain settlement;
                // do not roll back or repeat the handler through the replacement session.
            }
            abandonRequired = false;
        } catch (JMSException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                if (abandonRequired) {
                    abandon(current, failure);
                }
            } finally {
                if (delivery != null) {
                    activeDelivery.compareAndSet(delivery, null);
                    delivery.close();
                }
            }
        }
    }

    private ConnectorDelivery startDelivery(ConnectorDeliveryReservation reservation,
                                             MessageBatch<?> batch,
                                             RuntimeException mappingFailure) {
        deliveryLock.lock();
        try {
            deliveryStarting = true;
        } finally {
            deliveryLock.unlock();
        }
        ConnectorDelivery delivery = null;
        try {
            delivery = mappingFailure == null
                    ? reservation.start(batch)
                    : reservation.startFailed(batch, mappingFailure);
            return delivery;
        } finally {
            deliveryLock.lock();
            try {
                activeDelivery.set(delivery);
                deliveryStarting = false;
                deliveryPublished.signalAll();
            } finally {
                deliveryLock.unlock();
            }
        }
    }

    private boolean isDeliveryThread() {
        deliveryLock.lock();
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(config.closeTimeout().toMillis());
            while (deliveryStarting) {
                if (remaining <= 0) {
                    forceClose();
                    throw new MessagingException("Timed out waiting for AQ delivery handoff");
                }
                remaining = deliveryPublished.awaitNanos(remaining);
            }
            ConnectorDelivery delivery = activeDelivery.get();
            return delivery != null && delivery.isCurrentThread();
        } catch (InterruptedException e) {
            forceClose();
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while closing AQ delivery", e);
        } finally {
            deliveryLock.unlock();
        }
    }

    private boolean recover(AqResources current, Throwable failure) {
        return !closed.get() && !draining.get() && resources.reconnect(current, failure);
    }

    private void abandon(AqResources current, Throwable failure) throws JMSException {
        if (closed.get()) {
            return;
        }
        try {
            if (config.transacted()) {
                current.session().rollback();
            } else {
                current.session().recover();
            }
        } catch (JMSException | RuntimeException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                throw e;
            }
        }
    }
}
