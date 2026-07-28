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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelRegistryFailurePolicyTest {
    @Test
    void testDeadLetterPolicyCountsInitialAttemptAndStripsConnectorProperties() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();
        new ChannelRegistry(List.of(registration("orders", ignored -> { })),
                            yaml("""
                                    helidon:
                                      messaging:
                                        incoming:
                                          orders:
                                            connector: test-in
                                            failure:
                                              retry:
                                                delay: PT0.01S
                                                max-attempts: 2
                                              on-exhausted: DEAD_LETTER
                                              dead-letter:
                                                channel: orders-dlq
                                        outgoing:
                                          orders-dlq:
                                            connector: test-out
                                    """),
                            List.of(incoming),
                            List.of(outgoing));

        TestConnectorConfig connectorConfig = incoming.config("orders");
        assertThat(connectorConfig.properties().keySet()
                           .stream()
                           .noneMatch(key -> key.equals("failure") || key.startsWith("failure.")),
                   is(true));

        ConnectorSourceContext context = incoming.context("orders");
        IllegalStateException failure = new IllegalStateException("handler failed");
        Message<String> original = Message.builder("order-1").header("trace-id", "trace-1").build();

        assertThat(context.failurePolicy().retryDelay(), is(java.time.Duration.ofMillis(10)));
        assertThat(context.handleFailure(List.of(original), 1, failure),
                   is(ConnectorSourceContext.FailureResult.RETRY));
        assertThat(outgoing.messages().isEmpty(), is(true));

        assertThat(context.handleFailure(List.of(original), 2, failure),
                   is(ConnectorSourceContext.FailureResult.SETTLED));
        assertThat(outgoing.messages().size(), is(1));
        assertThat(outgoing.messages().getFirst(), instanceOf(DeadLetterMessage.class));
        DeadLetterMessage<?> deadLetter = (DeadLetterMessage<?>) outgoing.messages().getFirst();
        assertThat(deadLetter.originalMessage(), sameInstance(original));
        assertThat(deadLetter.sourceChannel(), is("orders"));
        assertThat(deadLetter.attempts(), is(2));
        assertThat(deadLetter.failureType(), is(IllegalStateException.class.getName()));
        assertThat(deadLetter.failureMessage(), is("handler failed"));
    }

    @Test
    void testConsumerOnlyDeadLetterTargetIsValid() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        List<Message<?>> received = new CopyOnWriteArrayList<>();
        ConsumerRegistration registration = registration("orders-dlq", received::add);
        new ChannelRegistry(List.of(registration("orders", ignored -> { }), registration),
                            yaml("""
                                    helidon:
                                      messaging:
                                        incoming:
                                          orders:
                                            connector: test-in
                                            failure:
                                              retry:
                                                max-attempts: 1
                                              on-exhausted: DEAD_LETTER
                                              dead-letter:
                                                channel: orders-dlq
                                    """),
                            List.of(incoming),
                            List.of());

        RuntimeException failure = new IllegalStateException("failed");
        ConnectorSourceContext.FailureResult result = incoming.context("orders")
                .handleFailure(List.of(Message.create("order-1")), 1, failure);

        assertThat(result, is(ConnectorSourceContext.FailureResult.SETTLED));
        assertThat(received.size(), is(1));
        assertThat(received.getFirst(), instanceOf(DeadLetterMessage.class));
    }

    @Test
    void testFailDropAndThirdPartyContextDefaults() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        new ChannelRegistry(List.of(registration("fail", ignored -> { }),
                                    registration("drop", ignored -> { })),
                            yaml("""
                                    helidon:
                                      messaging:
                                        incoming:
                                          fail:
                                            connector: test-in
                                            failure:
                                              retry:
                                                max-attempts: 1
                                          drop:
                                            connector: test-in
                                            failure:
                                              retry:
                                                max-attempts: 1
                                              on-exhausted: DROP
                                    """),
                            List.of(incoming),
                            List.of());

        Message<String> message = Message.create("order-1");
        IllegalStateException fail = new IllegalStateException("fail");
        assertThat(assertThrows(IllegalStateException.class,
                                () -> incoming.context("fail").handleFailure(List.of(message), 1, fail)),
                   sameInstance(fail));
        assertThat(incoming.context("drop").handleFailure(List.of(message), 1, fail),
                   is(ConnectorSourceContext.FailureResult.SETTLED));

        ConnectorSourceContext thirdParty = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "third-party";
            }

            @Override
            public <T> void emit(Message<T> ignored) {
            }
        };
        assertThat(thirdParty.failurePolicy().maxAttempts(), is(0));
        assertThat(assertThrows(IllegalStateException.class,
                                () -> thirdParty.handleFailure(List.of(message), 1, fail)),
                   sameInstance(fail));
    }

    @Test
    void testUnlimitedDropIsRejectedBeforeEndpointsAreCreated() throws InterruptedException {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();

        assertThrows(RuntimeException.class,
                     () -> new ChannelRegistry(List.of(),
                                               yaml("""
                                                       helidon:
                                                         messaging:
                                                           incoming:
                                                             orders:
                                                               connector: test-in
                                                               failure:
                                                                 on-exhausted: DROP
                                                           outgoing:
                                                             audit:
                                                               connector: test-out
                                                       """),
                                               List.of(incoming),
                                               List.of(outgoing)));

        assertThat(incoming.createdCount(), is(0));
        assertThat(outgoing.createdCount(), is(0));
        assertThat(incoming.awaitAnyStart(), is(false));
    }

    @Test
    void testDeadLetterRouteFailureIsNotRecursivelyHandled() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        IllegalStateException routeFailure = new IllegalStateException("sink failed");
        TestOutgoingConnector outgoing = new TestOutgoingConnector(routeFailure);
        new ChannelRegistry(List.of(registration("orders", ignored -> { })),
                            yaml("""
                                    helidon:
                                      messaging:
                                        incoming:
                                          orders:
                                            connector: test-in
                                            failure:
                                              retry:
                                                max-attempts: 1
                                              on-exhausted: DEAD_LETTER
                                              dead-letter:
                                                channel: orders-dlq
                                        outgoing:
                                          orders-dlq:
                                            connector: test-out
                                    """),
                            List.of(incoming),
                            List.of(outgoing));

        IllegalStateException processingFailure = new IllegalStateException("handler failed");
        MessagingException result = assertThrows(
                MessagingException.class,
                () -> incoming.context("orders")
                        .handleFailure(List.of(Message.create("order-1")), 1, processingFailure));

        assertThat(result.getCause(), sameInstance(routeFailure));
        assertThat(result.getSuppressed().length, is(1));
        assertThat(result.getSuppressed()[0], sameInstance(processingFailure));
        assertThat(outgoing.sendCount(), is(1));
    }

    @Test
    void testUnknownRouteCreatesNoEndpoints() throws InterruptedException {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(),
                                          yaml("""
                                                  helidon:
                                                    messaging:
                                                      incoming:
                                                        first:
                                                          connector: test-in
                                                        second:
                                                          connector: test-in
                                                          failure:
                                                            retry:
                                                              max-attempts: 1
                                                            on-exhausted: DEAD_LETTER
                                                            dead-letter:
                                                              channel: missing
                                                      outgoing:
                                                        audit:
                                                          connector: test-out
                                                  """),
                                          List.of(incoming),
                                          List.of(outgoing)));

        assertThat(failure.getMessage(), containsString("Unknown dead-letter channel missing"));
        assertThat(incoming.createdCount(), is(0));
        assertThat(outgoing.createdCount(), is(0));
        assertThat(incoming.awaitAnyStart(), is(false));
    }

    @Test
    void testOutputlessAndSelfRoutesAreRejectedBeforeSourceStarts() throws InterruptedException {
        TestIncomingConnector outputless = new TestIncomingConnector();
        IllegalArgumentException outputlessFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(),
                                          yaml("""
                                                  helidon:
                                                    messaging:
                                                      incoming:
                                                        orders:
                                                          connector: test-in
                                                          failure:
                                                            retry:
                                                              max-attempts: 1
                                                            on-exhausted: DEAD_LETTER
                                                            dead-letter:
                                                              channel: empty
                                                        empty:
                                                          connector: test-in
                                                  """),
                                          List.of(outputless),
                                          List.of()));
        assertThat(outputlessFailure.getMessage(), containsString("has no outputs"));
        assertThat(outputless.createdCount(), is(0));
        assertThat(outputless.awaitAnyStart(), is(false));

        TestIncomingConnector self = new TestIncomingConnector();
        IllegalArgumentException selfFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(registration("orders", ignored -> { })),
                                          yaml("""
                                                  helidon:
                                                    messaging:
                                                      incoming:
                                                        orders:
                                                          connector: test-in
                                                          failure:
                                                            retry:
                                                              max-attempts: 1
                                                            on-exhausted: DEAD_LETTER
                                                            dead-letter:
                                                              channel: orders
                                                  """),
                                          List.of(self),
                                          List.of()));
        assertThat(selfFailure.getMessage(), containsString("must not reference itself"));
        assertThat(self.createdCount(), is(0));
        assertThat(self.awaitAnyStart(), is(false));
    }

    @Test
    void testCyclicRoutesAreRejectedBeforeSourceStarts() throws InterruptedException {
        TestIncomingConnector incoming = new TestIncomingConnector();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(
                        List.of(registration("a", ignored -> { }),
                                registration("b", ignored -> { }),
                                registration("c", ignored -> { })),
                        yaml("""
                                helidon:
                                  messaging:
                                    incoming:
                                      a:
                                        connector: test-in
                                        failure:
                                          retry:
                                            max-attempts: 1
                                          on-exhausted: DEAD_LETTER
                                          dead-letter:
                                            channel: b
                                      b:
                                        connector: test-in
                                        failure:
                                          retry:
                                            max-attempts: 1
                                          on-exhausted: DEAD_LETTER
                                          dead-letter:
                                            channel: c
                                      c:
                                        connector: test-in
                                        failure:
                                          retry:
                                            max-attempts: 1
                                          on-exhausted: DEAD_LETTER
                                          dead-letter:
                                            channel: a
                                """),
                        List.of(incoming),
                        List.of()));

        assertThat(failure.getMessage(), containsString("Cyclic dead-letter channel route"));
        assertThat(failure.getMessage(), containsString("a"));
        assertThat(failure.getMessage(), containsString("b"));
        assertThat(failure.getMessage(), containsString("c"));
        assertThat(incoming.createdCount(), is(0));
        assertThat(incoming.awaitAnyStart(), is(false));
    }

    @Test
    void testConfiguredConnectorWithoutProviderIsRejected() {
        TestOutgoingConnector outgoing = new TestOutgoingConnector();
        IllegalArgumentException incomingFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(),
                                          yaml("""
                                                  helidon:
                                                    messaging:
                                                      incoming:
                                                        orders:
                                                          connector: missing-in
                                                      outgoing:
                                                        audit:
                                                          connector: test-out
                                                  """),
                                          List.of(),
                                          List.of(outgoing)));
        assertThat(incomingFailure.getMessage(), containsString("No incoming connector named missing-in"));
        assertThat(outgoing.createdCount(), is(0));

        IllegalArgumentException outgoingFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(),
                                          yaml("""
                                                  helidon:
                                                    messaging:
                                                      outgoing:
                                                        orders:
                                                          connector: missing-out
                                                  """),
                                          List.of(),
                                          List.of()));
        assertThat(outgoingFailure.getMessage(), containsString("No outgoing connector named missing-out"));
    }

    @Test
    void testIncomingChannelWithoutOutputIsRejectedBeforeEndpointsAreCreated() throws InterruptedException {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(),
                                          yaml("""
                                                  helidon:
                                                    messaging:
                                                      incoming:
                                                        orders:
                                                          connector: test-in
                                                      outgoing:
                                                        audit:
                                                          connector: test-out
                                                  """),
                                          List.of(incoming),
                                          List.of(outgoing)));

        assertThat(failure.getMessage(), containsString("Incoming channel orders has no outputs"));
        assertThat(incoming.createdCount(), is(0));
        assertThat(outgoing.createdCount(), is(0));
        assertThat(incoming.awaitAnyStart(), is(false));
    }

    @Test
    void testGenericConsumerTypesAreValidatedBeforeDispatch() {
        AtomicInteger broadDispatches = new AtomicInteger();
        AtomicInteger keyedDispatches = new AtomicInteger();
        ConsumerRegistration broad = registration(
                "orders",
                Integer.class,
                new GenericType<Integer>() { },
                Message.class,
                new GenericType<Message<Integer>>() { },
                ignored -> broadDispatches.incrementAndGet());
        ConsumerRegistration keyed = registration(
                "orders",
                Integer.class,
                new GenericType<Integer>() { },
                TestKeyedMessage.class,
                new GenericType<TestKeyedMessage<String, Integer>>() { },
                ignored -> keyedDispatches.incrementAndGet());

        ChannelRegistry registry = new ChannelRegistry(List.of(broad, keyed), yaml("{}"), List.of(), List.of());
        IllegalArgumentException dispatchFailure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.emit("orders", Message.create(1)));
        assertThat(dispatchFailure.getMessage(), containsString(TestKeyedMessage.class.getName()));
        assertThat(broadDispatches.get(), is(0));
        assertThat(keyedDispatches.get(), is(0));

        ConsumerRegistration conflictingKeyed = registration(
                "orders",
                Integer.class,
                new GenericType<Integer>() { },
                TestKeyedMessage.class,
                new GenericType<TestKeyedMessage<Long, Integer>>() { });
        IllegalArgumentException envelopeFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(keyed, conflictingKeyed), yaml("{}"), List.of(), List.of()));
        assertThat(envelopeFailure.getMessage(), containsString("conflicting message envelope types"));

        ConsumerRegistration conflictingSubtype = registration(
                "orders",
                Integer.class,
                new GenericType<Integer>() { },
                TestKeyedMessageSubtype.class,
                new GenericType<TestKeyedMessageSubtype<Long, Integer>>() { });
        assertThrows(IllegalArgumentException.class,
                     () -> new ChannelRegistry(List.of(keyed, conflictingSubtype),
                                               yaml("{}"),
                                               List.of(),
                                               List.of()));

        ConsumerRegistration stringList = registration(
                "lists",
                List.class,
                new GenericType<List<String>>() { },
                Message.class,
                new GenericType<Message<List<String>>>() { });
        ConsumerRegistration integerList = registration(
                "lists",
                List.class,
                new GenericType<List<Integer>>() { },
                Message.class,
                new GenericType<Message<List<Integer>>>() { });
        IllegalArgumentException payloadFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(stringList, integerList), yaml("{}"), List.of(), List.of()));
        assertThat(payloadFailure.getMessage(), containsString("conflicting payload types"));
        assertThat(payloadFailure.getMessage(), containsString("java.util.List<java.lang.String>"));
        assertThat(payloadFailure.getMessage(), containsString("java.util.List<java.lang.Integer>"));
    }

    @Test
    void testDeadLetterTargetRejectsIncompatibleConsumerBeforeEndpointsAreCreated() throws InterruptedException {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();
        ConsumerRegistration source = registration("orders", ignored -> { });
        ConsumerRegistration incompatibleTarget = registration(
                "orders-dlq",
                String.class,
                new GenericType<String>() { },
                TestSpecialMessage.class,
                new GenericType<TestSpecialMessage<String>>() { });

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(
                        List.of(source, incompatibleTarget),
                        yaml("""
                                helidon:
                                  messaging:
                                    incoming:
                                      orders:
                                        connector: test-in
                                        failure:
                                          retry:
                                            max-attempts: 1
                                          on-exhausted: DEAD_LETTER
                                          dead-letter:
                                            channel: orders-dlq
                                    outgoing:
                                      audit:
                                        connector: test-out
                                """),
                        List.of(incoming),
                        List.of(outgoing)));

        assertThat(failure.getMessage(), containsString("cannot accept"));
        assertThat(failure.getMessage(), containsString(DeadLetterMessage.class.getName()));
        assertThat(incoming.createdCount(), is(0));
        assertThat(outgoing.createdCount(), is(0));
        assertThat(incoming.awaitAnyStart(), is(false));
    }

    private static Config yaml(String yaml) {
        return Config.just(yaml, MediaTypes.APPLICATION_YAML);
    }

    private static ConsumerRegistration registration(String channel, Consumer<Message<?>> consumer) {
        return registration(channel,
                            String.class,
                            new GenericType<String>() { },
                            Message.class,
                            new GenericType<Message<String>>() { },
                            consumer);
    }

    private static ConsumerRegistration registration(String channel,
                                                     Class<?> payloadType,
                                                     GenericType<?> payloadGenericType,
                                                     Class<?> envelopeType,
                                                     GenericType<?> envelopeGenericType) {
        return registration(channel,
                            payloadType,
                            payloadGenericType,
                            envelopeType,
                            envelopeGenericType,
                            ignored -> { });
    }

    private static ConsumerRegistration registration(String channel,
                                                     Class<?> payloadType,
                                                     GenericType<?> payloadGenericType,
                                                     Class<?> envelopeType,
                                                     GenericType<?> envelopeGenericType,
                                                     Consumer<Message<?>> consumer) {
        return new ConsumerRegistration() {
            @Override
            public String channel() {
                return channel;
            }

            @Override
            public Class<?> payloadType() {
                return payloadType;
            }

            @Override
            public GenericType<?> payloadGenericType() {
                return payloadGenericType;
            }

            @Override
            public Class<?> envelopeType() {
                return envelopeType;
            }

            @Override
            public GenericType<?> envelopeGenericType() {
                return envelopeGenericType;
            }

            @Override
            public void dispatch(Message<?> message) {
                consumer.accept(message);
            }
        };
    }

    private interface TestKeyedMessage<K, V> extends Message<V> {
    }

    private interface TestKeyedMessageSubtype<K, V> extends TestKeyedMessage<K, V> {
    }

    private interface TestSpecialMessage<T> extends Message<T> {
    }

    public record TestConnectorConfig(ConnectorConfig.Direction direction,
                                      String channel,
                                      String connector,
                                      Map<String, String> properties) implements ConnectorConfig {
        public static TestConnectorConfig create(Config config) {
            return new TestConnectorConfig(
                    ConnectorConfig.Direction.valueOf(config.get("direction").asString().orElseThrow()),
                    config.get(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE).asString().orElseThrow(),
                    config.get(ConnectorConfig.CONNECTOR_ATTRIBUTE).asString().orElseThrow(),
                    Map.copyOf(config.detach().asMap().orElse(Map.of())));
        }
    }

    static final class TestIncomingConnector implements IncomingConnector<TestConnectorConfig> {
        private final Map<String, ConnectorSourceContext> contexts = new ConcurrentHashMap<>();
        private final Map<String, TestConnectorConfig> configs = new ConcurrentHashMap<>();
        private final AtomicInteger created = new AtomicInteger();
        private final CountDownLatch anyStart = new CountDownLatch(1);

        @Override
        public String connectorName() {
            return "test-in";
        }

        @Override
        public ConnectorSource createSource(TestConnectorConfig config, ConnectorSourceContext context) {
            created.incrementAndGet();
            configs.put(config.channel(), config);
            contexts.put(config.channel(), context);
            return anyStart::countDown;
        }

        private ConnectorSourceContext context(String channel) {
            return contexts.get(channel);
        }

        private TestConnectorConfig config(String channel) {
            return configs.get(channel);
        }

        private int createdCount() {
            return created.get();
        }

        private boolean awaitAnyStart() throws InterruptedException {
            return anyStart.await(100, TimeUnit.MILLISECONDS);
        }
    }

    static final class TestOutgoingConnector implements OutgoingConnector<TestConnectorConfig> {
        private final List<Message<?>> messages = new CopyOnWriteArrayList<>();
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger sends = new AtomicInteger();
        private final RuntimeException failure;

        private TestOutgoingConnector() {
            this(null);
        }

        private TestOutgoingConnector(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public String connectorName() {
            return "test-out";
        }

        @Override
        public ConnectorSink createSink(TestConnectorConfig config) {
            created.incrementAndGet();
            return new ConnectorSink() {
                @Override
                public <T> void send(Message<T> message) {
                    sends.incrementAndGet();
                    if (failure != null) {
                        throw failure;
                    }
                    messages.add(message);
                }
            };
        }

        private List<Message<?>> messages() {
            return List.copyOf(messages);
        }

        private int sendCount() {
            return sends.get();
        }

        private int createdCount() {
            return created.get();
        }
    }
}
