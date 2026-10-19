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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import io.helidon.common.media.type.MediaType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.webclient.api.ClientResponseTyped;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.extensions.chaos.ChaosRunState.RUNNING;
import static io.helidon.extensions.chaos.ChaosRunState.STOPPED;
import static io.helidon.extensions.chaos.ChaosRunState.STOPPING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ServerTest
class ChaosControlServiceTest {
    private static final String RUNS = "/chaos/v1/runs";
    private static final String HALT = "/chaos/v1/halt";
    private static final String LIMITS = "/chaos/v1/limits";
    private static final String VALIDATIONS = "/chaos/v1/validations";
    private static ChaosRunEngine engine;
    private static ChaosRuntimeRegistration registration;
    private static ChaosControlService service;
    private static Semaphore capacity;

    private final WebClient client;

    ChaosControlServiceTest(WebClient client) {
        this.client = client;
    }

    @SetUpRoute
    static void setUpRoute(HttpRouting.Builder routing) {
        ChaosLimitsConfig limits = ChaosLimitsConfig.builder()
                .maximumRetainedRuns(32)
                .build();
        ChaosConfig config = ChaosConfig.builder().limits(limits).buildPrototype();
        engine = ChaosRunEngine.create(limits);
        registration = ChaosRuntimeBridge.register(engine);
        capacity = new Semaphore(limits.maximumConcurrentControlRequests(), true);
        service = new ChaosControlService(registration, config, true, capacity);
        routing.register("/chaos/v1", service);
    }

    @BeforeEach
    void stopActiveRuns() {
        engine.list().stream()
                .filter(run -> run.state() == RUNNING)
                .forEach(run -> engine.stop(run.id()));
    }

    @AfterAll
    static void closeService() {
        service.afterStop();
    }

    @Test
    void createsReadsAndListsRuns() {
        ClientResponseTyped<JsonObject> created = post(validPlan("orders"));
        assertThat(created.status(), is(Status.CREATED_201));
        JsonObject createdBody = created.entity();
        String id = createdBody.stringValue("id").orElseThrow();
        assertThat(createdBody.stringValue("state").orElseThrow(), is("RUNNING"));
        assertThat(createdBody.stringValue("actor").orElseThrow(), is("anonymous-local"));
        assertThat(created.headers().first(HeaderNames.LOCATION).orElseThrow(), is(RUNS + "/" + id));
        assertJsonContentType(created);
        created.close();

        ClientResponseTyped<JsonObject> fetched = client.get(RUNS + "/" + id).request(JsonObject.class);
        assertThat(fetched.status(), is(Status.OK_200));
        assertThat(fetched.entity().stringValue("id").orElseThrow(), is(id));
        fetched.close();

        ClientResponseTyped<JsonArray> listed = client.get(RUNS).request(JsonArray.class);
        assertThat(listed.status(), is(Status.OK_200));
        assertThat(listed.entity().get(0).orElseThrow().asObject().stringValue("id").orElseThrow(), is(id));
        listed.close();
    }

    @Test
    void rejectsMalformedAndUnknownRunIds() {
        ClientResponseTyped<String> malformed = client.get(RUNS + "/not-a-uuid").request(String.class);
        assertProblem(malformed, Status.BAD_REQUEST_400, "invalid-request");
        ClientResponseTyped<String> abbreviated = client.get(RUNS + "/1-1-1-1-1").request(String.class);
        assertProblem(abbreviated, Status.BAD_REQUEST_400, "invalid-request");

        ClientResponseTyped<String> unknown =
                client.get(RUNS + "/" + UUID.randomUUID()).request(String.class);
        assertProblem(unknown, Status.NOT_FOUND_404, "run-not-found");
    }

    @Test
    void deleteReportsImmediateAndDrainingStops() {
        ClientResponseTyped<JsonObject> firstCreated = post(validPlan("first"));
        JsonObject first = firstCreated.entity();
        firstCreated.close();
        String firstId = first.stringValue("id").orElseThrow();
        ClientResponseTyped<JsonObject> stopped = client.delete(RUNS + "/" + firstId).request(JsonObject.class);
        assertThat(stopped.status(), is(Status.OK_200));
        assertThat(stopped.entity().stringValue("state").orElseThrow(), is(STOPPED.name()));
        stopped.close();

        ClientResponseTyped<JsonObject> secondCreated = post(validPlan("second"));
        JsonObject second = secondCreated.entity();
        secondCreated.close();
        UUID secondId = UUID.fromString(second.stringValue("id").orElseThrow());
        ChaosRunEngine.Reservation reservation = engine.reserveInbound("GET", "/orders/42").orElseThrow();
        try {
            ClientResponseTyped<JsonObject> stopping =
                    client.delete(RUNS + "/" + secondId).request(JsonObject.class);
            assertThat(stopping.status(), is(Status.ACCEPTED_202));
            assertThat(stopping.entity().stringValue("state").orElseThrow(), is(STOPPING.name()));
            stopping.close();
        } finally {
            reservation.close();
        }
        assertThat(engine.get(secondId).orElseThrow().state(), is(STOPPED));
    }

