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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable point-in-time representation of a chaos run.
 *
 * @param id run identifier
 * @param name diagnostic run name
 * @param state lifecycle state
 * @param plan normalized run plan
 * @param seed deterministic run seed
 * @param actor authenticated actor that created the run
 * @param createdAt creation time
 * @param startedAt activation time
 * @param expiresAt maximum-duration guard time
 * @param terminalAt terminal transition time, if terminal
 * @param terminalReason stable machine-readable terminal reason
 * @param matched matched inbound requests
 * @param activated activated disruptions
 * @param skippedActivation matches rejected by their activation policy
 * @param skippedConcurrent matches skipped by the concurrency ceiling
 * @param skippedBudget matches skipped by the cumulative ceiling
 * @param inFlight activations not yet released
 * @param completed released activations
 */
record ChaosRunView(UUID id,
                    String name,
                    ChaosRunState state,
                    ChaosRunPlan plan,
                    long seed,
                    String actor,
                    Instant createdAt,
                    Instant startedAt,
                    Instant expiresAt,
                    Optional<Instant> terminalAt,
                    Optional<String> terminalReason,
                    long matched,
                    long activated,
                    long skippedActivation,
                    long skippedConcurrent,
                    long skippedBudget,
                    long inFlight,
                    long completed) {
}
