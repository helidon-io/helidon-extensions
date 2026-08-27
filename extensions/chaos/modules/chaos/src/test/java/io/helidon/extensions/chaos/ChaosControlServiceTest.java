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

import java.util.UUID;
import java.util.concurrent.Semaphore;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.extensions.chaos.ChaosRunState.RUNNING;
import static io.helidon.extensions.chaos.ChaosRunState.STOPPED;
import static io.helidon.extensions.chaos.ChaosRunState.STOPPING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ServerTest
class ChaosControlServiceTest {
    private static final String RUNS = "/chaos/v1/runs";
    private static ChaosRunEngine engine;
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
        capacity = new Semaphore(limits.maximumConcurrentControlRequests(), true);
        routing.register("/chaos/v1", new ChaosControlService(engine, config, true, capacity));
    }

    @BeforeEach
    void stopActiveRuns() {
        engine.list().stream()
                .filter(run -> run.state() == RUNNING)
                .forEach(run -> engine.stop(run.id()));
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
        ChaosRunEngine.Reservation reservation = engine.reserve("GET", "/orders/42").orElseThrow();
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

    private ClientResponseTyped<JsonObject> post(JsonObject plan) {
        return client.post(RUNS)
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(plan, JsonObject.class);
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
}
