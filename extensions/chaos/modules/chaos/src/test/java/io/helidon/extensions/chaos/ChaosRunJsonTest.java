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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.chaos.ChaosActivation.PeriodicBurstActivation;
import io.helidon.extensions.chaos.ChaosActivation.ProbabilityActivation;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import org.junit.jupiter.api.Test;

import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.PREFIX;
import static io.helidon.extensions.chaos.ChaosRunState.RUNNING;
import static io.helidon.extensions.chaos.ChaosRunState.STOPPED;
import static io.helidon.extensions.chaos.ChaosRunState.STOPPING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosRunJsonTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final Instant CREATED = Instant.parse("2026-08-24T12:00:00Z");

    @Test
    void writesNormalizedRunningRepresentation() {
        JsonObject json = ChaosRunJson.toJson(view(RUNNING, Optional.empty(), Optional.empty()));

        assertThat(json.stringValue("id").orElseThrow(), is(ID.toString()));
        assertThat(json.stringValue("name").orElseThrow(), is("orders-unavailable"));
        assertThat(json.stringValue("state").orElseThrow(), is("RUNNING"));
        assertThat(json.longValue("seed").orElseThrow(), is(148_894L));
        assertThat(json.stringValue("actor").orElseThrow(), is("alice"));
        assertThat(json.stringValue("createdAt").orElseThrow(), is("2026-08-24T12:00:00Z"));
        assertThat(json.stringValue("startedAt").orElseThrow(), is("2026-08-24T12:00:00Z"));
        assertThat(json.stringValue("expiresAt").orElseThrow(), is("2026-08-24T12:00:30Z"));
        assertThat(json.containsKey("terminalAt"), is(false));
        assertThat(json.containsKey("terminalReason"), is(false));
        JsonObject currentStage = json.objectValue("currentStage").orElseThrow();
        assertThat(currentStage.intValue("index").orElseThrow(), is(0));
        assertThat(currentStage.stringValue("name").orElseThrow(), is("reject-orders"));
        assertThat(currentStage.stringValue("startedAt").orElseThrow(), is("2026-08-24T12:00:00Z"));
        assertThat(currentStage.stringValue("endsAt").orElseThrow(), is("2026-08-24T12:00:10Z"));

        JsonObject counters = json.objectValue("counters").orElseThrow();
        assertThat(counters.longValue("matched").orElseThrow(), is(7L));
        assertThat(counters.longValue("activated").orElseThrow(), is(5L));
        assertThat(counters.longValue("skippedActivation").orElseThrow(), is(2L));
        assertThat(counters.longValue("skippedConcurrent").orElseThrow(), is(1L));
        assertThat(counters.longValue("skippedBudget").orElseThrow(), is(1L));
        assertThat(counters.longValue("inFlight").orElseThrow(), is(2L));
        assertThat(counters.longValue("completed").orElseThrow(), is(3L));
        assertThat(json.objectValue("links").orElseThrow().stringValue("self").orElseThrow(),
                   is("/chaos/v1/runs/" + ID));

        assertNormalizedPlan(json.objectValue("plan").orElseThrow());
    }

    @Test
    void writesNormalizedProbabilityActivation() {
        ChaosRunPlan probabilityPlan = plan(new ProbabilityActivation(0.25));

        JsonObject json = ChaosRunJson.toJson(view(RUNNING,
                                                   Optional.empty(),
                                                   Optional.empty(),
                                                   probabilityPlan));

        JsonObject activation = json.objectValue("plan").orElseThrow()
                .arrayValue("stages").orElseThrow().get(0).orElseThrow().asObject()
                .arrayValue("disruptions").orElseThrow().get(0).orElseThrow().asObject()
                .objectValue("activation").orElseThrow();
        assertThat(activation.stringValue("type").orElseThrow(), is("probability"));
        assertThat(activation.doubleValue("probability").orElseThrow(), is(0.25));
    }

    @Test
    void writesNormalizedPeriodicBurstActivation() {
        ChaosRunPlan periodicBurstPlan = plan(new PeriodicBurstActivation(0, 10, 3));

        JsonObject json = ChaosRunJson.toJson(view(RUNNING,
                                                   Optional.empty(),
                                                   Optional.empty(),
                                                   periodicBurstPlan));

        JsonObject activation = json.objectValue("plan").orElseThrow()
                .arrayValue("stages").orElseThrow().get(0).orElseThrow().asObject()
                .arrayValue("disruptions").orElseThrow().get(0).orElseThrow().asObject()
                .objectValue("activation").orElseThrow();
        assertThat(activation.stringValue("type").orElseThrow(), is("periodic-burst"));
        assertThat(activation.longValue("initialSkip").orElseThrow(), is(0L));
        assertThat(activation.longValue("cycleSize").orElseThrow(), is(10L));
        assertThat(activation.longValue("burstSize").orElseThrow(), is(3L));
    }

    @Test
    void writesNormalizedLatencyEffect() {
        ChaosRunPlan latencyPlan = plan(new ChaosLatency(Duration.ofMillis(250), Duration.ofMillis(50)));

        JsonObject json = ChaosRunJson.toJson(view(RUNNING,
                                                   Optional.empty(),
                                                   Optional.empty(),
                                                   latencyPlan));

        JsonObject effect = json.objectValue("plan").orElseThrow()
                .arrayValue("stages").orElseThrow().get(0).orElseThrow().asObject()
                .arrayValue("disruptions").orElseThrow().get(0).orElseThrow().asObject()
                .objectValue("effect").orElseThrow();
        assertThat(effect.stringValue("type").orElseThrow(), is("latency"));
        assertThat(effect.stringValue("delay").orElseThrow(), is("PT0.25S"));
        assertThat(effect.stringValue("jitter").orElseThrow(), is("PT0.05S"));
    }

    @Test
    void writesNormalizedOutboundHttpScope() {
        ChaosOutboundHttpScope scope = new ChaosOutboundHttpScope(Set.of("get"),
                                                                   "HTTPS",
                                                                   "Inventory.Example.COM",
                                                                   443,
                                                                   PREFIX,
                                                                   "/v1/items");
        ChaosRunPlan plan = plan(scope, new ChaosLatency(Duration.ofMillis(250), Duration.ZERO));

        JsonObject rendered = ChaosRunJson.toJson(view(RUNNING, Optional.empty(), Optional.empty(), plan));

        JsonObject json = rendered.objectValue("plan").orElseThrow()
                .arrayValue("stages").orElseThrow().get(0).orElseThrow().asObject()
                .arrayValue("disruptions").orElseThrow().get(0).orElseThrow().asObject()
                .objectValue("scope").orElseThrow();
        assertThat(json.stringValue("type").orElseThrow(), is("outbound-http"));
        assertThat(json.arrayValue("methods").orElseThrow().get(0).orElseThrow().asString().value(), is("GET"));
        assertThat(json.stringValue("scheme").orElseThrow(), is("https"));
        assertThat(json.stringValue("host").orElseThrow(), is("inventory.example.com"));
        assertThat(json.intValue("port").orElseThrow(), is(443));
        assertThat(json.objectValue("path").orElseThrow().stringValue("match").orElseThrow(), is("prefix"));
        assertThat(json.objectValue("path").orElseThrow().stringValue("value").orElseThrow(), is("/v1/items"));
    }

    @Test
    void writesTerminalFieldsAndRunList() {
        JsonObject terminal = ChaosRunJson.toJson(view(STOPPED,
                                                       Optional.of(CREATED.plusSeconds(4)),
                                                       Optional.of("operator-stopped")));

        assertThat(terminal.stringValue("terminalAt").orElseThrow(), is("2026-08-24T12:00:04Z"));
        assertThat(terminal.stringValue("terminalReason").orElseThrow(), is("operator-stopped"));
        assertThat(terminal.containsKey("currentStage"), is(false));
        JsonArray list = ChaosRunJson.toJson(List.of(view(STOPPED,
                                                         Optional.of(CREATED.plusSeconds(4)),
                                                         Optional.of("operator-stopped"))));
        assertThat(list.size(), is(1));
        assertThat(list.get(0).orElseThrow().asObject().toString(), is(terminal.toString()));
    }

    @Test
    void omitsCurrentStageWhileStopping() {
        JsonObject stopping = ChaosRunJson.toJson(view(STOPPING, Optional.empty(), Optional.empty()));

        assertThat(stopping.containsKey("currentStage"), is(false));
    }

    @Test
    void writesAllStagesIncludingPassiveRecovery() {
        JsonObject json = ChaosRunJson.toJson(view(RUNNING,
                                                   Optional.empty(),
                                                   Optional.empty(),
                                                   multiStagePlan(),
                                                   Optional.of(new ChaosRunView.CurrentStage(1,
                                                                                             "recovery",
                                                                                             CREATED.plusSeconds(10),
                                                                                             CREATED.plusSeconds(15)))));

        JsonArray stages = json.objectValue("plan").orElseThrow().arrayValue("stages").orElseThrow();
        assertThat(stages.size(), is(2));
        JsonObject recovery = stages.get(1).orElseThrow().asObject();
        assertThat(recovery.stringValue("name").orElseThrow(), is("recovery"));
        assertThat(recovery.arrayValue("disruptions").orElseThrow().size(), is(0));
        assertThat(json.objectValue("currentStage").orElseThrow().intValue("index").orElseThrow(), is(1));
    }

    @Test
    void writesExactValidationProblem() {
        ChaosRequestException error = ChaosRequestException.invalidPlan("/stages/0/duration",
                                                                         "duration-limit",
                                                                         "Stage duration exceeds maximumDuration.");

        ChaosProblemJson.Problem problem = ChaosProblemJson.from(error, "/chaos/v1/runs");

        assertThat(problem.status(), is(422));
        assertThat(problem.body().toString(), is(json("""
                {
                  "type": "/chaos/v1/problems/invalid-plan",
                  "title": "Invalid chaos run plan",
                  "status": 422,
                  "detail": "The run plan violates one or more constraints.",
                  "instance": "/chaos/v1/runs",
                  "violations": [
                    {
                      "path": "/stages/0/duration",
                      "code": "duration-limit",
                      "message": "Stage duration exceeds maximumDuration."
                    }
                  ]
                }
                """).toString()));
    }

    @Test
    void mapsEngineAndBoundaryProblemsWithoutLeakingUnexpectedDetails() {
        TestChaosScheduler scheduler = new TestChaosScheduler();
        AtomicInteger ids = new AtomicInteger();
        ChaosRunEngine engine = ChaosRunEngine.create(ChaosLimitsConfig.builder().build(),
                                                      scheduler.clock(),
                                                      scheduler,
                                                      () -> new UUID(0, ids.incrementAndGet()));
        engine.create(plan(), "alice");
        ChaosRunEngine.ConflictException conflict =
                assertThrows(ChaosRunEngine.ConflictException.class, () -> engine.create(plan(), "bob"));
        ChaosRunEngine.NotFoundException notFound =
                assertThrows(ChaosRunEngine.NotFoundException.class, () -> engine.stop(UUID.randomUUID()));

        assertProblem(ChaosProblemJson.from(conflict, "/chaos/v1/runs"),
                      409,
                      "run-conflict");
        assertProblem(ChaosProblemJson.from(notFound, "/chaos/v1/runs/" + ID),
                      404,
                      "run-not-found");
        assertProblem(ChaosProblemJson.unsupportedMediaType("/chaos/v1/runs"),
                      415,
                      "unsupported-media-type");
        assertProblem(ChaosProblemJson.controlCapacity("/chaos/v1/runs"),
                      429,
                      "control-capacity");

        ChaosProblemJson.Problem unexpected =
                ChaosProblemJson.from(new IllegalStateException("/secret/path classpath token"), "/chaos/v1/runs");
        assertProblem(unexpected, 500, "internal-error");
        assertThat(unexpected.body().toString().contains("secret"), is(false));
        assertThat(unexpected.body().toString().contains("classpath"), is(false));
        assertThat(unexpected.body().toString().contains("IllegalStateException"), is(false));
    }

    private static void assertNormalizedPlan(JsonObject plan) {
        assertThat(plan.stringValue("name").orElseThrow(), is("orders-unavailable"));
        assertThat(plan.stringValue("maximumDuration").orElseThrow(), is("PT30S"));
        assertThat(plan.longValue("seed").orElseThrow(), is(148_894L));
        JsonObject stage = plan.arrayValue("stages").orElseThrow().get(0).orElseThrow().asObject();
        assertThat(stage.stringValue("name").orElseThrow(), is("reject-orders"));
        assertThat(stage.stringValue("duration").orElseThrow(), is("PT10S"));
        JsonObject disruption = stage.arrayValue("disruptions").orElseThrow().get(0).orElseThrow().asObject();
        assertThat(disruption.stringValue("name").orElseThrow(), is("orders-503"));
        JsonObject scope = disruption.objectValue("scope").orElseThrow();
        assertThat(scope.stringValue("type").orElseThrow(), is("inbound-http"));
        assertThat(scope.arrayValue("methods").orElseThrow().get(0).orElseThrow().asString().value(), is("GET"));
        assertThat(scope.objectValue("path").orElseThrow().stringValue("match").orElseThrow(), is("prefix"));
        assertThat(disruption.objectValue("activation").orElseThrow().stringValue("type").orElseThrow(), is("always"));
        JsonObject effect = disruption.objectValue("effect").orElseThrow();
        assertThat(effect.stringValue("type").orElseThrow(), is("synthetic-http-response"));
        assertThat(effect.intValue("status").orElseThrow(), is(503));
        assertThat(effect.objectValue("headers").orElseThrow().stringValue("Retry-After").orElseThrow(), is("1"));
        assertThat(effect.stringValue("mediaType").orElseThrow(), is("text/plain"));
        assertThat(effect.stringValue("body").orElseThrow(), is("failure"));
        JsonObject budget = disruption.objectValue("budget").orElseThrow();
        assertThat(budget.longValue("maximumActivations").orElseThrow(), is(20L));
        assertThat(budget.intValue("maximumConcurrent").orElseThrow(), is(2));
    }

    private static void assertProblem(ChaosProblemJson.Problem problem,
                                      int status,
                                      String type) {
        assertThat(problem.status(), is(status));
        assertThat(problem.body().intValue("status").orElseThrow(), is(status));
        assertThat(problem.body().stringValue("type").orElseThrow(), is("/chaos/v1/problems/" + type));
    }

    private static ChaosRunView view(ChaosRunState state,
                                     Optional<Instant> terminalAt,
                                     Optional<String> terminalReason) {
        return view(state, terminalAt, terminalReason, plan());
    }

    private static ChaosRunView view(ChaosRunState state,
                                     Optional<Instant> terminalAt,
                                     Optional<String> terminalReason,
                                     ChaosRunPlan plan) {
        Optional<ChaosRunView.CurrentStage> currentStage = state == RUNNING
                ? Optional.of(new ChaosRunView.CurrentStage(0,
                                                            plan.stages().getFirst().name(),
                                                            CREATED,
                                                            CREATED.plus(plan.stages().getFirst().duration())))
                : Optional.empty();
        return view(state, terminalAt, terminalReason, plan, currentStage);
    }

    private static ChaosRunView view(ChaosRunState state,
                                     Optional<Instant> terminalAt,
                                     Optional<String> terminalReason,
                                     ChaosRunPlan plan,
                                     Optional<ChaosRunView.CurrentStage> currentStage) {
        return new ChaosRunView(ID,
                                plan.name(),
                                state,
                                plan,
                                plan.seed(),
                                "alice",
                                CREATED,
                                CREATED,
                                CREATED.plusSeconds(30),
                                currentStage,
                                terminalAt,
                                terminalReason,
                                7,
                                5,
                                2,
                                1,
                                1,
                                2,
                                3);
    }

    private static ChaosRunPlan plan() {
        return plan(ChaosActivation.always());
    }

    private static ChaosRunPlan plan(ChaosActivation activation) {
        ChaosHttpScope scope = new ChaosHttpScope(Set.of("GET"), PREFIX, "/orders");
        ChaosSyntheticResponse response = new ChaosSyntheticResponse(503,
                                                                      Map.of("Retry-After", "1"),
                                                                      Optional.of(MediaTypes.TEXT_PLAIN),
                                                                      "failure".getBytes(StandardCharsets.UTF_8));
        return plan(activation, response);
    }

    private static ChaosRunPlan plan(ChaosEffect effect) {
        return plan(ChaosActivation.always(), effect);
    }

    private static ChaosRunPlan plan(ChaosActivation activation, ChaosEffect effect) {
        ChaosHttpScope scope = new ChaosHttpScope(Set.of("GET"), PREFIX, "/orders");
        return plan(scope, activation, effect);
    }

    private static ChaosRunPlan plan(ChaosScope scope, ChaosEffect effect) {
        return plan(scope, ChaosActivation.always(), effect);
    }

    private static ChaosRunPlan plan(ChaosScope scope, ChaosActivation activation, ChaosEffect effect) {
        ChaosBudget budget = new ChaosBudget(20, 2);
        ChaosRunPlan.ChaosDisruption disruption =
                new ChaosRunPlan.ChaosDisruption("orders-503", scope, activation, effect, budget);
        ChaosRunPlan.ChaosStage stage =
                new ChaosRunPlan.ChaosStage("reject-orders", Duration.ofSeconds(10), Optional.of(disruption));
        return new ChaosRunPlan("orders-unavailable", Duration.ofSeconds(30), 148_894, List.of(stage));
    }

    private static ChaosRunPlan multiStagePlan() {
        ChaosRunPlan.ChaosStage disruptive = plan().stages().getFirst();
        ChaosRunPlan.ChaosStage recovery =
                new ChaosRunPlan.ChaosStage("recovery", Duration.ofSeconds(5), Optional.empty());
        return new ChaosRunPlan("orders-unavailable",
                                Duration.ofSeconds(30),
                                148_894,
                                List.of(disruptive, recovery));
    }

    private static JsonObject json(String text) {
        return JsonParser.create(text).readJsonObject();
    }
}
