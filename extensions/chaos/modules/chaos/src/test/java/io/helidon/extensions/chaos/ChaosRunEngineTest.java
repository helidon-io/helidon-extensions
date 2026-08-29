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
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosRunEngineTest {

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
    void expirationGuardWinsIfItsTaskFiresFirst() {
        ChaosRunView created = engine.create(plan(10, 30, 20, 2, "/orders"), "alice");

        scheduler.fireLast();

        assertThat(engine.get(created.id()).orElseThrow().state(), is(EXPIRED));
    }

    @Test
    void cumulativeAndConcurrentBudgetsNeverOvershoot() {
        ChaosRunView created = engine.create(plan(10, 30, 2, 1, "/orders"), "alice");
        ChaosRunEngine.Reservation first = engine.reserve("GET", "/orders/42").orElseThrow();

        assertThat(engine.reserve("GET", "/orders/43"), is(Optional.empty()));
        first.close();
        try (ChaosRunEngine.Reservation second = engine.reserve("GET", "/orders/44").orElseThrow()) {
            assertThat(((ChaosSyntheticResponse) second.effect()).status(), is(503));
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
    void stableIdentityEncodingSeparatesEmbeddedNullCharacters() {
        assertThat(ChaosActivationDecider.streamSeed(42, "a", "b\0c")
                           == ChaosActivationDecider.streamSeed(42, "a\0b", "c"),
                   is(false));
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
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add(e);
                    } catch (RuntimeException | AssertionError e) {
                        failures.add(e);
                    } finally {
                        attempted.countDown();
                    }
                    if (candidate.isPresent()) {
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("Timed out waiting to release reservation");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failures.add(e);
                        } catch (AssertionError e) {
                            failures.add(e);
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

    private ChaosRunEngine engine(ChaosLimitsConfig limits) {
        return ChaosRunEngine.create(limits,
                                     scheduler.clock(),
                                     scheduler,
                                     () -> new UUID(0, ids.incrementAndGet()));
    }

    private static ChaosLimitsConfig limits(int activeRuns, int retainedRuns, Duration retention) {
        return ChaosLimitsConfig.builder()
                .maximumActiveRuns(activeRuns)
                .maximumRetainedRuns(retainedRuns)
                .terminalRunRetention(retention)
                .build();
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
                new ChaosRunPlan.ChaosStage("stage", Duration.ofSeconds(stageSeconds), disruption);
        return new ChaosRunPlan("run", Duration.ofSeconds(maximumSeconds), seed, stage);
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

}
