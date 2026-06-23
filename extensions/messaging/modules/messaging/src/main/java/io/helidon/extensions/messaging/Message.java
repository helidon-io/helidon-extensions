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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Message envelope with payload and headers.
 *
 * @param <T> payload type
 */
public final class Message<T> {
    private final T entity;
    private final Map<String, String> headers;

    private Message(T entity, Map<String, String> headers) {
        this.entity = entity;
        this.headers = Map.copyOf(headers);
    }

    /**
     * Create a message builder.
     *
     * @param entity payload
     * @param <T> payload type
     * @return builder
     */
    public static <T> Builder<T> builder(T entity) {
        return new Builder<>(entity);
    }

    /**
     * Create a payload-only message.
     *
     * @param entity payload
     * @param <T> payload type
     * @return message
     */
    public static <T> Message<T> create(T entity) {
        return builder(entity).build();
    }

    /**
     * Payload.
     *
     * @return payload
     */
    public T entity() {
        return entity;
    }

    /**
     * Headers.
     *
     * @return immutable headers
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * Header value.
     *
     * @param name header name
     * @return header value
     */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name));
    }

    /**
     * Message builder.
     *
     * @param <T> payload type
     */
    public static final class Builder<T> {
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
         * @return message
         */
        public Message<T> build() {
            return new Message<>(entity, Collections.unmodifiableMap(headers));
        }
    }
}
