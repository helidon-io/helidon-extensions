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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    private final List<Consumer<List<Message<?>>>> outputs;
    private final DeliveryEngine deliveryEngine;
    private final String channelName;
    private final MessagingGraph graph;

    private DefaultMessagingChannel(Class<T> payloadType,
                                    List<Consumer<List<Message<?>>>> outputs,
                                    DeliveryEngine deliveryEngine,
                                    String channelName,
                                    MessagingGraph graph) {
        this.payloadType = payloadType;
        this.outputs = new CopyOnWriteArrayList<>(outputs);
        this.deliveryEngine = deliveryEngine;
        this.channelName = channelName;
        this.graph = graph;
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
        if (graph != null) {
            graph.start();
        }
    }

    @Override
    public void close() {
        if (graph != null) {
            graph.close();
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
        if (graph != null) {
            graph.ensureRunning();
        }
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
        private final List<ConnectorSink> connectorOutputs = new ArrayList<>();
        private final List<MessageSizeEstimator> messageSizeEstimators = new ArrayList<>();
        private Class<T> payloadType;
        private MessagingExecutionConfig executionConfig = MessagingExecutionConfig.builder().build();
        private MessagingGraph messagingGraph;
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
            inputChannels.add(Objects.requireNonNull(input));
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
            ConnectorSink connector = Objects.requireNonNull(output);
            connectorOutputs.add(connector);
            outputs.add(messages -> DefaultMessagingChannel.send(connector, messages));
            return this;
        }

        @Override
        public MessagingChannel<T> build() {
            String actualChannelName = channelName == null
                    ? "imperative-" + CHANNEL_SEQUENCE.incrementAndGet()
                    : channelName;
            MessagingGraph actualGraph = messagingGraph == null ? imperativeGraph() : messagingGraph;
            DeliveryEngine actualDeliveryEngine = actualGraph.deliveryEngine();
            DefaultMessagingChannel<T> channel = new DefaultMessagingChannel<>(payloadType,
                                                                               outputs,
                                                                               actualDeliveryEngine,
                                                                               actualChannelName,
                                                                               actualGraph);

            Map<String, ConnectorSource> channelSources = new LinkedHashMap<>();
            int inputIndex = 0;
            for (Stream<?> input : inputs) {
                channelSources.put(actualChannelName + "-input-" + ++inputIndex,
                                   new StreamSource(input, channel::emitObject));
            }
            List<String> inputChannelNames = new ArrayList<>(inputChannels.size());
            for (MessagingChannel<?> inputChannel : inputChannels) {
                DefaultMessagingChannel<?> defaultInputChannel = (DefaultMessagingChannel<?>) inputChannel;
                if (defaultInputChannel.graph == actualGraph) {
                    inputChannelNames.add(defaultInputChannel.channelName);
                }
            }
            actualGraph.addChannelContribution(actualChannelName,
                                               channel,
                                               executionConfig,
                                               channelSources,
                                               connectorOutputs,
                                               inputChannelNames,
                                               () -> {
                                                   for (MessagingChannel<?> inputChannel : inputChannels) {
                                                       DefaultMessagingChannel<?> defaultInputChannel =
                                                               (DefaultMessagingChannel<?>) inputChannel;
                                                       defaultInputChannel.addBatchOutput(channel::emitBatchObject);
                                                   }
                                               });
            return channel;
        }

        private MessagingGraph imperativeGraph() {
            if (inputChannels.isEmpty()) {
                return newImperativeGraph();
            }

            MessagingGraph graph = null;
            boolean sharedGraph = true;
            for (MessagingChannel<?> inputChannel : inputChannels) {
                if (!(inputChannel instanceof DefaultMessagingChannel<?> defaultInputChannel)) {
                    throw new IllegalArgumentException("Unsupported channel implementation "
                                                               + inputChannel.getClass().getName());
                }
                if (defaultInputChannel.graph == null) {
                    throw new IllegalArgumentException("Declarative channels cannot be imperative graph inputs");
                }
                if (graph == null) {
                    graph = defaultInputChannel.graph;
                } else if (graph != defaultInputChannel.graph) {
                    sharedGraph = false;
                }
            }
            if (!sharedGraph) {
                return newImperativeGraph();
            }
            if (!messageSizeEstimators.isEmpty()) {
                throw new IllegalArgumentException("Message size estimators must be configured on the first channel "
                                                           + "of an imperative messaging graph");
            }
            if (!graph.deliveryEngine().shutdownTimeout().equals(executionConfig.shutdownTimeout())) {
                throw new IllegalArgumentException("Every channel in an imperative messaging graph must use the same "
                                                           + "shutdown-timeout");
            }
            return graph;
        }

        private MessagingGraph newImperativeGraph() {
            DeliveryEngine engine = new DeliveryEngine(executionConfig, messageSizeEstimators);
            return new MessagingGraph(engine);
        }

        Builder<T> messagingGraph(MessagingGraph messagingGraph,
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

    private static final class StreamSource implements ConnectorSource, ManagedConnectorBinding {
        private final Stream<?> stream;
        private final Consumer<Object> consumer;
        private final AtomicBoolean runStarted = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
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
            } catch (MessagingRejectedException e) {
                if (e.reason() != MessagingRejectedException.Reason.SHUTDOWN) {
                    throw e;
                }
            } finally {
                closed.set(true);
                owner.compareAndSet(current, null);
                closeStream();
            }
        }

        @Override
        public void forceClose() {
            close();
        }

        @Override
        public void close() {
            closed.set(true);
            closeStream();
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
