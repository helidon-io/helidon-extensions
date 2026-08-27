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

import io.helidon.builder.api.Prototype;

/**
 * Validation for {@link ChaosLimitsConfig}.
 */
final class ChaosLimitsConfigSupport {

    private ChaosLimitsConfigSupport() {
    }

    static final class BuilderDecorator implements Prototype.BuilderDecorator<ChaosLimitsConfig.BuilderBase<?, ?>> {

        @Override
        public void decorate(ChaosLimitsConfig.BuilderBase<?, ?> builder) {
            requirePositive(builder.maximumActiveRuns(), "maximum-active-runs");
            requirePositive(builder.maximumRunDuration(), "maximum-run-duration");
            requirePositive(builder.maximumActivationsPerDisruption(), "maximum-activations-per-disruption");
            requirePositive(builder.maximumConcurrentActivationsPerDisruption(),
                            "maximum-concurrent-activations-per-disruption");
            requirePositive(builder.maximumSyntheticBodyBytes(), "maximum-synthetic-body-bytes");
            requirePositive(builder.maximumControlRequestBytes(), "maximum-control-request-bytes");
            requirePositive(builder.maximumConcurrentControlRequests(), "maximum-concurrent-control-requests");
            requirePositive(builder.maximumRetainedRuns(), "maximum-retained-runs");
            requirePositive(builder.terminalRunRetention(), "terminal-run-retention");
            if (builder.maximumRetainedRuns() < builder.maximumActiveRuns()) {
                throw new IllegalArgumentException("maximum-retained-runs must be at least maximum-active-runs");
            }
        }

        private static void requirePositive(long value, String key) {
            if (value <= 0) {
                throw new IllegalArgumentException(key + " must be positive");
            }
        }

        private static void requirePositive(Duration value, String key) {
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(key + " must be positive");
            }
        }
    }
}
