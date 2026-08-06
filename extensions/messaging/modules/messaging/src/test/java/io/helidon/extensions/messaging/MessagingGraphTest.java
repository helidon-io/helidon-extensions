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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import io.helidon.common.GenericType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class MessagingGraphTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);

    @Test
    void preparesAndWaitsForEveryManagedSourceBeforeAdmission() {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicBoolean firstReady = new AtomicBoolean();
        AtomicBoolean secondReady = new AtomicBoolean();
        BooleanSupplier allReady = () -> firstReady.get() && secondReady.get();
        ManagedSource first = ManagedSource.running("first", events, firstReady, allReady);
        ManagedSource second = ManagedSource.running("second", events, secondReady, allReady);
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addSource("first", first);
        graph.addSource("second", second);

        graph.start();

        assertEquals(DefaultMessagingGraph.State.RUNNING, graph.state());
        assertTrue(first.prepared());
        assertTrue(second.prepared());
        int lastReady = Math.max(events.indexOf("ready-first"), events.indexOf("ready-second"));
        assertTrue(lastReady < events.indexOf("admit-first"), events.toString());
        assertTrue(lastReady < events.indexOf("admit-second"), events.toString());

        graph.close();
        graph.close();
        assertEquals(DefaultMessagingGraph.State.CLOSED, graph.state());
        assertEquals(List.of("close-second", "close-first"), lifecycleEvents(events));
    }

    @Test
    void startsOutgoingBeforeIncomingAndFlushesThenCheckpointsAfterDrain() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicBoolean outgoingReady = new AtomicBoolean();
        CountDownLatch incomingRunning = new CountDownLatch(1);
        CountDownLatch stopIncoming = new CountDownLatch(1);
        CountDownLatch admissionStopped = new CountDownLatch(1);
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        OutgoingEndpoint outgoing = new OutgoingEndpoint() {
            @Override
            public void start() {
                events.add("start-outgoing");
                outgoingReady.set(true);
            }

            @Override
            public <T> void sendBatch(MessageBatch<T> batch) {
            }

            @Override
            public void flush() {
                events.add("flush-outgoing");
            }

            @Override
            public void forceClose() {
                events.add("force-outgoing");
            }

            @Override
            public void close() {
                events.add("close-outgoing");
            }
        };
        IncomingEndpoint incoming = new IncomingEndpoint() {
            @Override
            public void prepareForGraph() {
                events.add("prepare-incoming");
            }

            @Override
            public void run() {
                if (!outgoingReady.get()) {
                    throw new AssertionError("Incoming endpoint ran before outgoing readiness");
                }
                events.add("run-incoming");
                incomingRunning.countDown();
                await(stopIncoming);
            }

            @Override
            public void awaitReady(Duration timeout) {
                ManagedSource.await(incomingRunning, timeout);
                events.add("ready-incoming");
            }

            @Override
            public void startAdmission() {
                events.add("admit-incoming");
            }

            @Override
            public void stopAdmission() {
                events.add("stop-incoming");
                admissionStopped.countDown();
                stopIncoming.countDown();
            }

            @Override
            public void checkpoint() {
                events.add("checkpoint-incoming");
            }

            @Override
            public void forceClose() {
                events.add("force-incoming");
                stopIncoming.countDown();
            }

            @Override
            public void close() {
                events.add("close-incoming");
                stopIncoming.countDown();
            }
        };
        MessagingExecutionConfig config = config(SHUTDOWN_TIMEOUT);
        DeliveryEngine engine = engine(config, "orders");
        DefaultMessagingGraph graph = new DefaultMessagingGraph(engine);
        graph.addBinding(outgoing);
        graph.addSource("incoming", incoming);

        graph.start();

        assertEquals(List.of("prepare-incoming",
                             "start-outgoing",
                             "run-incoming",
                             "ready-incoming",
                             "admit-incoming"),
                     events);

        AsyncTask delivery = async(() -> engine.dispatch("orders",
                                                         MessageBatch.create(List.of(message("order"))),
                                                         () -> {
            events.add("delivery-start");
            deliveryStarted.countDown();
            await(releaseDelivery);
            events.add("delivery-end");
        }));
        await(deliveryStarted);
        AsyncTask closing = async(graph::close);
        await(admissionStopped);

        assertFalse(events.contains("flush-outgoing"), "Outgoing endpoint flushed before runtime drain");
        assertFalse(events.contains("checkpoint-incoming"), "Incoming endpoint checkpointed before runtime drain");

        releaseDelivery.countDown();
        awaitSuccess(delivery);
        awaitSuccess(closing);

        assertEquals(List.of("prepare-incoming",
                             "start-outgoing",
                             "run-incoming",
                             "ready-incoming",
                             "admit-incoming",
                             "delivery-start",
                             "stop-incoming",
                             "delivery-end",
                             "flush-outgoing",
                             "checkpoint-incoming",
                             "close-incoming",
                             "close-outgoing"),
                     events);
        assertEquals(DefaultMessagingGraph.State.CLOSED, graph.state());
    }

    @Test
    void dualEndpointRegisteredAsOutgoingUsesOnlyOutgoingLifecycle() {
        List<String> events = new CopyOnWriteArrayList<>();
        DualEndpoint endpoint = new DualEndpoint(events);
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addBinding(endpoint);

        graph.start();
        graph.close();

        assertEquals(List.of("outgoing-start", "outgoing-flush", "close"), events);
        assertEquals(DefaultMessagingGraph.State.CLOSED, graph.state());
    }

    @Test
    void dualEndpointRegisteredAsIncomingUsesOnlyIncomingLifecycle() {
        List<String> events = new CopyOnWriteArrayList<>();
        DualEndpoint endpoint = new DualEndpoint(events);
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addSource("dual", endpoint);

        graph.start();
        graph.close();

        assertEquals(List.of("incoming-prepare",
                             "incoming-run",
                             "incoming-ready",
                             "incoming-admit",
                             "incoming-stop",
                             "incoming-checkpoint",
                             "close"),
                     events);
        assertEquals(DefaultMessagingGraph.State.CLOSED, graph.state());
    }

    @Test
    void forcedCleanupOfDualEndpointUsesItsRegistrationRole() {
        List<String> outgoingEvents = new CopyOnWriteArrayList<>();
        DualEndpoint outgoing = new DualEndpoint(outgoingEvents);
        DefaultMessagingGraph outgoingGraph = graph(config(SHUTDOWN_TIMEOUT));
        outgoingGraph.addBinding(outgoing);

        outgoingGraph.close();

        assertEquals(List.of("force-close", "close"), outgoingEvents);

        List<String> incomingEvents = new CopyOnWriteArrayList<>();
        DualEndpoint incoming = new DualEndpoint(incomingEvents);
        DefaultMessagingGraph incomingGraph = graph(config(SHUTDOWN_TIMEOUT));
        incomingGraph.addSource("dual", incoming);

        incomingGraph.close();

        assertEquals(List.of("force-close", "close"), incomingEvents);
    }

    @Test
    void concurrentStartCallersShareOneSuccessfulStartup() throws Exception {
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        StartupBlockingSource source = new StartupBlockingSource();
        graph.addSource("source", source);

        AsyncTask owner = async(graph::start);
        await(source.running());
        awaitState(graph, DefaultMessagingGraph.State.STARTING);
        AsyncTask waiter = async(graph::start);
        awaitWaiting(waiter);

        source.releaseStartup();
        awaitSuccess(owner);
        awaitSuccess(waiter);

        assertEquals(DefaultMessagingGraph.State.RUNNING, graph.state());
        assertEquals(1, source.runCalls());
        graph.close();
    }

    @Test
    void outgoingStartFailurePreventsIncomingTasksAndRollsBackEndpoints() {
        IllegalStateException startupFailure = new IllegalStateException("outgoing is not ready");
        AtomicInteger forceCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger flushCalls = new AtomicInteger();
        OutgoingEndpoint outgoing = new OutgoingEndpoint() {
            @Override
            public void start() {
                throw startupFailure;
            }

            @Override
            public <T> void sendBatch(MessageBatch<T> batch) {
            }

            @Override
            public void flush() {
                flushCalls.incrementAndGet();
            }

            @Override
            public void forceClose() {
                forceCalls.incrementAndGet();
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        };
        StartupBlockingSource incoming = new StartupBlockingSource();
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addBinding(outgoing);
        graph.addSource("incoming", incoming);

        assertSame(startupFailure, assertThrows(IllegalStateException.class, graph::start));

        assertEquals(0, incoming.runCalls());
        assertTrue(incoming.forced());
        assertEquals(1, forceCalls.get());
        assertEquals(1, closeCalls.get());
        assertEquals(0, flushCalls.get());
        assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
        graph.close();
    }

    @Test
    void preparationWaitersObserveFailureOnlyAfterRollbackCompletes() throws Exception {
        IllegalStateException preparationFailure = new IllegalStateException("source preparation failed");
        IllegalStateException cleanupFailure = new IllegalStateException("source cleanup failed");
        CountDownLatch preparationEntered = new CountDownLatch(1);
        CountDownLatch releasePreparation = new CountDownLatch(1);
        CountDownLatch forceStarted = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        AtomicInteger forceCalls = new AtomicInteger();
        AtomicReference<DefaultMessagingGraph> graphReference = new AtomicReference<>();
        IncomingEndpoint source = new IncomingEndpoint() {
            @Override
            public void prepareForGraph() {
                assertThrows(IllegalStateException.class,
                             () -> graphReference.get().addRoute("late-source", "late-target"));
                preparationEntered.countDown();
                await(releasePreparation);
                throw preparationFailure;
            }

            @Override
            public void run() {
            }

            @Override
            public void awaitReady(Duration timeout) {
            }

            @Override
            public void startAdmission() {
            }

            @Override
            public void stopAdmission() {
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public void forceClose() {
                forceCalls.incrementAndGet();
                forceStarted.countDown();
                await(releaseForce);
                throw cleanupFailure;
            }

            @Override
            public void close() {
            }
        };
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graphReference.set(graph);
        graph.addSource("source", source);

        AsyncTask owner = async(graph::start);
        await(preparationEntered);
        AsyncTask waiter = async(graph::start);
        awaitWaiting(waiter);
        releasePreparation.countDown();
        await(forceStarted);
        AsyncTask lateStartWaiter = async(graph::start);
        AsyncTask latePrepareWaiter = async(graph::prepare);
        awaitWaiting(lateStartWaiter);
        awaitWaiting(latePrepareWaiter);

        assertFalse(waiter.completion().isDone(), "preparation waiter returned before rollback completed");
        assertFalse(lateStartWaiter.completion().isDone(),
                    "late start waiter returned before rollback completed");
        assertFalse(latePrepareWaiter.completion().isDone(),
                    "late prepare waiter returned before rollback completed");
        releaseForce.countDown();

        assertSame(preparationFailure, failure(owner));
        assertSame(preparationFailure, failure(waiter));
        assertSame(preparationFailure, failure(lateStartWaiter));
        assertSame(preparationFailure, failure(latePrepareWaiter));
        assertSame(preparationFailure, graph.failure().orElseThrow());
        assertEquals(1, forceCalls.get());
        assertEquals(1, preparationFailure.getSuppressed().length);
        assertSame(cleanupFailure, preparationFailure.getSuppressed()[0]);
        graph.close();
    }

    @Test
    void startupWaitersObserveFailureOnlyAfterRollbackCompletes() throws Exception {
        IllegalStateException startupFailure = new IllegalStateException("source is not ready");
        IllegalStateException cleanupFailure = new IllegalStateException("source cleanup failed");
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch readinessEntered = new CountDownLatch(1);
        CountDownLatch releaseReadiness = new CountDownLatch(1);
        CountDownLatch forceStarted = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger forceCalls = new AtomicInteger();
        IncomingEndpoint source = new IncomingEndpoint() {
            @Override
            public void prepareForGraph() {
            }

            @Override
            public void run() {
                running.countDown();
                try {
                    stop.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void awaitReady(Duration timeout) {
                ManagedSource.await(running, timeout);
                readinessEntered.countDown();
                await(releaseReadiness);
                throw startupFailure;
            }

            @Override
            public void startAdmission() {
            }

            @Override
            public void stopAdmission() {
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public void forceClose() {
                forceCalls.incrementAndGet();
                forceStarted.countDown();
                await(releaseForce);
                stop.countDown();
                throw cleanupFailure;
            }

            @Override
            public void close() {
                stop.countDown();
            }
        };
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addSource("source", source);

        AsyncTask owner = async(graph::start);
        await(readinessEntered);
        AsyncTask waiter = async(graph::start);
        awaitWaiting(waiter);
        releaseReadiness.countDown();
        await(forceStarted);

        assertFalse(waiter.completion().isDone(), "startup waiter returned before rollback completed");
        releaseForce.countDown();

        assertSame(startupFailure, failure(owner));
        assertSame(startupFailure, failure(waiter));
        assertSame(startupFailure, graph.failure().orElseThrow());
        assertEquals(1, forceCalls.get());
        assertEquals(1, startupFailure.getSuppressed().length);
        assertSame(cleanupFailure, startupFailure.getSuppressed()[0]);
        graph.close();
    }

    @Test
    void concurrentCloseCallersWaitForOneCleanup() throws Exception {
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        ConnectorEndpoint binding = new ConnectorEndpoint() {
            @Override
            public void forceClose() {
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
                closeStarted.countDown();
                await(releaseClose);
            }
        };
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addBinding(binding);
        graph.start();

        AsyncTask owner = async(graph::close);
        await(closeStarted);
        AsyncTask waiter = async(graph::close);
        awaitWaiting(waiter);

        assertFalse(waiter.completion().isDone(), "close waiter returned before endpoint cleanup completed");
        releaseClose.countDown();
        awaitSuccess(owner);
        awaitSuccess(waiter);

        assertEquals(1, closeCalls.get());
        assertEquals(DefaultMessagingGraph.State.CLOSED, graph.state());
    }

    @Test
    void startupReadinessFailureRollsBackInReverseOrderAndMakesGraphTerminal() {
        List<String> events = new CopyOnWriteArrayList<>();
        IllegalStateException startupFailure = new IllegalStateException("second source is not ready");
        ManagedSource first = ManagedSource.running("first", events, new AtomicBoolean(), () -> true);
        ManagedSource second = ManagedSource.readinessFailure("second", events, startupFailure);
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addSource("first", first);
        graph.addSource("second", second);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, graph::start);

        assertSame(startupFailure, thrown);
        assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
        assertSame(startupFailure, graph.failure().orElseThrow());
        assertEquals(List.of("force-second", "force-first", "close-second", "close-first"),
                     lifecycleEvents(events));
        assertThrows(IllegalStateException.class, graph::start);
        assertThrows(IllegalStateException.class, graph::ensureRunning);

        graph.close();
        assertEquals(List.of("force-second", "force-first", "close-second", "close-first"),
                     lifecycleEvents(events));
    }

    @Test
    void startupAdmissionFailureRollsBackAlreadyAdmittedSources() {
        List<String> events = new CopyOnWriteArrayList<>();
        IllegalStateException startupFailure = new IllegalStateException("second source cannot start admission");
        ManagedSource first = ManagedSource.running("first", events, new AtomicBoolean(), () -> true);
        ManagedSource second = ManagedSource.admissionFailure("second", events, startupFailure);
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addSource("first", first);
        graph.addSource("second", second);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, graph::start);

        assertSame(startupFailure, thrown);
        assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
        assertSame(startupFailure, graph.failure().orElseThrow());
        assertTrue(events.indexOf("admit-first") < events.indexOf("admission-fail-second"), events.toString());
        assertEquals(List.of("force-second", "force-first", "close-second", "close-first"),
                     lifecycleEvents(events));
        assertThrows(IllegalStateException.class, graph::start);
        graph.close();
    }

    @Test
    void gracefulDrainAllowsAdmittedNestedDispatchAndRejectsNewTopLevelWork() throws Exception {
        MessagingExecutionConfig config = config(SHUTDOWN_TIMEOUT);
        DeliveryEngine engine = engine(config, "upstream", "downstream");
        DefaultMessagingGraph graph = new DefaultMessagingGraph(engine);
        graph.start();
        CountDownLatch rootStarted = new CountDownLatch(1);
        CountDownLatch allowNested = new CountDownLatch(1);
        CountDownLatch nestedCompleted = new CountDownLatch(1);

        AsyncTask admitted = async(() -> engine.dispatch("upstream",
                                                        MessageBatch.create(List.of(message("root"))),
                                                        () -> {
            rootStarted.countDown();
            await(allowNested);
            engine.dispatch("downstream",
                            MessageBatch.create(List.of(message("nested"))),
                            nestedCompleted::countDown);
        }));
        await(rootStarted);
        AsyncTask closing = async(graph::close);

        MessagingRejectedException rejected = awaitShutdownRejection(engine, "upstream");
        assertEquals(MessagingRejectedException.Reason.SHUTDOWN, rejected.reason());

        allowNested.countDown();
        await(nestedCompleted);
        awaitSuccess(admitted);
        awaitSuccess(closing);
        assertEquals(DefaultMessagingGraph.State.CLOSED, graph.state());
    }

    @Test
    void drainTimeoutForcesInterruptionAndClosesBindingsInReverseOrder() throws Exception {
        Duration timeout = Duration.ofMillis(100);
        MessagingExecutionConfig config = config(timeout);
        DeliveryEngine engine = engine(config, "orders");
        DefaultMessagingGraph graph = new DefaultMessagingGraph(engine);
        List<String> events = new CopyOnWriteArrayList<>();
        TrackingBinding first = new TrackingBinding("first", events);
        TrackingBinding second = new TrackingBinding("second", events);
        graph.addBinding(first);
        graph.addBinding(second);
        graph.start();
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        AsyncTask delivery = async(() -> engine.dispatch("orders",
                                                         MessageBatch.create(List.of(message("blocked"))),
                                                         () -> {
            deliveryStarted.countDown();
            try {
                releaseDelivery.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }));
        await(deliveryStarted);
        try {
            MessagingException failure = assertThrows(MessagingException.class, graph::close);

            assertThat(failure.getMessage(), containsString("drain timed out"));
            await(interrupted);
            assertEquals(List.of("force-second", "force-first", "close-second", "close-first"), events);
            assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
            assertThrows(MessagingException.class, graph::close);
        } finally {
            releaseDelivery.countDown();
            awaitCompletion(delivery);
        }
    }

    @Test
    void runtimeSourceFailureFailsGraphAndClosesResourcesInReverseOrder() {
        List<String> events = new CopyOnWriteArrayList<>();
        IllegalStateException runtimeFailure = new IllegalStateException("poll failed");
        TrackingBinding resource = new TrackingBinding("resource", events);
        ManagedSource source = ManagedSource.runtimeFailure("source", events, runtimeFailure);
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addBinding(resource);
        graph.addSource("source", source);
        graph.start();

        source.fail();
        await(resource.closedSignal());
        awaitState(graph, DefaultMessagingGraph.State.FAILED);

        Throwable graphFailure = graph.failure().orElseThrow();
        assertThat(graphFailure.getMessage(), containsString("source failed"));
        assertSame(runtimeFailure, graphFailure.getCause());
        assertEquals(List.of("force-source", "force-resource", "close-source", "close-resource"),
                     lifecycleEvents(events));
        assertThrows(IllegalStateException.class, graph::ensureRunning);
        assertSame(graphFailure, assertThrows(MessagingException.class, graph::close));
        assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
    }

    @Test
    void sourceFailureDuringDrainIsReported() {
        IllegalStateException sourceFailure = new IllegalStateException("source stop failed");
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addSource("source", new StopFailingSource(sourceFailure));
        graph.start();

        IllegalStateException failure = assertThrows(IllegalStateException.class, graph::close);

        assertSame(sourceFailure, failure);
        assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
        assertSame(sourceFailure, graph.failure().orElseThrow());
        assertSame(failure, assertThrows(IllegalStateException.class, graph::close));
    }

    @Test
    void rollbackHandlesOneFailureInstanceFromStartupAndCleanup() {
        IllegalStateException sharedFailure = new IllegalStateException("shared lifecycle failure");
        CountDownLatch running = new CountDownLatch(1);
        IncomingEndpoint source = new IncomingEndpoint() {
            @Override
            public void prepareForGraph() {
            }

            @Override
            public void run() {
                running.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void awaitReady(Duration timeout) {
                ManagedSource.await(running, timeout);
                throw sharedFailure;
            }

            @Override
            public void startAdmission() {
            }

            @Override
            public void stopAdmission() {
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public void forceClose() {
                throw sharedFailure;
            }

            @Override
            public void close() {
                throw sharedFailure;
            }
        };
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        graph.addSource("shared-failure", source);

        assertSame(sharedFailure, assertThrows(IllegalStateException.class, graph::start));
        assertEquals(0, sharedFailure.getSuppressed().length);

        graph.close();
        assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
    }

    @Test
    void startingUpstreamTransitivelyStartsDownstreamStreamInput() {
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch streamDelivered = new CountDownLatch(1);
        Message<String> streamMessage = message("from-stream");
        MessagingGraph.Builder builder = MessagingGraph.builder();
        MessagingChannel<String> upstream = builder.channel("upstream", String.class);
        MessagingChannel<String> downstream = builder.channel("downstream", String.class);
        builder.route(upstream, downstream)
                .messageSource(downstream, Stream.of(streamMessage))
                .messageSink(downstream, message -> {
                    delivered.add(message.entity());
                    if (message == streamMessage) {
                        streamDelivered.countDown();
                    }
                });

        try (MessagingGraph graph = builder.build()) {
            graph.start();
            await(streamDelivered);
            graph.emitter(upstream).emitMessage(message("from-upstream"));

            assertEquals(List.of("from-stream", "from-upstream"), delivered);
        }
    }

    @Test
    void closeCancelsSourceStartupWithoutWaitingForTheStartupDeadline() throws Exception {
        DefaultMessagingGraph graph = graph(config(Duration.ofSeconds(2)));
        StartupBlockingSource source = new StartupBlockingSource();
        graph.addSource("blocked", source);
        AsyncTask startup = async(graph::start);
        await(source.running());
        awaitState(graph, DefaultMessagingGraph.State.STARTING);

        long started = System.nanoTime();
        graph.close();
        long elapsed = System.nanoTime() - started;

        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(1), "Close did not cancel startup promptly");
        assertEquals(DefaultMessagingGraph.State.CLOSED, graph.state());
        assertTrue(source.forced());
        assertThrows(ExecutionException.class,
                     () -> startup.completion().get(WAIT.toNanos(), TimeUnit.NANOSECONDS));
    }

    @Test
    void closeCancelsBlockedPreparationWithoutAllowingLatePreparedTransition() throws Exception {
        CountDownLatch preparationStarted = new CountDownLatch(1);
        CountDownLatch releasePreparation = new CountDownLatch(1);
        CountDownLatch preparationReturned = new CountDownLatch(1);
        AtomicBoolean forced = new AtomicBoolean();
        AtomicInteger runCalls = new AtomicInteger();
        List<String> laterSourceEvents = new CopyOnWriteArrayList<>();
        IncomingEndpoint source = new IncomingEndpoint() {
            @Override
            public void prepareForGraph() {
                preparationStarted.countDown();
                await(releasePreparation);
                preparationReturned.countDown();
            }

            @Override
            public void run() {
                runCalls.incrementAndGet();
            }

            @Override
            public void awaitReady(Duration timeout) {
                throw new AssertionError("A source must not start after preparation cancellation");
            }

            @Override
            public void startAdmission() {
                throw new AssertionError("A source must not admit after preparation cancellation");
            }

            @Override
            public void stopAdmission() {
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public void forceClose() {
                forced.set(true);
                releasePreparation.countDown();
            }

            @Override
            public void close() {
                releasePreparation.countDown();
            }
        };
        DefaultMessagingGraph graph = graph(config(Duration.ofSeconds(2)));
        graph.addSource("blocked-prepare", source);
        graph.addSource("later-source",
                        ManagedSource.running("later-source",
                                              laterSourceEvents,
                                              new AtomicBoolean(),
                                              () -> true));
        AsyncTask startup = async(graph::start);
        await(preparationStarted);

        long started = System.nanoTime();
        graph.close();
        long elapsed = System.nanoTime() - started;

        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(1), "Close did not cancel preparation promptly");
        await(preparationReturned);
        assertThat(failure(startup).getMessage(), containsString("preparation was cancelled"));
        assertTrue(forced.get());
        assertEquals(0, runCalls.get());
        assertFalse(laterSourceEvents.contains("prepare-later-source"));
        assertEquals(DefaultMessagingGraph.State.CLOSED, graph.state());
    }

    @Test
    void closeCancelsBlockedAdmissionWithoutAllowingLateRunningTransition() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch admissionStarted = new CountDownLatch(1);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        CountDownLatch admissionReturned = new CountDownLatch(1);
        AtomicBoolean forced = new AtomicBoolean();
        List<String> laterSourceEvents = new CopyOnWriteArrayList<>();
        IncomingEndpoint source = new IncomingEndpoint() {
            @Override
            public void prepareForGraph() {
            }

            @Override
            public void run() {
                running.countDown();
                await(stopped);
            }

            @Override
            public void awaitReady(Duration timeout) {
                ManagedSource.await(running, timeout);
            }

            @Override
            public void startAdmission() {
                admissionStarted.countDown();
                await(releaseAdmission);
                admissionReturned.countDown();
            }

            @Override
            public void stopAdmission() {
                stopped.countDown();
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public void forceClose() {
                forced.set(true);
                releaseAdmission.countDown();
                stopped.countDown();
            }

            @Override
            public void close() {
                releaseAdmission.countDown();
                stopped.countDown();
            }
        };
        DefaultMessagingGraph graph = graph(config(Duration.ofSeconds(2)));
        graph.addSource("blocked-admission", source);
        graph.addSource("later-source",
                        ManagedSource.running("later-source",
                                              laterSourceEvents,
                                              new AtomicBoolean(),
                                              () -> true));
        AsyncTask startup = async(graph::start);
        await(admissionStarted);
        awaitState(graph, DefaultMessagingGraph.State.STARTING);

        long started = System.nanoTime();
        graph.close();
        long elapsed = System.nanoTime() - started;

        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(1), "Close did not cancel admission promptly");
        await(admissionReturned);
        assertThat(failure(startup).getMessage(), containsString("startup was cancelled"));
        assertTrue(forced.get());
        assertFalse(laterSourceEvents.contains("admit-later-source"));
        assertEquals(DefaultMessagingGraph.State.CLOSED, graph.state());
    }

    @Test
    void normalManagedSourceTerminationFailsRunningGraph() {
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        NormalEndingSource source = new NormalEndingSource();
        graph.addSource("ending", source);

        graph.start();
        awaitState(graph, DefaultMessagingGraph.State.FAILED);

        Throwable graphFailure = graph.failure().orElseThrow();
        assertThat(graphFailure.getMessage(), containsString("ending failed"));
        assertThrows(IllegalStateException.class, graph::ensureRunning);
        assertSame(graphFailure, assertThrows(MessagingException.class, graph::close));
    }

    @Test
    void forcedCleanupDefersCloseUntilTimedOutForceReturns() throws Exception {
        Duration timeout = Duration.ofMillis(50);
        DefaultMessagingGraph graph = graph(config(timeout));
        CountDownLatch forceStarted = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        CountDownLatch forceFinished = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicBoolean forced = new AtomicBoolean();
        AtomicBoolean closeBeforeForce = new AtomicBoolean();
        AtomicBoolean closeInterrupted = new AtomicBoolean();
        List<String> events = new CopyOnWriteArrayList<>();
        graph.addBinding(new ConnectorEndpoint() {
            @Override
            public void forceClose() {
                events.add("force-start");
                forceStarted.countDown();
                awaitUninterruptibly(releaseForce);
                forced.set(true);
                events.add("force-end");
                forceFinished.countDown();
            }

            @Override
            public void close() {
                closeBeforeForce.set(!forced.get());
                closeInterrupted.set(Thread.currentThread().isInterrupted());
                events.add("close");
                closeStarted.countDown();
            }
        });

        long started = System.nanoTime();
        AsyncTask closing = async(graph::close);
        try {
            await(forceStarted);
            Throwable closeFailure = failure(closing);
            long elapsed = System.nanoTime() - started;

            assertTrue(closeFailure instanceof MessagingException, closeFailure.toString());
            assertThat(closeFailure.getMessage(), containsString("Timed out while attempting to force close"));
            assertTrue(elapsed < TimeUnit.SECONDS.toNanos(1), "Forced cleanup exceeded its absolute deadline");
            assertFalse(forced.get());
            assertEquals(1L, closeStarted.getCount(), "Normal close entered before force close returned");
        } finally {
            releaseForce.countDown();
        }

        await(forceFinished);
        await(closeStarted);
        assertFalse(closeBeforeForce.get());
        assertFalse(closeInterrupted.get(), "Deferred close inherited the force timeout interruption");
        assertEquals(List.of("force-start", "force-end", "close"), events);
    }

    @Test
    void forcedCleanupDoesNotInterruptCloseWhenDeadlineExpires() throws Exception {
        Duration timeout = Duration.ofMillis(50);
        DefaultMessagingGraph graph = graph(config(timeout));
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicBoolean closeInterrupted = new AtomicBoolean();
        graph.addBinding(new ConnectorEndpoint() {
            @Override
            public void forceClose() {
            }

            @Override
            public void close() {
                closeStarted.countDown();
                awaitUninterruptibly(releaseClose);
                closeInterrupted.set(Thread.currentThread().isInterrupted());
                closeFinished.countDown();
            }
        });

        AsyncTask closing = async(graph::close);
        try {
            await(closeStarted);

            Throwable closeFailure = failure(closing);

            assertTrue(closeFailure instanceof MessagingException, closeFailure.toString());
            assertThat(closeFailure.getMessage(), containsString("Timed out while attempting to close"));
        } finally {
            releaseClose.countDown();
        }

        await(closeFinished);
        assertFalse(closeInterrupted.get(), "Post-force close inherited the lifecycle timeout interruption");
    }

    @Test
    void endpointCloseIsBoundedByOneCleanupDeadline() {
        Duration timeout = Duration.ofMillis(50);
        DefaultMessagingGraph graph = graph(config(timeout));
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicBoolean forceRequested = new AtomicBoolean();
        graph.addBinding(new ConnectorEndpoint() {
            @Override
            public void forceClose() {
                forceRequested.set(true);
                releaseClose.countDown();
            }

            @Override
            public void close() {
                closeStarted.countDown();
                try {
                    releaseClose.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        graph.start();

        long started = System.nanoTime();
        MessagingException failure = assertThrows(MessagingException.class, graph::close);
        long elapsed = System.nanoTime() - started;

        await(closeStarted);
        assertThat(failure.getMessage(), containsString("Timed out while attempting to close connector binding"));
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(1), "Endpoint close exceeded the bounded cleanup phase");
        assertTrue(forceRequested.get());
        assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
        assertThrows(MessagingException.class, graph::close);
    }

    @Test
    void graphRejectsReusedSourceAndManagedBindingIdentities() {
        DefaultMessagingGraph graph = graph(config(SHUTDOWN_TIMEOUT));
        ManagedSource source = ManagedSource.running("source", new CopyOnWriteArrayList<>(),
                                                     new AtomicBoolean(), () -> true);
        TrackingBinding binding = new TrackingBinding("sink", new CopyOnWriteArrayList<>());

        graph.addSource("first", source);
        graph.addBinding(binding);

        assertThrows(IllegalArgumentException.class, () -> graph.addSource("second", source));
        assertThrows(IllegalArgumentException.class, () -> graph.addBinding(binding));
        graph.close();
    }

    @Test
    void rejectsUnknownRouteBeforePreparingSources() {
        List<String> events = new CopyOnWriteArrayList<>();
        ManagedSource source = ManagedSource.running("source", events, new AtomicBoolean(), () -> true);
        MessagingExecutionConfig config = config(SHUTDOWN_TIMEOUT);
        DefaultMessagingGraph graph = graph(config);
        graph.addChannel("known", new NoOpChannel(), config);
        graph.addSource("source", source);
        graph.addRoute("known", "missing");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, graph::prepare);

        assertThat(failure.getMessage(), containsString("Unknown messaging route target missing"));
        assertFalse(source.prepared());
        assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
        assertEquals(List.of("force-source", "close-source"), lifecycleEvents(events));
    }

    @Test
    void rejectsCycleBeforePreparingSources() {
        List<String> events = new CopyOnWriteArrayList<>();
        ManagedSource source = ManagedSource.running("source", events, new AtomicBoolean(), () -> true);
        MessagingExecutionConfig config = config(SHUTDOWN_TIMEOUT);
        DefaultMessagingGraph graph = graph(config);
        graph.addChannel("first", new NoOpChannel(), config);
        graph.addChannel("second", new NoOpChannel(), config);
        graph.addSource("source", source);
        graph.addRoute("first", "second");
        graph.addRoute("second", "first");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, graph::prepare);

        assertThat(failure.getMessage(), containsString("first -> second -> first"));
        assertFalse(source.prepared());
        assertEquals(DefaultMessagingGraph.State.FAILED, graph.state());
        assertEquals(List.of("force-source", "close-source"), lifecycleEvents(events));
    }

    private static DefaultMessagingGraph graph(MessagingExecutionConfig config) {
        return new DefaultMessagingGraph(new DeliveryEngine(config, List.of()));
    }

    private static DeliveryEngine engine(MessagingExecutionConfig config, String... channels) {
        DeliveryEngine engine = new DeliveryEngine(config, List.of());
        for (String channel : channels) {
            engine.registerChannel(channel, config);
        }
        return engine;
    }

    private static MessagingExecutionConfig config(Duration shutdownTimeout) {
        return MessagingExecutionConfig.builder()
                .concurrency(1)
                .queueCapacity(0)
                .maxInFlightMessages(10)
                .maxInFlightBytes(1024)
                .shutdownTimeout(shutdownTimeout)
                .build();
    }

    private static Message<String> message(String value) {
        return Message.builder(value)
                .admissionBytes(value.length())
                .build();
    }

    private static List<String> lifecycleEvents(List<String> events) {
        return events.stream()
                .filter(event -> event.startsWith("force-") || event.startsWith("close-"))
                .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT.toNanos(), TimeUnit.NANOSECONDS), "Timed out waiting for test signal");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test signal", e);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitState(DefaultMessagingGraph graph, DefaultMessagingGraph.State expected) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (graph.state() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, graph.state());
    }

    private static void awaitWaiting(AsyncTask task) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        Thread.State state;
        do {
            if (task.completion().isDone()) {
                throw new AssertionError("Task completed instead of waiting");
            }
            state = task.thread().getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Task did not enter a waiting state; last state was " + state);
    }

    private static MessagingRejectedException awaitShutdownRejection(DeliveryEngine engine, String channel) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                engine.dispatch(channel, MessageBatch.create(List.of(message("probe"))), () -> { });
            } catch (MessagingRejectedException e) {
                return e;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Timed out waiting for messaging drain to reject new work");
    }

    private static AsyncTask async(Runnable runnable) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                runnable.run();
                completion.complete(null);
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        });
        return new AsyncTask(thread, completion);
    }

    private static void awaitSuccess(AsyncTask task)
            throws ExecutionException, InterruptedException, TimeoutException {
        task.completion().get(WAIT.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static void awaitCompletion(AsyncTask task) throws InterruptedException, TimeoutException {
        try {
            awaitSuccess(task);
        } catch (ExecutionException ignored) {
            // Forced shutdown is expected to reject the interrupted delivery.
        }
    }

    private static Throwable failure(AsyncTask task)
            throws InterruptedException, TimeoutException {
        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> task.completion().get(WAIT.toNanos(), TimeUnit.NANOSECONDS));
        return exception.getCause();
    }

    private record AsyncTask(Thread thread, CompletableFuture<Void> completion) {
    }

    private static final class NoOpChannel implements MessagingChannel<Object> {
        @Override
        public String name() {
            return "no-op";
        }

        @Override
        public GenericType<Object> payloadType() {
            return GenericType.OBJECT;
        }
    }

    private static final class DualEndpoint implements IncomingEndpoint, OutgoingEndpoint {
        private final List<String> events;
        private final CountDownLatch running = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);

        private DualEndpoint(List<String> events) {
            this.events = events;
        }

        @Override
        public void prepareForGraph() {
            events.add("incoming-prepare");
        }

        @Override
        public void run() {
            events.add("incoming-run");
            running.countDown();
            await(stopped);
        }

        @Override
        public void awaitReady(Duration timeout) {
            ManagedSource.await(running, timeout);
            events.add("incoming-ready");
        }

        @Override
        public void startAdmission() {
            events.add("incoming-admit");
        }

        @Override
        public void stopAdmission() {
            events.add("incoming-stop");
            stopped.countDown();
        }

        @Override
        public void checkpoint() {
            events.add("incoming-checkpoint");
        }

        @Override
        public void start() {
            events.add("outgoing-start");
        }

        @Override
        public <T> void sendBatch(MessageBatch<T> batch) {
        }

        @Override
        public void flush() {
            events.add("outgoing-flush");
        }

        @Override
        public void forceClose() {
            events.add("force-close");
            stopped.countDown();
        }

        @Override
        public void close() {
            events.add("close");
            stopped.countDown();
        }
    }

    private static class TrackingBinding implements ConnectorEndpoint {
        private final String name;
        private final List<String> events;
        private final CountDownLatch closedSignal = new CountDownLatch(1);
        private final AtomicBoolean forced = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackingBinding(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void forceClose() {
            if (forced.compareAndSet(false, true)) {
                events.add("force-" + name);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                events.add("close-" + name);
                closedSignal.countDown();
            }
        }

        private CountDownLatch closedSignal() {
            return closedSignal;
        }
    }

    private static final class ManagedSource extends TrackingBinding implements IncomingEndpoint {
        private final AtomicBoolean ready;
        private final BooleanSupplier admissionGuard;
        private final RuntimeException readinessFailure;
        private final RuntimeException admissionFailure;
        private final RuntimeException runtimeFailure;
        private final CountDownLatch running = new CountDownLatch(1);
        private final CountDownLatch admission = new CountDownLatch(1);
        private final CountDownLatch stop = new CountDownLatch(1);
        private final CountDownLatch fail = new CountDownLatch(1);
        private final AtomicBoolean prepared = new AtomicBoolean();

        private ManagedSource(String name,
                              List<String> events,
                              AtomicBoolean ready,
                              BooleanSupplier admissionGuard,
                              RuntimeException readinessFailure,
                              RuntimeException admissionFailure,
                              RuntimeException runtimeFailure) {
            super(name, events);
            this.ready = ready;
            this.admissionGuard = admissionGuard;
            this.readinessFailure = readinessFailure;
            this.admissionFailure = admissionFailure;
            this.runtimeFailure = runtimeFailure;
        }

        private static ManagedSource running(String name,
                                             List<String> events,
                                             AtomicBoolean ready,
                                             BooleanSupplier admissionGuard) {
            return new ManagedSource(name, events, ready, admissionGuard, null, null, null);
        }

        private static ManagedSource readinessFailure(String name,
                                                      List<String> events,
                                                      RuntimeException failure) {
            return new ManagedSource(name, events, new AtomicBoolean(), () -> true, failure, null, null);
        }

        private static ManagedSource admissionFailure(String name,
                                                      List<String> events,
                                                      RuntimeException failure) {
            return new ManagedSource(name, events, new AtomicBoolean(), () -> true, null, failure, null);
        }

        private static ManagedSource runtimeFailure(String name,
                                                    List<String> events,
                                                    RuntimeException failure) {
            return new ManagedSource(name, events, new AtomicBoolean(), () -> true, null, null, failure);
        }

        @Override
        public void prepareForGraph() {
            prepared.set(true);
            events().add("prepare-" + name());
        }

        @Override
        public void run() {
            running.countDown();
            MessagingGraphTest.await(admission);
            if (runtimeFailure != null) {
                MessagingGraphTest.await(fail);
                throw runtimeFailure;
            }
            MessagingGraphTest.await(stop);
        }

        @Override
        public void awaitReady(Duration timeout) {
            await(running, timeout);
            if (!prepared.get()) {
                throw new AssertionError("Source readiness was checked before graph preparation");
            }
            if (readinessFailure != null) {
                throw readinessFailure;
            }
            ready.set(true);
            events().add("ready-" + name());
        }

        @Override
        public void startAdmission() {
            if (!admissionGuard.getAsBoolean()) {
                throw new AssertionError("Source admission started before every source was ready");
            }
            if (admissionFailure != null) {
                events().add("admission-fail-" + name());
                throw admissionFailure;
            }
            events().add("admit-" + name());
            admission.countDown();
        }

        @Override
        public void stopAdmission() {
            events().add("stop-" + name());
            admission.countDown();
            stop.countDown();
        }

        @Override
        public void checkpoint() {
        }

        @Override
        public void forceClose() {
            super.forceClose();
            admission.countDown();
            stop.countDown();
            fail.countDown();
        }

        @Override
        public void close() {
            super.close();
            admission.countDown();
            stop.countDown();
            fail.countDown();
        }

        private boolean prepared() {
            return prepared.get();
        }

        private void fail() {
            fail.countDown();
        }

        private String name() {
            return super.name;
        }

        private List<String> events() {
            return super.events;
        }

        private static void await(CountDownLatch latch, Duration timeout) {
            try {
                if (!latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new MessagingException("Timed out waiting for managed test source");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while waiting for managed test source", e);
            }
        }
    }

    private static final class StartupBlockingSource implements IncomingEndpoint {
        private final CountDownLatch running = new CountDownLatch(1);
        private final CountDownLatch startupReleased = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean forced = new AtomicBoolean();
        private final AtomicInteger runCalls = new AtomicInteger();

        @Override
        public void prepareForGraph() {
        }

        @Override
        public void run() {
            runCalls.incrementAndGet();
            running.countDown();
            await(stopped);
        }

        @Override
        public void awaitReady(Duration timeout) {
            try {
                if (!startupReleased.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new MessagingException("Test source startup timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Test source startup was interrupted", e);
            }
        }

        @Override
        public void startAdmission() {
        }

        @Override
        public void stopAdmission() {
            stopped.countDown();
        }

        @Override
        public void checkpoint() {
        }

        @Override
        public void forceClose() {
            forced.set(true);
            startupReleased.countDown();
            stopped.countDown();
        }

        @Override
        public void close() {
            forceClose();
        }

        private CountDownLatch running() {
            return running;
        }

        private boolean forced() {
            return forced.get();
        }

        private void releaseStartup() {
            startupReleased.countDown();
        }

        private int runCalls() {
            return runCalls.get();
        }
    }

    private static final class NormalEndingSource implements IncomingEndpoint {
        private final CountDownLatch running = new CountDownLatch(1);
        private final CountDownLatch admission = new CountDownLatch(1);
        private final AtomicReference<Thread> owner = new AtomicReference<>();

        @Override
        public void prepareForGraph() {
        }

        @Override
        public void run() {
            owner.set(Thread.currentThread());
            running.countDown();
            await(admission);
        }

        @Override
        public void awaitReady(Duration timeout) {
            ManagedSource.await(running, timeout);
        }

        @Override
        public void startAdmission() {
            admission.countDown();
            Thread sourceThread = owner.get();
            try {
                sourceThread.join(WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while waiting for the normal-ending test source", e);
            }
            if (sourceThread.isAlive()) {
                throw new MessagingException("Normal-ending test source did not finish during startup");
            }
        }

        @Override
        public void stopAdmission() {
            admission.countDown();
        }

        @Override
        public void checkpoint() {
        }

        @Override
        public void forceClose() {
            admission.countDown();
        }

        @Override
        public void close() {
            admission.countDown();
        }
    }

    private static final class StopFailingSource implements IncomingEndpoint {
        private final RuntimeException failure;
        private final CountDownLatch running = new CountDownLatch(1);
        private final CountDownLatch admission = new CountDownLatch(1);
        private final CountDownLatch stop = new CountDownLatch(1);

        private StopFailingSource(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void prepareForGraph() {
        }

        @Override
        public void run() {
            running.countDown();
            await(admission);
            await(stop);
            throw failure;
        }

        @Override
        public void awaitReady(Duration timeout) {
            ManagedSource.await(running, timeout);
        }

        @Override
        public void startAdmission() {
            admission.countDown();
        }

        @Override
        public void stopAdmission() {
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
