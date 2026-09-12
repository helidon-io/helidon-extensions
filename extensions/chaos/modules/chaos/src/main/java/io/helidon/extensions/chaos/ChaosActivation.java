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
sealed interface ChaosActivation permits ChaosActivation.AlwaysActivation,
        ChaosActivation.PeriodicBurstActivation,
        ChaosActivation.ProbabilityActivation {

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

        // must be public, as the record is implicitly public (on interface), even though it cannot be accessed
        public ProbabilityActivation {
            if (!Double.isFinite(probability) || probability < MINIMUM || probability > 1) {
                throw new IllegalArgumentException("probability must be between 2^-53 and one");
            }
        }
    }

    /**
     * Activates the first part of each fixed-size invocation cycle after an initial skip.
     *
     * @param initialSkip number of initial matched invocations that do not activate
     * @param cycleSize size of each repeating cycle of matched invocations
     * @param burstSize number of invocations that activate at the start of each cycle
     */
    record PeriodicBurstActivation(long initialSkip, long cycleSize, long burstSize) implements ChaosActivation {

        // must be public, as the record is implicitly public (on interface), even though it cannot be accessed
        public PeriodicBurstActivation {
            if (initialSkip < 0) {
                throw new IllegalArgumentException("initialSkip must not be negative");
            }
            if (cycleSize <= 0) {
                throw new IllegalArgumentException("cycleSize must be positive");
            }
            if (burstSize <= 0 || burstSize > cycleSize) {
                throw new IllegalArgumentException("burstSize must be positive and at most cycleSize");
            }
        }
    }
}
