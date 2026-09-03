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
import java.util.Optional;
import java.util.UUID;

final class ChaosRun {
    private final UUID id;
    private final long sequence;
    private final ChaosRunPlan plan;
    private final String actor;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final long activationStreamSeed;

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
    private ChaosScheduler.Cancellable expirationTask;

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
        ChaosRunPlan.ChaosDisruption disruption = plan.stage().disruption();
        this.activationStreamSeed = ChaosActivationDecider.streamSeed(plan.seed(),
                                                                      plan.stage().name(),
                                                                      disruption.name());
    }

    UUID id() {
        return id;
    }

    long sequence() {
        return sequence;
    }

    ChaosHttpScope scope() {
        return plan.stage().disruption().scope();
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

    void tasks(ChaosScheduler.Cancellable completionTask, ChaosScheduler.Cancellable expirationTask) {
        this.completionTask = completionTask;
        this.expirationTask = expirationTask;
    }

    Optional<Activation> reserve(String method, String requestPath) {
        ChaosRunPlan.ChaosDisruption disruption = plan.stage().disruption();
        if (!running() || !disruption.scope().matches(method, requestPath)) {
            return Optional.empty();
        }
        matched++;
        if (!ChaosActivationDecider.activates(disruption.activation(), activationStreamSeed, matched)) {
            skippedActivation++;
            return Optional.empty();
        }
        ChaosBudget budget = disruption.budget();
        if (inFlight >= budget.maximumConcurrent()) {
            skippedConcurrent++;
            return Optional.empty();
        }
        if (activated >= budget.maximumActivations()) {
            skippedBudget++;
            return Optional.empty();
        }
        inFlight++;
        activated++;
        return Optional.of(new Activation(disruption.effect()));
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

    void release(Instant now) {
        if (inFlight == 0) {
            return;
        }
        inFlight--;
        completed++;
        if (state == ChaosRunState.STOPPING && inFlight == 0) {
            state = drainedState;
            terminalAt = now;
        }
    }

    ChaosRunView view() {
        return new ChaosRunView(id,
                                plan.name(),
                                state,
                                plan,
                                plan.seed(),
                                actor,
                                createdAt,
                                createdAt,
                                expiresAt,
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

    private void cancelTasks() {
        if (completionTask != null) {
            completionTask.cancel();
        }
        if (expirationTask != null) {
            expirationTask.cancel();
        }
    }

    record Activation(ChaosEffect effect) {
    }
}
