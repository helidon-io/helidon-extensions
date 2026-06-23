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

/**
 * Runtime context exposed to incoming connector sources.
 */
public interface ConnectorSourceContext {
    /**
     * Channel name.
     *
     * @return channel name
     */
    String channelName();

    /**
     * Emit a payload-only message.
     *
     * @param entity payload
     * @param <T> payload type
     */
    default <T> void emit(T entity) {
        emit(Message.create(entity));
    }

    /**
     * Emit a message into the channel.
     *
     * @param message message
     * @param <T> payload type
     */
    <T> void emit(Message<T> message);
}
