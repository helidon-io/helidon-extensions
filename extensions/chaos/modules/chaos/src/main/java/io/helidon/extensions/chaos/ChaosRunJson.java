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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;

/**
 * Explicit JSON representation of retained chaos runs.
 */
final class ChaosRunJson {
    private static final String RUN_PATH = "/chaos/v1/runs/";

    private ChaosRunJson() {
    }

    static JsonObject toJson(ChaosRunView run) {
        JsonObject.Builder result = JsonObject.builder()
                .set("id", run.id().toString())
                .set("name", run.name())
                .set("state", run.state().name())
                .set("plan", plan(run.plan()))
                .set("seed", run.seed())
                .set("actor", run.actor())
                .set("createdAt", run.createdAt().toString())
                .set("startedAt", run.startedAt().toString())
                .set("expiresAt", run.expiresAt().toString())
                .set("counters", counters(run))
                .set("links", JsonObject.builder().set("self", RUN_PATH + run.id()).build());
        run.terminalAt().ifPresent(value -> result.set("terminalAt", value.toString()));
        run.terminalReason().ifPresent(value -> result.set("terminalReason", value));
        return result.build();
    }

    static JsonArray toJson(List<ChaosRunView> runs) {
        return JsonArray.create(runs.stream().map(ChaosRunJson::toJson).toList());
    }

    private static JsonObject plan(ChaosRunPlan plan) {
        return JsonObject.builder()
                .set("name", plan.name())
                .set("maximumDuration", plan.maximumDuration().toString())
                .set("seed", plan.seed())
                .setValues("stages", List.of(stage(plan.stage())))
                .build();
    }

    private static JsonObject stage(ChaosRunPlan.ChaosStage stage) {
        return JsonObject.builder()
                .set("name", stage.name())
                .set("duration", stage.duration().toString())
                .setValues("disruptions", List.of(disruption(stage.disruption())))
                .build();
    }

    private static JsonObject disruption(ChaosRunPlan.ChaosDisruption disruption) {
        return JsonObject.builder()
                .set("name", disruption.name())
                .set("scope", scope(disruption.scope()))
                .set("activation", activation(disruption.activation()))
                .set("effect", effect(disruption.effect()))
                .set("budget", JsonObject.builder()
                        .set("maximumActivations", disruption.budget().maximumActivations())
                        .set("maximumConcurrent", disruption.budget().maximumConcurrent())
                        .build())
                .build();
    }

    private static JsonObject activation(ChaosActivation activation) {
        if (activation instanceof ProbabilityActivation probability) {
            return JsonObject.builder()
                    .set("type", "probability")
                    .set("probability", probability.probability())
                    .build();
        }
        return JsonObject.builder()
                .set("type", "always")
                .build();
    }

    private static JsonObject scope(ChaosHttpScope scope) {
        List<String> methods = new ArrayList<>(scope.methods());
        return JsonObject.builder()
                .set("type", "inbound-http")
                .set("methods", JsonArray.createStrings(methods))
                .set("path", JsonObject.builder()
                        .set("match", scope.pathMatch().name().toLowerCase(Locale.ROOT))
                        .set("value", scope.path())
                        .build())
                .build();
    }

    private static JsonObject effect(ChaosEffect effect) {
        ChaosSyntheticResponse synthetic = (ChaosSyntheticResponse) effect;
        JsonObject.Builder headers = JsonObject.builder();
        synthetic.headers().forEach(headers::set);
        JsonObject.Builder result = JsonObject.builder()
                .set("type", "synthetic-http-response")
                .set("status", synthetic.status())
                .set("headers", headers.build())
                .set("body", new String(synthetic.body(), StandardCharsets.UTF_8));
        synthetic.mediaType().ifPresent(mediaType -> result.set("mediaType", mediaType.text()));
        return result.build();
    }

    private static JsonObject counters(ChaosRunView run) {
        return JsonObject.builder()
                .set("matched", run.matched())
                .set("activated", run.activated())
                .set("skippedActivation", run.skippedActivation())
                .set("skippedConcurrent", run.skippedConcurrent())
                .set("skippedBudget", run.skippedBudget())
                .set("inFlight", run.inFlight())
                .set("completed", run.completed())
                .build();
    }
}
