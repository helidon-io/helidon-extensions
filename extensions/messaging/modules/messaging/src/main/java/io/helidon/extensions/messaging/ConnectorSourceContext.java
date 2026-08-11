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
     * {@link #handleFailure(MessageBatch, int, RuntimeException)} implementation still propagates
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
     * reservation and delegates its eventual start to {@link #submitDelivery(MessageBatch, Runnable)}.
     *
     * @param maxMessages maximum messages the connector may acquire
     * @return pending delivery reservation
     * @throws MessagingRejectedException if capacity cannot be reserved
     * @throws IllegalArgumentException if {@code maxMessages} is not positive
     */
    default ConnectorDeliveryReservation reserveDelivery(int maxMessages) {
        validateReservation(maxMessages);
        return new IndependentConnectorDeliveryReservation(this, maxMessages);
    }

    /**
     * Attempt to reserve pending capacity before acquiring one connector delivery without blocking.
     * <p>
     * A durable connector can pause new acquisition and continue transport maintenance while this method returns
     * empty. The compatibility default has no runtime admission layer and always returns a local reservation.
     *
     * @param maxMessages maximum messages the connector may acquire
     * @return reservation, or empty when pending capacity is currently unavailable
     * @throws MessagingRejectedException if the request can never fit or the runtime is shutting down
     * @throws IllegalArgumentException if {@code maxMessages} is not positive
     */
    default Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages) {
        return Optional.of(reserveDelivery(maxMessages));
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
        emitBatch(MessageBatch.create(Message.create(entity)));
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
    default <T> void emit(Message<T> message) {
        emitBatch(MessageBatch.create(message));
    }

    /**
     * Emit a batch of messages into the channel.
     * <p>
     * @param batch immutable message batch
     * @param <T> payload type
     * @throws BatchDeliveryException if downstream delivery completes partially or is indeterminate
     */
    <T> void emitBatch(MessageBatch<T> batch);

    /**
     * Submit one retained connector delivery to the messaging runtime.
     * <p>
     * The runtime holds message-count admission for the complete task, including retries and terminal
     * failure handling. This asynchronous connector-only facility allows a transport owner thread to continue
     * maintenance work, such as Kafka consumer-group polling, while application-facing dispatch remains synchronous.
     * Connector implementations must not acknowledge or commit the retained delivery until the returned task
     * completes successfully, and must close the returned lease only after that transport settlement succeeds or the
     * delivery is abandoned.
     * <p>
     * The retained lease covers the supplied batch and subsets created through {@link MessageBatch#subset(List)}.
     * Rebuilt batches and replacement envelopes require separate admission, even when they reuse the public batch ID.
     * <p>
     * Runtime-provided contexts override this method and own the task virtual thread. The default preserves
     * compatibility for independently implemented contexts.
     *
     * @param batch complete retained delivery
     * @param delivery delivery logic, including retry and terminal failure handling
     * @param <T> payload type
     * @return delivery task
     * @throws MessagingRejectedException if the delivery cannot be admitted
     */
    default <T> ConnectorDelivery submitDelivery(MessageBatch<T> batch, Runnable delivery) {
        Objects.requireNonNull(batch);
        Objects.requireNonNull(delivery);
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
     * The retained lease covers the supplied batch and subsets created through {@link MessageBatch#subset(List)}.
     * Rebuilt batches and replacement envelopes require separate admission, even when they reuse the public batch ID.
     *
     * @param batch complete retained delivery
     * @param delivery delivery logic, including retry and terminal failure handling
     * @param <T> payload type
     * @return the admitted delivery task, or empty when capacity is currently unavailable
     * @throws MessagingRejectedException if the delivery can never be admitted or the runtime is shutting down
     */
    default <T> Optional<ConnectorDelivery> trySubmitDelivery(MessageBatch<T> batch, Runnable delivery) {
        return Optional.of(submitDelivery(batch, delivery));
    }

    /**
     * Apply this incoming channel's failure policy to one failed delivery attempt.
     * <p>
     * {@code batch} is the exact ordered delivery subset to which the policy applies. Items that already succeeded are
     * excluded. When an attempt also has failed or indeterminate items, items that were not attempted are deferred and
     * handled separately by the source. An entirely not-attempted delivery may itself be the policy subset so a
     * persistent pre-dispatch failure observes the configured retry limit. If {@code failure} is a
     * {@link BatchDeliveryException}, its batch and locally indexed outcomes must be aligned with {@code batch}; source
     * implementations can use {@link BatchDeliveryException#align(MessageBatch, RuntimeException)} before invoking this
     * method.
     * <p>
     * The default implementation propagates the original failure, preserving the behavior
     * of third-party source contexts.
     *
     * @param batch exact failure-policy delivery subset
     * @param failedAttempt failed delivery attempt, where one is the initial delivery
     * @param failure processing failure
     * @param <T> payload type
     * @return retry or settled result
     * @throws RuntimeException if the delivery remains unsettled
     */
    default <T> FailureResult handleFailure(MessageBatch<T> batch,
                                            int failedAttempt,
                                            RuntimeException failure) {
        throw failure;
    }

    /**
     * Portable failure handling result.
     */
    enum FailureResult {
        /**
         * Retain and retry the supplied failure-policy delivery subset.
         */
        RETRY,

        /**
         * The configured terminal disposition settled the supplied failure-policy delivery subset.
         */
        SETTLED
    }

    private static void validateReservation(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than zero");
        }
    }
}
