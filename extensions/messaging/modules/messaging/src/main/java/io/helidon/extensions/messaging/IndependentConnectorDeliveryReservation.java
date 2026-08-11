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

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Compatibility reservation for independently implemented connector contexts.
 */
final class IndependentConnectorDeliveryReservation implements ConnectorDeliveryReservation {
    private final ConnectorSourceContext context;
    private final int maxMessages;
    private final ReentrantLock stateLock = new ReentrantLock();
    private State state = State.OPEN;

    IndependentConnectorDeliveryReservation(ConnectorSourceContext context,
                                            int maxMessages) {
        this.context = Objects.requireNonNull(context);
        this.maxMessages = maxMessages;
    }

    @Override
    public <T> ConnectorDelivery start(MessageBatch<T> batch, Runnable delivery) {
        stateLock.lock();
        try {
            requireOpen();
            try {
                validateActual(batch, delivery);
                ConnectorDelivery result = Objects.requireNonNull(
                        context.submitDelivery(batch, delivery),
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
    public <T> Optional<ConnectorDelivery> tryStart(MessageBatch<T> batch, Runnable delivery) {
        stateLock.lock();
        try {
            requireOpen();
            try {
                validateActual(batch, delivery);
                Optional<ConnectorDelivery> result = Objects.requireNonNull(
                        context.trySubmitDelivery(batch, delivery),
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

    private void validateActual(MessageBatch<?> batch, Runnable delivery) {
        Objects.requireNonNull(batch);
        Objects.requireNonNull(delivery);
        if (batch.size() > maxMessages) {
            rejectOversized("Connector delivery exceeds its pending reservation");
        }
    }

    private void rejectOversized(String message) {
        state = State.CLOSED;
        throw new MessagingRejectedException(context.channelName(),
                                             MessagingRejectedException.Reason.OVERSIZED,
                                             message + " on channel " + context.channelName());
    }

    private enum State {
        OPEN,
        STARTED,
        CLOSED
    }
}
