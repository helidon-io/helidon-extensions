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

/**
 * Determines whether a matched invocation attempts to reserve disruption budget.
 */
sealed interface ChaosActivation permits ChaosActivation.AlwaysActivation, ChaosActivation.ProbabilityActivation {

    /**
     * Shared activation that accepts every matched invocation.
     */
    ChaosActivation ALWAYS = new AlwaysActivation();

    static ChaosActivation always() {
        return ALWAYS;
    }

    /**
     * Activates every matched invocation.
     */
    final class AlwaysActivation implements ChaosActivation {
    }

    /**
     * Activates a deterministic fraction of matched invocations.
     *
     * @param probability value greater than zero and at most one
     */
    record ProbabilityActivation(double probability) implements ChaosActivation {
        private static final double MINIMUM = 0x1.0p-53;

        public ProbabilityActivation {
            if (!Double.isFinite(probability) || probability < MINIMUM || probability > 1) {
                throw new IllegalArgumentException("probability must be between 2^-53 and one");
            }
        }
    }
}
