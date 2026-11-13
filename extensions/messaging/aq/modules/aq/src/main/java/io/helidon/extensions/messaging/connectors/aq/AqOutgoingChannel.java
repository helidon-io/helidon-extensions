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

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import javax.jms.JMSException;
import javax.jms.MessageProducer;

import io.helidon.messaging.BatchDeliveryException;
import io.helidon.messaging.BatchItemOutcome;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.spi.OutgoingChannel;

import oracle.jms.AQjmsAgent;
import oracle.jms.AQjmsProducer;

/**
 * Synchronous AQ sends with explicit per-item outcomes and local transaction boundaries.
 */
final class AqOutgoingChannel implements OutgoingChannel {
    private static final System.Logger LOGGER = System.getLogger(AqOutgoingChannel.class.getName());

    private final AqRuntimeConfig config;
    private final AqResourceManager resources;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Thread> sender = new AtomicReference<>();
    private final ReentrantLock sendLock = new ReentrantLock();

    AqOutgoingChannel(AqRuntimeConfig config, AqResourceResolver resolver) {
        this.config = Objects.requireNonNull(config);
        this.resources = new AqResourceManager(config, resolver, false);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("AQ outgoing channel can only start once");
        }
        requireAccepting();
        requireOpen();
        try {
            resources.open();
        } catch (RuntimeException | Error failure) {
            closed.set(true);
            try {
                resources.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public void sendBatch(MessageBatch<?> batch) {
        Objects.requireNonNull(batch);
        requireAccepting();
        requireOpen();
        if (!started.get()) {
            throw new IllegalStateException("AQ outgoing channel has not been started");
        }
        long startedAt = System.nanoTime();
        long timeout = TimeUnit.NANOSECONDS.convert(config.reconnect().waitTimeout());
        while (true) {
            AqResources generation = awaitReady(batch, startedAt, timeout);
            acquireSendLock();
            try {
                requireAccepting();
                requireOpen();
                // Another batch can retire this generation while we wait for the session.
                if (!resources.current(generation)) {
                    continue;
                }
                sender.set(Thread.currentThread());
                if (config.transacted()) {
                    sendTransacted(generation, batch);
                } else {
                    sendIndividual(generation, batch);
                }
                return;
            } catch (Error failure) {
                // An unexpected fatal failure must not leave an uncommitted session available for another send.
                forceClose();
                throw failure;
            } finally {
                sender.set(null);
                sendLock.unlock();
            }
        }
    }

    @Override
    public void forceClose() {
        closing.set(true);
        closed.set(true);
        resources.forceClose();
        Thread currentSender = sender.get();
        if (currentSender != null && currentSender != Thread.currentThread()) {
            currentSender.interrupt();
        }
    }

    @Override
    public void close() {
        closing.set(true);
        resources.stopRecovery();
        try {
            if (!sendLock.tryLock(config.closeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                forceClose();
                throw new MessagingException("Timed out closing AQ outgoing channel " + config.channelName());
            }
            try {
                closed.set(true);
                resources.close();
            } finally {
                sendLock.unlock();
            }
        } catch (InterruptedException e) {
            forceClose();
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while closing AQ outgoing channel " + config.channelName(), e);
        }
    }

    private static AQjmsAgent toAgent(AqRecipient recipient) {
        try {
            return new AQjmsAgent(recipient.name().orElse(null), recipient.address().orElse(null), recipient.protocol());
        } catch (SQLException e) {
            throw new MessagingException("Cannot create AQ recipient", e);
        }
    }

    private static void release(javax.jms.Message nativeMessage) {
        if (nativeMessage != null) {
            try {
                AqMessageMapper.releaseNative(nativeMessage);
            } catch (RuntimeException e) {
                // The transport outcome is already known. Cleanup must not turn a successful enqueue into a retry.
                LOGGER.log(System.Logger.Level.WARNING, "Cannot release temporary AQ message payload", e);
            }
        }
    }

    private void sendIndividual(AqResources generation, MessageBatch<?> batch) {
        List<BatchItemOutcome> outcomes = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            javax.jms.Message nativeMessage = null;
            boolean sendInvoked = false;
            Throwable connectionFailure = null;
            boolean terminalFailure = false;
            try {
                requireOpen();
                Message<?> message = batch.get(i);
                AQjmsAgent[] recipients = prepareRecipients(generation, message);
                nativeMessage = AqMessageMapper.toNative(generation.session(), message, config);
                requireOpen();
                sendInvoked = true;
                send(generation, nativeMessage, recipients);
                outcomes.add(BatchItemOutcome.succeeded(i));
            } catch (JMSException | RuntimeException failure) {
                outcomes.add(sendInvoked
                                     ? BatchItemOutcome.indeterminate(i, failure)
                                     : BatchItemOutcome.failed(i, failure));
                for (int remaining = i + 1; remaining < batch.size(); remaining++) {
                    outcomes.add(BatchItemOutcome.notAttempted(remaining));
                }
                if ((sendInvoked || failure instanceof AqMappingException) && AqReconnectSupport.recoverable(failure)) {
                    connectionFailure = failure;
                } else {
                    terminalFailure = sendInvoked;
                }
                throw new BatchDeliveryException("AQ send failed", failure, batch, outcomes);
            } finally {
                release(nativeMessage);
                finishFailure(generation, connectionFailure, terminalFailure);
            }
        }
    }

    private void sendTransacted(AqResources generation, MessageBatch<?> batch) {
        if (batch.size() == 0) {
            return;
        }
        List<javax.jms.Message> prepared = new ArrayList<>(batch.size());
        List<AQjmsAgent[]> recipients = new ArrayList<>(batch.size());
        int index = 0;
        int attempted = 0;
        boolean commitInvoked = false;
        Throwable connectionFailure = null;
        boolean terminalFailure = false;
        try {
            for (; index < batch.size(); index++) {
                requireOpen();
                Message<?> message = batch.get(index);
                recipients.add(prepareRecipients(generation, message));
                prepared.add(AqMessageMapper.toNative(generation.session(), message, config));
            }
            for (index = 0; index < batch.size(); index++) {
                requireOpen();
                attempted = index + 1;
                send(generation, prepared.get(index), recipients.get(index));
            }
            requireOpen();
            commitInvoked = true;
            generation.session().commit();
        } catch (JMSException | RuntimeException failure) {
            Throwable rollbackFailure = rollback(generation, failure);
            boolean rolledBack = rollbackFailure == null;
            List<BatchItemOutcome> outcomes = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                if (commitInvoked || i < attempted && !rolledBack) {
                    outcomes.add(BatchItemOutcome.indeterminate(i, failure));
                } else if (i < attempted || i == index) {
                    outcomes.add(BatchItemOutcome.failed(i, failure));
                } else {
                    outcomes.add(BatchItemOutcome.notAttempted(i));
                }
            }
            if ((attempted > 0 || failure instanceof AqMappingException) && AqReconnectSupport.recoverable(failure)) {
                connectionFailure = failure;
            } else if (rollbackFailure != null && AqReconnectSupport.recoverable(rollbackFailure)) {
                connectionFailure = rollbackFailure;
            } else {
                terminalFailure = commitInvoked || !rolledBack;
            }
            throw new BatchDeliveryException("AQ transaction failed", failure, batch, outcomes);
        } finally {
            prepared.forEach(AqOutgoingChannel::release);
            finishFailure(generation, connectionFailure, terminalFailure);
        }
    }

    private void send(AqResources generation, javax.jms.Message nativeMessage, AQjmsAgent[] recipients) throws JMSException {
        MessageProducer producer = generation.producer();
        if (recipients.length > 0) {
            ((AQjmsProducer) producer).publish(nativeMessage,
                                               recipients,
                                               config.deliveryMode(),
                                               config.priority(),
                                               config.timeToLive().toMillis());
        } else if (producer instanceof AQjmsProducer aq && config.destinationType() == AqDestinationType.TOPIC) {
            aq.publish(nativeMessage, config.deliveryMode(), config.priority(), config.timeToLive().toMillis());
        } else {
            producer.send(nativeMessage, config.deliveryMode(), config.priority(), config.timeToLive().toMillis());
        }
    }

    private AQjmsAgent[] prepareRecipients(AqResources generation, Message<?> message) {
        if (message instanceof AqMessage<?> aq && !aq.recipients().isEmpty()) {
            if (config.destinationType() != AqDestinationType.TOPIC || !(generation.producer() instanceof AQjmsProducer)) {
                throw new MessagingException("AQ recipient lists require an Oracle AQ multi-consumer topic producer");
            }
            return aq.recipients().stream().map(AqOutgoingChannel::toAgent).toArray(AQjmsAgent[]::new);
        }
        return new AQjmsAgent[0];
    }

    private Throwable rollback(AqResources generation, Throwable failure) {
        try {
            generation.session().rollback();
            return null;
        } catch (JMSException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return rollbackFailure;
        }
    }

    private void finishFailure(AqResources generation, Throwable connectionFailure, boolean terminalFailure) {
        if (connectionFailure != null) {
            if (!resources.reconnect(generation, connectionFailure)) {
                forceClose();
            }
        } else if (terminalFailure) {
            forceClose();
        }
    }

    private AqResources awaitReady(MessageBatch<?> batch, long startedAt, long timeout) {
        long remaining = Math.max(0, timeout - (System.nanoTime() - startedAt));
        try {
            return resources.awaitReady(Duration.ofNanos(remaining));
        } catch (MessagingException failure) {
            List<BatchItemOutcome> outcomes = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                outcomes.add(BatchItemOutcome.notAttempted(i));
            }
            throw new BatchDeliveryException("AQ connection is unavailable", failure, batch, outcomes);
        }
    }

    private void acquireSendLock() {
        try {
            do {
                requireAccepting();
                requireOpen();
            } while (!sendLock.tryLock(10, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while waiting to send to AQ", e);
        }
    }

    private void requireAccepting() {
        if (closing.get()) {
            throw new MessagingException("AQ outgoing channel is closing");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new MessagingException("AQ outgoing channel has been closed");
        }
    }

}
