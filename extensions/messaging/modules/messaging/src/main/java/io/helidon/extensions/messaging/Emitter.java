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

import io.helidon.service.registry.Service;

/**
 * Emitter facade matching the design-doc shape.
 *
 * @param <T> payload type
 */
@Service.Contract
public interface Emitter<T> {
    /**
     * Emit a payload-only message.
     *
     * @param entity payload
     */
    void emit(T entity);

    /**
     * Emit a message with metadata.
     *
     * @param message message
     */
    void emit(Message<T> message);
}
