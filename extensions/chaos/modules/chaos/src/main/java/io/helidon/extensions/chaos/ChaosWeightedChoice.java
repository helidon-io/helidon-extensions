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

import java.util.List;
import java.util.Objects;

/**
 * Deterministically selects one weighted leaf effect for an activation.
 */
final class ChaosWeightedChoice implements ChaosEffect {
    private final List<Outcome> outcomes;
    private final long totalWeight;

    ChaosWeightedChoice(List<Outcome> outcomes) {
        Objects.requireNonNull(outcomes, "outcomes is null");
        this.outcomes = List.copyOf(outcomes);
        if (this.outcomes.isEmpty()) {
            throw new IllegalArgumentException("outcomes must not be empty");
        }
        long total = 0;
        for (Outcome outcome : this.outcomes) {
            if (outcome.effect() instanceof ChaosWeightedChoice) {
                throw new IllegalArgumentException("weighted-choice outcomes must be leaf effects");
            }
            try {
                total = Math.addExact(total, outcome.weight());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("outcome weight total is too large", exception);
            }
        }
        this.totalWeight = total;
    }

    List<Outcome> outcomes() {
        return outcomes;
    }

    ChaosEffectAction resolve(long choiceSample, long effectSample) {
        ChaosEffect selected = select(choiceSample);
        return switch (selected) {
        case ChaosLatency latency -> latency.resolve(effectSample);
        case ChaosSyntheticResponse synthetic -> synthetic;
        case ChaosWeightedChoice choice -> throw new IllegalStateException(
                "Nested weighted choice with " + choice.outcomes().size() + " outcomes");
        };
    }

    private ChaosEffect select(long randomSample) {
        long selectedWeight = ChaosRandom.boundedLong(randomSample, totalWeight);
        long cumulativeWeight = 0;
        for (Outcome outcome : outcomes) {
            cumulativeWeight += outcome.weight();
            if (selectedWeight < cumulativeWeight) {
                return outcome.effect();
            }
        }
        throw new IllegalStateException("Weighted choice did not select an outcome");
    }

    /**
     * One weighted leaf effect.
     *
     * @param weight relative positive weight
     * @param effect leaf effect
     */
    record Outcome(long weight, ChaosEffect effect) {
        Outcome {
            if (weight <= 0) {
                throw new IllegalArgumentException("weight must be positive");
            }
            Objects.requireNonNull(effect, "effect is null");
        }
    }
}
