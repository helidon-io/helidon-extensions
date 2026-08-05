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
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.helidon.common.GenericType;

/**
 * Internal in-memory messaging channel runtime.
 *
 * @param <T> payload type
 */
final class DefaultMessagingChannel<T> implements MessagingChannel<T>, Emitter<T> {
    private final GenericType<T> payloadType;
    private final List<Consumer<List<Message<?>>>> outputs;
    private final DeliveryEngine deliveryEngine;
    private final String channelName;
    private final DefaultMessagingGraph graph;

    private DefaultMessagingChannel(GenericType<T> payloadType,
                                    List<Consumer<List<Message<?>>>> outputs,
                                    DeliveryEngine deliveryEngine,
                                    String channelName,
                                    DefaultMessagingGraph graph) {
        this.payloadType = payloadType;
        this.outputs = new CopyOnWriteArrayList<>(outputs);
        this.deliveryEngine = deliveryEngine;
        this.channelName = channelName;
        this.graph = graph;
    }

    @Override
    public String name() {
        return channelName;
    }

    @Override
    public GenericType<T> payloadType() {
        return payloadType;
    }

    @Override
    public void emit(T entity) {
        emitMessage(Message.create(entity));
    }

    @Override
    public void emitMessage(Message<? extends T> message) {
        emitBatch(List.of(message));
    }

    @Override
    public void emitBatch(List<? extends Message<? extends T>> messages) {
        emitBatchObject(messages);
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

    DefaultMessagingGraph graph() {
        return graph;
    }

    void emitPayloadObject(Object entity) {
        emitBatchObject(List.of(Message.create(entity)));
    }

    void emitMessageObject(Message<?> message) {
        emitBatchObject(List.of(Objects.requireNonNull(message)));
    }

    void emitBatchObject(List<? extends Message<?>> messages) {
        Objects.requireNonNull(messages);
        if (messages.isEmpty()) {
            return;
        }
        graph.ensureRunning();
        List<Message<?>> batch = toBatch(messages);
        deliveryEngine.dispatch(channelName, batch, () -> dispatchBatch(batch));
    }

    private void dispatchBatch(List<Message<?>> batch) {
        for (Consumer<List<Message<?>>> output : outputs) {
            output.accept(batch);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Message<T> toMessage(Object value) {
        Message<?> message = (Message<?>) value;
        if (isExpectedPayload(message)) {
            return (Message<T>) message;
        }
        throw new IllegalArgumentException("Channel expected payload type "
                                                   + payloadType.getTypeName()
                                                   + " but received " + message.entity().getClass().getName());
    }

    private boolean isExpectedPayload(Message<?> message) {
        Object entity = message.entity();
        return entity == null || payloadType.rawType().isInstance(entity);
    }

    static final class Builder<T> {
        private final List<Consumer<List<Message<?>>>> outputs = new ArrayList<>();
        private final List<ConnectorSink> connectorOutputs = new ArrayList<>();
        private GenericType<T> payloadType;
        private MessagingExecutionConfig executionConfig;
        private DefaultMessagingGraph messagingGraph;
        private String channelName;

        Builder<T> payloadType(Class<T> payloadType) {
            return payloadType(GenericType.create(payloadType));
        }

        Builder<T> payloadType(GenericType<T> payloadType) {
            this.payloadType = Objects.requireNonNull(payloadType);
            return this;
        }

        Builder<T> addOutput(Consumer<Message<T>> output) {
            outputs.add(messages -> messages.forEach(message -> output.accept(cast(message))));
            return this;
        }

        Builder<T> addBatchOutput(Consumer<List<Message<T>>> output) {
            outputs.add(messages -> output.accept(castBatch(messages)));
            return this;
        }

        Builder<T> addOutgoingConnector(ConnectorSink output) {
            ConnectorSink connector = Objects.requireNonNull(output);
            connectorOutputs.add(connector);
            outputs.add(messages -> DefaultMessagingChannel.send(connector, messages));
            return this;
        }

        DefaultMessagingChannel<T> build() {
            GenericType<T> actualPayloadType = Objects.requireNonNull(payloadType, "payloadType");
            String actualChannelName = Objects.requireNonNull(channelName, "channelName");
            DefaultMessagingGraph actualGraph = Objects.requireNonNull(messagingGraph, "messagingGraph");
            MessagingExecutionConfig actualExecutionConfig = Objects.requireNonNull(executionConfig, "executionConfig");
            DefaultMessagingChannel<T> channel = new DefaultMessagingChannel<>(actualPayloadType,
                                                                               outputs,
                                                                               actualGraph.deliveryEngine(),
                                                                               actualChannelName,
                                                                               actualGraph);
            actualGraph.addChannelContribution(actualChannelName,
                                               channel,
                                               actualExecutionConfig,
                                               java.util.Map.of(),
                                               connectorOutputs,
                                               List.of());
            return channel;
        }

        Builder<T> messagingGraph(DefaultMessagingGraph messagingGraph,
                                  String channelName,
                                  MessagingExecutionConfig executionConfig) {
            this.messagingGraph = Objects.requireNonNull(messagingGraph);
            this.channelName = Objects.requireNonNull(channelName);
            this.executionConfig = Objects.requireNonNull(executionConfig);
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

    static ConnectorSource streamSource(Stream<?> stream, Consumer<Object> consumer) {
        return new StreamSource(stream, consumer);
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

    private static final class StreamSource implements ConnectorSource, ConnectorEndpoint {
        private final Stream<?> stream;
        private final Consumer<Object> consumer;
        private final AtomicBoolean runStarted = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean forceCloseRequested = new AtomicBoolean();
        private final AtomicBoolean streamClosed = new AtomicBoolean();
        private final AtomicReference<Thread> owner = new AtomicReference<>();

        private StreamSource(Stream<?> stream, Consumer<Object> consumer) {
            this.stream = stream;
            this.consumer = consumer;
        }

        @Override
        public void run() {
            if (!runStarted.compareAndSet(false, true)) {
                throw new IllegalStateException("Messaging stream source can only be run once");
            }
            if (closed.get()) {
                return;
            }
            Thread current = Thread.currentThread();
            owner.set(current);
            try {
                Iterator<?> iterator = stream.sequential().iterator();
                while (!closed.get() && iterator.hasNext()) {
                    consumer.accept(iterator.next());
                }
            } finally {
                closed.set(true);
                owner.compareAndSet(current, null);
                if (!forceCloseRequested.get()) {
                    closeStream();
                }
            }
        }

        @Override
        public void forceClose() {
            forceCloseRequested.set(true);
            stop();
        }

        @Override
        public void close() {
            stop();
            closeStream();
        }

        private void stop() {
            closed.set(true);
            Thread current = owner.get();
            if (current != null && current != Thread.currentThread()) {
                current.interrupt();
            }
        }

        private void closeStream() {
            if (streamClosed.compareAndSet(false, true)) {
                stream.close();
            }
        }
    }
}
