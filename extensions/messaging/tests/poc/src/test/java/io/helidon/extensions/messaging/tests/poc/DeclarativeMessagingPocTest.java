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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.ConnectorSink;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingChannel;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.MessagingRuntime;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.BatchChannelOneConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.BroadCustomMessageConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.ChannelTwoConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.CustomMessage;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.CustomMessageBatchConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.CustomMessageConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.FailingConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.FirstChannelOneConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.ForwardedMessageConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.ImmutableCustomMessage;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.ImmutableMultiHopMessage;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.MultiHopMessage;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.MultiHopMessageConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.Producer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.SecondChannelOneConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.ShutdownConsumer;
import io.helidon.extensions.messaging.tests.poc.ChannelMessagingTypes.TestIncomingConnector;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeclarativeMessagingPocTest {
    private ServiceRegistryManager registryManager;
    private ServiceRegistry registry;

    @BeforeEach
    void initRegistry() {
        registryManager = ServiceRegistryManager.create();
        registry = registryManager.registry();
    }

    @AfterEach
    void tearDownRegistry() {
        registryManager.shutdown();
    }

    @Test
    void testImperativeChannelInputsOutputs() throws InterruptedException {
        List<Message<Integer>> messages = new ArrayList<>();
        CountDownLatch drained = new CountDownLatch(2);

        MessagingChannel<Integer> channel = MessagingChannel.<Integer>builder()
                .payloadType(Integer.class)
                .addInput(Stream.of(1, Message.builder(2).header("source", "message").build()).parallel())
                .addOutput(message -> {
                    messages.add(message);
                    drained.countDown();
                })
                .build();

        assertThat(messages, empty());

        channel.start();

        assertThat(drained.await(10, TimeUnit.SECONDS), is(true));
        assertThat(messages, hasSize(2));
        assertThat(messages.get(0).entity(), is(1));
        assertThat(messages.get(1).entity(), is(2));
        assertThat(messages.get(1).header("source").orElseThrow(), is("message"));
    }

    @Test
    void testImperativeChannelCanUseAnotherChannelAsInput() {
        List<Message<Integer>> downstreamMessages = new CopyOnWriteArrayList<>();

        MessagingChannel<Integer> upstream = MessagingChannel.<Integer>builder()
                .payloadType(Integer.class)
                .build();
        MessagingChannel.<Integer>builder()
                .payloadType(Integer.class)
                .addInput(upstream)
                .addOutput(downstreamMessages::add)
                .build();

        upstream.emit(42);

        assertThat(downstreamMessages, hasSize(1));
        assertThat(downstreamMessages.getFirst().entity(), is(42));
    }

    @Test
    void testImperativeChannelCanUseOutgoingConnector() {
        List<Message<?>> sentMessages = new CopyOnWriteArrayList<>();

        MessagingChannel<String> channel = MessagingChannel.<String>builder()
                .payloadType(String.class)
                .addOutgoingConnector(sink(sentMessages))
                .build();

        channel.emit(Message.builder("connector message")
                             .header("source", "test")
                             .build());

        assertThat(sentMessages, hasSize(1));
        assertThat(sentMessages.getFirst().entity(), is("connector message"));
        assertThat(sentMessages.getFirst().header("source").orElseThrow(), is("test"));
    }

    @Test
    void testImperativeChannelPreservesBatchForBatchOutputsAndConnectors() {
        List<List<Message<String>>> batches = new CopyOnWriteArrayList<>();
        List<List<String>> connectorBatches = new CopyOnWriteArrayList<>();

        MessagingChannel<String> channel = MessagingChannel.<String>builder()
                .payloadType(String.class)
                .addBatchOutput(batch -> batches.add(List.copyOf(batch)))
                .addOutgoingConnector(new ConnectorSink() {
                    @Override
                    public <T> void send(Message<T> message) {
                        throw new AssertionError("sendBatch should be used");
                    }

                    @Override
                    public <T> void sendBatch(List<? extends Message<T>> messages) {
                        List<String> entities = new ArrayList<>();
                        for (Message<T> message : messages) {
                            entities.add(String.valueOf(message.entity()));
                        }
                        connectorBatches.add(entities);
                    }
                })
                .build();

        channel.emitBatch(List.of(Message.builder("first")
                                          .header("source", "batch")
                                          .build(),
                                  Message.create("second")));

        assertThat(batches, hasSize(1));
        assertThat(batches.getFirst(), hasSize(2));
        assertThat(batches.getFirst().getFirst().header("source").orElseThrow(), is("batch"));
        assertThat(connectorBatches, is(List.of(List.of("first", "second"))));
    }

    @Test
    void testOutgoingConnectorFailureFailsChannelEmit() {
        MessagingChannel<String> channel = MessagingChannel.<String>builder()
                .payloadType(String.class)
                .addOutgoingConnector(new ConnectorSink() {
                    @Override
                    public <T> void send(Message<T> message) {
                        throw new MessagingException("connector failed", new IOException("I/O failed"));
                    }
                })
                .build();

        MessagingException thrown = assertThrows(MessagingException.class, () -> channel.emit("test message"));
        assertThat(thrown.getCause().getMessage(), is("I/O failed"));
    }

    @Test
    void testImperativeEmitWaitsForRequiredOutputsAndFailsFast() throws InterruptedException {
        CountDownLatch firstOutputEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstOutput = new CountDownLatch(1);
        CountDownLatch emissionCompleted = new CountDownLatch(1);
        List<String> invokedOutputs = new CopyOnWriteArrayList<>();
        MessagingException expectedFailure = new MessagingException("second output failed",
                                                                     new IOException("output I/O failed"));
        AtomicReference<Throwable> actualFailure = new AtomicReference<>();

        MessagingChannel<String> channel = MessagingChannel.<String>builder()
                .payloadType(String.class)
                .addOutput(message -> {
                    invokedOutputs.add("first");
                    firstOutputEntered.countDown();
                    try {
                        releaseFirstOutput.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MessagingException("first output interrupted", e);
                    }
                })
                .addOutput(message -> {
                    invokedOutputs.add("second");
                    throw expectedFailure;
                })
                .addOutput(message -> invokedOutputs.add("third"))
                .build();

        Thread.ofVirtual().start(() -> {
            try {
                channel.emit("test message");
            } catch (Throwable throwable) {
                actualFailure.set(throwable);
            } finally {
                emissionCompleted.countDown();
            }
        });

        assertThat(firstOutputEntered.await(10, TimeUnit.SECONDS), is(true));
        try {
            assertThat(emissionCompleted.getCount(), is(1L));
            assertThat(invokedOutputs, is(List.of("first")));
        } finally {
            releaseFirstOutput.countDown();
        }

        assertThat(emissionCompleted.await(10, TimeUnit.SECONDS), is(true));
        assertThat(actualFailure.get(), sameInstance(expectedFailure));
        assertThat(actualFailure.get().getCause(), sameInstance(expectedFailure.getCause()));
        assertThat(invokedOutputs, is(List.of("first", "second")));
    }

    @Test
    void testRetryAfterFanOutFailureCanDuplicateCompletedOutputs() {
        List<String> deliveries = new ArrayList<>();
        AtomicInteger secondOutputAttempts = new AtomicInteger();
        MessagingException expectedFailure = new MessagingException("temporary output failure");

        MessagingChannel<String> channel = MessagingChannel.<String>builder()
                .payloadType(String.class)
                .addOutput(message -> deliveries.add("first"))
                .addOutput(message -> {
                    deliveries.add("second");
                    if (secondOutputAttempts.getAndIncrement() == 0) {
                        throw expectedFailure;
                    }
                })
                .addOutput(message -> deliveries.add("third"))
                .build();

        MessagingException thrown = assertThrows(MessagingException.class, () -> channel.emit("test message"));
        assertThat(thrown, sameInstance(expectedFailure));

        channel.emit("test message");

        assertThat(deliveries, is(List.of("first", "second", "first", "second", "third")));
    }

    @Test
    void testRuntimeCanEmitIntoNamedChannel() {
        MessagingRuntime runtime = registry.get(MessagingRuntime.class);

        runtime.emit(ChannelMessagingTypes.CHANNEL_ONE,
                     Message.builder("runtime message")
                             .header("key", "runtime")
                             .build());

        var firstConsumer = registry.get(FirstChannelOneConsumer.class);
        var secondConsumer = registry.get(SecondChannelOneConsumer.class);
        var batchConsumer = registry.get(BatchChannelOneConsumer.class);

        assertThat(firstConsumer.messages(), hasSize(1));
        assertThat(firstConsumer.keys(), is(List.of("runtime")));
        assertThat(secondConsumer.messages(), hasSize(1));
        assertThat(secondConsumer.messages().getFirst().entity(), is("runtime message"));
        assertThat(batchConsumer.batches(), hasSize(1));
        assertThat(batchConsumer.batches().getFirst().getFirst().entity(), is("runtime message"));
    }

    @Test
    void testGeneratedEmitterRejectsUnknownChannel() {
        var producer = registry.get(Producer.class);

        MessagingException thrown = assertThrows(MessagingException.class,
                                                  () -> producer.emitUnknownChannel("test message"));

        assertThat(thrown.getMessage(), containsString(ChannelMessagingTypes.UNKNOWN_CHANNEL));
    }

    @Test
    void testRuntimeCanEmitBatchIntoNamedChannel() {
        MessagingRuntime runtime = registry.get(MessagingRuntime.class);

        runtime.emitBatch(ChannelMessagingTypes.CHANNEL_ONE,
                          List.of(Message.builder("runtime batch first")
                                          .header("key", "batch-1")
                                          .build(),
                                  Message.builder("runtime batch second")
                                          .header("key", "batch-2")
                                          .build()));

        var firstConsumer = registry.get(FirstChannelOneConsumer.class);
        var secondConsumer = registry.get(SecondChannelOneConsumer.class);
        var batchConsumer = registry.get(BatchChannelOneConsumer.class);

        assertThat(firstConsumer.messages(), hasSize(2));
        assertThat(firstConsumer.keys(), is(List.of("batch-1", "batch-2")));
        assertThat(secondConsumer.messages(), hasSize(2));
        assertThat(batchConsumer.batches(), hasSize(1));
        assertThat(batchConsumer.batches().getFirst(), hasSize(2));
        assertThat(batchConsumer.batches().getFirst().get(0).entity(), is("runtime batch first"));
        assertThat(batchConsumer.batches().getFirst().get(1).header("key").orElseThrow(), is("batch-2"));
    }

    @Test
    void testCustomMessageSubtypeDispatch() {
        MessagingRuntime runtime = registry.get(MessagingRuntime.class);
        CustomMessage<String, Integer> message =
                new ImmutableCustomMessage<>("custom-key", 42, Map.of("source", "custom"));

        runtime.emit(ChannelMessagingTypes.CUSTOM_MESSAGE_CHANNEL, message);

        var consumer = registry.get(CustomMessageConsumer.class);
        assertThat(consumer.messages(), is(List.of(message)));
        assertThat(consumer.messages().getFirst().key(), is("custom-key"));
        assertThat(consumer.messages().getFirst().entity(), is(42));
        assertThat(consumer.messages().getFirst().header("source").orElseThrow(), is("custom"));

        var broadConsumer = registry.get(BroadCustomMessageConsumer.class);
        assertThat(broadConsumer.messages(), is(List.of(message)));
    }

    @Test
    void testCustomMessageSubtypeBatchDispatch() {
        MessagingRuntime runtime = registry.get(MessagingRuntime.class);
        List<CustomMessage<String, Integer>> batch =
                List.of(new ImmutableCustomMessage<>("first-key", 1, Map.of()),
                        new ImmutableCustomMessage<>("second-key", 2, Map.of("source", "batch")));

        runtime.emitBatch(ChannelMessagingTypes.CUSTOM_MESSAGE_BATCH_CHANNEL, batch);

        var consumer = registry.get(CustomMessageBatchConsumer.class);
        assertThat(consumer.batches(), is(List.of(batch)));
        assertThat(consumer.batches().getFirst().get(0).key(), is("first-key"));
        assertThat(consumer.batches().getFirst().get(1).entity(), is(2));
        assertThat(consumer.batches().getFirst().get(1).header("source").orElseThrow(), is("batch"));
    }

    @Test
    void testCustomMessageSubtypeRejectsBaseEnvelope() {
        MessagingRuntime runtime = registry.get(MessagingRuntime.class);

        IllegalArgumentException singleFailure =
                assertThrows(IllegalArgumentException.class,
                             () -> runtime.emit(ChannelMessagingTypes.CUSTOM_MESSAGE_CHANNEL, Message.create(42)));
        assertThat(singleFailure.getMessage(), containsString("expected message envelope type"));
        assertThat(singleFailure.getMessage(), containsString(CustomMessage.class.getName()));
        assertThat(registry.get(BroadCustomMessageConsumer.class).messages(), empty());
        assertThat(registry.get(CustomMessageConsumer.class).messages(), empty());

        IllegalArgumentException batchFailure =
                assertThrows(IllegalArgumentException.class,
                             () -> runtime.emitBatch(ChannelMessagingTypes.CUSTOM_MESSAGE_BATCH_CHANNEL,
                                                    List.of(Message.create(1), Message.create(2))));
        assertThat(batchFailure.getMessage(), containsString("expected message envelope type"));
        assertThat(batchFailure.getMessage(), containsString(CustomMessage.class.getName()));
    }

    @Test
    void testMultiHopMessageSubtypeResolvesParameterizedPayload() {
        MessagingRuntime runtime = registry.get(MessagingRuntime.class);
        MultiHopMessage<String, List<Integer>> message =
                new ImmutableMultiHopMessage<>("multi-hop-key", List.of(1, 2, 3), Map.of("source", "multi-hop"));

        runtime.emit(ChannelMessagingTypes.MULTI_HOP_MESSAGE_CHANNEL, message);

        var consumer = registry.get(MultiHopMessageConsumer.class);
        assertThat(consumer.messages(), is(List.of(message)));
        assertThat(consumer.messages().getFirst().key(), is("multi-hop-key"));
        assertThat(consumer.messages().getFirst().entity(), is(List.of(1, 2, 3)));
        assertThat(consumer.messages().getFirst().header("source").orElseThrow(), is("multi-hop"));
    }

    @Test
    void testNamedChannelFanOutAndHeaders() {
        var producer = registry.get(Producer.class);

        producer.emitChannelOne("test message 1");

        var firstConsumer = registry.get(FirstChannelOneConsumer.class);
        var secondConsumer = registry.get(SecondChannelOneConsumer.class);
        var batchConsumer = registry.get(BatchChannelOneConsumer.class);
        var channelTwoConsumer = registry.get(ChannelTwoConsumer.class);

        assertThat(firstConsumer.messages(), hasSize(1));
        assertThat(firstConsumer.keys(), is(List.of("value")));
        assertThat(secondConsumer.messages(), hasSize(1));
        assertThat(batchConsumer.batches(), hasSize(1));
        assertThat(batchConsumer.batches().getFirst(), hasSize(1));
        assertThat(channelTwoConsumer.messages(), empty());

        Message<String> firstMessage = firstConsumer.messages().getFirst();
        Message<String> secondMessage = secondConsumer.messages().getFirst();
        assertThat(firstMessage.entity(), is("test message 1"));
        assertThat(secondMessage.entity(), is("test message 1"));
        assertThat(firstMessage.header("key").orElseThrow(), is("value"));
        assertThat(secondMessage.header("key").orElseThrow(), is("value"));
    }

    @Test
    void testNamedEmitterPreservesBatch() {
        var producer = registry.get(Producer.class);

        producer.emitChannelOneBatch("emitter batch first", "emitter batch second");

        var firstConsumer = registry.get(FirstChannelOneConsumer.class);
        var secondConsumer = registry.get(SecondChannelOneConsumer.class);
        var batchConsumer = registry.get(BatchChannelOneConsumer.class);

        assertThat(firstConsumer.messages(), hasSize(2));
        assertThat(firstConsumer.keys(), is(List.of("batch-first", "batch-second")));
        assertThat(secondConsumer.messages(), hasSize(2));
        assertThat(batchConsumer.batches(), hasSize(1));
        assertThat(batchConsumer.batches().getFirst(), hasSize(2));
        assertThat(batchConsumer.batches().getFirst().get(0).entity(), is("emitter batch first"));
        assertThat(batchConsumer.batches().getFirst().get(1).entity(), is("emitter batch second"));
    }

    @Test
    void testGeneratedEmitterPreservesHandlerFailure() {
        var producer = registry.get(Producer.class);
        var consumer = registry.get(FailingConsumer.class);

        MessagingException thrown = assertThrows(MessagingException.class,
                                                  () -> producer.emitFailingChannel("test message"));

        assertThat(thrown, sameInstance(consumer.failure()));
        assertThat(thrown.getCause(), sameInstance(consumer.failure().getCause()));
    }

    @Test
    void testIncomingConnectorSourcePreservesHandlerFailure() throws InterruptedException {
        useConfig(Map.of("helidon.messaging.incoming." + ChannelMessagingTypes.FAILING_CHANNEL + ".connector",
                         ChannelMessagingTypes.TEST_CONNECTOR));
        registry.get(MessagingRuntime.class);
        var connector = registry.get(TestIncomingConnector.class);
        var consumer = registry.get(FailingConsumer.class);

        assertThat(connector.awaitDelivery(), is(true));
        RuntimeException thrown = connector.deliveryFailure().orElseThrow();

        assertThat(thrown, sameInstance(consumer.failure()));
        assertThat(thrown.getCause(), sameInstance(consumer.failure().getCause()));
    }

    @Test
    void testDeclarativeHandlerForwardsThroughNamedEmitterSynchronously() {
        MessagingRuntime runtime = registry.get(MessagingRuntime.class);

        runtime.emit(ChannelMessagingTypes.FORWARDING_INPUT_CHANNEL, Message.create("test message"));

        var consumer = registry.get(ForwardedMessageConsumer.class);
        assertThat(consumer.messages(), hasSize(1));
        assertThat(consumer.messages().getFirst().entity(), is("forwarded: test message"));
        assertThat(consumer.messages().getFirst().header("processor").orElseThrow(), is("forwarding"));
    }

    @Test
    void testDeclarativeHandlerPreservesDownstreamFailure() {
        MessagingRuntime runtime = registry.get(MessagingRuntime.class);
        var consumer = registry.get(ForwardedMessageConsumer.class);

        MessagingException thrown =
                assertThrows(MessagingException.class,
                             () -> runtime.emit(ChannelMessagingTypes.FORWARDING_INPUT_CHANNEL,
                                                Message.create("fail")));

        assertThat(thrown, sameInstance(consumer.failure()));
        assertThat(thrown.getCause(), sameInstance(consumer.failure().getCause()));
        assertThat(consumer.messages(), empty());
    }

    @Test
    void testIncomingConnectorEmitsIntoNamedChannel() throws InterruptedException {
        useConfig(Map.of("helidon.messaging.incoming." + ChannelMessagingTypes.CHANNEL_ONE + ".connector",
                         ChannelMessagingTypes.TEST_CONNECTOR));
        registry.get(MessagingRuntime.class);
        var connector = registry.get(TestIncomingConnector.class);

        assertThat(connector.awaitDelivery(), is(true));
        assertThat(connector.deliveryFailure().isEmpty(), is(true));

        var firstConsumer = registry.get(FirstChannelOneConsumer.class);
        var secondConsumer = registry.get(SecondChannelOneConsumer.class);
        var batchConsumer = registry.get(BatchChannelOneConsumer.class);
        var channelTwoConsumer = registry.get(ChannelTwoConsumer.class);

        assertThat(firstConsumer.messages(), hasSize(1));
        assertThat(firstConsumer.keys(), is(List.of("connector")));
        assertThat(secondConsumer.messages(), hasSize(1));
        assertThat(batchConsumer.batches(), hasSize(1));
        assertThat(channelTwoConsumer.messages(), empty());
        assertThat(firstConsumer.messages().getFirst().entity(), is("connector message"));
        assertThat(secondConsumer.messages().getFirst().header("key").orElseThrow(), is("connector"));
    }

    @Test
    void testMessagingStopsBeforeConsumerServiceIsDestroyed() {
        ShutdownConsumer.events().clear();
        useConfig(Map.of("helidon.messaging.incoming." + ChannelMessagingTypes.SHUTDOWN_CHANNEL + ".connector",
                         ChannelMessagingTypes.SHUTDOWN_CONNECTOR));
        registry.get(MessagingRuntime.class);
        registry.get(ShutdownConsumer.class);

        registryManager.shutdown();

        assertThat(ShutdownConsumer.events(), is(List.of("source-start", "source-stop", "consumer-close")));
    }

    @Test
    void testMessagingStartsEagerlyAtItsRunLevel() {
        ShutdownConsumer.events().clear();

        startWithConfig(Map.of("helidon.messaging.incoming." + ChannelMessagingTypes.SHUTDOWN_CHANNEL + ".connector",
                               ChannelMessagingTypes.SHUTDOWN_CONNECTOR));

        assertThat(registry.get(Config.class)
                           .get("helidon.messaging.incoming." + ChannelMessagingTypes.SHUTDOWN_CHANNEL + ".connector")
                           .asString()
                           .orElse(""),
                   is(ChannelMessagingTypes.SHUTDOWN_CONNECTOR));
        assertThat(ShutdownConsumer.events(), is(List.of("source-start")));
    }

    @Test
    void testNamedChannelsAreIsolated() {
        var producer = registry.get(Producer.class);

        producer.emitChannelTwo("test message 2");

        var firstConsumer = registry.get(FirstChannelOneConsumer.class);
        var secondConsumer = registry.get(SecondChannelOneConsumer.class);
        var batchConsumer = registry.get(BatchChannelOneConsumer.class);
        var channelTwoConsumer = registry.get(ChannelTwoConsumer.class);

        assertThat(firstConsumer.messages(), empty());
        assertThat(secondConsumer.messages(), empty());
        assertThat(batchConsumer.batches(), empty());
        assertThat(channelTwoConsumer.messages(), hasSize(1));

        Message<String> message = channelTwoConsumer.messages().getFirst();
        assertThat(message.entity(), is("test message 2"));
        assertThat(message.headers().isEmpty(), is(true));
    }

    @Test
    void testNamedEmitterInjectionUsesServiceNamed() {
        var producer = registry.get(Producer.class);

        assertThat(producer.emittersInjected(), is(true));
    }

    private void useConfig(Map<String, String> values) {
        registryManager.shutdown();
        Config config = Config.just(ConfigSources.create(values));
        ServiceRegistryConfig registryConfig = ServiceRegistryConfig.builder()
                .putContractInstance(Config.class, config)
                .build();
        registryManager = ServiceRegistryManager.create(registryConfig);
        registry = registryManager.registry();
    }

    private void startWithConfig(Map<String, String> values) {
        registryManager.shutdown();
        Config config = Config.just(ConfigSources.create(values));
        ServiceRegistryConfig registryConfig = ServiceRegistryConfig.builder()
                .putContractInstance(Config.class, config)
                .build();
        registryManager = ServiceRegistryManager.start(registryConfig);
        registry = registryManager.registry();
    }

    private static ConnectorSink sink(List<Message<?>> messages) {
        return new ConnectorSink() {
            @Override
            public <T> void send(Message<T> message) {
                messages.add(message);
            }
        };
    }
}
