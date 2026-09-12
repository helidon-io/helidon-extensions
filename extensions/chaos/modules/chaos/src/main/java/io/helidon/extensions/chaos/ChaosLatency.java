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
 * Latency applied before application routing.
 *
 * @param delay base delay
 * @param jitter maximum variation below or above the base delay
 */
record ChaosLatency(Duration delay, Duration jitter) implements ChaosEffect {
    ChaosLatency {
        Objects.requireNonNull(delay, "delay is null");
        Objects.requireNonNull(jitter, "jitter is null");
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be positive");
        }
        if (jitter.isNegative()) {
            throw new IllegalArgumentException("jitter must not be negative");
        }
        if (jitter.compareTo(delay) > 0) {
            throw new IllegalArgumentException("jitter must not exceed delay");
        }
    }

    ChaosLatencyAction resolve(long randomSample) {
        if (jitter.isZero()) {
            return new ChaosLatencyAction(delay);
        }
        long jitterNanos = jitter.toNanos();
        long range = Math.addExact(Math.multiplyExact(jitterNanos, 2), 1);
        long adjustment = ChaosRandom.boundedLong(randomSample, range) - jitterNanos;
        return new ChaosLatencyAction(delay.plusNanos(adjustment));
    }
}
