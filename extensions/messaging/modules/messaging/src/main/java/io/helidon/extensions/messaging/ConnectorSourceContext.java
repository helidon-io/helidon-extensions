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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Synchronous runtime context exposed to incoming connector sources.
 * <p>
 * A source may acknowledge or commit an incoming delivery only after the corresponding emission returns successfully.
 * If emission throws, the source must not settle the incoming delivery as successfully processed; it must apply its
 * configured failure policy, such as retry, negative acknowledgement (nack), or dead-letter queue (DLQ) handoff. A
 * retry can redeliver the message to outputs that completed before the failure.
 */
public interface ConnectorSourceContext {
    /**
     * Failure policy for this incoming channel.
     * <p>
     * The default is exposed for compatibility with third-party contexts. The default
     * {@link #handleFailure(List, int, RuntimeException)} implementation still propagates
     * failures immediately.
     *
     * @return failure policy
     */
    default FailurePolicy failurePolicy() {
        return FailurePolicy.create();
    }

    /**
     * Channel name.
     *
     * @return channel name
     */
    String channelName();

    /**
     * Maximum messages the runtime can admit in one retained connector delivery.
     * <p>
     * Sources should use this limit to bound polling or reading before submitting a delivery.
     *
     * @return maximum messages per delivery
     */
    default int maxDeliveryMessages() {
        return Integer.MAX_VALUE;
    }

    /**
     * Maximum declared admission byte weight the runtime can admit in one retained connector delivery.
     * <p>
     * Sources should use this limit to bound polling or reading before submitting a delivery.
     *
     * @return maximum admission bytes per delivery
     */
    default long maxDeliveryBytes() {
        return Long.MAX_VALUE;
    }

    /**
     * Runtime-conservative admission byte estimate for one complete message.
     * <p>
     * Runtime-provided contexts include all applicable {@link MessageSizeEstimator} services. The compatibility
     * default validates and returns the estimate declared by {@link Message#admissionBytes()}.
     *
     * @param message message to estimate
     * @return complete message admission weight, or empty when its size is unknown
     * @throws IllegalArgumentException if the message declares a negative size
     */
    default OptionalLong messageAdmissionBytes(Message<?> message) {
        OptionalLong result = Objects.requireNonNull(
                Objects.requireNonNull(message).admissionBytes(),
                "Message admission byte size");
        if (result.isPresent() && result.getAsLong() < 0) {
            throw new IllegalArgumentException("Message admission byte size must be zero or greater");
        }
        return result;
    }

    /**
     * Maximum time a connector should wait for retained-delivery admission.
     *
     * @return configured admission timeout, or empty to wait while the source remains active
     */
    default Optional<Duration> admissionTimeout() {
        return Optional.empty();
    }

    /**
     * Reserve pending capacity before acquiring one connector delivery.
     * <p>
     * Runtime-provided contexts block with bounded pending accounting. The compatibility default creates a local
     * reservation and delegates its eventual start to {@link #submitDelivery(List, long, Runnable)}.
     *
     * @param maxMessages maximum messages the connector may acquire
     * @param maxAdmissionBytes maximum admission bytes the connector may acquire
     * @return pending delivery reservation
     * @throws MessagingRejectedException if capacity cannot be reserved
     * @throws IllegalArgumentException if {@code maxMessages} is not positive or {@code maxAdmissionBytes} is negative
     */
    default ConnectorDeliveryReservation reserveDelivery(int maxMessages, long maxAdmissionBytes) {
        validateReservation(maxMessages, maxAdmissionBytes);
        return new IndependentConnectorDeliveryReservation(this, maxMessages, maxAdmissionBytes);
    }

