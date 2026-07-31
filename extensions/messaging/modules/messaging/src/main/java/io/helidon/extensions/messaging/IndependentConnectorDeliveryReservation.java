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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Compatibility reservation for independently implemented connector contexts.
 */
final class IndependentConnectorDeliveryReservation implements ConnectorDeliveryReservation {
    private final ConnectorSourceContext context;
    private final int maxMessages;
    private final long maxAdmissionBytes;
    private final ReentrantLock stateLock = new ReentrantLock();
    private State state = State.OPEN;

    IndependentConnectorDeliveryReservation(ConnectorSourceContext context,
                                            int maxMessages,
                                            long maxAdmissionBytes) {
        this.context = Objects.requireNonNull(context);
        this.maxMessages = maxMessages;
        this.maxAdmissionBytes = maxAdmissionBytes;
    }

    @Override
    public <T> ConnectorDelivery start(List<? extends Message<T>> messages,
                                       long admissionBytes,
                                       Runnable delivery) {
        stateLock.lock();
        try {
            requireOpen();
            try {
                validateActual(messages, admissionBytes, delivery);
                ConnectorDelivery result = Objects.requireNonNull(
                        context.submitDelivery(messages, admissionBytes, delivery),
                        "Connector delivery");
                state = State.STARTED;
                return result;
            } catch (RuntimeException | Error e) {
                state = State.CLOSED;
                throw e;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public <T> Optional<ConnectorDelivery> tryStart(List<? extends Message<T>> messages,
                                                    long admissionBytes,
                                                    Runnable delivery) {
        stateLock.lock();
        try {
            requireOpen();
            try {
                validateActual(messages, admissionBytes, delivery);
                Optional<ConnectorDelivery> result = Objects.requireNonNull(
                        context.trySubmitDelivery(messages, admissionBytes, delivery),
                        "Connector delivery result");
                if (result.isPresent()) {
                    state = State.STARTED;
                }
                return result;
            } catch (RuntimeException | Error e) {
                state = State.CLOSED;
                throw e;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void close() {
        stateLock.lock();
        try {
            if (state == State.OPEN) {
                state = State.CLOSED;
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Connector delivery reservation is no longer open");
        }
    }

    private void validateActual(List<? extends Message<?>> messages,
                                long admissionBytes,
                                Runnable delivery) {
        Objects.requireNonNull(messages);
        Objects.requireNonNull(delivery);
        if (messages.isEmpty()) {
            state = State.CLOSED;
            throw new IllegalArgumentException("Connector delivery must contain at least one message");
        }
        if (admissionBytes < 0) {
            state = State.CLOSED;
            throw new IllegalArgumentException("admissionBytes must be zero or greater");
        }
        long knownBytes = 0;
        try {
            for (Message<?> message : messages) {
                OptionalLong declared = declaredAdmissionBytes(
                        Objects.requireNonNull(message),
                        Collections.newSetFromMap(new IdentityHashMap<>()));
                if (declared.isPresent()) {
                    knownBytes = Math.addExact(knownBytes, declared.getAsLong());
                }
            }
        } catch (ArithmeticException e) {
            rejectOversized("Connector delivery admission size exceeds the supported range", e);
        }
        long actualBytes = Math.max(admissionBytes, knownBytes);
        if (messages.size() > maxMessages || actualBytes > maxAdmissionBytes) {
            rejectOversized("Connector delivery exceeds its pending reservation", null);
        }
    }

    private OptionalLong declaredAdmissionBytes(Message<?> message, Set<Message<?>> path) {
        if (!path.add(message)) {
            throw new IllegalArgumentException("Dead-letter message original-message chain must not be cyclic");
        }
        try {
            OptionalLong result = Objects.requireNonNull(message.admissionBytes());
            if (result.isPresent() && result.getAsLong() < 0) {
                state = State.CLOSED;
                throw new IllegalArgumentException("Message admission byte size must be zero or greater");
            }
            if (message instanceof DeadLetterMessage<?> deadLetterMessage
                    && deadLetterMessage.originalMessage() != message) {
                OptionalLong originalBytes = declaredAdmissionBytes(deadLetterMessage.originalMessage(), path);
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

    private void rejectOversized(String message, Throwable cause) {
        state = State.CLOSED;
        if (cause == null) {
            throw new MessagingRejectedException(context.channelName(),
                                                 MessagingRejectedException.Reason.OVERSIZED,
                                                 message + " on channel " + context.channelName());
        }
        throw new MessagingRejectedException(context.channelName(),
                                             MessagingRejectedException.Reason.OVERSIZED,
                                             message + " on channel " + context.channelName(),
                                             cause);
    }

    private enum State {
        OPEN,
        STARTED,
        CLOSED
    }
}
