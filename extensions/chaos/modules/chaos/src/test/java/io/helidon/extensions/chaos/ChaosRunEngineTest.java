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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.chaos.ChaosActivation.PeriodicBurstActivation;
import io.helidon.extensions.chaos.ChaosActivation.ProbabilityActivation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.PREFIX;
import static io.helidon.extensions.chaos.ChaosRunState.COMPLETED;
import static io.helidon.extensions.chaos.ChaosRunState.EXPIRED;
import static io.helidon.extensions.chaos.ChaosRunState.RUNNING;
import static io.helidon.extensions.chaos.ChaosRunState.STOPPED;
import static io.helidon.extensions.chaos.ChaosRunState.STOPPING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosRunEngineTest {
    private static final Instant STARTED_AT = Instant.parse("2026-08-24T12:00:00Z");

    private TestChaosScheduler scheduler;
    private ChaosRunEngine engine;
    private AtomicInteger ids;

    @BeforeEach
    void setUp() {
        scheduler = new TestChaosScheduler();
        ids = new AtomicInteger();
        engine = engine(limits(1, 8, Duration.ofMinutes(5)));
    }

    @Test
    void completesAtStageDuration() {
        ChaosRunView created = engine.create(plan(10, 30, 20, 2, "/orders"), "alice");

        scheduler.advance(Duration.ofSeconds(10));

        ChaosRunView completed = engine.get(created.id()).orElseThrow();
        assertThat(completed.state(), is(COMPLETED));
        assertThat(completed.terminalReason().orElseThrow(), is("stage-duration-completed"));
        assertThat(engine.reserve("GET", "/orders"), is(Optional.empty()));
    }

    @Test
    void selectsStagesAtExactWallClockBoundaries() {
        ChaosRunView created = engine.create(sequencePlan(), "alice");

        assertCurrentStage(created, 0, "slow-orders", STARTED_AT, STARTED_AT.plusSeconds(5));

        try (ChaosRunEngine.Reservation reservation = engine.reserve("GET", "/orders/42").orElseThrow()) {
            assertThat(reservation.action(), instanceOf(ChaosLatencyAction.class));
        }

        scheduler.advance(Duration.ofMillis(4_999));
        assertCurrentStage(engine.get(created.id()).orElseThrow(),
                           0,
                           "slow-orders",
                           STARTED_AT,
                           STARTED_AT.plusSeconds(5));

        scheduler.advance(Duration.ofMillis(1));
        assertCurrentStage(engine.get(created.id()).orElseThrow(),
                           1,
                           "orders-outage",
                           STARTED_AT.plusSeconds(5),
                           STARTED_AT.plusSeconds(15));
        try (ChaosRunEngine.Reservation reservation = engine.reserve("GET", "/orders/42").orElseThrow()) {
            assertThat(((ChaosSyntheticResponse) reservation.action()).status(), is(503));
        }

        scheduler.advance(Duration.ofSeconds(10));
        assertCurrentStage(engine.get(created.id()).orElseThrow(),
                           2,
                           "recovery",
                           STARTED_AT.plusSeconds(15),
                           STARTED_AT.plusSeconds(20));
        assertThat(engine.reserve("GET", "/orders/42"), is(Optional.empty()));

        scheduler.advance(Duration.ofSeconds(5));
        ChaosRunView completed = engine.get(created.id()).orElseThrow();
        assertThat(completed.state(), is(COMPLETED));
        assertThat(completed.currentStage(), is(Optional.empty()));
        assertThat(completed.terminalReason().orElseThrow(), is("stage-duration-completed"));
        assertThat(completed.matched(), is(2L));
        assertThat(completed.activated(), is(2L));
        assertThat(completed.completed(), is(2L));
    }

    @Test
    void keepsBudgetsAndReservationsLocalToTheirOriginatingStage() {
        ChaosRunPlan plan = new ChaosRunPlan("stage-local-budgets",
                                              Duration.ofSeconds(20),
                                              42,
                                              List.of(stage("first",
                                                            5,
                                                            "/orders",
                                                            ChaosActivation.always(),
                                                            synthetic(503),
                                                            1,
                                                            1),
                                                      stage("second",
                                                            5,
                                                            "/orders",
                                                            ChaosActivation.always(),
                                                            synthetic(529),
                                                            1,
                                                            1)));
        ChaosRunView created = engine.create(plan, "alice");
        ChaosRunEngine.Reservation first = engine.reserve("GET", "/orders").orElseThrow();
        assertThat(engine.reserve("GET", "/orders"), is(Optional.empty()));

        scheduler.advance(Duration.ofSeconds(5));
        ChaosRunEngine.Reservation second = engine.reserve("GET", "/orders").orElseThrow();
        assertThat(((ChaosSyntheticResponse) second.action()).status(), is(529));
        assertThat(engine.get(created.id()).orElseThrow().inFlight(), is(2L));

        first.close();
        assertThat(engine.get(created.id()).orElseThrow().inFlight(), is(1L));
        second.close();
        assertThat(engine.reserve("GET", "/orders"), is(Optional.empty()));

        ChaosRunView view = engine.get(created.id()).orElseThrow();
        assertThat(view.matched(), is(4L));
        assertThat(view.activated(), is(2L));
        assertThat(view.skippedConcurrent(), is(1L));
        assertThat(view.skippedBudget(), is(1L));
        assertThat(view.inFlight(), is(0L));
        assertThat(view.completed(), is(2L));
    }

    @Test
    void restartsActivationSequenceForEachStage() {
        PeriodicBurstActivation activation = new PeriodicBurstActivation(1, 2, 1);
        ChaosRunPlan plan = new ChaosRunPlan("stage-local-activation",
                                              Duration.ofSeconds(20),
                                              42,
                                              List.of(stage("first", 5, "/orders", activation, synthetic(503), 10, 2),
                                                      stage("second", 5, "/orders", activation, synthetic(503), 10, 2)));
        ChaosRunView created = engine.create(plan, "alice");

        assertThat(reserveSequence(engine, 2), is(List.of(false, true)));
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(reserveSequence(engine, 2), is(List.of(false, true)));

        ChaosRunView view = engine.get(created.id()).orElseThrow();
        assertThat(view.matched(), is(4L));
        assertThat(view.activated(), is(2L));
        assertThat(view.skippedActivation(), is(2L));
    }

    @Test
    void derivesIndependentProbabilityStreamsForEachStage() {
        ProbabilityActivation activation = new ProbabilityActivation(0.5);
        ChaosRunPlan plan = new ChaosRunPlan("stage-local-randomness",
                                              Duration.ofSeconds(20),
                                              42,
                                              List.of(stage("first", 5, "/orders", activation, synthetic(503), 20, 2),
                                                      stage("second", 5, "/orders", activation, synthetic(503), 20, 2)));
        engine.create(plan, "alice");

        List<Boolean> first = reserveSequence(engine, 8);
        scheduler.advance(Duration.ofSeconds(5));
        List<Boolean> second = reserveSequence(engine, 8);

        assertThat(second, not(is(first)));
    }

    @Test
    void finalStageCompletionWaitsForEarlierReservationToDrain() {
        ChaosRunPlan plan = new ChaosRunPlan("draining-completion",
                                              Duration.ofSeconds(20),
                                              42,
                                              List.of(stage("first",
                                                            5,
                                                            "/orders",
                                                            ChaosActivation.always(),
                                                            synthetic(503),
                                                            10,
                                                            2),
                                                      passiveStage("recovery", 5)));
        ChaosRunView created = engine.create(plan, "alice");
        ChaosRunEngine.Reservation reservation = engine.reserve("GET", "/orders").orElseThrow();

        scheduler.advance(Duration.ofSeconds(10));

        ChaosRunView stopping = engine.get(created.id()).orElseThrow();
        assertThat(stopping.state(), is(STOPPING));
        assertThat(stopping.currentStage(), is(Optional.empty()));
        reservation.close();

        ChaosRunView completed = engine.get(created.id()).orElseThrow();
        assertThat(completed.state(), is(COMPLETED));
        assertThat(completed.terminalReason().orElseThrow(), is("stage-duration-completed"));
    }

    @Test
    void expirationGuardWinsIfItsTaskFiresFirst() {
        ChaosRunView created = engine.create(plan(10, 30, 20, 2, "/orders"), "alice");

        scheduler.fireLast();

        assertThat(engine.get(created.id()).orElseThrow().state(), is(EXPIRED));
    }

    @Test
    void completesNormallyWhenStageTotalEqualsMaximumDuration() {
        ChaosRunView created = engine.create(plan(10, 10, 20, 2, "/orders"), "alice");

        scheduler.fireLast();

        ChaosRunView completed = engine.get(created.id()).orElseThrow();
        assertThat(completed.state(), is(COMPLETED));
        assertThat(completed.terminalReason().orElseThrow(), is("stage-duration-completed"));
    }

    @Test
    void cumulativeAndConcurrentBudgetsNeverOvershoot() {
        ChaosRunView created = engine.create(plan(10, 30, 2, 1, "/orders"), "alice");
        ChaosRunEngine.Reservation first = engine.reserve("GET", "/orders/42").orElseThrow();

        assertThat(engine.reserve("GET", "/orders/43"), is(Optional.empty()));
        first.close();
        try (ChaosRunEngine.Reservation second = engine.reserve("GET", "/orders/44").orElseThrow()) {
            assertThat(((ChaosSyntheticResponse) second.action()).status(), is(503));
        }
        assertThat(engine.reserve("GET", "/orders/45"), is(Optional.empty()));

        ChaosRunView view = engine.get(created.id()).orElseThrow();
        assertThat(view.matched(), is(4L));
        assertThat(view.activated(), is(2L));
        assertThat(view.skippedConcurrent(), is(1L));
        assertThat(view.skippedBudget(), is(1L));
        assertThat(view.inFlight(), is(0L));
        assertThat(view.completed(), is(2L));
    }

    @Test
    void probabilityUsesStableSeededSequenceWithoutConsumingBudgetOnMisses() {
        ChaosRunPlan plan = plan(10,
                                 30,
                                 20,
                                 2,
                                 "/orders",
                                 new ProbabilityActivation(0.5),
                                 42,
                                 "synthetic");
        ChaosRunView created = engine.create(plan, "alice");

        List<Boolean> decisions = reserveSequence(engine, 8);

        assertThat(decisions, is(List.of(false, false, false, false, true, false, false, true)));
        ChaosRunView view = engine.get(created.id()).orElseThrow();
        assertThat(view.matched(), is(8L));
        assertThat(view.activated(), is(2L));
        assertThat(view.skippedActivation(), is(6L));
        assertThat(view.skippedConcurrent(), is(0L));
        assertThat(view.skippedBudget(), is(0L));
        assertThat(view.completed(), is(2L));
    }

    @Test
    void probabilityStreamIncludesSeedAndStableDisruptionIdentity() {
        ChaosRunEngine seedEngine = engine(limits(1, 8, Duration.ofMinutes(5)));
        seedEngine.create(plan(10, 30, 20, 2, "/orders",
                               new ProbabilityActivation(0.5), 44, "synthetic"), "alice");
        assertThat(reserveSequence(seedEngine, 8),
                   is(List.of(false, true, false, true, true, false, false, false)));

        ChaosRunEngine identityEngine = engine(limits(1, 8, Duration.ofMinutes(5)));
        identityEngine.create(plan(10, 30, 20, 2, "/orders",
                                   new ProbabilityActivation(0.5), 42, "alternate"), "alice");
        assertThat(reserveSequence(identityEngine, 8),
                   is(List.of(false, true, true, true, true, false, false, true)));
    }

    @Test
    void periodicBurstSkipsInitiallyThenRepeatsWithoutConsumingBudgetOnMisses() {
        ChaosRunPlan plan = plan(10,
                                 30,
                                 20,
                                 2,
                                 "/orders",
                                 new PeriodicBurstActivation(2, 4, 2),
                                 42,
                                 "synthetic");
        ChaosRunView created = engine.create(plan, "alice");

        List<Boolean> decisions = reserveSequence(engine, 12);

        assertThat(decisions,
                   is(List.of(false, false, true, true, false, false,
                              true, true, false, false, true, true)));
        ChaosRunView view = engine.get(created.id()).orElseThrow();
        assertThat(view.matched(), is(12L));
        assertThat(view.activated(), is(6L));
        assertThat(view.skippedActivation(), is(6L));
        assertThat(view.skippedConcurrent(), is(0L));
        assertThat(view.skippedBudget(), is(0L));
        assertThat(view.completed(), is(6L));
    }

    @Test
    void periodicBurstCountsOnlyMatchingRequestsAndIgnoresSeed() {
        PeriodicBurstActivation activation = new PeriodicBurstActivation(1, 3, 1);
        engine.create(plan(10, 30, 20, 2, "/orders", activation, 42, "synthetic"), "alice");

        assertThat(engine.reserve("GET", "/customers"), is(Optional.empty()));
        assertThat(engine.reserve("POST", "/orders/42"), is(Optional.empty()));
        List<Boolean> decisions = reserveSequence(engine, 5);

        assertThat(decisions, is(List.of(false, true, false, false, true)));
        assertThat(engine.list().getFirst().matched(), is(5L));

        ChaosRunEngine differentSeedEngine = engine(limits(1, 8, Duration.ofMinutes(5)));
        differentSeedEngine.create(
                plan(10, 30, 20, 2, "/orders", activation, 99, "synthetic"),
                "alice");
        assertThat(reserveSequence(differentSeedEngine, 5),
                   is(List.of(false, true, false, false, true)));
    }

    @Test
    void periodicBurstSupportsLongCounterBoundary() {
        PeriodicBurstActivation activation =
                new PeriodicBurstActivation(Long.MAX_VALUE - 2, Long.MAX_VALUE, 1);

        assertThat(ChaosActivationDecider.activates(activation, 42, Long.MAX_VALUE - 2), is(false));
        assertThat(ChaosActivationDecider.activates(activation, 42, Long.MAX_VALUE - 1), is(true));
        assertThat(ChaosActivationDecider.activates(activation, 42, Long.MAX_VALUE), is(false));
    }

    @Test
    void stableIdentityEncodingSeparatesEmbeddedNullCharacters() {
        assertThat(ChaosActivationDecider.streamSeed(42, "a", "b\0c")
                           == ChaosActivationDecider.streamSeed(42, "a\0b", "c"),
                   is(false));
    }

    @Test
    void latencyUsesStableIndependentSeededSequence() {
        ChaosLatency latency = new ChaosLatency(Duration.ofMillis(250), Duration.ofMillis(50));
        ChaosRunEngine firstEngine = engine(limits(1, 8, Duration.ofMinutes(5)));
        firstEngine.create(plan(10, 30, 20, 2, "/orders",
                                ChaosActivation.always(), 42, "latency", latency), "alice");
        List<Duration> first = latencySequence(firstEngine, 8);

        ChaosRunEngine sameEngine = engine(limits(1, 8, Duration.ofMinutes(5)));
        sameEngine.create(plan(10, 30, 20, 2, "/orders",
                               ChaosActivation.always(), 42, "latency", latency), "alice");
        assertThat(latencySequence(sameEngine, 8), is(first));

        ChaosRunEngine differentSeedEngine = engine(limits(1, 8, Duration.ofMinutes(5)));
        differentSeedEngine.create(plan(10, 30, 20, 2, "/orders",
                                        ChaosActivation.always(), 43, "latency", latency), "alice");
        assertThat(latencySequence(differentSeedEngine, 8), not(is(first)));
    }

    @Test
    void reservationCloseIsIdempotent() {
        ChaosRunView created = engine.create(plan(10, 30, 2, 1, "/orders"), "alice");
        ChaosRunEngine.Reservation reservation = engine.reserve("GET", "/orders").orElseThrow();

        reservation.close();
        reservation.close();

        ChaosRunView view = engine.get(created.id()).orElseThrow();
        assertThat(view.inFlight(), is(0L));
        assertThat(view.completed(), is(1L));
    }

    @Test
    void deleteWaitsForInFlightReservation() {
        ChaosRunView created = engine.create(plan(10, 30, 2, 1, "/orders"), "alice");
        ChaosRunEngine.Reservation reservation = engine.reserve("GET", "/orders").orElseThrow();

        assertThat(engine.stop(created.id()).state(), is(STOPPING));
        assertThat(engine.reserve("GET", "/orders"), is(Optional.empty()));
        reservation.close();

        assertThat(engine.get(created.id()).orElseThrow().state(), is(STOPPED));
        assertThat(engine.stop(created.id()).state(), is(STOPPED));
    }

    @Test
    void rejectsActiveLimitAndOverlappingScopes() {
        engine.create(plan(10, 30, 2, 1, "/orders"), "alice");
        assertThrows(ChaosRunEngine.ConflictException.class,
                     () -> engine.create(plan(10, 30, 2, 1, "/payments"), "bob"));

        ChaosRunEngine twoRunEngine = engine(limits(2, 8, Duration.ofMinutes(5)));
        twoRunEngine.create(plan(10, 30, 2, 1, "/orders"), "alice");
        assertThrows(ChaosRunEngine.ConflictException.class,
                     () -> twoRunEngine.create(plan(10, 30, 2, 1, "/orders/42"), "bob"));
        assertThat(twoRunEngine.create(plan(10, 30, 2, 1, "/payments"), "bob").state(), is(RUNNING));
    }

    @Test
    void reservesEveryFutureDisruptiveScopeButNoPassiveScope() {
        ChaosRunEngine twoRunEngine = engine(limits(2, 8, Duration.ofMinutes(5)));
        ChaosRunPlan sequence = new ChaosRunPlan("changing-scope",
                                                 Duration.ofSeconds(30),
                                                 42,
                                                 List.of(stage("orders",
                                                               5,
                                                               "/orders",
                                                               ChaosActivation.always(),
                                                               synthetic(503),
                                                               10,
                                                               2),
                                                         passiveStage("recovery", 5),
                                                         stage("payments",
                                                               5,
                                                               "/payments",
                                                               ChaosActivation.always(),
                                                               synthetic(503),
                                                               10,
                                                               2)));
        twoRunEngine.create(sequence, "alice");

        assertThrows(ChaosRunEngine.ConflictException.class,
                     () -> twoRunEngine.create(plan(10, 30, 2, 1, "/payments/42"), "bob"));

        ChaosRunEngine passiveEngine = engine(limits(2, 8, Duration.ofMinutes(5)));
        ChaosRunPlan passive = new ChaosRunPlan("passive",
                                                Duration.ofSeconds(30),
                                                42,
                                                List.of(passiveStage("observe", 5)));
        passiveEngine.create(passive, "alice");
        assertThat(passiveEngine.create(plan(10, 30, 2, 1, "/orders"), "bob").state(), is(RUNNING));
    }

    @Test
    void retainsScopesUntilStoppingRunDrains() {
        ChaosRunEngine twoRunEngine = engine(limits(2, 8, Duration.ofMinutes(5)));
        ChaosRunView created = twoRunEngine.create(plan(10, 30, 2, 1, "/orders"), "alice");
        ChaosRunEngine.Reservation reservation = twoRunEngine.reserve("GET", "/orders").orElseThrow();

        assertThat(twoRunEngine.stop(created.id()).state(), is(STOPPING));
        assertThrows(ChaosRunEngine.ConflictException.class,
                     () -> twoRunEngine.create(plan(10, 30, 2, 1, "/orders/42"), "bob"));

        reservation.close();
        assertThat(twoRunEngine.get(created.id()).orElseThrow().state(), is(STOPPED));
        assertThat(twoRunEngine.create(plan(10, 30, 2, 1, "/orders/42"), "bob").state(), is(RUNNING));
    }

    @Test
    void listsNewestRunFirstAndRejectsUnknownStop() {
        ChaosRunEngine twoRunEngine = engine(limits(2, 8, Duration.ofMinutes(5)));
        ChaosRunView first = twoRunEngine.create(plan(10, 30, 2, 1, "/orders"), "alice");
        ChaosRunView second = twoRunEngine.create(plan(10, 30, 2, 1, "/payments"), "bob");

        assertThat(twoRunEngine.list(), hasSize(2));
        assertThat(twoRunEngine.list().get(0).id(), is(second.id()));
        assertThat(twoRunEngine.list().get(1).id(), is(first.id()));
        assertThrows(ChaosRunEngine.NotFoundException.class, () -> twoRunEngine.stop(UUID.randomUUID()));
    }

    @Test
    void evictsTerminalRunsByAgeAndCapacity() {
        ChaosRunEngine retainedEngine = engine(limits(1, 2, Duration.ofSeconds(5)));
        ChaosRunView first = retainedEngine.create(plan(10, 30, 2, 1, "/orders"), "alice");
        retainedEngine.stop(first.id());
        ChaosRunView second = retainedEngine.create(plan(10, 30, 2, 1, "/payments"), "bob");
        retainedEngine.stop(second.id());
        ChaosRunView third = retainedEngine.create(plan(10, 30, 2, 1, "/inventory"), "carol");

        assertThat(retainedEngine.get(first.id()), is(Optional.empty()));
        assertThat(retainedEngine.get(third.id()).isPresent(), is(true));

        retainedEngine.stop(third.id());
        scheduler.advance(Duration.ofSeconds(6));
        assertThat(retainedEngine.list(), hasSize(0));
    }

    @Test
    void concurrentReservationsRespectConcurrencyCeiling() throws InterruptedException {
        engine.create(plan(10, 30, 100, 4, "/orders"), "alice");
        int attempts = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(attempts);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    Optional<ChaosRunEngine.Reservation> candidate = Optional.empty();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out waiting to start reservation attempt");
                        }
                        candidate = engine.reserve("GET", "/orders");
                        if (candidate.isPresent()) {
                            reserved.incrementAndGet();
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        failures.add(exception);
                    } catch (RuntimeException | AssertionError exception) {
                        failures.add(exception);
                    } finally {
                        attempted.countDown();
                    }
                    if (candidate.isPresent()) {
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("Timed out waiting to release reservation");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            failures.add(exception);
                        } catch (AssertionError exception) {
                            failures.add(exception);
                        } finally {
                            candidate.orElseThrow().close();
                        }
                    }
                });
            }
            start.countDown();
            try {
                assertThat(attempted.await(5, TimeUnit.SECONDS), is(true));
                assertThat(failures, is(empty()));
                assertThat(reserved.get(), is(4));
                assertThat(engine.list().getFirst().inFlight(), is(4L));
            } finally {
                release.countDown();
            }
        }

        assertThat(failures, is(empty()));
        assertThat(engine.list().getFirst().inFlight(), is(0L));
    }

    private static ChaosLimitsConfig limits(int activeRuns, int retainedRuns, Duration retention) {
        return ChaosLimitsConfig.builder()
                .maximumActiveRuns(activeRuns)
                .maximumRetainedRuns(retainedRuns)
                .terminalRunRetention(retention)
                .build();
    }

    private static ChaosRunPlan sequencePlan() {
        return new ChaosRunPlan("orders-sequence",
                                Duration.ofSeconds(30),
                                42,
                                List.of(stage("slow-orders",
                                              5,
                                              "/orders",
                                              ChaosActivation.always(),
                                              new ChaosLatency(Duration.ofMillis(10), Duration.ZERO),
                                              20,
                                              2),
                                        stage("orders-outage",
                                              10,
                                              "/orders",
                                              ChaosActivation.always(),
                                              synthetic(503),
                                              20,
                                              2),
                                        passiveStage("recovery", 5)));
    }

    private static ChaosRunPlan.ChaosStage stage(String name,
                                                  int durationSeconds,
                                                  String path,
                                                  ChaosActivation activation,
                                                  ChaosEffect effect,
                                                  long maximumActivations,
                                                  int maximumConcurrent) {
        ChaosHttpScope scope = new ChaosHttpScope(Set.of("GET"), PREFIX, path);
        ChaosBudget budget = new ChaosBudget(maximumActivations, maximumConcurrent);
        ChaosRunPlan.ChaosDisruption disruption =
                new ChaosRunPlan.ChaosDisruption(name + "-disruption", scope, activation, effect, budget);
        return new ChaosRunPlan.ChaosStage(name,
                                            Duration.ofSeconds(durationSeconds),
                                            Optional.of(disruption));
    }

    private static ChaosRunPlan.ChaosStage passiveStage(String name, int durationSeconds) {
        return new ChaosRunPlan.ChaosStage(name, Duration.ofSeconds(durationSeconds), Optional.empty());
    }

    private static ChaosSyntheticResponse synthetic(int status) {
        return new ChaosSyntheticResponse(status, Map.of(), Optional.empty(), new byte[0]);
    }

    private static void assertCurrentStage(ChaosRunView view,
                                           int index,
                                           String name,
                                           Instant startedAt,
                                           Instant endsAt) {
        ChaosRunView.CurrentStage current = view.currentStage().orElseThrow();
        assertThat(current.index(), is(index));
        assertThat(current.name(), is(name));
        assertThat(current.startedAt(), is(startedAt));
        assertThat(current.endsAt(), is(endsAt));
    }

    private static ChaosRunPlan plan(int stageSeconds,
                                     int maximumSeconds,
                                     long maximumActivations,
                                     int maximumConcurrent,
                                     String path) {
        return plan(stageSeconds,
                    maximumSeconds,
                    maximumActivations,
                    maximumConcurrent,
                    path,
                    ChaosActivation.always(),
                    42,
                    "synthetic");
    }

    private static ChaosRunPlan plan(int stageSeconds,
                                     int maximumSeconds,
                                     long maximumActivations,
                                     int maximumConcurrent,
                                     String path,
                                     ChaosActivation activation,
                                     long seed,
                                     String disruptionName) {
        ChaosSyntheticResponse response = new ChaosSyntheticResponse(503,
                                                                      Map.of("Retry-After", "1"),
                                                                      Optional.of(MediaTypes.TEXT_PLAIN),
                                                                      "failure".getBytes(StandardCharsets.UTF_8));
        return plan(stageSeconds,
                    maximumSeconds,
                    maximumActivations,
                    maximumConcurrent,
                    path,
                    activation,
                    seed,
                    disruptionName,
                    response);
    }

    private static ChaosRunPlan plan(int stageSeconds,
                                     int maximumSeconds,
                                     long maximumActivations,
                                     int maximumConcurrent,
                                     String path,
                                     ChaosActivation activation,
                                     long seed,
                                     String disruptionName,
                                     ChaosEffect effect) {
        ChaosHttpScope scope = new ChaosHttpScope(Set.of("GET"), PREFIX, path);
        ChaosBudget budget = new ChaosBudget(maximumActivations, maximumConcurrent);
        ChaosRunPlan.ChaosDisruption disruption =
                new ChaosRunPlan.ChaosDisruption(disruptionName, scope, activation, effect, budget);
        ChaosRunPlan.ChaosStage stage =
                new ChaosRunPlan.ChaosStage("stage", Duration.ofSeconds(stageSeconds), Optional.of(disruption));
        return new ChaosRunPlan("run", Duration.ofSeconds(maximumSeconds), seed, List.of(stage));
    }

    private static List<Boolean> reserveSequence(ChaosRunEngine runEngine, int count) {
        List<Boolean> decisions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Optional<ChaosRunEngine.Reservation> reservation = runEngine.reserve("GET", "/orders/42");
            decisions.add(reservation.isPresent());
            reservation.ifPresent(ChaosRunEngine.Reservation::close);
        }
        return decisions;
    }

    private static List<Duration> latencySequence(ChaosRunEngine runEngine, int count) {
        List<Duration> delays = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try (ChaosRunEngine.Reservation reservation = runEngine.reserve("GET", "/orders/42").orElseThrow()) {
                ChaosLatencyAction latency = (ChaosLatencyAction) reservation.action();
                delays.add(latency.delay());
            }
        }
        return delays;
    }

    private ChaosRunEngine engine(ChaosLimitsConfig limits) {
        return ChaosRunEngine.create(limits,
                                     scheduler.clock(),
                                     scheduler,
                                     () -> new UUID(0, ids.incrementAndGet()));
    }

}
