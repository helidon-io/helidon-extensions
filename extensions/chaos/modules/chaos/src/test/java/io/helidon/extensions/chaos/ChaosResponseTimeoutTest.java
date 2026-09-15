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

import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosResponseTimeoutTest {

    @Test
    void fixedDurationIgnoresRandomSample() {
        ChaosResponseTimeout timeout = new ChaosResponseTimeout(Duration.ofMillis(250), Duration.ZERO);

        assertThat(timeout.resolve(0).duration(), is(Duration.ofMillis(250)));
        assertThat(timeout.resolve(Long.MIN_VALUE).duration(), is(Duration.ofMillis(250)));
        assertThat(timeout.resolve(-1).duration(), is(Duration.ofMillis(250)));
    }

    @Test
    void jitterMapsSamplesAcrossInclusiveDurationRange() {
        ChaosResponseTimeout timeout = new ChaosResponseTimeout(Duration.ofMillis(250), Duration.ofMillis(50));

        assertThat(timeout.resolve(0).duration(), is(Duration.ofMillis(200)));
        assertThat(timeout.resolve(100_000_000).duration(), is(Duration.ofMillis(250)));
        assertThat(timeout.resolve(200_000_000).duration(), is(Duration.ofMillis(300)));
    }

    @Test
    void timeoutActionThrowsSocketTimeoutException() {
        ChaosResponseTimeoutAction action = new ChaosResponseTimeoutAction(Duration.ZERO);

        UncheckedIOException exception = assertThrows(UncheckedIOException.class, action::apply);

        assertThat(exception.getCause(), instanceOf(SocketTimeoutException.class));
        assertThat(exception.getCause().getMessage(), is("Response timed out due to chaos disruption"));
    }

    @Test
    void interruptedTimeoutRestoresInterruptStatusAndThrows() {
        ChaosResponseTimeoutAction action = new ChaosResponseTimeoutAction(Duration.ofSeconds(1));
        Thread.currentThread().interrupt();
        try {
            UncheckedIOException exception = assertThrows(UncheckedIOException.class, action::apply);

            assertThat(exception.getCause(), instanceOf(SocketTimeoutException.class));
            assertThat(Thread.currentThread().isInterrupted(), is(true));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void protectsInvariants() {
        assertThrows(NullPointerException.class, () -> new ChaosResponseTimeout(null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new ChaosResponseTimeout(Duration.ofMillis(1), null));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosResponseTimeout(Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosResponseTimeout(Duration.ofMillis(1), Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosResponseTimeout(Duration.ofMillis(1), Duration.ofMillis(2)));
        assertThrows(NullPointerException.class, () -> new ChaosResponseTimeoutAction(null));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosResponseTimeoutAction(Duration.ofMillis(-1)));
    }
}
