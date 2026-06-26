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

import io.helidon.service.registry.Service;

/**
 * Generated consumer registration contract.
 */
@Service.Contract
public interface ConsumerRegistration {
    /**
     * Channel name.
     *
     * @return channel name
     */
    String channel();

    /**
     * Expected payload type.
     *
     * @return payload type
     */
    Class<?> payloadType();

    /**
     * Whether this registration consumes message batches.
     *
     * @return {@code true} for batch consumers
     */
    default boolean batch() {
        return false;
    }

    /**
     * Dispatch the message to the generated consumer invoker.
     *
     * @param message message to dispatch
     */
    void dispatch(Message<?> message);

    /**
     * Dispatch a batch to the generated consumer invoker.
     *
     * @param messages messages to dispatch
     */
    default void dispatchBatch(List<Message<?>> messages) {
        for (Message<?> message : messages) {
            dispatch(message);
        }
    }
}
