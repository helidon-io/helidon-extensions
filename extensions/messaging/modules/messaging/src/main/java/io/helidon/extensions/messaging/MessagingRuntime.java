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

import io.helidon.common.Api;
import io.helidon.service.registry.Service;

/**
 * Internal synchronous runtime contract used by generated messaging emitters.
 * <p>
 * A successful emission returns only after all required outputs complete. Outputs are invoked sequentially and the
 * first failure is propagated to the caller. Already completed outputs are not rolled back, so a retry can duplicate
 * delivery.
 */
@Api.Internal
@Service.Contract
public interface MessagingRuntime {
    /**
     * Declarative messaging graph run level, aligned with Helidon declarative {@code RunLevels.MESSAGING}.
     */
    double RUN_LEVEL = 30D;

    /**
     * Emit a message to a named channel.
     * <p>
     * A successful return means all required outputs completed. Handler and connector failures are propagated to the
     * caller with their causes preserved.
     *
     * @param channel channel name
     * @param message message
     * @param <T> payload type
     * @throws MessagingException if the named channel does not exist
     * @throws RuntimeException if a handler or outgoing connector fails
     */
    <T> void emit(String channel, Message<T> message);

    /**
     * Emit a batch of messages to a named channel.
     * <p>
     * A successful return means all required outputs completed. Handler and connector failures are propagated to the
     * caller with their causes preserved. Completed outputs are not rolled back if a later output fails.
     *
     * @param channel channel name
     * @param messages messages
     * @param <T> payload type
     * @throws MessagingException if the named channel does not exist
     * @throws RuntimeException if a handler or outgoing connector fails
     */
    <T> void emitBatch(String channel, List<? extends Message<T>> messages);
}
