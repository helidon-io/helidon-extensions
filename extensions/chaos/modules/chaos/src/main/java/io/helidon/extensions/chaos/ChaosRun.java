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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.helidon.extensions.chaos.ChaosRunState.COMPLETED;

final class ChaosRun {
    private static final String REASON_COMPLETED = "stage-duration-completed";

    private final UUID id;
    private final long sequence;
    private final ChaosRunPlan plan;
    private final String actor;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant completesAt;
    private final List<StageRuntime> stages;

    private ChaosRunState state = ChaosRunState.RUNNING;
    private ChaosRunState drainedState;
    private Instant terminalAt;
    private String terminalReason;
    private long matched;
    private long activated;
    private long skippedActivation;
    private long skippedConcurrent;
    private long skippedBudget;
    private long inFlight;
    private long completed;
    private ChaosScheduler.Cancellable completionTask;
    private Optional<ChaosScheduler.Cancellable> expirationTask = Optional.empty();

    ChaosRun(UUID id,
             long sequence,
             ChaosRunPlan plan,
             String actor,
             Instant createdAt) {
        this.id = id;
        this.sequence = sequence;
        this.plan = plan;
        this.actor = actor;
        this.createdAt = createdAt;
        this.expiresAt = createdAt.plus(plan.maximumDuration());

        List<StageRuntime> runtimes = new ArrayList<>(plan.stages().size());
        Instant startedAt = createdAt;
        for (int index = 0; index < plan.stages().size(); index++) {
            ChaosRunPlan.ChaosStage stage = plan.stages().get(index);
            Instant endsAt = startedAt.plus(stage.duration());
            runtimes.add(new StageRuntime(index, stage, startedAt, endsAt, plan.seed()));
            startedAt = endsAt;
        }
        this.stages = List.copyOf(runtimes);
        this.completesAt = startedAt;
    }

    UUID id() {
        return id;
    }

    long sequence() {
        return sequence;
    }

    List<ChaosHttpScope> scopes() {
        return stages.stream()
                .flatMap(stage -> stage.disruption.stream())
                .map(disruption -> disruption.plan.scope())
                .toList();
    }

    boolean running() {
        return state == ChaosRunState.RUNNING;
    }

    boolean terminal() {
        return state != ChaosRunState.RUNNING && state != ChaosRunState.STOPPING;
    }

    boolean terminalAtOrBefore(Instant instant) {
        return terminal() && terminalAt != null && !terminalAt.isAfter(instant);
    }

    void tasks(ChaosScheduler.Cancellable completionTask,
               Optional<ChaosScheduler.Cancellable> expirationTask) {
        this.completionTask = completionTask;
        this.expirationTask = expirationTask;
    }

    Optional<Activation> reserve(String method, String requestPath, Instant now) {
        refresh(now);
        if (!running()) {
            return Optional.empty();
        }
        Optional<StageRuntime> current = currentStage(now);
        if (current.isEmpty() || current.orElseThrow().disruption.isEmpty()) {
            return Optional.empty();
        }
        StageRuntime stage = current.orElseThrow();
        DisruptionRuntime disruption = stage.disruption.orElseThrow();
        if (!disruption.plan.scope().matches(method, requestPath)) {
            return Optional.empty();
        }
        disruption.matched++;
        matched++;
        if (!ChaosActivationDecider.activates(disruption.plan.activation(),
                                              disruption.activationStreamSeed,
                                              disruption.matched)) {
            skippedActivation++;
            return Optional.empty();
        }
        ChaosBudget budget = disruption.plan.budget();
        if (disruption.inFlight >= budget.maximumConcurrent()) {
            skippedConcurrent++;
            return Optional.empty();
        }
        if (disruption.activated >= budget.maximumActivations()) {
            skippedBudget++;
            return Optional.empty();
        }
        ChaosEffectAction action = switch (disruption.plan.effect()) {
        case ChaosLatency latency -> latency.resolve(ChaosRandom.sample(disruption.effectStreamSeed,
                                                                        disruption.matched));
        case ChaosSyntheticResponse synthetic -> synthetic;
        case ChaosWeightedChoice choice -> choice.resolve(ChaosRandom.sample(disruption.choiceStreamSeed,
                                                                             disruption.matched),
                                                          ChaosRandom.sample(disruption.effectStreamSeed,
                                                                             disruption.matched));
        };
        disruption.inFlight++;
        disruption.activated++;
        inFlight++;
        activated++;
        return Optional.of(new Activation(stage.index, action));
    }

