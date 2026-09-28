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
 * Simulated response timeout for an outbound WebClient request.
 *
 * @param duration base duration before the timeout
 * @param jitter maximum variation below or above the base duration
 */
record ChaosResponseTimeout(Duration duration, Duration jitter) implements ChaosEffect {

    ChaosResponseTimeout {
        Objects.requireNonNull(duration, "duration is null");
        Objects.requireNonNull(jitter, "jitter is null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        if (jitter.isNegative()) {
            throw new IllegalArgumentException("jitter must not be negative");
        }
        if (jitter.compareTo(duration) > 0) {
            throw new IllegalArgumentException("jitter must not exceed duration");
        }
    }

    ChaosResponseTimeoutAction resolve(long randomSample) {
        if (jitter.isZero()) {
            return new ChaosResponseTimeoutAction(duration);
        }
        long jitterNanos = jitter.toNanos();
        long range = Math.addExact(Math.multiplyExact(jitterNanos, 2), 1);
        long adjustment = ChaosRandom.boundedLong(randomSample, range) - jitterNanos;
        return new ChaosResponseTimeoutAction(duration.plusNanos(adjustment));
    }
}
