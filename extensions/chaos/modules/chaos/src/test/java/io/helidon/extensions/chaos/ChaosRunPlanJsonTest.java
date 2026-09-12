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
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

import io.helidon.extensions.chaos.ChaosActivation.PeriodicBurstActivation;
import io.helidon.extensions.chaos.ChaosActivation.ProbabilityActivation;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosRunPlanJsonTest {

    private static final ChaosLimitsConfig LIMITS = ChaosLimitsConfig.create();

    @Test
    void parsesAndNormalizesOneStagePlan() {
        ChaosRunPlan plan = ChaosRunPlanJson.parse(json(validJson()), LIMITS);

        assertThat(plan.name(), is("orders-unavailable"));
        assertThat(plan.maximumDuration(), is(Duration.ofSeconds(30)));
        assertThat(plan.seed(), is(148_894L));
        assertThat(plan.stage().duration(), is(Duration.ofSeconds(10)));
        assertThat(plan.stage().disruption().scope().methods(), contains("GET"));
        assertThat(plan.stage().disruption().activation(), is(ChaosActivation.always()));
        ChaosSyntheticResponse effect = (ChaosSyntheticResponse) plan.stage().disruption().effect();
        assertThat(effect.status(), is(503));
        assertThat(effect.headers(), hasEntry("Retry-After", "1"));
        assertThat(new String(effect.body(), StandardCharsets.UTF_8),
                   is("Synthetic service failure"));
    }

    @Test
    void parsesFixedLatencyWithDefaultJitter() {
        ChaosRunPlan plan = ChaosRunPlanJson.parse(json(withEffect("""
                {
                  "type": "latency",
                  "delay": "PT0.25S"
                }
                """)), LIMITS);

        ChaosLatency effect = (ChaosLatency) plan.stage().disruption().effect();
        assertThat(effect.delay(), is(Duration.ofMillis(250)));
        assertThat(effect.jitter(), is(Duration.ZERO));
    }

    @Test
    void parsesLatencyJitter() {
        ChaosRunPlan plan = ChaosRunPlanJson.parse(json(withEffect("""
                {
                  "type": "latency",
                  "delay": "PT0.25S",
                  "jitter": "PT0.05S"
                }
                """)), LIMITS);

        ChaosLatency effect = (ChaosLatency) plan.stage().disruption().effect();
        assertThat(effect.delay(), is(Duration.ofMillis(250)));
        assertThat(effect.jitter(), is(Duration.ofMillis(50)));
    }

    @Test
    void parsesWeightedChoiceEffect() {
        assertDoesNotThrow(() -> ChaosRunPlanJson.parse(json(withEffect("""
                {
                  "type": "weighted-choice",
                  "outcomes": [
                    {
                      "weight": 3,
                      "effect": {"type": "synthetic-http-response", "status": 503}
                    },
                    {
                      "weight": 1,
                      "effect": {"type": "latency", "delay": "PT0.25S"}
                    }
                  ]
                }
                """)), LIMITS));
    }

    @Test
    void rejectsInvalidLatency() {
        assertBadRequest(withEffect("""
                {"type": "latency"}
                """), "/stages/0/disruptions/0/effect/delay");
        assertInvalidPlan(withEffect("""
                {"type": "latency", "delay": "not-a-duration"}
                """), "/stages/0/disruptions/0/effect/delay");
        assertInvalidPlan(withEffect("""
                {"type": "latency", "delay": "PT0S"}
                """), "/stages/0/disruptions/0/effect/delay");
        assertInvalidPlan(withEffect("""
                {"type": "latency", "delay": "-PT0.001S"}
                """), "/stages/0/disruptions/0/effect/delay");
        assertInvalidPlan(withEffect("""
                {"type": "latency", "delay": "PT0.25S", "jitter": "-PT0.001S"}
                """), "/stages/0/disruptions/0/effect/jitter");
        assertInvalidPlan(withEffect("""
                {"type": "latency", "delay": "PT0.25S", "jitter": "PT0.251S"}
                """), "/stages/0/disruptions/0/effect/jitter");

        ChaosLimitsConfig limits = ChaosLimitsConfig.builder().maximumLatency(Duration.ofMillis(275)).build();
        assertInvalidPlan(withEffect("""
                {"type": "latency", "delay": "PT0.276S"}
                """), limits, "/stages/0/disruptions/0/effect/delay");
        assertInvalidPlan(withEffect("""
                {"type": "latency", "delay": "PT0.25S", "jitter": "PT0.05S"}
                """), limits, "/stages/0/disruptions/0/effect/jitter");
    }

    @Test
    void rejectsMalformedWeightedChoiceEffects() {
        assertBadRequest(withEffect("""
                {"type": "weighted-choice"}
                """), "/stages/0/disruptions/0/effect/outcomes");
        assertBadRequest(withEffect("""
                {
                  "type": "weighted-choice",
                  "outcomes": [
                    {"effect": {"type": "synthetic-http-response", "status": 503}}
                  ]
                }
                """), "/stages/0/disruptions/0/effect/outcomes/0/weight");
        assertBadRequest(withEffect("""
                {
                  "type": "weighted-choice",
                  "outcomes": [
                    {"weight": 1.5, "effect": {"type": "synthetic-http-response", "status": 503}}
                  ]
                }
                """), "/stages/0/disruptions/0/effect/outcomes/0/weight");
        assertBadRequest(withEffect("""
                {
                  "type": "weighted-choice",
                  "outcomes": [{"weight": 1}]
                }
                """), "/stages/0/disruptions/0/effect/outcomes/0/effect");
        assertBadRequest(withEffect("""
                {
                  "type": "weighted-choice",
                  "outcomes": [
                    {
                      "weight": 1,
                      "unexpected": true,
                      "effect": {"type": "synthetic-http-response", "status": 503}
                    }
                  ]
                }
                """), "/stages/0/disruptions/0/effect/outcomes/0/unexpected");
    }

    @Test
    void rejectsInvalidWeightedChoiceEffects() {
        assertInvalidPlan(withEffect("""
                {"type": "weighted-choice", "outcomes": []}
                """), "/stages/0/disruptions/0/effect/outcomes");
        assertInvalidPlan(withEffect("""
                {
                  "type": "weighted-choice",
                  "outcomes": [
                    {"weight": 0, "effect": {"type": "synthetic-http-response", "status": 503}}
                  ]
                }
                """), "/stages/0/disruptions/0/effect/outcomes/0/weight");
        assertInvalidPlan(withEffect("""
                {
                  "type": "weighted-choice",
                  "outcomes": [
                    {"weight": -1, "effect": {"type": "synthetic-http-response", "status": 503}}
                  ]
                }
                """), "/stages/0/disruptions/0/effect/outcomes/0/weight");
        assertInvalidPlan(withEffect("""
                {
                  "type": "weighted-choice",
                  "outcomes": [
                    {
                      "weight": 1,
                      "effect": {"type": "weighted-choice", "outcomes": [
                        {"weight": 1, "effect": {"type": "synthetic-http-response", "status": 503}}
                      ]}
                    }
                  ]
                }
                """), "/stages/0/disruptions/0/effect/outcomes/0/effect/type");
        assertInvalidPlan(withEffect("""
                {
                  "type": "weighted-choice",
                  "outcomes": [
                    {
                      "weight": 9223372036854775807,
                      "effect": {"type": "synthetic-http-response", "status": 503}
                    },
                    {"weight": 1, "effect": {"type": "latency", "delay": "PT0.001S"}}
                  ]
                }
                """), "/stages/0/disruptions/0/effect/outcomes");
    }

    @Test
    void keepsEffectPropertiesMutuallyExclusive() {
        assertBadRequest(withEffect("""
                {"type": "latency", "delay": "PT0.25S", "status": 503}
                """), "/stages/0/disruptions/0/effect/status");
        assertBadRequest(withEffect("""
                {"type": "synthetic-http-response", "status": 503, "delay": "PT0.25S"}
                """), "/stages/0/disruptions/0/effect/delay");
        assertBadRequest(withEffect("""
                {
                  "type": "weighted-choice",
                  "status": 503,
                  "outcomes": [
                    {"weight": 1, "effect": {"type": "synthetic-http-response", "status": 503}}
                  ]
                }
                """), "/stages/0/disruptions/0/effect/status");
    }

    @Test
    void enforcesLatencyInvariants() {
        assertThrows(NullPointerException.class, () -> new ChaosLatency(null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new ChaosLatency(Duration.ofMillis(1), null));
        assertThrows(IllegalArgumentException.class, () -> new ChaosLatency(Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosLatency(Duration.ofMillis(1), Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosLatency(Duration.ofMillis(1), Duration.ofMillis(2)));
    }

    @Test
    void parsesProbabilityActivation() {
        String input = validJson().replace("{\"type\": \"always\"}",
                                           "{\"type\": \"probability\", \"probability\": 0.25}");

        ChaosRunPlan plan = ChaosRunPlanJson.parse(json(input), LIMITS);

        assertThat(plan.stage().disruption().activation(), is(new ProbabilityActivation(0.25)));

        ChaosRunPlan minimum = ChaosRunPlanJson.parse(
                json(withActivation("{\"type\": \"probability\", "
                                            + "\"probability\": 1.1102230246251565e-16}")),
                LIMITS);
        assertThat(minimum.stage().disruption().activation(),
                   is(new ProbabilityActivation(0x1.0p-53)));
        ChaosRunPlan certain = ChaosRunPlanJson.parse(
                json(withActivation("{\"type\": \"probability\", \"probability\": 1}")),
                LIMITS);
        assertThat(certain.stage().disruption().activation(), is(new ProbabilityActivation(1)));
    }

    @Test
    void parsesPeriodicBurstActivation() {
        ChaosRunPlan explicit = ChaosRunPlanJson.parse(
                json(withActivation("""
                        {
                          "type": "periodic-burst",
                          "initialSkip": 20,
                          "cycleSize": 10,
                          "burstSize": 3
                        }
                        """)),
                LIMITS);
        ChaosRunPlan defaultInitialSkip = ChaosRunPlanJson.parse(
                json(withActivation("""
                        {
                          "type": "periodic-burst",
                          "cycleSize": 10,
                          "burstSize": 3
                        }
                        """)),
                LIMITS);

        assertThat(explicit.stage().disruption().activation(), is(new PeriodicBurstActivation(20, 10, 3)));
        assertThat(defaultInitialSkip.stage().disruption().activation(), is(new PeriodicBurstActivation(0, 10, 3)));
    }

    @Test
    void rejectsMalformedPeriodicBurstActivation() {
        assertBadRequest(withActivation("""
                {"type": "periodic-burst", "burstSize": 3}
                """), "/stages/0/disruptions/0/activation/cycleSize");
        assertBadRequest(withActivation("""
                {"type": "periodic-burst", "cycleSize": 10}
                """), "/stages/0/disruptions/0/activation/burstSize");
        assertBadRequest(withActivation("""
                {"type": "periodic-burst", "initialSkip": "twenty", "cycleSize": 10, "burstSize": 3}
                """), "/stages/0/disruptions/0/activation/initialSkip");
        assertBadRequest(withActivation("""
                {"type": "periodic-burst", "cycleSize": 10.5, "burstSize": 3}
                """), "/stages/0/disruptions/0/activation/cycleSize");
    }

    @Test
    void rejectsInvalidPeriodicBurstActivation() {
        assertInvalidPlan(withActivation("""
                {"type": "periodic-burst", "initialSkip": -1, "cycleSize": 10, "burstSize": 3}
                """), "/stages/0/disruptions/0/activation/initialSkip");
        assertInvalidPlan(withActivation("""
                {"type": "periodic-burst", "cycleSize": 0, "burstSize": 3}
                """), "/stages/0/disruptions/0/activation/cycleSize");
        assertInvalidPlan(withActivation("""
                {"type": "periodic-burst", "cycleSize": 10, "burstSize": 0}
                """), "/stages/0/disruptions/0/activation/burstSize");
        assertInvalidPlan(withActivation("""
                {"type": "periodic-burst", "cycleSize": 10, "burstSize": 11}
                """), "/stages/0/disruptions/0/activation/burstSize");
    }

    @Test
    void enforcesPeriodicBurstActivationInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new PeriodicBurstActivation(-1, 10, 3));
        assertThrows(IllegalArgumentException.class, () -> new PeriodicBurstActivation(0, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new PeriodicBurstActivation(0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new PeriodicBurstActivation(0, 10, 11));
    }

    @Test
    void keepsActivationPropertiesMutuallyExclusive() {
        assertBadRequest(withActivation("""
                {"type": "always", "cycleSize": 10}
                """), "/stages/0/disruptions/0/activation/cycleSize");
        assertBadRequest(withActivation("""
                {"type": "probability", "probability": 0.25, "burstSize": 3}
                """), "/stages/0/disruptions/0/activation/burstSize");
        assertBadRequest(withActivation("""
                {"type": "periodic-burst", "probability": 0.25, "cycleSize": 10, "burstSize": 3}
                """), "/stages/0/disruptions/0/activation/probability");
    }

    @Test
    void rejectsMissingNonNumericAndOutOfRangeProbability() {
        assertBadRequest(withActivation("{\"type\": \"probability\"}"),
                         "/stages/0/disruptions/0/activation/probability");
        assertBadRequest(withActivation("{\"type\": \"probability\", \"probability\": \"often\"}"),
                         "/stages/0/disruptions/0/activation/probability");
        assertInvalidPlan(withActivation("{\"type\": \"probability\", \"probability\": 0}"),
                          "/stages/0/disruptions/0/activation/probability");
        assertInvalidPlan(withActivation("{\"type\": \"probability\", \"probability\": -0.1}"),
                          "/stages/0/disruptions/0/activation/probability");
        assertInvalidPlan(withActivation("{\"type\": \"probability\", \"probability\": 1.01}"),
                          "/stages/0/disruptions/0/activation/probability");
        assertInvalidPlan(withActivation("{\"type\": \"probability\", \"probability\": 1e-10000}"),
                          "/stages/0/disruptions/0/activation/probability");
        assertInvalidPlan(withActivation("{\"type\": \"probability\", \"probability\": 1e-100}"),
                          "/stages/0/disruptions/0/activation/probability");
        assertInvalidPlan(withActivation("{\"type\": \"probability\", "
                                                 + "\"probability\": 0.99999999999999999}"),
                          "/stages/0/disruptions/0/activation/probability");
    }

    @Test
    void rejectsUnknownRootPropertyAsBadRequest() {
        String input = validJson().replace("\"name\":", "\"enabled\":true,\"name\":");

        ChaosRequestException exception = assertThrows(ChaosRequestException.class,
                                                        () -> ChaosRunPlanJson.parse(json(input), LIMITS));

        assertThat(exception.status(), is(400));
        assertThat(exception.violations().getFirst().path(), is("/enabled"));
        assertThat(exception.violations().getFirst().code(), is("unknown-property"));
    }

    @Test
    void rejectsUnsupportedScopeActivationAndEffectTypes() {
        assertInvalidPlan(validJson().replace("inbound-http", "outbound-http"), "/stages/0/disruptions/0/scope/type");
        assertInvalidPlan(validJson().replace("\"always\"", "\"invocation-cycle\""),
                          "/stages/0/disruptions/0/activation/type");
        assertInvalidPlan(validJson().replace("synthetic-http-response", "future-effect"),
                          "/stages/0/disruptions/0/effect/type");
    }

    @Test
    void rejectsUnknownActivationPropertyBeforeUnsupportedType() {
        String input = withActivation("{\"type\": \"future\", \"unexpected\": true}");

        assertBadRequest(input, "/stages/0/disruptions/0/activation/unexpected");
    }

    @Test
    void rejectsUnsafeOrNonNormalizedPaths() {
        assertInvalidPlan(validJson().replace("/orders", "/chaos/v1"),
                          "/stages/0/disruptions/0/scope/path/value");
        assertInvalidPlan(validJson().replace("/orders", "orders"),
                          "/stages/0/disruptions/0/scope/path/value");
        assertInvalidPlan(validJson().replace("/orders", "/orders/../admin"),
                          "/stages/0/disruptions/0/scope/path/value");
    }

    @Test
    void rejectsEmptyMethods() {
        assertInvalidPlan(validJson().replace("[\"get\"]", "[]"),
                          "/stages/0/disruptions/0/scope/methods");
    }

    @Test
    void rejectsInvalidDurationsAndServerLimitExcess() {
        assertInvalidPlan(validJson().replace("PT30S", "not-a-duration"), "/maximumDuration");
        assertInvalidPlan(validJson().replace("PT10S", "PT31S"), "/stages/0/duration");

        ChaosLimitsConfig limits = ChaosLimitsConfig.builder()
                .maximumRunDuration(Duration.ofSeconds(20))
                .build();
        assertInvalidPlan(validJson(), limits, "/maximumDuration");
    }

    @Test
    void retainsParsingCauseWithoutExposingItInProblemResponse() {
        ChaosRequestException exception = assertThrows(ChaosRequestException.class,
                                                        () -> ChaosRunPlanJson.parse(
                                                                json(validJson().replace("PT30S", "not-a-duration")),
                                                                LIMITS));

        assertThat(exception.getCause(), instanceOf(DateTimeParseException.class));
        assertThat(ChaosProblemJson.from(exception, "/chaos/v1/runs").body().toString(),
                   not(containsString("not-a-duration")));
    }

    @Test
    void rejectsInvalidOrExcessiveBudgets() {
        assertInvalidPlan(validJson().replace("\"maximumActivations\": 20", "\"maximumActivations\": 0"),
                          "/stages/0/disruptions/0/budget/maximumActivations");
        assertInvalidPlan(validJson().replace("\"maximumConcurrent\": 2", "\"maximumConcurrent\": 0"),
                          "/stages/0/disruptions/0/budget/maximumConcurrent");

        ChaosLimitsConfig limits = ChaosLimitsConfig.builder()
                .maximumActivationsPerDisruption(19)
                .build();
        assertInvalidPlan(validJson(), limits,
                          "/stages/0/disruptions/0/budget/maximumActivations");
    }

    @Test
    void restrictsSyntheticStatusHeadersAndBody() {
        assertInvalidPlan(validJson().replace("\"status\": 503", "\"status\": 399"),
                          "/stages/0/disruptions/0/effect/status");
        assertInvalidPlan(validJson().replace("\"Retry-After\": \"1\"", "\"Content-Length\": \"2\""),
                          "/stages/0/disruptions/0/effect/headers/Content-Length");
        assertInvalidPlan(validJson().replace("\"Retry-After\": \"1\"",
                                              "\"Retry-After\": \"1\", \"retry-after\": \"60\""),
                          "/stages/0/disruptions/0/effect/headers/retry-after");
        assertInvalidPlan(validJson().replace("\"Retry-After\": \"1\"", "\"X-Test\": \"bad\\nvalue\""),
                          "/stages/0/disruptions/0/effect/headers/X-Test");
        assertInvalidPlan(validJson().replace("\"Retry-After\": \"1\"", "\"X-Test\": \"bad\\u0001value\""),
                          "/stages/0/disruptions/0/effect/headers/X-Test");
        assertInvalidPlan(validJson().replace("\"Retry-After\": \"1\"", "\"X-Test\": \"bad\\u007fvalue\""),
                          "/stages/0/disruptions/0/effect/headers/X-Test");

        ChaosLimitsConfig limits = ChaosLimitsConfig.builder().maximumSyntheticBodyBytes(4).build();
        assertInvalidPlan(validJson().replace("Synthetic service failure", "ééé"), limits,
                          "/stages/0/disruptions/0/effect/body");
    }

    @Test
    void reportsNullSyntheticResponseComponents() {
        assertThat(assertThrows(NullPointerException.class,
                                () -> new ChaosSyntheticResponse(503, null, Optional.empty(), new byte[0])).getMessage(),
                   is("headers is null"));
        assertThat(assertThrows(NullPointerException.class,
                                () -> new ChaosSyntheticResponse(503, Map.of(), null, new byte[0])).getMessage(),
                   is("mediaType is null"));
        assertThat(assertThrows(NullPointerException.class,
                                () -> new ChaosSyntheticResponse(503, Map.of(), Optional.empty(), null)).getMessage(),
                   is("body is null"));
    }

    @Test
    void rejectsUnpairedUnicodeSurrogates() {
        assertBadRequest(validJson().replace("orders-unavailable", "bad\\ud800name"), "/name");
    }

    @Test
    void rejectsWrongStageAndDisruptionCounts() {
        assertInvalidPlan("""
                {"name":"empty","maximumDuration":"PT30S","stages":[]}
                """, "/stages");
        assertInvalidPlan("""
                {
                  "name":"empty",
                  "maximumDuration":"PT30S",
                  "stages":[{"name":"stage","duration":"PT10S","disruptions":[]}]
                }
                """, "/stages/0/disruptions");
    }

    private static void assertInvalidPlan(String input, String path) {
        assertInvalidPlan(input, LIMITS, path);
    }

    private static void assertBadRequest(String input, String path) {
        ChaosRequestException exception = assertThrows(ChaosRequestException.class,
                                                        () -> ChaosRunPlanJson.parse(json(input), LIMITS));
        assertThat(exception.status(), is(400));
        assertThat(exception.violations().getFirst().path(), is(path));
    }

    private static void assertInvalidPlan(String input, ChaosLimitsConfig limits, String path) {
        ChaosRequestException exception = assertThrows(ChaosRequestException.class,
                                                        () -> ChaosRunPlanJson.parse(json(input), limits));
        assertThat(exception.status(), is(422));
        assertThat(exception.violations().getFirst().path(), is(path));
    }

    private static JsonObject json(String text) {
        return JsonParser.create(text).readJsonObject();
    }

    private static String withActivation(String activation) {
        return validJson().replace("{\"type\": \"always\"}", activation);
    }

    private static String withEffect(String effect) {
        return planJson(effect.strip());
    }

    private static String validJson() {
        return planJson("""
                {
                  "type": "synthetic-http-response",
                  "status": 503,
                  "headers": {"Retry-After": "1"},
                  "mediaType": "text/plain",
                  "body": "Synthetic service failure"
                }
                """);
    }

    private static String planJson(String effect) {
        return """
                {
                  "name": "orders-unavailable",
                  "maximumDuration": "PT30S",
                  "seed": 148894,
                  "stages": [{
                    "name": "reject-orders",
                    "duration": "PT10S",
                    "disruptions": [{
                      "name": "orders-503",
                      "scope": {
                        "type": "inbound-http",
                        "methods": ["get"],
                        "path": {"match": "prefix", "value": "/orders"}
                      },
                      "activation": {"type": "always"},
                      "effect": %s,
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(effect);
    }
}
