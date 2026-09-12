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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable normalized plan for the first Chaos vertical slice.
 *
 * @param name diagnostic run name
 * @param maximumDuration run guard duration
 * @param seed deterministic run seed
 * @param stages ordered run stages
 */
record ChaosRunPlan(String name, Duration maximumDuration, long seed, List<ChaosStage> stages) {

    ChaosRunPlan {
        Objects.requireNonNull(name);
        Objects.requireNonNull(maximumDuration);
        Objects.requireNonNull(stages);
        stages = List.copyOf(stages);
    }

    Duration duration() {
        Duration result = Duration.ZERO;
        for (ChaosStage stage : stages) {
            result = result.plus(stage.duration());
        }
        return result;
    }

    /**
     * One stage in a run plan.
     *
     * @param name diagnostic stage name
     * @param duration active stage duration
     * @param disruption optional disruption
     */
    record ChaosStage(String name, Duration duration, Optional<ChaosDisruption> disruption) {
        ChaosStage {
            Objects.requireNonNull(name);
            Objects.requireNonNull(duration);
            Objects.requireNonNull(disruption);
        }
    }

    /**
     * Single inbound HTTP disruption.
     *
     * @param name diagnostic disruption name
     * @param scope inbound request scope
     * @param activation matched-invocation activation policy
     * @param effect disruption effect
     * @param budget cumulative and concurrent budget
     */
    record ChaosDisruption(String name,
                           ChaosHttpScope scope,
                           ChaosActivation activation,
                           ChaosEffect effect,
                           ChaosBudget budget) {
        ChaosDisruption {
            Objects.requireNonNull(name);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(activation);
            Objects.requireNonNull(effect);
            Objects.requireNonNull(budget);
        }
    }
}
