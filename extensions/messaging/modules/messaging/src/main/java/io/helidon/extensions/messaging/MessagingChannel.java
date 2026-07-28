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
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Imperative synchronous messaging channel API.
 * <p>
 * All configured outputs are required. An emission invokes them sequentially in configuration order and returns only
 * after all of them complete successfully. The first output failure is propagated to the caller and remaining outputs
 * are not invoked. Outputs completed before a failure are not rolled back, so retrying can deliver the same message
 * more than once.
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
     * <p>
     * A successful return means all required outputs completed. A thrown exception means delivery failed or its
     * outcome is indeterminate.
     *
     * @param entity payload
     * @throws RuntimeException if an output fails
     */
    void emit(T entity);

    /**
     * Emit a message to all channel outputs.
     * <p>
     * A successful return means all required outputs completed. A thrown exception means delivery failed or its
     * outcome is indeterminate.
     *
     * @param message message
     * @throws RuntimeException if an output fails
     */
    void emit(Message<T> message);

    /**
     * Emit a batch of messages to all channel outputs.
     * <p>
     * The same batch is delivered sequentially to every required output. Completed outputs are not rolled back if a
     * later output fails.
     *
     * @param messages messages
     * @throws RuntimeException if an output fails
     */
    void emitBatch(List<? extends Message<T>> messages);

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
         * Add a required output consumer.
         * <p>
         * Outputs are invoked sequentially in the order in which they are added.
         *
         * @param output output consumer
         * @return updated builder
         */
        Builder<T> addOutput(Consumer<Message<T>> output);

        /**
         * Add a required batch output consumer.
         * <p>
         * Outputs are invoked sequentially in the order in which they are added.
         *
         * @param output output consumer
         * @return updated builder
         */
        Builder<T> addBatchOutput(Consumer<List<Message<T>>> output);

        /**
         * Add an outgoing connector as a required output.
         * <p>
         * Outputs are invoked sequentially in the order in which they are added.
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
