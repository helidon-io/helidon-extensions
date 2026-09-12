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

import io.helidon.extensions.chaos.ChaosActivation.PeriodicBurstActivation;
import io.helidon.extensions.chaos.ChaosActivation.ProbabilityActivation;

/**
 * Stable activation decisions that do not depend on JDK random-generator implementations.
 */
final class ChaosActivationDecider {
    private ChaosActivationDecider() {
    }

    static long streamSeed(long runSeed, String... identities) {
        return ChaosRandom.streamSeed(runSeed, identities);
    }

    static boolean activates(ChaosActivation activation, long streamSeed, long matchedInvocation) {
        if (activation instanceof ProbabilityActivation probability) {
            double sample = ChaosRandom.unitInterval(ChaosRandom.sample(streamSeed, matchedInvocation));
            return sample < probability.probability();
        }
        if (activation instanceof PeriodicBurstActivation periodicBurst) {
            long cycleInvocation = matchedInvocation - periodicBurst.initialSkip();
            if (cycleInvocation <= 0) {
                return false;
            }
            long cyclePosition = (cycleInvocation - 1) % periodicBurst.cycleSize();
            return cyclePosition < periodicBurst.burstSize();
        }
        return true;
    }
}
