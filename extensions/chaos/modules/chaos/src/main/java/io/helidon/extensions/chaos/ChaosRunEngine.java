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
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static io.helidon.extensions.chaos.ChaosRunState.COMPLETED;
import static io.helidon.extensions.chaos.ChaosRunState.EXPIRED;
import static io.helidon.extensions.chaos.ChaosRunState.STOPPED;

/**
 * Bounded in-process lifecycle and activation engine.
 *
 * <p>All mutations are deliberately serialized. The critical section contains
 * no user code, blocking work, or sleeps, making the safety checks and
 * lifecycle races atomic while keeping request-path work bounded.</p>
 */
final class ChaosRunEngine implements AutoCloseable {
    private static final String REASON_COMPLETED = "stage-duration-completed";
    private static final String REASON_EXPIRED = "maximum-duration-expired";
    private static final String REASON_STOPPED = "operator-stopped";
    private static final int ID_ATTEMPTS = 16;

    private final ChaosLimitsConfig limits;
    private final Clock clock;
    private final ChaosScheduler scheduler;
    private final Supplier<UUID> idSupplier;
    private final Map<UUID, ChaosRun> runs = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private long sequence;
    private boolean closed;

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
            evictExpiredTerminalRuns();

            List<ChaosRun> activeRuns = activeRuns();
            if (activeRuns.size() >= limits.maximumActiveRuns()) {
                throw new ConflictException("The maximum number of active chaos runs has been reached");
            }
            ChaosHttpScope requestedScope = plan.stage().disruption().scope();
            if (activeRuns.stream().anyMatch(run -> overlaps(run.scope(), requestedScope))) {
                throw new ConflictException("The requested scope overlaps an active chaos disruption");
            }
            evictForCapacity();

            UUID id = nextId();
            ChaosRun run = new ChaosRun(id, ++sequence, plan, actor, clock.instant());
            ChaosScheduler.Cancellable completionTask = scheduler.schedule(plan.stage().duration(),
                                                                           () -> terminate(id,
                                                                                           COMPLETED,
                                                                                           REASON_COMPLETED));
            try {
                ChaosScheduler.Cancellable expirationTask = scheduler.schedule(plan.maximumDuration(),
                                                                                () -> terminate(id,
                                                                                                EXPIRED,
                                                                                                REASON_EXPIRED));
                run.tasks(completionTask, expirationTask);
            } catch (RuntimeException e) {
                completionTask.cancel();
                throw e;
            }
            runs.put(id, run);
            return run.view();
        } finally {
            lock.unlock();
        }
    }

    Optional<Reservation> reserve(String method, String requestPath) {
        lock.lock();
        try {
            Objects.requireNonNull(method);
            Objects.requireNonNull(requestPath);
            if (closed) {
                return Optional.empty();
            }
            for (ChaosRun run : runs.values()) {
                Optional<ChaosRun.Activation> activation = run.reserve(method, requestPath);
                if (activation.isPresent()) {
                    return Optional.of(new Reservation(this, run.id(), activation.orElseThrow()));
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    Optional<ChaosRunView> get(UUID id) {
        lock.lock();
        try {
            Objects.requireNonNull(id);
            evictExpiredTerminalRuns();
            return Optional.ofNullable(runs.get(id)).map(ChaosRun::view);
        } finally {
            lock.unlock();
        }
    }

    List<ChaosRunView> list() {
        lock.lock();
        try {
            evictExpiredTerminalRuns();
            return runs.values()
                    .stream()
                    .sorted(Comparator.comparingLong(ChaosRun::sequence).reversed())
                    .map(ChaosRun::view)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    ChaosRunView stop(UUID id) {
        lock.lock();
        try {
            Objects.requireNonNull(id);
            evictExpiredTerminalRuns();
            ChaosRun run = runs.get(id);
            if (run == null) {
                throw new NotFoundException(id);
            }
            if (run.running()) {
                run.terminate(STOPPED, REASON_STOPPED, clock.instant());
            }
            return run.view();
        } finally {
            lock.unlock();
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
                    run.terminate(STOPPED, "engine-closed", clock.instant());
                }
            }
            scheduler.close();
        } finally {
            lock.unlock();
        }
    }

    private static boolean overlaps(ChaosHttpScope first, ChaosHttpScope second) {
        if (!methodsOverlap(first.methods(), second.methods())) {
            return false;
        }
        return switch (first.pathMatch()) {
        case EXACT -> switch (second.pathMatch()) {
            case EXACT -> first.path().equals(second.path());
            case PREFIX -> prefixContains(second.path(), first.path());
        };
        case PREFIX -> switch (second.pathMatch()) {
            case EXACT -> prefixContains(first.path(), second.path());
            case PREFIX -> prefixContains(first.path(), second.path()) || prefixContains(second.path(), first.path());
        };
        };
    }

    private static boolean methodsOverlap(Set<String> first, Set<String> second) {
        return first.stream().anyMatch(second::contains);
    }

    private static boolean prefixContains(String prefix, String path) {
        return prefix.equals("/") || path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private void terminate(UUID id, ChaosRunState state, String reason) {
        lock.lock();
        try {
            ChaosRun run = runs.get(id);
            if (run != null && run.running()) {
                run.terminate(state, reason, clock.instant());
            }
        } finally {
            lock.unlock();
        }
    }

    private void release(UUID id) {
        lock.lock();
        try {
            ChaosRun run = runs.get(id);
            if (run != null) {
                run.release(clock.instant());
            }
        } finally {
            lock.unlock();
        }
    }

    private List<ChaosRun> activeRuns() {
        return runs.values().stream().filter(ChaosRun::running).toList();
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
        private final ChaosEffect effect;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(ChaosRunEngine engine, UUID runId, ChaosRun.Activation activation) {
            this.engine = engine;
            this.runId = runId;
            this.effect = activation.effect();
        }

        ChaosEffect effect() {
            return effect;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                engine.release(runId);
            }
        }
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
