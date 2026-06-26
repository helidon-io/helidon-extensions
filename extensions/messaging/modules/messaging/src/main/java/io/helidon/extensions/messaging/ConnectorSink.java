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
 * Outgoing connector sink.
 */
public interface ConnectorSink {
    /**
     * Send a payload-only message.
     *
     * @param entity payload
     * @param <T> payload type
     */
    default <T> void send(T entity) {
        send(Message.create(entity));
    }

    /**
     * Send a message to the connector target.
     *
     * @param message message
     * @param <T> payload type
     */
    <T> void send(Message<T> message);

    /**
     * Send a batch of messages to the connector target.
     *
     * @param messages messages
     * @param <T> payload type
     */
    default <T> void sendBatch(List<Message<T>> messages) {
        for (Message<T> message : messages) {
            send(message);
        }
    }
}