    void refresh(Instant now) {
        if (running() && !now.isBefore(completesAt)) {
            terminate(COMPLETED, REASON_COMPLETED, now);
        }
    }

    void terminate(ChaosRunState terminalState, String reason, Instant now) {
        cancelTasks();
        terminalReason = reason;
        if (inFlight == 0) {
            state = terminalState;
            terminalAt = now;
        } else {
            state = ChaosRunState.STOPPING;
            drainedState = terminalState;
        }
    }

    void release(int stageIndex, Instant now) {
        DisruptionRuntime disruption = stages.get(stageIndex).disruption.orElseThrow();
        if (disruption.inFlight == 0) {
            return;
        }
        disruption.inFlight--;
        inFlight--;
        completed++;
        if (state == ChaosRunState.STOPPING && inFlight == 0) {
            state = drainedState;
            terminalAt = now;
        }
    }

    ChaosRunView view(Instant now) {
        refresh(now);
        Optional<ChaosRunView.CurrentStage> current = running()
                ? currentStage(now).map(StageRuntime::view)
                : Optional.empty();
        return new ChaosRunView(id,
                                plan.name(),
                                state,
                                plan,
                                plan.seed(),
                                actor,
                                createdAt,
                                createdAt,
                                expiresAt,
                                current,
                                Optional.ofNullable(terminalAt),
                                Optional.ofNullable(terminalReason),
                                matched,
                                activated,
                                skippedActivation,
                                skippedConcurrent,
                                skippedBudget,
                                inFlight,
                                completed);
    }

    private Optional<StageRuntime> currentStage(Instant now) {
        return stages.stream()
                .filter(stage -> !now.isBefore(stage.startedAt) && now.isBefore(stage.endsAt))
                .findFirst();
    }

    private void cancelTasks() {
        if (completionTask != null) {
            completionTask.cancel();
        }
        expirationTask.ifPresent(ChaosScheduler.Cancellable::cancel);
    }

    record Activation(int stageIndex, ChaosEffectAction action) {
    }

    private static final class StageRuntime {
        private final int index;
        private final ChaosRunPlan.ChaosStage plan;
        private final Instant startedAt;
        private final Instant endsAt;
        private final Optional<DisruptionRuntime> disruption;

        private StageRuntime(int index,
                             ChaosRunPlan.ChaosStage plan,
                             Instant startedAt,
                             Instant endsAt,
                             long seed) {
            this.index = index;
            this.plan = plan;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
            this.disruption = plan.disruption()
                    .map(value -> new DisruptionRuntime(value, seed, plan.name()));
        }

        private ChaosRunView.CurrentStage view() {
            return new ChaosRunView.CurrentStage(index, plan.name(), startedAt, endsAt);
        }
    }

    private static final class DisruptionRuntime {
        private final ChaosRunPlan.ChaosDisruption plan;
        private final long activationStreamSeed;
        private final long effectStreamSeed;
        private final long choiceStreamSeed;
        private long matched;
        private long activated;
        private long inFlight;

        private DisruptionRuntime(ChaosRunPlan.ChaosDisruption plan, long seed, String stageName) {
            this.plan = plan;
            this.activationStreamSeed = ChaosActivationDecider.streamSeed(seed, stageName, plan.name());
            this.effectStreamSeed = ChaosRandom.streamSeed(seed, stageName, plan.name(), "effect");
            this.choiceStreamSeed = ChaosRandom.streamSeed(seed, stageName, plan.name(), "choice");
        }
    }
}
