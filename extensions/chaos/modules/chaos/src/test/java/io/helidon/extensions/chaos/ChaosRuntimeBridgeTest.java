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
package io.helidon.extensions.chaos;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosRuntimeBridgeTest {
    private static final ChaosRunPlan PLAN = new ChaosRunPlan("test",
                                                               Duration.ofSeconds(1),
                                                               1,
                                                               List.of(new ChaosRunPlan.ChaosStage("stage",
                                                                                                    Duration.ofSeconds(1),
                                                                                                    Optional.empty())));

    @Test
    void registeredEngineReceivesOutboundReservations() {
        ChaosRunEngine engine = engine();
        engine.create(outboundPlan(), "test");
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            try (ChaosRunEngine.Reservation reservation = ChaosRuntimeBridge.reserveOutbound("GET",
                                                                                              "https",
                                                                                              "inventory.example.com",
                                                                                              443,
                                                                                              "/v1/items").orElseThrow()) {
                assertThat(reservation.action(), instanceOf(ChaosLatencyAction.class));
            }
        } finally {
            registration.close();
            engine.close();
        }
    }

    @Test
    void nullEngineIsRejected() {
        NullPointerException exception = assertThrows(NullPointerException.class, () -> ChaosRuntimeBridge.register(null));

        assertThat(exception.getMessage(), is("engine is null"));
        assertRuntimeAvailable();
    }

    @Test
    void duplicateRegistrationLeavesRejectedEngineCallerOwned() {
        ChaosRunEngine firstEngine = engine();
        ChaosRunEngine rejectedEngine = engine();
        ChaosRuntimeRegistration first = ChaosRuntimeBridge.register(firstEngine);
        try {
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                            () -> ChaosRuntimeBridge.register(rejectedEngine));

            assertThat(exception.getMessage(), is("A chaos runtime is already registered"));
            assertRuntimeRegistered();
            assertOpen(rejectedEngine);
        } finally {
            first.close();
            firstEngine.close();
            rejectedEngine.close();
        }
    }

    @Test
    void closingActiveRegistrationUnregistersAndClosesItsEngine() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            registration.close();

            assertClosed(engine);
            assertRuntimeAvailable();
        } finally {
            registration.close();
            engine.close();
        }
    }

    @Test
    void repeatedCloseLeavesStateEmptyAndEngineClosed() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            registration.close();
            registration.close();

            assertClosed(engine);
            assertRuntimeAvailable();
        } finally {
            registration.close();
            engine.close();
        }
    }

    @Test
    void staleRegistrationCannotRemoveOrCloseNewerRegistration() {
        ChaosRunEngine firstEngine = engine();
        ChaosRunEngine secondEngine = engine();
        ChaosRuntimeRegistration first = ChaosRuntimeBridge.register(firstEngine);
        ChaosRuntimeRegistration second = null;
        try {
            first.close();
            second = ChaosRuntimeBridge.register(secondEngine);
            first.close();

            assertRuntimeRegistered();
            assertClosed(firstEngine);
            assertOpen(secondEngine);
        } finally {
            first.close();
            if (second != null) {
                second.close();
            }
            firstEngine.close();
            secondEngine.close();
        }
    }

    @Test
    void closingRegistrationRemainsExclusiveUntilItsEngineCloses() throws Exception {
        BlockingChaosScheduler closingScheduler = new BlockingChaosScheduler();
        ChaosRunEngine firstEngine = engine(closingScheduler);
        ChaosRunEngine secondEngine = engine();
        ChaosRuntimeRegistration first = ChaosRuntimeBridge.register(firstEngine);
        AtomicReference<ChaosRuntimeRegistration> racedRegistration = new AtomicReference<>();
        ChaosRuntimeRegistration second = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var closing = executor.submit(first::close);
            try {
                assertThat(closingScheduler.closeStarted.await(5, TimeUnit.SECONDS), is(true));
                assertThat(first.isActive(), is(false));
                assertThrows(IllegalStateException.class,
                             () -> racedRegistration.set(ChaosRuntimeBridge.register(secondEngine)));

                closingScheduler.releaseClose.countDown();
                closing.get(5, TimeUnit.SECONDS);
                second = ChaosRuntimeBridge.register(secondEngine);
                first.close();

                assertRuntimeRegistered();
            } finally {
                closingScheduler.releaseClose.countDown();
                closing.get(5, TimeUnit.SECONDS);
                ChaosRuntimeRegistration raced = racedRegistration.get();
                if (raced != null) {
                    raced.close();
                }
                if (second != null) {
                    second.close();
                }
                first.close();
                firstEngine.close();
                secondEngine.close();
            }
        }
    }

    @Test
    void simultaneousRegistrationsAllowOnlyOneWinner() throws InterruptedException {
        ChaosRunEngine firstEngine = engine();
        ChaosRunEngine secondEngine = engine();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        ConcurrentLinkedQueue<ChaosRuntimeRegistration> registrations = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        try {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                submitRegistration(executor, firstEngine, ready, start, finished, registrations, failures);
                submitRegistration(executor, secondEngine, ready, start, finished, registrations, failures);
                try {
                    assertThat(ready.await(5, TimeUnit.SECONDS), is(true));
                    start.countDown();
                    assertThat(finished.await(5, TimeUnit.SECONDS), is(true));
                } finally {
                    start.countDown();
                }
            }

            assertThat(registrations, hasSize(1));
            assertThat(failures, hasSize(1));
            assertThat(failures.peek(), instanceOf(IllegalStateException.class));
            assertRuntimeRegistered();
        } finally {
            registrations.forEach(ChaosRuntimeRegistration::close);
            firstEngine.close();
            secondEngine.close();
        }
    }

    @Test
    void concurrentCloseIsSafe() throws InterruptedException {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        try {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                submitClose(executor, registration, ready, start, finished, failures);
                submitClose(executor, registration, ready, start, finished, failures);
                try {
                    assertThat(ready.await(5, TimeUnit.SECONDS), is(true));
                    start.countDown();
                    assertThat(finished.await(5, TimeUnit.SECONDS), is(true));
                } finally {
                    start.countDown();
                }
            }

            assertThat(failures, is(empty()));
            assertClosed(engine);
            assertRuntimeAvailable();
        } finally {
            registration.close();
            engine.close();
        }
    }

    private static ChaosRunEngine engine() {
        TestChaosScheduler scheduler = new TestChaosScheduler();
        return ChaosRunEngine.create(ChaosLimitsConfig.builder().build(),
                                     scheduler.clock(),
                                     scheduler,
                                     UUID::randomUUID);
    }

    private static ChaosRunEngine engine(ChaosScheduler scheduler) {
        return ChaosRunEngine.create(ChaosLimitsConfig.builder().build(), Clock.systemUTC(), scheduler, UUID::randomUUID);
    }

    private static void assertOpen(ChaosRunEngine engine) {
        engine.create(PLAN, "test");
    }

    private static void assertClosed(ChaosRunEngine engine) {
        assertThrows(ChaosRunEngine.ConflictException.class, () -> engine.create(PLAN, "test"));
    }

    private static void assertRuntimeRegistered() {
        ChaosRunEngine candidate = engine();
        try {
            assertThrows(IllegalStateException.class, () -> ChaosRuntimeBridge.register(candidate));
            assertOpen(candidate);
        } finally {
            candidate.close();
        }
    }

    private static void assertRuntimeAvailable() {
        ChaosRunEngine candidate = engine();
        try (ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(candidate)) {
            assertOpen(candidate);
        }
        assertClosed(candidate);
    }

    private static ChaosRunPlan outboundPlan() {
        ChaosOutboundHttpScope scope = new ChaosOutboundHttpScope(Set.of("GET"),
                                                                   "https",
                                                                   "inventory.example.com",
                                                                   443,
                                                                   ChaosHttpScope.PathMatch.PREFIX,
                                                                   "/v1/items");
        ChaosRunPlan.ChaosDisruption disruption = new ChaosRunPlan.ChaosDisruption(
                "outbound-latency",
                scope,
                new ChaosActivation.ProbabilityActivation(1.0),
                new ChaosLatency(Duration.ofMillis(1), Duration.ZERO),
                new ChaosBudget(1, 1));
        ChaosRunPlan.ChaosStage stage = new ChaosRunPlan.ChaosStage("slow-inventory",
                                                                      Duration.ofSeconds(1),
                                                                      Optional.of(disruption));
        return new ChaosRunPlan("outbound-latency", Duration.ofSeconds(1), 1, List.of(stage));
    }

    private static void submitRegistration(ExecutorService executor,
                                           ChaosRunEngine engine,
                                           CountDownLatch ready,
                                           CountDownLatch start,
                                           CountDownLatch finished,
                                           ConcurrentLinkedQueue<ChaosRuntimeRegistration> registrations,
                                           ConcurrentLinkedQueue<Throwable> failures) {
        executor.submit(() -> {
            try {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to start registration");
                }
                registrations.add(ChaosRuntimeBridge.register(engine));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failures.add(exception);
            } catch (RuntimeException | AssertionError exception) {
                failures.add(exception);
            } finally {
                finished.countDown();
            }
        });
    }

    private static void submitClose(ExecutorService executor,
                                    ChaosRuntimeRegistration registration,
                                    CountDownLatch ready,
                                    CountDownLatch start,
                                    CountDownLatch finished,
                                    ConcurrentLinkedQueue<Throwable> failures) {
        executor.submit(() -> {
            try {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to start close");
                }
                registration.close();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failures.add(exception);
            } catch (RuntimeException | AssertionError exception) {
                failures.add(exception);
            } finally {
                finished.countDown();
            }
        });
    }

    private static final class BlockingChaosScheduler implements ChaosScheduler {
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        @Override
        public Cancellable schedule(Duration delay, Runnable action) {
            throw new AssertionError("No run should be scheduled by this test");
        }

        @Override
        public void close() {
            closeStarted.countDown();
            try {
                if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to complete engine close");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while completing engine close", exception);
            }
        }
    }
}
