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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Imperative in-memory messaging channel used by the declarative POC runtime.
 *
 * @param <T> payload type
 */
final class DefaultMessagingChannel<T> implements MessagingChannel<T> {
    private static final AtomicLong CHANNEL_SEQUENCE = new AtomicLong();

    private final Class<T> payloadType;
    private final List<Stream<?>> inputs;
    private final List<Consumer<List<Message<?>>>> outputs;
    private final DeliveryEngine deliveryEngine;
    private final String channelName;
    private final boolean ownsDeliveryEngine;
    private final AtomicBoolean started = new AtomicBoolean();

    private DefaultMessagingChannel(Class<T> payloadType,
                                    List<Stream<?>> inputs,
                                    List<Consumer<List<Message<?>>>> outputs,
                                    DeliveryEngine deliveryEngine,
                                    String channelName,
                                    boolean ownsDeliveryEngine) {
        this.payloadType = payloadType;
        this.inputs = List.copyOf(inputs);
        this.outputs = new CopyOnWriteArrayList<>(outputs);
        this.deliveryEngine = deliveryEngine;
        this.channelName = channelName;
        this.ownsDeliveryEngine = ownsDeliveryEngine;
    }

    @Override
    public void emit(T entity) {
        emit(Message.create(entity));
    }

    @Override
    public void emit(Message<T> message) {
        emitBatch(List.of(message));
    }

    @Override
    public void emitBatch(List<? extends Message<T>> messages) {
        emitBatchObject(messages);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        int index = 0;
        for (Stream<?> input : inputs) {
            deliveryEngine.startSource(channelName + "-input-" + ++index, () -> drainInput(input));
        }
    }

    @Override
    public void close() {
        if (ownsDeliveryEngine) {
            deliveryEngine.close();
        }
    }

    void addOutput(Consumer<Message<?>> output) {
        outputs.add(messages -> messages.forEach(output));
    }

    void addBatchOutput(Consumer<List<Message<?>>> output) {
        outputs.add(output);
    }

    void addOutgoingConnector(ConnectorSink output) {
        outputs.add(messages -> send(output, messages));
    }

    void emitObject(Object value) {
        Message<T> message = toMessage(value);
        emitBatch(List.of(message));
    }

    void emitBatchObject(List<? extends Message<?>> messages) {
        Objects.requireNonNull(messages);
        if (messages.isEmpty()) {
            return;
        }
        List<Message<?>> batch = toBatch(messages);
        deliveryEngine.dispatch(channelName, batch, () -> dispatchBatch(batch));
    }

    private void dispatchBatch(List<Message<?>> batch) {
        for (Consumer<List<Message<?>>> output : outputs) {
            output.accept(batch);
        }
    }

