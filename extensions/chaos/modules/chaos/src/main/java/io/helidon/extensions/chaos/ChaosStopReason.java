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
 * Closed list of reasons a run stops, exposed as the stable {@code terminalReason} string.
 */
enum ChaosStopReason {
    /** All stages ran. */
    STAGE_DURATION_COMPLETED("stage-duration-completed", ChaosRunState.COMPLETED),
    /** The {@code maximumDuration} guard fired. */
    MAXIMUM_DURATION_EXPIRED("maximum-duration-expired", ChaosRunState.EXPIRED),
    /** An operator stopped the run. */
    OPERATOR_STOPPED("operator-stopped", ChaosRunState.STOPPED),
    /** An operator halted all runs. */
    HALTED("halted", ChaosRunState.STOPPED),
    /** The local breaker tripped. */
    BREAKER_TRIPPED("breaker-tripped", ChaosRunState.STOPPED),
    /** Breaker evaluation failed; the breaker failed closed. */
    BREAKER_FAILED("breaker-failed", ChaosRunState.STOPPED),
    /** The server shut down. */
    ENGINE_CLOSED("engine-closed", ChaosRunState.STOPPED);

    private final String value;
    private final ChaosRunState state;

    ChaosStopReason(String value, ChaosRunState state) {
        this.value = value;
        this.state = state;
    }

    String value() {
        return value;
    }

    ChaosRunState state() {
        return state;
    }
}
