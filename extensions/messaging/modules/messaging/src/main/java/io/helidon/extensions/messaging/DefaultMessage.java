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

import java.util.Map;
import java.util.OptionalLong;

/**
 * Default immutable message implementation.
 *
 * @param <T> payload type
 */
final class DefaultMessage<T> implements Message<T> {
    private final T entity;
    private final Map<String, String> headers;
    private final OptionalLong admissionBytes;

    DefaultMessage(T entity, Map<String, String> headers, OptionalLong admissionBytes) {
        this.entity = entity;
        this.headers = Map.copyOf(headers);
        OptionalLong knownBytes = MessageSizes.logicalBytes(entity, this.headers);
        if (admissionBytes.isPresent() && knownBytes.isPresent()) {
            this.admissionBytes = OptionalLong.of(Math.max(admissionBytes.getAsLong(), knownBytes.getAsLong()));
        } else {
            this.admissionBytes = admissionBytes.isPresent() ? admissionBytes : knownBytes;
        }
    }

    @Override
    public T entity() {
        return entity;
    }

    @Override
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public OptionalLong admissionBytes() {
        return admissionBytes;
    }
}
