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

package io.helidon.extensions.messaging.connectors.aq;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.helidon.builder.api.Prototype;
import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessageMetadata;

final class AqMessageSupport {
    private AqMessageSupport() {
    }

    @Prototype.RuntimeTypeFactoryMethod("message")
    static <T> AqMessage<T> create(AqMessageConfig<T> config) {
        return AqMessage.create(config);
    }

    /**
     * Set the delay before this message becomes available for dequeue.
     * Stores {@link io.helidon.extensions.messaging.connectors.aq.AqMessage#DELIVERY_DELAY_METADATA}
     * in local metadata, preserving other entries. Zero means no added delay.
     * The delay applies to each enqueue of this outgoing envelope and is ignored during dead-letter routing.
     * Passing {@code null} throws {@link java.lang.NullPointerException}.
     *
     * @param builder target builder
     * @param delay non-negative delay in whole seconds, at most {@link java.lang.Integer#MAX_VALUE} seconds
     * @throws IllegalArgumentException if the delay is negative, has fractional seconds, or exceeds the supported range
     */
    @Prototype.BuilderMethod
    static void deliveryDelay(AqMessageConfig.BuilderBase<?, ?, ?> builder, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative() || delay.getNano() != 0 || delay.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("AQ delivery delay must be whole seconds between 0 and " + Integer.MAX_VALUE);
        }
        builder.localMetadata(AqMessage.DELIVERY_DELAY_METADATA, MessageHeaderValue.IntegerValue.create(delay.getSeconds()));
    }

    /**
     * Set a local text metadata value, replacing the value with the same exact name.
     *
     * @param builder target builder
     * @param name exact metadata name
     * @param value text value
     * @throws NullPointerException if {@code name} or {@code value} is {@code null}
     */
    @Prototype.BuilderMethod
    static void localMetadata(AqMessageConfig.BuilderBase<?, ?, ?> builder, String name, String value) {
        builder.localMetadata(Objects.requireNonNull(name, "name"),
                              MessageHeaderValue.TextValue.create(Objects.requireNonNull(value, "value")));
    }

    /**
     * Replace all local metadata with the supplied immutable snapshot.
     *
     * @param builder target builder
     * @param localMetadata local metadata
     * @throws NullPointerException if {@code localMetadata} is {@code null}
     */
    @Prototype.BuilderMethod
    static void localMetadata(AqMessageConfig.BuilderBase<?, ?, ?> builder, MessageMetadata localMetadata) {
        builder.localMetadata(Objects.requireNonNull(localMetadata, "localMetadata").values());
    }

    /**
     * Replace all local metadata using an initially empty metadata builder.
     *
     * @param builder target builder
     * @param consumer metadata builder consumer
     * @throws NullPointerException if {@code consumer} is {@code null}
     */
    @Prototype.BuilderMethod
    static void localMetadata(AqMessageConfig.BuilderBase<?, ?, ?> builder, Consumer<MessageMetadata.Builder> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        MessageMetadata.Builder metadata = MessageMetadata.builder();
        consumer.accept(metadata);
        localMetadata(builder, metadata.build());
    }

    /**
     * Replace all local metadata using a supplier evaluated immediately.
     *
     * @param builder target builder
     * @param supplier local metadata supplier
     * @throws NullPointerException if {@code supplier} or its result is {@code null}
     */
    @Prototype.BuilderMethod
    static void localMetadata(AqMessageConfig.BuilderBase<?, ?, ?> builder, Supplier<? extends MessageMetadata> supplier) {
        localMetadata(builder, Objects.requireNonNull(supplier, "supplier").get());
    }

    static final class BuilderDecorator implements Prototype.BuilderDecorator<AqMessageConfig.BuilderBase<?, ?, ?>> {
        @Override
        public void decorate(AqMessageConfig.BuilderBase<?, ?, ?> target) {
            target.localMetadata().forEach((name, value) -> {
                Objects.requireNonNull(name, "metadata name");
                Objects.requireNonNull(value, "metadata value");
            });
        }
    }
}
