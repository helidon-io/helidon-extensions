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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.helidon.service.registry.Service;

/**
 * Declarative messaging annotations and message factory.
 */
public final class Messaging {
    private Messaging() {
    }

    /**
     * Create a message builder.
     *
     * @param entity payload
     * @param <T> payload type
     * @return message builder
     */
    public static <T> Message.Builder<T> message(T entity) {
        return Message.builder(entity);
    }

    /**
     * Marks a method that receives messages from a channel.
     */
    @Documented
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.METHOD)
    @Service.EntryPoint
    public @interface ReceiveFrom {
        /**
         * Source channel name.
         *
         * @return source channel name
         */
        String value();
    }

    /**
     * Target channel for the value returned by a one-to-one message processor method.
     */
    @Documented
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.METHOD)
    public @interface SendTo {
        /**
         * Target channel name.
         *
         * @return target channel name
         */
        String value();
    }

    /**
     * Message header parameter.
     */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.PARAMETER)
    public @interface HeaderParam {
        /**
         * Header name.
         *
         * @return header name
         */
        String value();
    }

    /**
     * Message payload parameter.
     */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.PARAMETER)
    public @interface Entity {
    }

}
