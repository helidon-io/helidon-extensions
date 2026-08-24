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

package io.helidon.extensions.messaging.connectors.pulsar;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.messaging.ConnectorConfig;
import io.helidon.messaging.ConnectorDelivery;
import io.helidon.messaging.ConnectorDeliveryReservation;
import io.helidon.messaging.IncomingConnector;
import io.helidon.messaging.IncomingConnectorContext;
import io.helidon.messaging.MessageBatch;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.SubscriptionMode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class PulsarIncomingConnectorTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void reservesBeforeReceiveAndAcknowledgesOnlyAfterRuntimeCompletion() throws Exception {
        TestContext context = new TestContext(false, true);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage("payload", 7), context.reserved);
        IncomingConnector connector = new PulsarIncomingConnector(ignored -> source.client())
                .createIncomingConnector(config(1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(context.deliveryEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        assertThat(source.acks.get(), is(0));
        context.allowDelivery.countDown();
        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        assertThat(owner.isAlive(), is(false));
        assertThat(runFailure.get(), nullValue());
        assertThat(source.receivedAfterReservation.get(), is(true));
        assertThat(context.awaitRunningCalls.get(), is(1));
        assertThat(context.started.get(), is(1));
        assertThat(context.failedStarts.get(), is(0));
        assertThat(source.acks.get(), is(1));
        assertThat(source.negativeAcks.get(), is(0));
        assertThat(source.durable.get(), is(true));
        assertThat(source.startedPaused.get(), is(true));
        assertThat(source.ackReceipts.get(), is(true));
        assertThat(source.batchIndexAcknowledgment.get(), is(false));
        assertThat(source.closed.get(), is(true));
    }

    @Test
    void oversizedMappingFailureUsesStartFailedThenAcknowledgesTerminalPolicy() throws Exception {
        TestContext context = new TestContext(false);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage("oversized", 9), context.reserved);
        IncomingConnector connector = new PulsarIncomingConnector(ignored -> source.client())
                .createIncomingConnector(config(1));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.acknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        connector.drain();
        owner.join(WAIT.toMillis());
        connector.close();

        assertThat(runFailure.get(), nullValue());
        assertThat(context.started.get(), is(0));
        assertThat(context.failedStarts.get(), is(1));
        assertThat(source.acks.get(), is(1));
        assertThat(source.negativeAcks.get(), is(0));
    }

    @Test
    void failedRuntimeDeliveryIsNegativelyAcknowledged() throws Exception {
        TestContext context = new TestContext(true);
        FakeSource source = new FakeSource(PulsarTestSupport.nativeMessage("poison", 6), context.reserved);
        IncomingConnector connector = new PulsarIncomingConnector(ignored -> source.client())
                .createIncomingConnector(config(1024));
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread owner = Thread.ofVirtual().start(() -> run(connector, context, runFailure));

        assertThat(source.negativelyAcknowledged.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        owner.join(WAIT.toMillis());

        assertThat(owner.isAlive(), is(false));
        assertThat(runFailure.get() instanceof IllegalStateException, is(true));
        assertThat(source.acks.get(), is(0));
        assertThat(source.negativeAcks.get(), is(1));
        assertThat(source.closed.get(), is(true));
    }

    @Test
    void closeInterruptsBlockedReceiveAndForceCloseInterruptsRunningGate() throws Exception {
        TestContext receiveContext = new TestContext(false);
        FakeSource blockedSource = new FakeSource(null, receiveContext.reserved);
        blockedSource.blockReceive.set(true);
        IncomingConnector blockedConnector = new PulsarIncomingConnector(ignored -> blockedSource.client())
                .createIncomingConnector(config(1024));
        AtomicReference<Throwable> blockedFailure = new AtomicReference<>();
        Thread blockedOwner = Thread.ofVirtual().start(() -> run(blockedConnector, receiveContext, blockedFailure));
        assertThat(blockedSource.receiveEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));

        blockedConnector.close();
        blockedOwner.join(WAIT.toMillis());

        assertThat(blockedOwner.isAlive(), is(false));
        assertThat(blockedFailure.get(), nullValue());

        BlockingGateContext gateContext = new BlockingGateContext();
        FakeSource gatedSource = new FakeSource(null, gateContext.reserved);
        IncomingConnector gatedConnector = new PulsarIncomingConnector(ignored -> gatedSource.client())
                .createIncomingConnector(config(1024));
        AtomicReference<Throwable> gatedFailure = new AtomicReference<>();
        Thread gatedOwner = Thread.ofVirtual().start(() -> run(gatedConnector, gateContext, gatedFailure));
        assertThat(gateContext.entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), is(true));

        gatedConnector.forceClose();
        gatedOwner.join(WAIT.toMillis());

        assertThat(gatedOwner.isAlive(), is(false));
        assertThat(gatedFailure.get(), nullValue());
        assertThat(gatedSource.closed.get(), is(true));
    }

    private static void run(IncomingConnector connector,
                            IncomingConnectorContext context,
                            AtomicReference<Throwable> failure) {
        try {
            connector.run(context);
        } catch (Throwable e) {
            failure.set(e);
        }
    }

    private static PulsarConnectorConfig config(int maxMessageBytes) {
        return PulsarConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel("in")
                .connector(PulsarConnectorProvider.CONNECTOR_TYPE)
                .serviceUrl("pulsar://localhost:6650")
                .topic("persistent://public/default/in")
                .maxMessageBytes(maxMessageBytes)
                .build();
    }

    private static final class TestContext implements IncomingConnectorContext {
        private final boolean failDelivery;
        private final boolean blockDelivery;
        private final AtomicBoolean reserved = new AtomicBoolean();
        private final AtomicInteger awaitRunningCalls = new AtomicInteger();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger failedStarts = new AtomicInteger();
        private final CountDownLatch deliveryEntered = new CountDownLatch(1);
        private final CountDownLatch allowDelivery = new CountDownLatch(1);

        private TestContext(boolean failDelivery) {
            this(failDelivery, false);
        }

        private TestContext(boolean failDelivery, boolean blockDelivery) {
            this.failDelivery = failDelivery;
            this.blockDelivery = blockDelivery;
        }

        @Override
        public String channel() {
            return "in";
        }

        @Override
        public int maxDeliveryMessages() {
            return 4;
        }

        @Override
        public boolean awaitRunning() {
            awaitRunningCalls.incrementAndGet();
            return true;
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery() {
            return reservation();
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
            reserved.set(true);
            return Optional.of(reservation());
        }

        private ConnectorDeliveryReservation reservation() {
            return new ConnectorDeliveryReservation() {
                @Override
                public ConnectorDelivery start(MessageBatch<?> batch) {
                    started.incrementAndGet();
                    return delivery();
                }

                @Override
                public ConnectorDelivery startFailed(MessageBatch<?> batch, RuntimeException failure) {
                    failedStarts.incrementAndGet();
                    return PulsarTestSupport.completedDelivery();
                }

                @Override
                public Optional<ConnectorDelivery> tryStart(MessageBatch<?> batch) {
                    return Optional.of(start(batch));
                }

                @Override
                public void close() {
                }
            };
        }

        private ConnectorDelivery delivery() {
            if (!failDelivery) {
                if (!blockDelivery) {
                    return PulsarTestSupport.completedDelivery();
                }
                return blockingDelivery();
            }
            return new ConnectorDelivery() {
                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public boolean isCurrentThread() {
                    return false;
                }

                @Override
                public void await() {
                    throw new IllegalStateException("expected delivery failure");
                }

                @Override
                public boolean await(Duration timeout) {
                    throw new IllegalStateException("expected delivery failure");
                }

                @Override
                public void cancel() {
                }

                @Override
                public void close() {
                }
            };
        }

        private ConnectorDelivery blockingDelivery() {
            return new ConnectorDelivery() {
                @Override
                public boolean isDone() {
                    return allowDelivery.getCount() == 0;
                }

                @Override
                public boolean isCurrentThread() {
                    return false;
                }

                @Override
                public void await() throws InterruptedException {
                    deliveryEntered.countDown();
                    allowDelivery.await();
                }

                @Override
                public boolean await(Duration timeout) throws InterruptedException {
                    deliveryEntered.countDown();
                    return allowDelivery.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
                }

                @Override
                public void cancel() {
                    allowDelivery.countDown();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class BlockingGateContext implements IncomingConnectorContext {
        private final AtomicBoolean reserved = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public boolean awaitRunning() {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public String channel() {
            return "in";
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery() {
            throw new AssertionError("No reservation expected before running gate opens");
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery() {
            reserved.set(true);
            throw new AssertionError("No reservation expected before running gate opens");
        }
    }

    private static final class FakeSource {
        private final AtomicReference<org.apache.pulsar.client.api.Message<Object>> next;
        private final AtomicBoolean reserved;
        private final AtomicBoolean receivedAfterReservation = new AtomicBoolean();
        private final AtomicBoolean durable = new AtomicBoolean();
        private final AtomicBoolean startedPaused = new AtomicBoolean();
        private final AtomicBoolean ackReceipts = new AtomicBoolean();
        private final AtomicBoolean batchIndexAcknowledgment = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger acks = new AtomicInteger();
        private final AtomicInteger negativeAcks = new AtomicInteger();
        private final CountDownLatch acknowledged = new CountDownLatch(1);
        private final CountDownLatch negativelyAcknowledged = new CountDownLatch(1);
        private final CountDownLatch receiveEntered = new CountDownLatch(1);
        private final AtomicBoolean blockReceive = new AtomicBoolean();
        private final Consumer<Object> consumer = consumer();
        private final ConsumerBuilder<Object> consumerBuilder = consumerBuilder();
        private final PulsarClient client = clientProxy();

        private FakeSource(org.apache.pulsar.client.api.Message<Object> message, AtomicBoolean reserved) {
            this.next = new AtomicReference<>(message);
            this.reserved = reserved;
        }

        private PulsarClient client() {
            return client;
        }

        private PulsarClient clientProxy() {
            return PulsarTestSupport.proxy(PulsarClient.class, (ignored, method, args) -> switch (method.getName()) {
            case "newConsumer" -> consumerBuilder;
            case "closeAsync" -> {
                closed.set(true);
                yield CompletableFuture.completedFuture(null);
            }
            case "shutdown" -> {
                closed.set(true);
                yield null;
            }
            case "isClosed" -> closed.get();
            default -> PulsarTestSupport.defaultValue(method);
            });
        }

        @SuppressWarnings("unchecked")
        private ConsumerBuilder<Object> consumerBuilder() {
            final ConsumerBuilder<Object>[] reference = new ConsumerBuilder[1];
            reference[0] = PulsarTestSupport.proxy(ConsumerBuilder.class, (ignored, method, args) -> {
                switch (method.getName()) {
                case "subscriptionMode" -> durable.set(args[0] == SubscriptionMode.Durable);
                case "startPaused" -> startedPaused.set((boolean) args[0]);
                case "isAckReceiptEnabled" -> ackReceipts.set((boolean) args[0]);
                case "enableBatchIndexAcknowledgment" -> batchIndexAcknowledgment.set((boolean) args[0]);
                case "maxTotalReceiverQueueSizeAcrossPartitions" ->
                    throw new AssertionError("Connector must not collapse partition queues to zero");
                case "subscribe" -> {
                    return consumer;
                }
                default -> {
                }
                }
                return reference[0];
            });
            return reference[0];
        }

        @SuppressWarnings("unchecked")
        private Consumer<Object> consumer() {
            return PulsarTestSupport.proxy(Consumer.class, (ignored, method, args) -> switch (method.getName()) {
            case "receive" -> {
                receiveEntered.countDown();
                if (blockReceive.get()) {
                    new CountDownLatch(1).await();
                }
                receivedAfterReservation.set(reserved.get());
                yield next.getAndSet(null);
            }
            case "acknowledgeAsync" -> {
                acks.incrementAndGet();
                acknowledged.countDown();
                yield CompletableFuture.completedFuture(null);
            }
            case "negativeAcknowledge" -> {
                negativeAcks.incrementAndGet();
                negativelyAcknowledged.countDown();
                yield null;
            }
            case "closeAsync" -> CompletableFuture.completedFuture(null);
            default -> PulsarTestSupport.defaultValue(method);
            });
        }
    }
}