    private void drainInput(Stream<?> input) {
        try (input) {
            input.sequential().forEachOrdered(this::emitObject);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Message<T> toMessage(Object value) {
        Message<?> message = value instanceof Message<?> typed ? typed : Message.create(value);
        if (isExpectedPayload(message)) {
            return (Message<T>) message;
        }
        throw new IllegalArgumentException("Channel expected payload type "
                                                   + payloadType.getName()
                                                   + " but received " + message.entity().getClass().getName());
    }

    private boolean isExpectedPayload(Message<?> message) {
        Object entity = message.entity();
        return payloadType == null || entity == null || payloadType.isInstance(entity);
    }

    static final class Builder<T> implements MessagingChannel.Builder<T> {
        private final List<Stream<?>> inputs = new ArrayList<>();
        private final List<MessagingChannel<?>> inputChannels = new ArrayList<>();
        private final List<Consumer<List<Message<?>>>> outputs = new ArrayList<>();
        private final List<MessageSizeEstimator> messageSizeEstimators = new ArrayList<>();
        private Class<T> payloadType;
        private MessagingExecutionConfig executionConfig = MessagingExecutionConfig.builder().build();
        private DeliveryEngine deliveryEngine;
        private String channelName;

        @Override
        public MessagingChannel.Builder<T> payloadType(Class<T> payloadType) {
            this.payloadType = Objects.requireNonNull(payloadType);
            return this;
        }

        @Override
        public MessagingChannel.Builder<T> executionConfig(MessagingExecutionConfig executionConfig) {
            this.executionConfig = Objects.requireNonNull(executionConfig);
            return this;
        }

        @Override
        public MessagingChannel.Builder<T> addMessageSizeEstimator(MessageSizeEstimator estimator) {
            messageSizeEstimators.add(Objects.requireNonNull(estimator));
            return this;
        }

        @Override
        public MessagingChannel.Builder<T> addInput(Stream<?> input) {
            inputs.add(Objects.requireNonNull(input));
            return this;
        }

        @Override
        public MessagingChannel.Builder<T> addInput(MessagingChannel<?> input) {
            inputChannels.add(input);
            return this;
        }

        @Override
        public MessagingChannel.Builder<T> addOutput(Consumer<Message<T>> output) {
            outputs.add(messages -> messages.forEach(message -> output.accept(cast(message))));
            return this;
        }

        @Override
        public MessagingChannel.Builder<T> addBatchOutput(Consumer<List<Message<T>>> output) {
            outputs.add(messages -> output.accept(castBatch(messages)));
            return this;
        }

        @Override
        public MessagingChannel.Builder<T> addOutgoingConnector(ConnectorSink output) {
            outputs.add(messages -> DefaultMessagingChannel.send(output, messages));
            return this;
        }

        @Override
        public MessagingChannel<T> build() {
            boolean ownsDeliveryEngine = deliveryEngine == null;
            DeliveryEngine actualDeliveryEngine = ownsDeliveryEngine
                    ? new DeliveryEngine(executionConfig, messageSizeEstimators)
                    : deliveryEngine;
            String actualChannelName = channelName == null
                    ? "imperative-" + CHANNEL_SEQUENCE.incrementAndGet()
                    : channelName;
            if (ownsDeliveryEngine) {
                actualDeliveryEngine.registerChannel(actualChannelName, executionConfig);
            }
            DefaultMessagingChannel<T> channel = new DefaultMessagingChannel<>(payloadType,
                                                                               inputs,
                                                                               outputs,
                                                                               actualDeliveryEngine,
                                                                               actualChannelName,
                                                                               ownsDeliveryEngine);

            for (MessagingChannel<?> inputChannel : inputChannels) {
                if (!(inputChannel instanceof DefaultMessagingChannel<?> defaultInputChannel)) {
                    throw new IllegalArgumentException("Unsupported channel implementation "
                                                               + inputChannel.getClass().getName());
                }
                defaultInputChannel.addBatchOutput(channel::emitBatchObject);
            }
            return channel;
        }

        Builder<T> deliveryEngine(DeliveryEngine deliveryEngine,
                                  String channelName,
                                  MessagingExecutionConfig executionConfig) {
            this.deliveryEngine = Objects.requireNonNull(deliveryEngine);
            this.channelName = Objects.requireNonNull(channelName);
            this.executionConfig = Objects.requireNonNull(executionConfig);
            deliveryEngine.registerChannel(channelName, executionConfig);
            return this;
        }

        @SuppressWarnings("unchecked")
        private Message<T> cast(Message<?> message) {
            return (Message<T>) message;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private List<Message<T>> castBatch(List<Message<?>> messages) {
            return (List) messages;
        }

    }

    private List<Message<?>> toBatch(List<? extends Message<?>> messages) {
        List<Message<?>> batch = new ArrayList<>(messages.size());
        for (Message<?> message : messages) {
            batch.add(toMessage(Objects.requireNonNull(message)));
        }
        return List.copyOf(batch);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void send(ConnectorSink output, List<Message<?>> messages) {
        output.sendBatch((List) messages);
    }
}
