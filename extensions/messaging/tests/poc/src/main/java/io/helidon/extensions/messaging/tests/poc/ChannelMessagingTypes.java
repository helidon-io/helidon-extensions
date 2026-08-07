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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.config.Config;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.Emitter;
import io.helidon.extensions.messaging.IncomingConnectorProvider;
import io.helidon.extensions.messaging.IncomingEndpoint;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.MessageSizeEstimator;
import io.helidon.extensions.messaging.Messaging;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.service.registry.Interception;
import io.helidon.service.registry.InterceptionContext;
import io.helidon.service.registry.Service;

class ChannelMessagingTypes {
    static final String CHANNEL_ONE = "channel-one";
    static final String CHANNEL_TWO = "channel-two";
    static final String CUSTOM_MESSAGE_CHANNEL = "custom-message-channel";
    static final String MULTI_HOP_MESSAGE_CHANNEL = "multi-hop-message-channel";
    static final String FORWARDING_INPUT_CHANNEL = "forwarding-input-channel";
    static final String FORWARDING_OUTPUT_CHANNEL = "forwarding-output-channel";
    static final String PAYLOAD_PROCESSOR_INPUT_CHANNEL = "payload-processor-input-channel";
    static final String PAYLOAD_PROCESSOR_OUTPUT_CHANNEL = "payload-processor-output-channel";
    static final String ARRAY_PROCESSOR_INPUT_CHANNEL = "array-processor-input-channel";
    static final String ARRAY_PROCESSOR_OUTPUT_CHANNEL = "array-processor-output-channel";
    static final String REQUIRED_HEADER_CHANNEL = "required-header-channel";
    static final String OPTIONAL_HEADER_CHANNEL = "optional-header-channel";
    static final String PER_LOOKUP_INTERCEPTED_CHANNEL = "per-lookup-intercepted-channel";
    static final String FAILING_CHANNEL = "failing-channel";
    static final String TEST_CONNECTOR = "test";
    static final String SHUTDOWN_CHANNEL = "shutdown-channel";
    static final String SHUTDOWN_CONNECTOR = "shutdown-test";

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

        void emitChannelOne(String entity) {
            channelOne.emitMessage(Messaging.message(entity)
                                           .header("key", "value")
                                           .build());
        }

        void emitChannelOneBatch(String first, String second) {
            channelOne.emitBatch(MessageBatch.create(List.of(Messaging.message(first)
                                                                     .header("key", "batch-first")
                                                                     .build(),
                                                             Messaging.message(second)
                                                                     .header("key", "batch-second")
                                                                     .build())));
        }

        void emitChannelTwo(String entity) {
            channelTwo.emit(entity);
        }

        void emitFailingChannel(String entity) {
            failingChannel.emit(entity);
        }

