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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static io.helidon.extensions.chaos.ChaosStopReason.ENGINE_CLOSED;
import static io.helidon.extensions.chaos.ChaosStopReason.HALTED;
import static io.helidon.extensions.chaos.ChaosStopReason.MAXIMUM_DURATION_EXPIRED;
import static io.helidon.extensions.chaos.ChaosStopReason.OPERATOR_STOPPED;
import static io.helidon.extensions.chaos.ChaosStopReason.STAGE_DURATION_COMPLETED;

/**
 * Bounded in-process lifecycle and activation engine.
 *
 * <p>All mutations are deliberately serialized. The critical section contains
 * no user code, blocking work, or sleeps, making the safety checks and
 * lifecycle races atomic while keeping request-path work bounded.</p>
 */
final class ChaosRunEngine implements AutoCloseable {
    private static final int ID_ATTEMPTS = 16;

    private final ChaosLimitsConfig limits;
    private final Clock clock;
    private final ChaosScheduler scheduler;
    private final Supplier<UUID> idSupplier;
    private final Map<UUID, ChaosRun> runs = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private long sequence;
    private boolean closed;
    private volatile boolean reservationsPossible;
    private volatile boolean inboundWorkNeeded;

    private ChaosRunEngine(ChaosLimitsConfig limits,
                           Clock clock,
                           ChaosScheduler scheduler,
                           Supplier<UUID> idSupplier) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.idSupplier = Objects.requireNonNull(idSupplier);
    }

    static ChaosRunEngine create(ChaosLimitsConfig limits) {
        return new ChaosRunEngine(limits,
                                  Clock.systemUTC(),
                                  ChaosScheduler.create(),
                                  UUID::randomUUID);
    }

    static ChaosRunEngine create(ChaosLimitsConfig limits,
                                 Clock clock,
                                 ChaosScheduler scheduler,
                                 Supplier<UUID> idSupplier) {
        return new ChaosRunEngine(limits, clock, scheduler, idSupplier);
    }

    ChaosRunView create(ChaosRunPlan plan, String actor) {
        lock.lock();
        try {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(actor);
            ensureOpen();
            Instant now = clock.instant();
            refreshRuns(now);
            evictExpiredTerminalRuns();

            List<ChaosRun> activeRuns = activeRuns();
            if (activeRuns.size() >= limits.maximumActiveRuns()) {
                throw new ConflictException("The maximum number of active chaos runs has been reached");
            }
            List<ChaosScope> requestedScopes = scopes(plan);
            if (activeRuns.stream().anyMatch(run -> overlaps(run.scopes(), requestedScopes))) {
                throw new ConflictException("The requested scope overlaps an active chaos disruption");
            }
            evictForCapacity();

            UUID id = nextId();
            ChaosRun run = new ChaosRun(id, ++sequence, plan, actor, now);
            Duration runDuration = plan.duration();
            ChaosScheduler.Cancellable completionTask = scheduler.schedule(runDuration,
                                                                           () -> terminate(id, STAGE_DURATION_COMPLETED));
            Optional<ChaosScheduler.Cancellable> expirationTask = Optional.empty();
            try {
                if (plan.maximumDuration().compareTo(runDuration) > 0) {
                    expirationTask = Optional.of(scheduler.schedule(plan.maximumDuration(),
                                                                    () -> terminate(id, MAXIMUM_DURATION_EXPIRED)));
                }
                run.tasks(completionTask, expirationTask);
            } catch (RuntimeException exception) {
                completionTask.cancel();
                throw exception;
            }
            runs.put(id, run);
            return run.view(now);
        } finally {
            try {
                publishRequestPathWork();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Whether any nonterminal run exists. Read without the lock; a stale {@code false} only lets a request
     * proceed undisrupted.
     *
     * @return whether outbound reservations can succeed
     */
    boolean reservationsPossible() {
        return reservationsPossible;
    }

    /**
     * Whether inbound requests need the engine. Read without the lock.
     *
     * @return whether the inbound filter must consult the engine
     */
    boolean inboundWorkNeeded() {
        return inboundWorkNeeded;
    }

    Optional<Reservation> reserveInbound(String method, String requestPath) {
        Objects.requireNonNull(method);
        Objects.requireNonNull(requestPath);
        return reserve((run, now) -> run.reserveInbound(method, requestPath, now));
    }

    Optional<Reservation> reserveOutbound(String method,
                                          String scheme,
                                          String host,
                                          int port,
                                          String requestPath) {
        Objects.requireNonNull(method);
        Objects.requireNonNull(scheme);
        Objects.requireNonNull(host);
        Objects.requireNonNull(requestPath);
        return reserve((run, now) -> run.reserveOutbound(method, scheme, host, port, requestPath, now));
    }

    Optional<ChaosRunView> get(UUID id) {
        lock.lock();
        try {
            Objects.requireNonNull(id);
            Instant now = clock.instant();
            refreshRuns(now);
            evictExpiredTerminalRuns();
            return Optional.ofNullable(runs.get(id)).map(run -> run.view(now));
        } finally {
            try {
                publishRequestPathWork();
            } finally {
                lock.unlock();
            }
        }
    }

    List<ChaosRunView> list() {
        lock.lock();
        try {
            Instant now = clock.instant();
            refreshRuns(now);
            evictExpiredTerminalRuns();
            return runs.values()
                    .stream()
                    .sorted(Comparator.comparingLong(ChaosRun::sequence).reversed())
                    .map(run -> run.view(now))
                    .toList();
        } finally {
            try {
                publishRequestPathWork();
            } finally {
                lock.unlock();
            }
        }
    }

    ChaosRunView stop(UUID id) {
        lock.lock();
        try {
            Objects.requireNonNull(id);
            Instant now = clock.instant();
            refreshRuns(now);
            evictExpiredTerminalRuns();
            ChaosRun run = runs.get(id);
            if (run == null) {
                throw new NotFoundException(id);
            }
            if (run.running()) {
                run.terminate(OPERATOR_STOPPED, now);
            }
            return run.view(now);
        } finally {
            try {
                publishRequestPathWork();
            } finally {
                lock.unlock();
            }
        }
    }

    int activeRunCount() {
        lock.lock();
        try {
            refreshRuns(clock.instant());
            return activeRuns().size();
        } finally {
            try {
                publishRequestPathWork();
            } finally {
                lock.unlock();
            }
        }
    }

    HaltResult haltAll() {
        lock.lock();
        try {
            Instant now = clock.instant();
            refreshRuns(now);
            List<UUID> halted = new ArrayList<>();
            for (ChaosRun run : runs.values()) {
                if (run.running()) {
                    run.terminate(HALTED, now);
                    halted.add(run.id());
                }
            }
            List<UUID> draining = runs.values()
                    .stream()
                    .filter(run -> !run.running() && !run.terminal())
                    .map(ChaosRun::id)
                    .toList();
            return new HaltResult(List.copyOf(halted), draining);
        } finally {
            try {
                publishRequestPathWork();
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            for (ChaosRun run : runs.values()) {
                if (run.running()) {
                    run.terminate(ENGINE_CLOSED, clock.instant());
                }
            }
            scheduler.close();
        } finally {
            try {
                publishRequestPathWork();
            } finally {
                lock.unlock();
            }
        }
    }

    private static List<ChaosScope> scopes(ChaosRunPlan plan) {
        return plan.stages().stream()
                .flatMap(stage -> stage.disruption().stream())
                .map(ChaosRunPlan.ChaosDisruption::scope)
                .toList();
    }

    private static boolean overlaps(List<ChaosScope> first, List<ChaosScope> second) {
        return first.stream()
                .anyMatch(firstScope -> second.stream()
                .anyMatch(firstScope::overlaps));
    }

    private Optional<Reservation> reserve(BiFunction<ChaosRun, Instant, Optional<ChaosRun.Activation>> reserve) {
        boolean runStopped = false;
        lock.lock();
        try {
            if (closed) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            for (ChaosRun run : runs.values()) {
                boolean wasRunning = run.running();
                Optional<ChaosRun.Activation> activation = reserve.apply(run, now);
                // A request can complete a run before its timer fires; the timer is then cancelled.
                runStopped |= wasRunning && !run.running();
                if (activation.isPresent()) {
                    return Optional.of(new Reservation(this, run.id(), activation.orElseThrow()));
                }
            }
            return Optional.empty();
        } finally {
            try {
                if (runStopped) {
                    publishRequestPathWork();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void terminate(UUID id, ChaosStopReason reason) {
        lock.lock();
        try {
            ChaosRun run = runs.get(id);
            if (run != null && run.running()) {
                run.terminate(reason, clock.instant());
            }
        } finally {
            try {
                publishRequestPathWork();
            } finally {
                lock.unlock();
            }
        }
    }

    private void release(UUID id, int stageIndex) {
        lock.lock();
        try {
            ChaosRun run = runs.get(id);
            if (run != null) {
                run.release(stageIndex, clock.instant());
            }
        } finally {
            try {
                publishRequestPathWork();
            } finally {
                lock.unlock();
            }
        }
    }

    // Called with the lock held, before every unlock that can change run state; reserve() calls it only when a
    // run stopped during the call, so the request path stays cheap.
    private void publishRequestPathWork() {
        boolean active = !closed && runs.values().stream().anyMatch(run -> !run.terminal());
        reservationsPossible = active;
        inboundWorkNeeded = active;
    }

    private List<ChaosRun> activeRuns() {
        return runs.values().stream().filter(run -> !run.terminal()).toList();
    }

    private void refreshRuns(Instant now) {
        runs.values().forEach(run -> run.refresh(now));
    }

    private UUID nextId() {
        for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
            UUID candidate = Objects.requireNonNull(idSupplier.get());
            if (!runs.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate a unique chaos run identifier");
    }

    private void ensureOpen() {
        if (closed) {
            throw new ConflictException("The chaos run engine is closed");
        }
    }

    private void evictExpiredTerminalRuns() {
        Instant oldestRetained = clock.instant().minus(limits.terminalRunRetention());
        runs.values().removeIf(run -> run.terminalAtOrBefore(oldestRetained));
    }

    private void evictForCapacity() {
        while (runs.size() >= limits.maximumRetainedRuns()) {
            Iterator<ChaosRun> candidates = runs.values()
                    .stream()
                    .filter(ChaosRun::terminal)
                    .sorted(Comparator.comparingLong(ChaosRun::sequence))
                    .iterator();
            if (!candidates.hasNext()) {
                throw new ConflictException("The retained chaos run capacity has been reached");
            }
            runs.remove(candidates.next().id());
        }
    }

    /**
     * One accepted disruption activation. Closing it releases the concurrency budget.
     */
    static final class Reservation implements AutoCloseable {
        private final ChaosRunEngine engine;
        private final UUID runId;
        private final int stageIndex;
        private final ChaosEffectAction action;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(ChaosRunEngine engine, UUID runId, ChaosRun.Activation activation) {
            this.engine = engine;
            this.runId = runId;
            this.stageIndex = activation.stageIndex();
            this.action = activation.action();
        }

        ChaosEffectAction action() {
            return action;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                engine.release(runId, stageIndex);
            }
        }
    }

    /**
     * Outcome of halting all runs.
     *
     * @param halted runs this call stopped, oldest first
     * @param draining runs still waiting for in-flight work, oldest first
     */
    record HaltResult(List<UUID> halted, List<UUID> draining) {
    }

    /**
     * A requested run conflicts with a server-enforced lifecycle or scope limit.
     */
    static final class ConflictException extends RuntimeException {
        private ConflictException(String message) {
            super(message);
        }
    }

    /**
     * No retained run has the requested identifier.
     */
    static final class NotFoundException extends RuntimeException {
        NotFoundException(UUID id) {
            super("Chaos run not found: " + id);
        }
    }
}
