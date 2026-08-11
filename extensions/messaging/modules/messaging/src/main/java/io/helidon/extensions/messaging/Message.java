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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Message envelope with payload and headers.
 * <p>
 * Implementations must be immutable snapshots and must return an immutable map from {@link #headers()}.
 *
 * @param <T> payload type
 */
public interface Message<T> {
    /**
     * Create a message builder.
     *
     * @param entity payload
     * @param <T> payload type
     * @return builder
     */
    static <T> Builder<T> builder(T entity) {
        return new Builder<>(entity);
    }

    /**
     * Create a payload-only message.
     *
     * @param entity payload
     * @param <T> payload type
     * @return message
     */
    static <T> Message<T> create(T entity) {
        return builder(entity).build();
    }

    /**
     * Payload.
     *
     * @return payload
     */
    T entity();

    /**
     * Headers.
     *
     * @return immutable headers
     */
    Map<String, String> headers();

    /**
     * Header value.
     *
     * @param name header name
     * @return header value
     */
    default Optional<String> header(String name) {
        return Optional.ofNullable(headers().get(name));
    }

    /**
     * Message builder.
     *
     * @param <T> payload type
     */
    final class Builder<T> {
        private final T entity;
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder(T entity) {
            this.entity = entity;
        }

        /**
         * Add a header.
         *
         * @param name header name
         * @param value header value
         * @return updated builder
         */
        public Builder<T> header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /**
         * Create the message.
         *
         * @return immutable message
         */
        public Message<T> build() {
            return new DefaultMessage<>(entity, headers);
        }
    }
}