    /**
     * Attempt to reserve pending capacity before acquiring one connector delivery without blocking.
     * <p>
     * A durable connector can pause new acquisition and continue transport maintenance while this method returns
     * empty. The compatibility default has no runtime admission layer and always returns a local reservation.
     *
     * @param maxMessages maximum messages the connector may acquire
     * @param maxAdmissionBytes maximum admission bytes the connector may acquire
     * @return reservation, or empty when pending capacity is currently unavailable
     * @throws MessagingRejectedException if the request can never fit or the runtime is shutting down
     * @throws IllegalArgumentException if {@code maxMessages} is not positive or {@code maxAdmissionBytes} is negative
     */
    default Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages, long maxAdmissionBytes) {
        return Optional.of(reserveDelivery(maxMessages, maxAdmissionBytes));
    }

    /**
     * Emit a payload-only message.
     * <p>
     * A successful return means all required downstream outputs completed.
     *
     * @param entity payload
     * @param <T> payload type
     * @throws RuntimeException if downstream delivery fails
     */
    default <T> void emit(T entity) {
        emit(Message.create(entity));
    }

    /**
     * Emit a message into the channel.
     * <p>
     * A successful return means all required downstream outputs completed. Handler and connector failures are
     * propagated with their causes preserved.
     *
     * @param message message
     * @param <T> payload type
     * @throws RuntimeException if downstream delivery fails
     */
    <T> void emit(Message<T> message);

    /**
     * Emit a batch of messages into the channel.
     * <p>
     * The default implementation emits messages sequentially and stops at the first failure. Messages delivered
     * before that failure are not rolled back.
     *
     * @param messages messages
     * @param <T> payload type
     * @throws RuntimeException if downstream delivery fails
     */
    default <T> void emitBatch(List<? extends Message<T>> messages) {
        for (Message<T> message : messages) {
            emit(message);
        }
    }

    /**
     * Submit one retained connector delivery to the messaging runtime.
     * <p>
     * The runtime holds message-count and byte admission for the complete task, including retries and terminal
     * failure handling. This asynchronous connector-only facility allows a transport owner thread to continue
     * maintenance work, such as Kafka consumer-group polling, while application-facing dispatch remains synchronous.
     * Connector implementations must not acknowledge or commit the retained delivery until the returned task
     * completes successfully, and must close the returned lease only after that transport settlement succeeds or the
     * delivery is abandoned.
     * <p>
     * The retained lease covers the supplied message instances. Delivery logic that emits back into this same channel
     * must emit those exact instances, or a subset of them. Replacement envelopes require separate admission.
     * <p>
     * Runtime-provided contexts override this method and own the task virtual thread. The default preserves
     * compatibility for independently implemented contexts.
     *
     * @param messages complete retained delivery
     * @param admissionBytes declared admission weight of the complete delivery
     * @param delivery delivery logic, including retry and terminal failure handling
     * @param <T> payload type
     * @return delivery task
     * @throws MessagingRejectedException if the delivery cannot be admitted
     * @throws IllegalArgumentException if the delivery is empty or {@code admissionBytes} is negative
     */
    default <T> ConnectorDelivery submitDelivery(List<? extends Message<T>> messages,
                                                 long admissionBytes,
                                                 Runnable delivery) {
        Objects.requireNonNull(messages);
        Objects.requireNonNull(delivery);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Connector delivery must contain at least one message");
        }
        if (admissionBytes < 0) {
            throw new IllegalArgumentException("admissionBytes must be zero or greater");
        }
        return IndependentConnectorDelivery.start(channelName(), delivery);
    }

    /**
     * Attempt to submit one retained connector delivery without blocking the transport owner thread.
     * <p>
     * A durable transport that must perform maintenance while capacity is unavailable should pause new acquisition,
     * call this method, and continue its maintenance loop when the result is empty. Runtime-provided contexts perform
     * an immediate bounded admission attempt. The compatibility default has no runtime admission layer and therefore
     * always starts the delivery.
     * <p>
     * The retained lease covers the supplied message instances. Delivery logic that emits back into this same channel
     * must emit those exact instances, or a subset of them. Replacement envelopes require separate admission.
     *
     * @param messages complete retained delivery
     * @param admissionBytes declared admission weight of the complete delivery
     * @param delivery delivery logic, including retry and terminal failure handling
     * @param <T> payload type
     * @return the admitted delivery task, or empty when capacity is currently unavailable
     * @throws MessagingRejectedException if the delivery can never be admitted or the runtime is shutting down
     * @throws IllegalArgumentException if the delivery is empty or {@code admissionBytes} is negative
     */
    default <T> Optional<ConnectorDelivery> trySubmitDelivery(List<? extends Message<T>> messages,
                                                              long admissionBytes,
                                                              Runnable delivery) {
        return Optional.of(submitDelivery(messages, admissionBytes, delivery));
    }

    /**
     * Apply this incoming channel's failure policy to one failed delivery attempt.
     * <p>
     * The default implementation propagates the original failure, preserving the behavior
     * of third-party source contexts.
     *
     * @param messages complete retained source delivery
     * @param failedAttempt failed delivery attempt, where one is the initial delivery
     * @param failure processing failure
     * @param <T> payload type
     * @return retry or settled result
     * @throws RuntimeException if the delivery remains unsettled
     */
    default <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                            int failedAttempt,
                                            RuntimeException failure) {
        throw failure;
    }

    /**
     * Portable failure handling result.
     */
    enum FailureResult {
        /**
         * Retain and retry the complete source delivery.
         */
        RETRY,

        /**
         * The configured terminal disposition settled the source delivery.
         */
        SETTLED
    }

    private static void validateReservation(int maxMessages, long maxAdmissionBytes) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than zero");
        }
        if (maxAdmissionBytes < 0) {
            throw new IllegalArgumentException("maxAdmissionBytes must be zero or greater");
        }
    }
}