    @Test
    void haltStopsActiveRunsAndReportsDraining() {
        ClientResponseTyped<JsonObject> created = post(validPlan("halt"));
        String id = created.entity().stringValue("id").orElseThrow();
        created.close();
        ChaosRunEngine.Reservation reservation = engine.reserveInbound("GET", "/orders/42").orElseThrow();
        try {
            ClientResponseTyped<JsonObject> halted = client.post(HALT).request(JsonObject.class);
            assertThat(halted.status(), is(Status.OK_200));
            assertJsonContentType(halted);
            assertThat(halted.entity().arrayValue("halted").orElseThrow().toString(), is("[\"" + id + "\"]"));
            assertThat(halted.entity().arrayValue("draining").orElseThrow().toString(), is("[\"" + id + "\"]"));
            halted.close();
        } finally {
            reservation.close();
        }

        ChaosRunView run = engine.get(UUID.fromString(id)).orElseThrow();
        assertThat(run.state(), is(STOPPED));
        assertThat(run.terminalReason().orElseThrow(), is("halted"));
    }

    @Test
    void haltWithoutActiveRunsSucceeds() {
        ClientResponseTyped<JsonObject> halted = client.post(HALT).request(JsonObject.class);

        assertThat(halted.status(), is(Status.OK_200));
        assertThat(halted.entity().arrayValue("halted").orElseThrow().toString(), is("[]"));
        assertThat(halted.entity().arrayValue("draining").orElseThrow().toString(), is("[]"));
        halted.close();
    }

    @Test
    void limitsReportConfiguredValuesAndActiveRuns() {
        JsonObject expected = JsonParser.create("""
                {
                  "maximumActiveRuns": 1,
                  "maximumRunDuration": "PT15M",
                  "maximumStagesPerRun": 16,
                  "maximumActivationsPerDisruption": 10000,
                  "maximumConcurrentActivationsPerDisruption": 64,
                  "maximumLatency": "PT30S",
                  "maximumResponseTimeout": "PT30S",
                  "maximumSyntheticBodyBytes": 65536,
                  "maximumControlRequestBytes": 65536,
                  "maximumConcurrentControlRequests": 16,
                  "maximumRetainedRuns": 32,
                  "terminalRunRetention": "PT15M"
                }
                """).readJsonObject();

        ClientResponseTyped<JsonObject> idle = client.get(LIMITS).request(JsonObject.class);
        assertThat(idle.status(), is(Status.OK_200));
        assertJsonContentType(idle);
        assertThat(idle.entity().objectValue("limits").orElseThrow().toString(), is(expected.toString()));
        assertThat(idle.entity().intValue("activeRuns").orElseThrow(), is(0));
        idle.close();

        post(validPlan("limits")).close();
        ClientResponseTyped<JsonObject> active = client.get(LIMITS).request(JsonObject.class);
        assertThat(active.entity().intValue("activeRuns").orElseThrow(), is(1));
        active.close();
    }

    @Test
    void validationReturnsNormalizedPlanWithoutCreatingRun() {
        ClientResponseTyped<JsonObject> validated = client.post(VALIDATIONS)
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(validPlan("validated"), JsonObject.class);
        assertThat(validated.status(), is(Status.OK_200));
        assertJsonContentType(validated);
        JsonObject body = validated.entity();
        validated.close();
        assertThat(body.objectValue("effective").orElseThrow().stringValue("worstCaseDuration").orElseThrow(),
                   is("PT30S"));
        assertThat(engine.activeRunCount(), is(0));

        ClientResponseTyped<JsonObject> created = post(validPlan("validated"));
        assertThat(body.objectValue("plan").orElseThrow().toString(),
                   is(created.entity().objectValue("plan").orElseThrow().toString()));
        created.close();
    }

    @Test
    void validationAgreesWithCreateOnEveryError() {
        JsonObject tooLong = JsonObject.builder().from(validPlan("too-long")).set("maximumDuration", "PT30M").build();
        JsonObject unknownField = JsonObject.builder().from(validPlan("unknown")).set("extra", 1).build();
        assertSameProblem(MediaTypes.TEXT_PLAIN, "not-json", 415);
        assertSameProblem(MediaTypes.APPLICATION_JSON, "{", 400);
        assertSameProblem(MediaTypes.APPLICATION_JSON, "", 400);
        assertSameProblem(MediaTypes.APPLICATION_JSON, unknownField.toString(), 400);
        assertSameProblem(MediaTypes.APPLICATION_JSON, tooLong.toString(), 422);

        post(validPlan("active")).close();
        assertSameProblem(MediaTypes.APPLICATION_JSON, validPlan("overlapping").toString(), 409);
    }

