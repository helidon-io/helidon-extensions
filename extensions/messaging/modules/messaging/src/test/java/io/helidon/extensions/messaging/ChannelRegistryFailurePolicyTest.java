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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void testGlobalAndChannelExecutionConfigurationMerge() {
        Config config = yaml("""
                helidon:
                  messaging:
                    execution:
                      concurrency: 2
                      queue-capacity: 3
                      max-pending-admissions: 4
                      max-pending-messages: 5
                      max-in-flight-messages: 7
                      admission-timeout: PT0.009S
                      shutdown-timeout: PT0.01S
                    channel:
                      orders:
                        execution:
                          concurrency: 11
                          queue-capacity: 12
                          max-pending-messages: 14
                          admission-timeout: PT0.018S
                """);

        MessagingExecutionConfig global = ChannelRegistry.executionConfig(config, null);
        assertThat(global.concurrency(), is(2));
        assertThat(global.queueCapacity(), is(3));
        assertThat(global.maxPendingAdmissions(), is(4));
        assertThat(global.maxPendingMessages(), is(5));
        assertThat(global.maxInFlightMessages(), is(7));
        assertThat(global.admissionTimeout(), is(java.util.Optional.of(Duration.ofMillis(9))));
        assertThat(global.shutdownTimeout(), is(Duration.ofMillis(10)));

        MessagingExecutionConfig orders = ChannelRegistry.executionConfig(config, "orders");
        assertThat(orders.concurrency(), is(11));
        assertThat(orders.queueCapacity(), is(12));
        assertThat(orders.maxPendingAdmissions(), is(4));
        assertThat(orders.maxPendingMessages(), is(14));
        assertThat(orders.maxInFlightMessages(), is(7));
        assertThat(orders.admissionTimeout(), is(java.util.Optional.of(Duration.ofMillis(18))));
        assertThat(orders.shutdownTimeout(), is(Duration.ofMillis(10)));
    }

    @Test
    void testChannelCannotOverrideGlobalShutdownTimeout() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(
                        List.of(registration("orders", ignored -> { })),
                        yaml("""
                                helidon:
                                  messaging:
                                    channel:
                                      orders:
                                        execution:
                                          shutdown-timeout: PT1S
                                """),
                        List.of()));

        assertThat(failure.getMessage(), containsString("must not override global shutdown-timeout"));
    }

    @Test
    void testDeadLetterPolicyCountsInitialAttemptAndStripsConnectorProperties() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();
        ChannelRegistry registry = new ChannelRegistry(List.of(registration("orders", ignored -> { })),
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
                            List.of(incoming, outgoing));
        registry.start();

        TestConnectorConfig connectorConfig = incoming.config("orders");
        assertThat(incoming.configCreatedCount(), is(1));
        assertThat(outgoing.configCreatedCount(), is(1));
        assertThat(connectorConfig.properties().keySet()
                           .stream()
                           .noneMatch(key -> key.equals("failure") || key.startsWith("failure.")),
                   is(true));

        ConnectorSourceContext context = incoming.context("orders");
        IllegalStateException failure = new IllegalStateException("handler failed");
        Message<String> original = Message.builder("order-1").header("trace-id", "trace-1").build();

        assertThat(context.failurePolicy().retryDelay(), is(java.time.Duration.ofMillis(10)));
        assertThat(context.handleFailure(MessageBatch.create(List.of(original)), 1, failure),
                   is(ConnectorSourceContext.FailureResult.RETRY));
        assertThat(outgoing.messages().isEmpty(), is(true));

        assertThat(context.handleFailure(MessageBatch.create(List.of(original)), 2, failure),
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
    void testDeadLetterRoutesCustomMessageImplementations() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();
        ChannelRegistry registry = new ChannelRegistry(List.of(registration("orders", ignored -> { })),
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
                            List.of(incoming, outgoing));
        registry.start();
        MessageBatch<String> batch = MessageBatch.<String>builder()
                .messages(List.of(customMessage("order-1"), customMessage("order-2")))
                .build();

        ConnectorSourceContext.FailureResult result = incoming.context("orders")
                .handleFailure(batch, 1, new IllegalStateException("handler failed"));

        assertThat(result, is(ConnectorSourceContext.FailureResult.SETTLED));
        assertThat(outgoing.messages().size(), is(2));
        assertThat(outgoing.messages().stream().allMatch(DeadLetterMessage.class::isInstance), is(true));
    }

    @Test
    void testDeadLetterUsesApplicationFailureFromStructuredDeliveryFailure() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();
        IllegalStateException applicationFailure = new IllegalStateException("application failed");
        List<String> handled = new CopyOnWriteArrayList<>();
        ChannelRegistry registry = new ChannelRegistry(List.of(registration("orders", message -> {
                                String entity = (String) message.entity();
                                handled.add(entity);
                                if (entity.equals("poison")) {
                                    throw applicationFailure;
                                }
                            })),
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
                            List.of(incoming, outgoing));
        registry.start();
        MessageBatch<String> batch = MessageBatch.create(List.of(Message.create("first"),
                                                                 Message.create("poison"),
                                                                 Message.create("untouched")));
        ConnectorSourceContext context = incoming.context("orders");

        BatchDeliveryException deliveryFailure = assertThrows(BatchDeliveryException.class,
                                                               () -> context.emitBatch(batch));
        assertThat(deliveryFailure.outcomes().stream().map(BatchItemOutcome::status).toList(),
                   is(List.of(BatchItemStatus.SUCCEEDED,
                              BatchItemStatus.INDETERMINATE,
                              BatchItemStatus.NOT_ATTEMPTED)));
        ConnectorSourceContext.FailureResult result = context.handleFailure(batch.subset(List.of(1)),
                                                                            1,
                                                                            deliveryFailure);
        context.emitBatch(batch.subset(List.of(2)));

        assertThat(result, is(ConnectorSourceContext.FailureResult.SETTLED));
        assertThat(handled, is(List.of("first", "poison", "untouched")));
        DeadLetterMessage<?> deadLetter = (DeadLetterMessage<?>) outgoing.messages().getFirst();
        assertThat(deadLetter.entity(), is("poison"));
        assertThat(deadLetter.failureType(), is(IllegalStateException.class.getName()));
        assertThat(deadLetter.failureMessage(), is("application failed"));
    }

    @Test
    void testDeadLetterUsesOnlySelectedFailureSubset() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();
        ChannelRegistry registry = new ChannelRegistry(List.of(registration("orders", ignored -> { })),
                            yaml("""
                                    helidon:
                                      messaging:
                                        channel:
                                          orders-dlq:
                                            execution:
                                              max-in-flight-messages: 1
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
                            List.of(incoming, outgoing));
        registry.start();
        Message<String> healthy = Message.create("healthy");
        Message<String> poison = Message.create("poison");
        MessageBatch<String> retained = MessageBatch.<String>builder()
                .messages(List.of(healthy, poison))
                .build();
        MessageBatch<String> failedSubset = retained.subset(List.of(1));

        ConnectorSourceContext.FailureResult result = incoming.context("orders")
                .handleFailure(failedSubset, 1, new IllegalStateException("handler failed"));

        assertThat(failedSubset.size(), is(1));
        assertThat(failedSubset.isRetainedSubsetOf(retained), is(true));
        assertThat(failedSubset.get(0), sameInstance(poison));
        assertThat(result, is(ConnectorSourceContext.FailureResult.SETTLED));
        assertThat(outgoing.messages().size(), is(1));
        DeadLetterMessage<?> deadLetter = (DeadLetterMessage<?>) outgoing.messages().getFirst();
        assertThat(deadLetter.originalMessage(), sameInstance(poison));
    }

    @Test
    void testConsumerOnlyDeadLetterTargetIsValid() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        List<Message<?>> received = new CopyOnWriteArrayList<>();
        ConsumerRegistration registration = registration("orders-dlq", received::add);
        ChannelRegistry registry = new ChannelRegistry(List.of(registration("orders", ignored -> { }), registration),
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
                            List.of(incoming));
        registry.start();

        RuntimeException failure = new IllegalStateException("failed");
        ConnectorSourceContext.FailureResult result = incoming.context("orders")
                .handleFailure(MessageBatch.create(List.of(Message.create("order-1"))), 1, failure);

        assertThat(result, is(ConnectorSourceContext.FailureResult.SETTLED));
        assertThat(received.size(), is(1));
        assertThat(received.getFirst(), instanceOf(DeadLetterMessage.class));
    }

    @Test
    void testFailDropAndThirdPartyContextDefaults() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        ChannelRegistry registry = new ChannelRegistry(List.of(registration("fail", ignored -> { }),
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
                            List.of(incoming));
        registry.start();

        Message<String> message = Message.create("order-1");
        MessageBatch<String> batch = MessageBatch.create(List.of(message));
        IllegalStateException fail = new IllegalStateException("fail");
        assertThat(assertThrows(IllegalStateException.class,
                                () -> incoming.context("fail").handleFailure(batch, 1, fail)),
                   sameInstance(fail));
        assertThat(incoming.context("drop").handleFailure(batch, 1, fail),
                   is(ConnectorSourceContext.FailureResult.SETTLED));

        MessageBatch<String> completeBatch = MessageBatch.create(List.of(Message.create("succeeded"),
                                                                         Message.create("failed"),
                                                                         Message.create("deferred")));
        BatchDeliveryException completeFailure = BatchDeliveryException.sequential(
                "Test delivery",
                completeBatch,
                1,
                fail);
        MessageBatch<String> policyBatch = completeBatch.subset(List.of(1));
        BatchDeliveryException alignedFailure = assertThrows(
                BatchDeliveryException.class,
                () -> incoming.context("fail").handleFailure(policyBatch, 1, completeFailure));
        assertThat(alignedFailure.batch(), sameInstance(policyBatch));
        assertThat(alignedFailure.getCause(), sameInstance(fail));
        assertThat(alignedFailure.outcomes().size(), is(1));
        assertThat(alignedFailure.outcome(0).index(), is(0));
        assertThat(alignedFailure.outcome(0).status(), is(BatchItemStatus.INDETERMINATE));
        assertThat(alignedFailure.outcome(0).failure().orElseThrow(), sameInstance(fail));

        ConnectorSourceContext thirdParty = new ConnectorSourceContext() {
            @Override
            public String channelName() {
                return "third-party";
            }

            @Override
            public <T> void emitBatch(MessageBatch<T> ignored) {
            }
        };
        assertThat(thirdParty.failurePolicy().maxAttempts(), is(0));
        assertThat(assertThrows(IllegalStateException.class,
                                () -> thirdParty.handleFailure(batch, 1, fail)),
                   sameInstance(fail));
    }

    @Test
    void testUnlimitedDropIsRejectedBeforeConnectorsAreCreated() throws InterruptedException {
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
                                               List.of(incoming, outgoing)));

        assertThat(incoming.createdCount(), is(0));
        assertThat(outgoing.createdCount(), is(0));
        assertThat(incoming.awaitAnyStart(), is(false));
    }

    @Test
    void testDeadLetterRouteFailureIsNotRecursivelyHandled() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        IllegalStateException routeFailure = new IllegalStateException("sink failed");
        TestOutgoingConnector outgoing = new TestOutgoingConnector(routeFailure);
        ChannelRegistry registry = new ChannelRegistry(List.of(registration("orders", ignored -> { })),
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
                            List.of(incoming, outgoing));
        registry.start();

        IllegalStateException processingFailure = new IllegalStateException("handler failed");
        MessagingException result = assertThrows(
                MessagingException.class,
                () -> incoming.context("orders")
                        .handleFailure(MessageBatch.create(List.of(Message.create("order-1"))),
                                       1,
                                       processingFailure));

        assertThat(result.getCause(), instanceOf(BatchDeliveryException.class));
        assertThat(result.getCause().getCause(), sameInstance(routeFailure));
        assertThat(result.getSuppressed().length, is(1));
        assertThat(result.getSuppressed()[0], sameInstance(processingFailure));
        assertThat(outgoing.sendCount(), is(1));
    }

    @Test
    void testPartialDeadLetterRouteFailureMapsOutcomesToPolicyBatch() {
        TestIncomingConnector incoming = new TestIncomingConnector();
        IllegalStateException routeFailure = new IllegalStateException("DLQ consumer failed");
        IllegalStateException processingFailure = new IllegalStateException("handler failed");
        List<String> routed = new CopyOnWriteArrayList<>();
        ConsumerRegistration deadLetterConsumer = registration("orders-dlq", message -> {
            String entity = (String) message.entity();
            routed.add(entity);
            if (entity.equals("second")) {
                throw routeFailure;
            }
        });
        ChannelRegistry registry = new ChannelRegistry(List.of(registration("orders", ignored -> { }), deadLetterConsumer),
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
                            List.of(incoming));
        registry.start();
        MessageBatch<String> policyBatch = MessageBatch.create(List.of(Message.create("first"),
                                                                       Message.create("second"),
                                                                       Message.create("third")));

        BatchDeliveryException failure = assertThrows(
                BatchDeliveryException.class,
                () -> incoming.context("orders").handleFailure(policyBatch, 1, processingFailure));

        assertThat(failure.batch(), sameInstance(policyBatch));
        assertThat(failure.outcomes().stream().map(BatchItemOutcome::status).toList(),
                   is(List.of(BatchItemStatus.SUCCEEDED,
                              BatchItemStatus.INDETERMINATE,
                              BatchItemStatus.NOT_ATTEMPTED)));
        assertThat(failure.outcome(1).failure().orElseThrow(), sameInstance(routeFailure));
        assertThat(failure.getSuppressed().length, is(1));
        assertThat(failure.getSuppressed()[0], sameInstance(processingFailure));
        assertThat(routed, is(List.of("first", "second")));
    }

    @Test
    void testUnknownRouteCreatesNoConnectors() throws InterruptedException {
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
                                          List.of(incoming, outgoing)));

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
                                          List.of(outputless)));
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
                                          List.of(self)));
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
                        List.of(incoming)));

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
                                          List.of(outgoing)));
        assertThat(incomingFailure.getMessage(), containsString("No connector provider of type missing-in"));
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
                                          List.of()));
        assertThat(outgoingFailure.getMessage(), containsString("No connector provider of type missing-out"));
    }

    @Test
    void testUnsupportedProviderDirectionIsRejectedBeforeConnectorCreation() {
        TestOutgoingConnector outgoing = new TestOutgoingConnector();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(registration("orders", ignored -> { })),
                                          yaml("""
                                                  helidon:
                                                    messaging:
                                                      incoming:
                                                        orders:
                                                          connector: test-out
                                                  """),
                                          List.of(outgoing)));

        assertThat(failure.getMessage(), containsString("does not support incoming channel orders"));
        assertThat(outgoing.createdCount(), is(0));
    }

    @Test
    void testDuplicateConnectorProviderTypeIsRejected() {
        TestOutgoingConnector first = new TestOutgoingConnector();
        TestOutgoingConnector second = new TestOutgoingConnector();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(), yaml("{}"), List.of(first, second)));

        assertThat(failure.getMessage(), containsString("Duplicate connector provider type test-out"));
        assertThat(first.createdCount(), is(0));
        assertThat(second.createdCount(), is(0));
    }

    @Test
    void testBlankConnectorProviderTypeIsRejected() {
        AtomicInteger configCreated = new AtomicInteger();
        ConnectorProvider provider = new ConnectorProvider() {
            @Override
            public String connectorType() {
                return " ";
            }

        };

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(), yaml("{}"), List.of(provider)));

        assertThat(failure.getMessage(), containsString("Connector provider type must not be blank"));
        assertThat(configCreated.get(), is(0));
    }

    @Test
    void testIncomingChannelWithoutOutputIsRejectedBeforeConnectorsAreCreated() throws InterruptedException {
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
                                          List.of(incoming, outgoing)));

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

        ChannelRegistry registry = new ChannelRegistry(List.of(broad, keyed), yaml("{}"), List.of());
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
                () -> new ChannelRegistry(List.of(keyed, conflictingKeyed), yaml("{}"), List.of()));
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
                () -> new ChannelRegistry(List.of(stringList, integerList), yaml("{}"), List.of()));
        assertThat(payloadFailure.getMessage(), containsString("conflicting payload types"));
        assertThat(payloadFailure.getMessage(), containsString("java.util.List<java.lang.String>"));
        assertThat(payloadFailure.getMessage(), containsString("java.util.List<java.lang.Integer>"));
    }

    @Test
    void testDeadLetterTargetRejectsIncompatibleConsumerBeforeConnectorsAreCreated() throws InterruptedException {
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
                        List.of(incoming, outgoing)));

        assertThat(failure.getMessage(), containsString("cannot accept"));
        assertThat(failure.getMessage(), containsString(DeadLetterMessage.class.getName()));
        assertThat(incoming.createdCount(), is(0));
        assertThat(outgoing.createdCount(), is(0));
        assertThat(incoming.awaitAnyStart(), is(false));
    }

    @Test
    void testDeadLetterTargetRejectsIncompatiblePayloadBeforeConnectorsAreCreated() throws InterruptedException {
        TestIncomingConnector incoming = new TestIncomingConnector();
        TestOutgoingConnector outgoing = new TestOutgoingConnector();
        ConsumerRegistration source = registration("orders", ignored -> { });
        ConsumerRegistration incompatibleTarget = registration(
                "orders-dlq",
                Integer.class,
                new GenericType<Integer>() { },
                DeadLetterMessage.class,
                new GenericType<DeadLetterMessage<Integer>>() { });

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
                        List.of(incoming, outgoing)));

        assertThat(failure.getMessage(), containsString("Dead-letter channel orders-dlq"));
        assertThat(failure.getMessage(), containsString("payload type java.lang.Integer"));
        assertThat(failure.getMessage(), containsString("incoming channel orders has payload type java.lang.String"));
        assertThat(incoming.createdCount(), is(0));
        assertThat(outgoing.createdCount(), is(0));
        assertThat(incoming.awaitAnyStart(), is(false));
    }

    @Test
    void testRawProducerEnvelopeDoesNotSatisfyParameterizedConsumer() {
        ConsumerRegistration target = registration(
                "orders",
                Integer.class,
                new GenericType<Integer>() { },
                TestKeyedMessage.class,
                new GenericType<TestKeyedMessage<Long, Integer>>() { });
        EmitterRegistration rawProducer = emitterRegistration(
                "orders",
                "publisher#orders",
                new GenericType<Integer>() { },
                GenericType.create(TestKeyedMessage.class));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelRegistry(List.of(target),
                                          List.of(rawProducer),
                                          yaml("{}"),
                                          List.of()));

        assertThat(failure.getMessage(), containsString("produces envelope type"));
        assertThat(failure.getMessage(), containsString("cannot accept"));
        assertThat(failure.getMessage(), containsString("TestKeyedMessage<java.lang.Long, java.lang.Integer>"));
    }

    private static Config yaml(String yaml) {
        return Config.just(yaml, MediaTypes.APPLICATION_YAML);
    }

    private static Message<String> customMessage(String entity) {
        return new Message<>() {
            @Override
            public String entity() {
                return entity;
            }

            @Override
            public Map<String, String> headers() {
                return Map.of();
            }
        };
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
            public void dispatch(MessageBatch<?> batch) {
                for (int i = 0; i < batch.size(); i++) {
                    try {
                        consumer.accept(batch.get(i));
                    } catch (RuntimeException e) {
                        throw BatchDeliveryException.sequential("Test consumer", batch, i, e);
                    }
                }
            }
        };
    }

    private static EmitterRegistration emitterRegistration(String channel,
                                                           String producerId,
                                                           GenericType<?> payloadType,
                                                           GenericType<?> envelopeType) {
        return new EmitterRegistration() {
            @Override
            public String channel() {
                return channel;
            }

            @Override
            public String producerId() {
                return producerId;
            }

            @Override
            public GenericType<?> payloadGenericType() {
                return payloadType;
            }

            @Override
            public GenericType<?> envelopeGenericType() {
                return envelopeType;
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
        private static TestConnectorConfig from(Config config) {
            return new TestConnectorConfig(
                    ConnectorConfig.Direction.valueOf(config.get("direction").asString().orElseThrow()),
                    config.get(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE).asString().orElseThrow(),
                    config.get(ConnectorConfig.CONNECTOR_ATTRIBUTE).asString().orElseThrow(),
                    Map.copyOf(config.detach().asMap().orElse(Map.of())));
        }
    }

    static final class TestIncomingConnector implements IncomingConnectorProvider {
        private final Map<String, ConnectorSourceContext> contexts = new ConcurrentHashMap<>();
        private final Map<String, TestConnectorConfig> configs = new ConcurrentHashMap<>();
        private final AtomicInteger configCreated = new AtomicInteger();
        private final AtomicInteger created = new AtomicInteger();
        private final CountDownLatch anyStart = new CountDownLatch(1);

        @Override
        public String connectorType() {
            return "test-in";
        }

        @Override
        public IncomingConnector createIncomingConnector(Config config) {
            configCreated.incrementAndGet();
            TestConnectorConfig connectorConfig = TestConnectorConfig.from(config);
            created.incrementAndGet();
            configs.put(connectorConfig.channel(), connectorConfig);
            return new IncomingConnector() {
                private final CountDownLatch stopped = new CountDownLatch(1);
                private final AtomicBoolean closed = new AtomicBoolean();

                @Override
                public void run(ConnectorSourceContext context) {
                    contexts.put(connectorConfig.channel(), context);
                    anyStart.countDown();
                    if (!context.awaitRunning()) {
                        return;
                    }
                    await(stopped, Duration.ofDays(1), "stop");
                }

                @Override
                public void drain() {
                    stopped.countDown();
                }

                @Override
                public void forceClose() {
                    close();
                }

                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        stopped.countDown();
                    }
                }
            };
        }

        private static void await(CountDownLatch latch, Duration timeout, String operation) {
            try {
                if (!latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new MessagingException("Test incoming connector " + operation + " timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Test incoming connector " + operation + " was interrupted", e);
            }
        }

        private ConnectorSourceContext context(String channel) {
            ConnectorSourceContext context = contexts.get(channel);
            if (context == null) {
                throw new AssertionError("Connector context is not available; start the registry first");
            }
            return context;
        }

        private TestConnectorConfig config(String channel) {
            return configs.get(channel);
        }

        private int createdCount() {
            return created.get();
        }

        private int configCreatedCount() {
            return configCreated.get();
        }

        private boolean awaitAnyStart() throws InterruptedException {
            return anyStart.await(100, TimeUnit.MILLISECONDS);
        }
    }

    static final class TestOutgoingConnector implements OutgoingConnectorProvider {
        private final List<Message<?>> messages = new CopyOnWriteArrayList<>();
        private final AtomicInteger configCreated = new AtomicInteger();
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
        public String connectorType() {
            return "test-out";
        }

        @Override
        public OutgoingConnector createOutgoingConnector(Config config) {
            configCreated.incrementAndGet();
            TestConnectorConfig.from(config);
            created.incrementAndGet();
            return new OutgoingConnector() {
                @Override
                public void start() {
                }

                @Override
                public void sendBatch(MessageBatch<?> batch) {
                    sends.addAndGet(batch.size());
                    if (failure != null) {
                        throw failure;
                    }
                    messages.addAll(batch.messages());
                }

                @Override
                public void forceClose() {
                }

                @Override
                public void close() {
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

        private int configCreatedCount() {
            return configCreated.get();
        }
    }
}
