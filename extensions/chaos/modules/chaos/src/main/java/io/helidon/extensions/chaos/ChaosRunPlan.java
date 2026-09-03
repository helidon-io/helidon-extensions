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
import java.util.Objects;

/**
 * Immutable normalized plan for the first Chaos vertical slice.
 *
 * @param name diagnostic run name
 * @param maximumDuration run guard duration
 * @param seed deterministic run seed
 * @param stage single run stage
 */
record ChaosRunPlan(String name, Duration maximumDuration, long seed, ChaosStage stage) {

    ChaosRunPlan {
        Objects.requireNonNull(name);
        Objects.requireNonNull(maximumDuration);
        Objects.requireNonNull(stage);
    }

    /**
     * Single stage in a run plan.
     *
     * @param name diagnostic stage name
     * @param duration active stage duration
     * @param disruption single disruption
     */
    record ChaosStage(String name, Duration duration, ChaosDisruption disruption) {
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
