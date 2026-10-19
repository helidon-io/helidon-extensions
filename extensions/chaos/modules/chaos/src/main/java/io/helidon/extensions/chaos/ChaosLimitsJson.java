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

import io.helidon.json.JsonObject;

/**
 * JSON representation of the server-enforced limits.
 */
final class ChaosLimitsJson {
    private ChaosLimitsJson() {
    }

    static JsonObject toJson(ChaosLimitsConfig limits, int activeRuns) {
        JsonObject values = JsonObject.builder()
                .set("maximumActiveRuns", limits.maximumActiveRuns())
                .set("maximumRunDuration", limits.maximumRunDuration().toString())
                .set("maximumStagesPerRun", limits.maximumStagesPerRun())
                .set("maximumActivationsPerDisruption", limits.maximumActivationsPerDisruption())
                .set("maximumConcurrentActivationsPerDisruption", limits.maximumConcurrentActivationsPerDisruption())
                .set("maximumLatency", limits.maximumLatency().toString())
                .set("maximumResponseTimeout", limits.maximumResponseTimeout().toString())
                .set("maximumSyntheticBodyBytes", limits.maximumSyntheticBodyBytes())
                .set("maximumControlRequestBytes", limits.maximumControlRequestBytes())
                .set("maximumConcurrentControlRequests", limits.maximumConcurrentControlRequests())
                .set("maximumRetainedRuns", limits.maximumRetainedRuns())
                .set("terminalRunRetention", limits.terminalRunRetention().toString())
                .build();
        return JsonObject.builder()
                .set("limits", values)
                .set("activeRuns", activeRuns)
                .build();
    }
}
