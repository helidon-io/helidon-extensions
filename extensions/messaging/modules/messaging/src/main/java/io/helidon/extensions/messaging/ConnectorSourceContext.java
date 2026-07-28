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

import java.util.List;

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
}
