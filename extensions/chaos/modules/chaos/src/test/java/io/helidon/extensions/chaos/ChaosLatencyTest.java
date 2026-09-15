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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class ChaosLatencyTest {

    @Test
    void fixedLatencyIgnoresRandomSample() {
        ChaosLatency latency = new ChaosLatency(Duration.ofMillis(250), Duration.ZERO);

        assertThat(latency.resolve(0).delay(), is(Duration.ofMillis(250)));
        assertThat(latency.resolve(Long.MIN_VALUE).delay(), is(Duration.ofMillis(250)));
        assertThat(latency.resolve(-1).delay(), is(Duration.ofMillis(250)));
    }

    @Test
    void jitterMapsSamplesAcrossInclusiveDelayRange() {
        ChaosLatency latency = new ChaosLatency(Duration.ofMillis(250), Duration.ofMillis(50));

        assertThat(latency.resolve(0).delay(), is(Duration.ofMillis(200)));
        assertThat(latency.resolve(100_000_000).delay(), is(Duration.ofMillis(250)));
        ChaosLatencyAction upper = latency.resolve(200_000_000);
        assertThat(upper.delay(), is(Duration.ofMillis(300)));
    }

    @Test
    void jitterEqualToDelayCanResolveToZero() {
        ChaosLatency latency = new ChaosLatency(Duration.ofMillis(50), Duration.ofMillis(50));

        assertThat(latency.resolve(0).delay(), is(Duration.ZERO));
    }

    @Test
    void oneNanosecondJitterHasThreeDistinctOutcomes() {
        ChaosLatency latency = new ChaosLatency(Duration.ofNanos(2), Duration.ofNanos(1));

        assertThat(latency.resolve(0).delay(), is(Duration.ofNanos(1)));
        assertThat(latency.resolve(2).delay(), is(Duration.ofNanos(2)));
        assertThat(latency.resolve(4).delay(), is(Duration.ofNanos(3)));
    }

    @Test
    void largestSupportedJitterDoesNotEscapeItsRange() {
        long jitterNanos = (Long.MAX_VALUE - 1) / 2;
        ChaosLatency latency = new ChaosLatency(Duration.ofNanos(jitterNanos), Duration.ofNanos(jitterNanos));

        assertThat(latency.resolve(0).delay(), is(Duration.ZERO));
    }

    @Test
    void interruptedDelayRestoresInterruptStatus() {
        ChaosLatency latency = new ChaosLatency(Duration.ofSeconds(1), Duration.ZERO);
        Thread.currentThread().interrupt();
        try {
            latency.resolve(0).apply();

            assertThat(Thread.currentThread().isInterrupted(), is(true));
        } finally {
            Thread.interrupted();
        }
    }
}