    @Test
    void rejectsUnsupportedMediaTypeAndInvalidPlan() {
        ClientResponseTyped<String> unsupported = client.post(RUNS)
                .contentType(MediaTypes.TEXT_PLAIN)
                .submit("not-json", String.class);
        assertProblem(unsupported, Status.UNSUPPORTED_MEDIA_TYPE_415, "unsupported-media-type");

        ClientResponseTyped<String> malformed = client.post(RUNS)
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("{", String.class);
        assertProblem(malformed, Status.BAD_REQUEST_400, "invalid-request");

        JsonObject invalid = JsonObject.builder()
                .from(validPlan("too-long"))
                .set("maximumDuration", "PT30M")
                .build();
        ClientResponseTyped<String> validation = client.post(RUNS)
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(invalid, String.class);
        JsonObject validationBody = JsonParser.create(validation.entity()).readJsonObject();
        assertThat(validationBody.arrayValue("violations").orElseThrow().size(), is(1));
        assertProblem(validation, Status.UNPROCESSABLE_CONTENT_422, "invalid-plan");
    }

    @Test
    void rejectsRequestsWhenControlCapacityIsExhausted() throws InterruptedException {
        int acquired = capacity.availablePermits();
        capacity.acquire(acquired);
        try {
            ClientResponseTyped<String> response = client.get(RUNS).request(String.class);
            assertProblem(response, Status.TOO_MANY_REQUESTS_429, "control-capacity");
        } finally {
            capacity.release(acquired);
        }
    }

    @Test
    void rejectsNullRuntimeRegistration() {
        ChaosConfig config = ChaosConfig.builder().buildPrototype();

        NullPointerException exception = assertThrows(NullPointerException.class,
                                                       () -> new ChaosControlService(null,
                                                                                     config,
                                                                                     true,
                                                                                     new Semaphore(1)));

        assertThat(exception.getMessage(), is("registration is null"));
    }

    private static void assertProblem(ClientResponseTyped<String> response,
                                      Status status,
                                      String type) {
        assertThat(response.status(), is(status));
        JsonObject body = JsonParser.create(response.entity()).readJsonObject();
        assertThat(body.stringValue("type").orElseThrow(), is("/chaos/v1/problems/" + type));
        assertThat(response.headers().contentType().orElseThrow().fullType(), is("application/problem+json"));
        response.close();
    }

    private void assertSameProblem(MediaType contentType, String body, int expectedStatus) {
        ClientResponseTyped<String> validation = client.post(VALIDATIONS).contentType(contentType).submit(body, String.class);
        ClientResponseTyped<String> creation = client.post(RUNS).contentType(contentType).submit(body, String.class);
        assertThat(body, validation.status().code(), is(expectedStatus));
        assertThat(body, creation.status().code(), is(expectedStatus));
        JsonObject validationProblem = JsonParser.create(validation.entity()).readJsonObject();
        JsonObject creationProblem = JsonParser.create(creation.entity()).readJsonObject();
        for (String field : List.of("type", "title", "detail")) {
            assertThat(body + " " + field,
                       validationProblem.stringValue(field).orElseThrow(),
                       is(creationProblem.stringValue(field).orElseThrow()));
        }
        assertThat(body,
                   String.valueOf(validationProblem.arrayValue("violations").map(Object::toString)),
                   is(String.valueOf(creationProblem.arrayValue("violations").map(Object::toString))));
        validation.close();
        creation.close();
    }

    private static void assertJsonContentType(ClientResponseTyped<?> response) {
        assertThat(response.headers().contentType().orElseThrow().mediaType(), is(MediaTypes.APPLICATION_JSON));
    }

    private static JsonObject validPlan(String name) {
        String json = """
                {
                  "name": "%s",
                  "maximumDuration": "PT30S",
                  "seed": 148894,
                  "stages": [{
                    "name": "reject-orders",
                    "duration": "PT10S",
                    "disruptions": [{
                      "name": "orders-503",
                      "scope": {
                        "type": "inbound-http",
                        "methods": ["GET"],
                        "path": {"match": "prefix", "value": "/orders"}
                      },
                      "activation": {"type": "always"},
                      "effect": {
                        "type": "synthetic-http-response",
                        "status": 503,
                        "body": "failure"
                      },
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(name);
        return JsonParser.create(json).readJsonObject();
    }

    private ClientResponseTyped<JsonObject> post(JsonObject plan) {
        return client.post(RUNS)
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(plan, JsonObject.class);
    }

}
