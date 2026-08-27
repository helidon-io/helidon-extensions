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

import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
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
    void parsesProbabilityActivation() {
        String input = validJson().replace("{\"type\": \"always\"}",
                                           "{\"type\": \"probability\", \"probability\": 0.25}");

        ChaosRunPlan plan = ChaosRunPlanJson.parse(json(input), LIMITS);

        assertThat(plan.stage().disruption().activation(), is(new ChaosActivation.Probability(0.25)));

        ChaosRunPlan minimum = ChaosRunPlanJson.parse(
                json(withActivation("{\"type\": \"probability\", "
                                            + "\"probability\": 1.1102230246251565e-16}")),
                LIMITS);
        assertThat(minimum.stage().disruption().activation(),
                   is(new ChaosActivation.Probability(0x1.0p-53)));
        ChaosRunPlan certain = ChaosRunPlanJson.parse(
                json(withActivation("{\"type\": \"probability\", \"probability\": 1}")),
                LIMITS);
        assertThat(certain.stage().disruption().activation(), is(new ChaosActivation.Probability(1)));
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
