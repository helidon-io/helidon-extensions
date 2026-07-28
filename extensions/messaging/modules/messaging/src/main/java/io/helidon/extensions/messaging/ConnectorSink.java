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
 * Synchronous outgoing connector sink.
 * <p>
 * Each implementation defines and documents its external send-completion point. A send method must not return before
 * that success point is reached. It must throw if sending failed or its outcome is indeterminate, preserving the
 * underlying cause when one is available.
 */
public interface ConnectorSink {
    /**
     * Send a payload-only message.
     * <p>
     * A successful return means the connector-specific send-completion point was reached.
     *
     * @param entity payload
     * @param <T> payload type
     * @throws RuntimeException if sending fails or its outcome is indeterminate
     */
    default <T> void send(T entity) {
        send(Message.create(entity));
    }

    /**
     * Send a message to the connector target.
     * <p>
     * A successful return means the connector-specific send-completion point was reached.
     *
     * @param message message
     * @param <T> payload type
     * @throws RuntimeException if sending fails or its outcome is indeterminate
     */
    <T> void send(Message<T> message);

    /**
     * Send a batch of messages to the connector target.
     * <p>
     * The default implementation sends messages sequentially and stops at the first failure. Messages sent before that
     * failure are not rolled back and can be duplicated if the caller retries the batch.
     *
     * @param messages messages
     * @param <T> payload type
     * @throws RuntimeException if sending fails or its outcome is indeterminate
     */
    default <T> void sendBatch(List<? extends Message<T>> messages) {
        for (Message<T> message : messages) {
            send(message);
        }
    }
}
