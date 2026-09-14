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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosWeightedChoiceTest {

    @Test
    void selectsAcrossExactWeightBoundary() {
        ChaosSyntheticResponse synthetic = synthetic(503);
        ChaosLatency latency = new ChaosLatency(Duration.ofMillis(10), Duration.ZERO);
        ChaosWeightedChoice choice = new ChaosWeightedChoice(List.of(
                new ChaosWeightedChoice.Outcome(3, synthetic),
                new ChaosWeightedChoice.Outcome(1, latency)));

        assertThat(choice.resolve(4, 0), sameInstance(synthetic));
        assertThat(((ChaosLatencyAction) choice.resolve(6, 0)).delay(), is(Duration.ofMillis(10)));
    }

    @Test
    void resolvesConnectFailureLeaf() {
        ChaosConnectFailure failure = ChaosConnectFailure.instance();
        ChaosWeightedChoice choice = new ChaosWeightedChoice(List.of(
                new ChaosWeightedChoice.Outcome(1, failure)));

        assertThat(choice.resolve(0, 0), sameInstance(failure));
    }

    @Test
    void resolvesDnsFailureLeaf() {
        ChaosDnsFailure failure = ChaosDnsFailure.instance();
        ChaosWeightedChoice choice = new ChaosWeightedChoice(List.of(
                new ChaosWeightedChoice.Outcome(1, failure)));

        assertThat(choice.resolve(0, 0), sameInstance(failure));
    }

    @Test
    void resolvesResponseTimeoutLeaf() {
        ChaosResponseTimeout timeout = new ChaosResponseTimeout(Duration.ofMillis(250), Duration.ofMillis(50));
        ChaosWeightedChoice choice = new ChaosWeightedChoice(List.of(
                new ChaosWeightedChoice.Outcome(1, timeout)));

        ChaosResponseTimeoutAction action = (ChaosResponseTimeoutAction) choice.resolve(0, 100_000_000);

        assertThat(action.duration(), is(Duration.ofMillis(250)));
    }

    @Test
    void protectsOutcomeInvariantsAndOrder() {
        assertThrows(NullPointerException.class, () -> new ChaosWeightedChoice(null));
        assertThrows(IllegalArgumentException.class, () -> new ChaosWeightedChoice(List.of()));
        var nullOutcome = new ArrayList<ChaosWeightedChoice.Outcome>();
        nullOutcome.add(null);
        assertThrows(NullPointerException.class, () -> new ChaosWeightedChoice(nullOutcome));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosWeightedChoice.Outcome(0, synthetic(503)));
        assertThrows(NullPointerException.class,
                     () -> new ChaosWeightedChoice.Outcome(1, null));

        ChaosWeightedChoice nested = new ChaosWeightedChoice(List.of(
                new ChaosWeightedChoice.Outcome(1, synthetic(503))));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosWeightedChoice(List.of(new ChaosWeightedChoice.Outcome(1, nested))));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosWeightedChoice(List.of(
                             new ChaosWeightedChoice.Outcome(Long.MAX_VALUE, synthetic(503)),
                             new ChaosWeightedChoice.Outcome(1, synthetic(504)))));

        var source = new ArrayList<>(List.of(
                new ChaosWeightedChoice.Outcome(1, synthetic(503)),
                new ChaosWeightedChoice.Outcome(1, synthetic(504))));
        ChaosWeightedChoice choice = new ChaosWeightedChoice(source);
        source.clear();

        assertThat(choice.outcomes(), hasSize(2));
        assertThat(((ChaosSyntheticResponse) choice.outcomes().get(0).effect()).status(), is(503));
        assertThrows(UnsupportedOperationException.class, () -> choice.outcomes().clear());
    }

    private static ChaosSyntheticResponse synthetic(int status) {
        return new ChaosSyntheticResponse(status, Map.of(), Optional.empty(), new byte[0]);
    }
}
