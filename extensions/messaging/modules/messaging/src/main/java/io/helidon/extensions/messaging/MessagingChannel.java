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

import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Imperative messaging channel API.
 *
 * @param <T> payload type
 */
public interface MessagingChannel<T> {
    /**
     * Create a new channel builder.
     *
     * @param <T> payload type
     * @return builder
     */
    static <T> Builder<T> builder() {
        return new DefaultMessagingChannel.Builder<>();
    }

    /**
     * Emit a payload-only message.
     *
     * @param entity payload
     */
    void emit(T entity);

    /**
     * Emit a message to all channel outputs.
     *
     * @param message message
     */
    void emit(Message<T> message);

    /**
     * Start this channel's lifecycle inputs.
     * <p>
     * Stream inputs are consumed on virtual threads after this method returns.
     */
    void start();

    /**
     * Imperative channel builder.
     *
     * @param <T> payload type
     */
    interface Builder<T> {
        /**
         * Configure expected payload type for channel validation.
         *
         * @param payloadType payload type
         * @return updated builder
         */
        Builder<T> payloadType(Class<T> payloadType);

        /**
         * Add an input stream. Stream items can be raw payloads or {@link Message} instances.
         *
         * @param input input stream
         * @return updated builder
         */
        Builder<T> addInput(Stream<?> input);

        /**
         * Add another channel as input. This channel is registered as an output of the input channel.
         *
         * @param input input channel
         * @return updated builder
         */
        Builder<T> addInput(MessagingChannel<?> input);

        /**
         * Add an output consumer.
         *
         * @param output output consumer
         * @return updated builder
         */
        Builder<T> addOutput(Consumer<Message<T>> output);

        /**
         * Add an outgoing connector as an output.
         *
         * @param output outgoing connector sink
         * @return updated builder
         */
        Builder<T> addOutgoingConnector(ConnectorSink output);

        /**
         * Build the channel and connect configured channel inputs.
         * <p>
         * Stream inputs are not consumed during build; they are consumed when the channel is started.
         *
         * @return channel
         */
        MessagingChannel<T> build();
    }
}