        boolean emittersInjected() {
            return channelOne != null && channelTwo != null && failingChannel != null;
        }
    }

    @Service.Singleton
    static class FirstChannelOneConsumer extends MessageConsumer {
        private final List<String> keys = new CopyOnWriteArrayList<>();

        @Messaging.ReceiveFrom(CHANNEL_ONE)
        void consume(@Messaging.HeaderParam("key") String key,
                     Message<String> message) {
            keys.add(key);
            messages().add(message);
        }

        List<String> keys() {
            return keys;
        }
    }

    @Service.Singleton
    static class SecondChannelOneConsumer extends MessageConsumer {
        @Messaging.ReceiveFrom(CHANNEL_ONE)
        void consume(Message<String> message) {
            messages().add(message);
        }
    }

    @Service.Singleton
    static class BatchChannelOneConsumer {
        private final List<MessageBatch<String>> batches = new CopyOnWriteArrayList<>();

        @Messaging.ReceiveFrom(CHANNEL_ONE)
        void consume(MessageBatch<String> batch) {
            batches.add(batch);
        }

        List<MessageBatch<String>> batches() {
            return batches;
        }
    }

    @Service.Singleton
    static class ChannelTwoConsumer extends MessageConsumer {
        @Messaging.ReceiveFrom(CHANNEL_TWO)
        void consume(String payload) {
            messages().add(Message.builder(payload).build());
        }
    }

    @Service.Singleton
    static class CustomMessageConsumer {
        private final List<CustomMessage<String, Integer>> messages = new CopyOnWriteArrayList<>();

        @Messaging.ReceiveFrom(CUSTOM_MESSAGE_CHANNEL)
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

        @Messaging.ReceiveFrom(CUSTOM_MESSAGE_CHANNEL)
        void consume(Message<Integer> message) {
            messages.add(message);
        }

        List<Message<Integer>> messages() {
            return messages;
        }
    }

    @Service.Singleton
    static class MultiHopMessageConsumer {
        private final List<MultiHopMessage<String, List<Integer>>> messages = new CopyOnWriteArrayList<>();

        @Messaging.ReceiveFrom(MULTI_HOP_MESSAGE_CHANNEL)
        void consume(MultiHopMessage<String, List<Integer>> message) {
            messages.add(message);
        }

        List<MultiHopMessage<String, List<Integer>>> messages() {
            return messages;
        }
    }

    @Service.Singleton
    static class ForwardingProcessor {
        private final MessagingException failure = new MessagingException("processor failed",
                                                                          new IOException("processor I/O failed"));

        @Messaging.ReceiveFrom(FORWARDING_INPUT_CHANNEL)
        @Messaging.SendTo(FORWARDING_OUTPUT_CHANNEL)
        Message<String> forward(String payload) {
            if ("processor-fail".equals(payload)) {
                throw failure;
            }
            return Messaging.message("forwarded: " + payload)
                    .header("processor", "forwarding")
                    .build();
        }

        MessagingException failure() {
            return failure;
        }
    }

    @Service.Singleton
    static class PayloadProcessor {
        @Messaging.ReceiveFrom(PAYLOAD_PROCESSOR_INPUT_CHANNEL)
        @Messaging.SendTo(PAYLOAD_PROCESSOR_OUTPUT_CHANNEL)
        String process(String payload) {
            return "processed: " + payload;
        }
    }

    @Service.Singleton
    static class PayloadProcessorConsumer extends MessageConsumer {
        @Messaging.ReceiveFrom(PAYLOAD_PROCESSOR_OUTPUT_CHANNEL)
        void consume(Message<String> message) {
            messages().add(message);
        }
    }

    @Service.Singleton
    static class ArrayEnvelopeProcessor {
        @Messaging.ReceiveFrom(ARRAY_PROCESSOR_INPUT_CHANNEL)
        @Messaging.SendTo(ARRAY_PROCESSOR_OUTPUT_CHANNEL)
        ArrayMessage<String> process(String payload) {
            String processed = "processed: " + payload;
            long admissionBytes = payload.getBytes(StandardCharsets.UTF_8).length
                    + processed.getBytes(StandardCharsets.UTF_8).length;
            return new ImmutableArrayMessage<>(new String[][] {{payload}, {processed}}, Map.of(), admissionBytes);
        }
    }

    @Service.Singleton
    static class ArrayPayloadConsumer {
        private final List<String[][]> payloads = new CopyOnWriteArrayList<>();

        @Messaging.ReceiveFrom(ARRAY_PROCESSOR_OUTPUT_CHANNEL)
        void consume(String[][] payload) {
            payloads.add(payload);
        }

        List<String[][]> payloads() {
            return payloads;
        }
    }

    @Service.Singleton
    static class RequiredHeaderConsumer {
        private final List<HeaderDelivery> deliveries = new CopyOnWriteArrayList<>();

        @Messaging.ReceiveFrom(REQUIRED_HEADER_CHANNEL)
        void consume(@Messaging.Entity String payload,
                     @Messaging.HeaderParam("required") String required) {
            deliveries.add(new HeaderDelivery(payload, required));
        }

        List<HeaderDelivery> deliveries() {
            return deliveries;
        }
    }

    @Service.Singleton
    static class OptionalHeaderConsumer {
        private final List<OptionalHeaderDelivery> deliveries = new CopyOnWriteArrayList<>();

        @Messaging.ReceiveFrom(OPTIONAL_HEADER_CHANNEL)
        void consume(@Messaging.Entity String payload,
                     @Messaging.HeaderParam("trace-id") Optional<String> traceId) {
            deliveries.add(new OptionalHeaderDelivery(payload, traceId));
        }

        List<OptionalHeaderDelivery> deliveries() {
            return deliveries;
        }
    }

    @Service.Singleton
    static class ForwardedMessageConsumer extends MessageConsumer {
        private final MessagingException failure = new MessagingException("downstream handler failed",
                                                                          new IOException("downstream I/O failed"));

        @Messaging.ReceiveFrom(FORWARDING_OUTPUT_CHANNEL)
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
    static class ForwardedBatchConsumer {
        private final List<MessageBatch<String>> batches = new CopyOnWriteArrayList<>();

        @Messaging.ReceiveFrom(FORWARDING_OUTPUT_CHANNEL)
        void consume(MessageBatch<String> batch) {
            batches.add(batch);
        }

        List<MessageBatch<String>> batches() {
            return batches;
        }
    }

    @Service.Singleton
    static class FailingConsumer {
        private final MessagingException failure = new MessagingException("handler failed",
                                                                          new IOException("handler I/O failed"));

        @Messaging.ReceiveFrom(FAILING_CHANNEL)
        void consume(String payload) {
            throw failure;
        }

        MessagingException failure() {
            return failure;
        }
    }

    @Service.PerLookup
    static class PerLookupInterceptedConsumer {
        private static final Queue<PerLookupInterceptedConsumer> INSTANCES = new ConcurrentLinkedQueue<>();

        @Messaging.ReceiveFrom(PER_LOOKUP_INTERCEPTED_CHANNEL)
        void consume(String payload) {
            INSTANCES.add(this);
        }

        static void reset() {
            INSTANCES.clear();
        }

        static List<PerLookupInterceptedConsumer> instances() {
            return List.copyOf(INSTANCES);
        }
    }

    @SuppressWarnings({"deprecation", "helidon:api:incubating"})
    @Service.Singleton
    static class TestEntryPointInterceptor implements Interception.EntryPointInterceptor {
        private static final Queue<String> EXECUTIONS = new ConcurrentLinkedQueue<>();
        private static final Queue<InterceptedInstance> INTERCEPTED_INSTANCES = new ConcurrentLinkedQueue<>();

        @Override
        public <T> T proceed(InterceptionContext invocationContext,
                             Interception.Interceptor.Chain<T> chain,
                             Object... args) throws Exception {
            String serviceType = invocationContext.serviceInfo().serviceType().fqName();
            EXECUTIONS.add(serviceType + "." + invocationContext.elementInfo().signature().text());
            INTERCEPTED_INSTANCES.add(new InterceptedInstance(serviceType,
                                                              invocationContext.serviceInstance().orElseThrow()));
            return chain.proceed(args);
        }

        static void reset() {
            EXECUTIONS.clear();
            INTERCEPTED_INSTANCES.clear();
        }

        static List<String> executions() {
            return List.copyOf(EXECUTIONS);
        }

        static List<Object> serviceInstances(Class<?> serviceType) {
            return INTERCEPTED_INSTANCES.stream()
                    .filter(instance -> instance.serviceType().equals(serviceType.getCanonicalName()))
                    .map(InterceptedInstance::serviceInstance)
                    .toList();
        }

        private record InterceptedInstance(String serviceType, Object serviceInstance) {
        }
    }

    @Service.Singleton
    static class TestConnectorObserver {
        private final CountDownLatch deliveryCompleted = new CountDownLatch(1);
        private final AtomicReference<RuntimeException> deliveryFailure = new AtomicReference<>();

        void deliveryCompleted(RuntimeException failure) {
            deliveryFailure.set(failure);
            deliveryCompleted.countDown();
        }

        boolean awaitDelivery() throws InterruptedException {
            return deliveryCompleted.await(10, TimeUnit.SECONDS);
        }

        Optional<RuntimeException> deliveryFailure() {
            return Optional.ofNullable(deliveryFailure.get());
        }
    }

    @Service.Singleton
    static class TestIncomingConnector implements IncomingConnectorProvider<ConnectorConfig> {
        private final TestConnectorObserver observer;

        @Service.Inject
        TestIncomingConnector(TestConnectorObserver observer) {
            this.observer = observer;
        }

        @Override
        public String connectorType() {
            return TEST_CONNECTOR;
        }

        @Override
        public ConnectorConfig createConfig(Config config) {
            return ConnectorConfig.create(config);
        }

        @Override
        public IncomingEndpoint createIncomingEndpoint(ConnectorConfig config, ConnectorSourceContext context) {
            return new TestIncomingEndpoint(context, observer);
        }

        private static final class TestIncomingEndpoint implements IncomingEndpoint {
            private final ConnectorSourceContext context;
            private final TestConnectorObserver observer;
            private final CountDownLatch ready = new CountDownLatch(1);
            private final CountDownLatch admission = new CountDownLatch(1);
            private final CountDownLatch stop = new CountDownLatch(1);
            private final AtomicBoolean stopped = new AtomicBoolean();

            private TestIncomingEndpoint(ConnectorSourceContext context, TestConnectorObserver observer) {
                this.context = context;
                this.observer = observer;
            }

            @Override
            public void prepareForGraph() {
            }

            @Override
            public void run() {
                ready.countDown();
                await(admission);
                if (stopped.get()) {
                    return;
                }
                RuntimeException failure = null;
                try {
                    context.emit(Message.builder("connector message")
                                         .header("key", "connector")
                                         .build());
                } catch (RuntimeException e) {
                    failure = e;
                } finally {
                    observer.deliveryCompleted(failure);
                }
                await(stop);
            }

            @Override
            public void awaitReady(Duration timeout) {
                await(ready, timeout, "Test connector source readiness");
            }

            @Override
            public void startAdmission() {
                admission.countDown();
            }

            @Override
            public void stopAdmission() {
                stopped.set(true);
                admission.countDown();
                stop.countDown();
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public void forceClose() {
                stopAdmission();
            }

            @Override
            public void close() {
                forceClose();
            }
        }
    }

    @Service.Singleton
    static class ShutdownConsumer {
        private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

        @Messaging.ReceiveFrom(SHUTDOWN_CHANNEL)
        void consume(String ignored) {
        }

        @Service.PreDestroy
        void close() {
            EVENTS.add("consumer-close");
        }

        static List<String> events() {
            return EVENTS;
        }
    }

    @Service.Singleton
    static class ShutdownIncomingConnector implements IncomingConnectorProvider<ConnectorConfig> {
        @Override
        public String connectorType() {
            return SHUTDOWN_CONNECTOR;
        }

        @Override
        public ConnectorConfig createConfig(Config config) {
            return ConnectorConfig.create(config);
        }

        @Override
        public IncomingEndpoint createIncomingEndpoint(ConnectorConfig config, ConnectorSourceContext context) {
            return new ShutdownSource();
        }

        private static final class ShutdownSource implements IncomingEndpoint {
            private final CountDownLatch ready = new CountDownLatch(1);
            private final CountDownLatch admission = new CountDownLatch(1);
            private final CountDownLatch stop = new CountDownLatch(1);

            @Override
            public void prepareForGraph() {
            }

            @Override
            public void run() {
                ready.countDown();
                await(admission);
                await(stop);
            }

            @Override
            public void awaitReady(Duration timeout) {
                try {
                    if (!ready.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                        throw new MessagingException("Shutdown test source readiness timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException("Shutdown test source readiness was interrupted", e);
                }
            }

            @Override
            public void startAdmission() {
                ShutdownConsumer.events().add("source-start");
                admission.countDown();
            }

            @Override
            public void stopAdmission() {
                ShutdownConsumer.events().add("source-stop");
                admission.countDown();
                stop.countDown();
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public void forceClose() {
                admission.countDown();
                stop.countDown();
            }

            @Override
            public void close() {
                forceClose();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch, Duration timeout, String operation) {
        try {
            if (!latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new MessagingException(operation + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException(operation + " was interrupted", e);
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

    record HeaderDelivery(String payload, String header) {
    }

    record OptionalHeaderDelivery(String payload, Optional<String> header) {
    }

    interface CustomMessage<K, V> extends Message<V> {
        K key();
    }

    interface ArrayMessage<T> extends Message<T[][]> {
    }

    record ImmutableArrayMessage<T>(T[][] entity,
                                    Map<String, String> headers,
                                    long declaredAdmissionBytes) implements ArrayMessage<T> {
        ImmutableArrayMessage {
            headers = Map.copyOf(headers);
        }

        @Override
        public OptionalLong admissionBytes() {
            return OptionalLong.of(declaredAdmissionBytes);
        }
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
