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

package io.helidon.extensions.messaging.tests.poc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorSource;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.Emitter;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageSizeEstimator;
import io.helidon.extensions.messaging.Messaging;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.service.registry.Service;

class ChannelMessagingTypes {
    static final String CHANNEL_ONE = "channel-one";
    static final String CHANNEL_TWO = "channel-two";
    static final String CUSTOM_MESSAGE_CHANNEL = "custom-message-channel";
    static final String CUSTOM_MESSAGE_BATCH_CHANNEL = "custom-message-batch-channel";
    static final String MULTI_HOP_MESSAGE_CHANNEL = "multi-hop-message-channel";
    static final String FORWARDING_INPUT_CHANNEL = "forwarding-input-channel";
    static final String FORWARDING_OUTPUT_CHANNEL = "forwarding-output-channel";
    static final String FAILING_CHANNEL = "failing-channel";
    static final String TEST_CONNECTOR = "test";
    static final String UNKNOWN_CHANNEL = "unknown-channel";

    private ChannelMessagingTypes() {
    }

    @Service.Singleton
    static class Producer {
        @Service.Named(CHANNEL_ONE)
        @Service.Inject
        Emitter<String> channelOne;

        @Service.Named(CHANNEL_TWO)
        @Service.Inject
        Emitter<String> channelTwo;

        @Service.Named(FAILING_CHANNEL)
        @Service.Inject
        Emitter<String> failingChannel;

        @Service.Named(UNKNOWN_CHANNEL)
        @Service.Inject
        Emitter<String> unknownChannel;

        void emitChannelOne(String entity) {
            channelOne.emit(Messaging.message(entity)
                                    .header("key", "value")
                                    .build());
        }

        void emitChannelOneBatch(String first, String second) {
            channelOne.emitBatch(List.of(Messaging.message(first)
                                                 .header("key", "batch-first")
                                                 .build(),
                                         Messaging.message(second)
                                                 .header("key", "batch-second")
                                                 .build()));
        }

        void emitChannelTwo(String entity) {
            channelTwo.emit(entity);
        }

        void emitFailingChannel(String entity) {
            failingChannel.emit(entity);
        }

        void emitUnknownChannel(String entity) {
            unknownChannel.emit(entity);
        }

        boolean emittersInjected() {
            return channelOne != null && channelTwo != null && failingChannel != null && unknownChannel != null;
        }
    }

    @Service.Singleton
    static class FirstChannelOneConsumer extends MessageConsumer {
        private final List<String> keys = new CopyOnWriteArrayList<>();

        @Messaging.OnMessage(CHANNEL_ONE)
        void consume(@Messaging.HeaderParam("key") String key,
                     @Messaging.Entity String payload,
                     Message<String> message) {
            keys.add(key);
            messages().add(Message.builder(payload)
                                   .header("key", message.header("key").orElseThrow())
                                   .build());
        }

        List<String> keys() {
            return keys;
        }
    }

    @Service.Singleton
    static class SecondChannelOneConsumer extends MessageConsumer {
        @Messaging.OnMessage(CHANNEL_ONE)
        void consume(Message<String> message) {
            messages().add(message);
        }
    }

    @Service.Singleton
    static class BatchChannelOneConsumer {
        private final List<List<Message<String>>> batches = new CopyOnWriteArrayList<>();

        @Messaging.OnMessage(CHANNEL_ONE)
        void consume(List<Message<String>> batch) {
            batches.add(List.copyOf(batch));
        }

        List<List<Message<String>>> batches() {
            return batches;
        }
    }

    @Service.Singleton
    static class ChannelTwoConsumer extends MessageConsumer {
        @Messaging.OnMessage(CHANNEL_TWO)
        void consume(String payload) {
            messages().add(Message.builder(payload).build());
        }
    }

    @Service.Singleton
    static class CustomMessageConsumer {
        private final List<CustomMessage<String, Integer>> messages = new CopyOnWriteArrayList<>();

        @Messaging.OnMessage(CUSTOM_MESSAGE_CHANNEL)
        void consume(CustomMessage<String, Integer> message) {
            messages.add(message);
        }

        List<CustomMessage<String, Integer>> messages() {
            return messages;
        }
    }

    @Service.Singleton
    static class BroadCustomMessageConsumer {
        private final List<Message<Integer>> messages = new CopyOnWriteArrayList<>();

        @Messaging.OnMessage(CUSTOM_MESSAGE_CHANNEL)
        void consume(Message<Integer> message) {
            messages.add(message);
        }

        List<Message<Integer>> messages() {
            return messages;
        }
    }

    @Service.Singleton
    static class CustomMessageBatchConsumer {
        private final List<List<CustomMessage<String, Integer>>> batches = new CopyOnWriteArrayList<>();

        @Messaging.OnMessage(CUSTOM_MESSAGE_BATCH_CHANNEL)
        void consume(List<CustomMessage<String, Integer>> batch) {
            batches.add(List.copyOf(batch));
        }

        List<List<CustomMessage<String, Integer>>> batches() {
            return batches;
        }
    }

    @Service.Singleton
    static class MultiHopMessageConsumer {
        private final List<MultiHopMessage<String, List<Integer>>> messages = new CopyOnWriteArrayList<>();

        @Messaging.OnMessage(MULTI_HOP_MESSAGE_CHANNEL)
        void consume(MultiHopMessage<String, List<Integer>> message) {
            messages.add(message);
        }

        List<MultiHopMessage<String, List<Integer>>> messages() {
            return messages;
        }
    }

    @Service.Singleton
    static class ForwardingProcessor {
        @Service.Named(FORWARDING_OUTPUT_CHANNEL)
        @Service.Inject
        Emitter<String> output;

        @Messaging.OnMessage(FORWARDING_INPUT_CHANNEL)
        void forward(String payload) {
            output.emit(Messaging.message("forwarded: " + payload)
                                .header("processor", "forwarding")
                                .build());
        }
    }

    @Service.Singleton
    static class ForwardedMessageConsumer extends MessageConsumer {
        private final MessagingException failure = new MessagingException("downstream handler failed",
                                                                          new IOException("downstream I/O failed"));

        @Messaging.OnMessage(FORWARDING_OUTPUT_CHANNEL)
        void consume(Message<String> message) {
            if (message.entity().equals("forwarded: fail")) {
                throw failure;
            }
            messages().add(message);
        }

        MessagingException failure() {
            return failure;
        }
    }

    @Service.Singleton
    static class FailingConsumer {
        private final MessagingException failure = new MessagingException("handler failed",
                                                                          new IOException("handler I/O failed"));

        @Messaging.OnMessage(FAILING_CHANNEL)
        void consume(String payload) {
            throw failure;
        }

        MessagingException failure() {
            return failure;
        }
    }

    @Service.Singleton
    static class TestIncomingConnector implements IncomingConnector<ConnectorConfig> {
        private final CountDownLatch deliveryCompleted = new CountDownLatch(1);
        private final AtomicReference<RuntimeException> deliveryFailure = new AtomicReference<>();

        @Override
        public String connectorName() {
            return TEST_CONNECTOR;
        }

        @Override
        public ConnectorSource createSource(ConnectorConfig config, ConnectorSourceContext context) {
            return () -> {
                try {
                    context.emit(Message.builder("connector message")
                                         .header("key", "connector")
                                         .build());
                } catch (RuntimeException e) {
                    deliveryFailure.set(e);
                } finally {
                    deliveryCompleted.countDown();
                }
            };
        }

        boolean awaitDelivery() throws InterruptedException {
            return deliveryCompleted.await(10, TimeUnit.SECONDS);
        }

        Optional<RuntimeException> deliveryFailure() {
            return Optional.ofNullable(deliveryFailure.get());
        }
    }

    @Service.Singleton
    static class CustomMessageSizeEstimator implements MessageSizeEstimator {
        @Override
        public OptionalLong estimate(Message<?> message) {
            Object key;
            if (message instanceof CustomMessage<?, ?> customMessage) {
                key = customMessage.key();
            } else if (message instanceof IntermediateMessage<?, ?> intermediateMessage) {
                key = intermediateMessage.key();
            } else {
                return OptionalLong.empty();
            }
            OptionalLong keyBytes = contentBytes(key);
            OptionalLong payloadBytes = contentBytes(message.entity());
            if (keyBytes.isEmpty() || payloadBytes.isEmpty()) {
                return OptionalLong.empty();
            }
            try {
                long result = Math.addExact(keyBytes.getAsLong(), payloadBytes.getAsLong());
                for (Map.Entry<String, String> header : message.headers().entrySet()) {
                    result = Math.addExact(result, utf8Bytes(header.getKey()));
                    result = Math.addExact(result, utf8Bytes(header.getValue()));
                }
                return OptionalLong.of(result);
            } catch (ArithmeticException e) {
                return OptionalLong.empty();
            }
        }

        private OptionalLong contentBytes(Object value) {
            if (value == null) {
                return OptionalLong.of(0);
            }
            if (value instanceof byte[] bytes) {
                return OptionalLong.of(bytes.length);
            }
            if (value instanceof ByteBuffer buffer) {
                return OptionalLong.of(buffer.capacity());
            }
            if (value instanceof String text) {
                return OptionalLong.of(utf8Bytes(text));
            }
            if (value instanceof Byte || value instanceof Boolean) {
                return OptionalLong.of(Byte.BYTES);
            }
            if (value instanceof Short || value instanceof Character) {
                return OptionalLong.of(Short.BYTES);
            }
            if (value instanceof Integer || value instanceof Float) {
                return OptionalLong.of(Integer.BYTES);
            }
            if (value instanceof Long || value instanceof Double) {
                return OptionalLong.of(Long.BYTES);
            }
            if (value instanceof List<?> values) {
                long result = 0;
                for (Object element : values) {
                    OptionalLong elementBytes = contentBytes(element);
                    if (elementBytes.isEmpty()) {
                        return OptionalLong.empty();
                    }
                    try {
                        result = Math.addExact(result, elementBytes.getAsLong());
                    } catch (ArithmeticException e) {
                        return OptionalLong.empty();
                    }
                }
                return OptionalLong.of(result);
            }
            return OptionalLong.empty();
        }

        private int utf8Bytes(String value) {
            return value.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    abstract static class MessageConsumer {
        private final List<Message<String>> messages = new CopyOnWriteArrayList<>();

        List<Message<String>> messages() {
            return messages;
        }
    }

    interface CustomMessage<K, V> extends Message<V> {
        K key();
    }

    record ImmutableCustomMessage<K, V>(K key, V entity, Map<String, String> headers) implements CustomMessage<K, V> {
        ImmutableCustomMessage {
            headers = Map.copyOf(headers);
        }
    }

    interface IntermediateMessage<P, I> extends Message<P> {
        I key();
    }

    interface MultiHopMessage<K, V> extends IntermediateMessage<V, K> {
    }

    record ImmutableMultiHopMessage<K, V>(K key, V entity, Map<String, String> headers)
            implements MultiHopMessage<K, V> {
        ImmutableMultiHopMessage {
            headers = Map.copyOf(headers);
        }
    }
}
