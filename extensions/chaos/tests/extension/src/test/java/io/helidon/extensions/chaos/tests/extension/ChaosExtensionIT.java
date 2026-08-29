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
package io.helidon.extensions.chaos.tests.extension;

import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.http.Status;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import io.helidon.webserver.testing.junit5.SetUpServer;
import io.helidon.webserver.testing.junit5.Socket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ServerTest
class ChaosExtensionIT {

    private static final String CONTROL_SOCKET = "chaos-control";
    private static final String RUNS = "/chaos/v1/runs";
    private static final AtomicInteger APPLICATION_INVOCATIONS = new AtomicInteger();

    private final Http1Client application;
    private final Http1Client control;

    ChaosExtensionIT(Http1Client application, @Socket(CONTROL_SOCKET) Http1Client control) {
        this.application = application;
        this.control = control;
    }

    @SetUpServer
    static void setUpServer(WebServerConfig.Builder server) {
        Config config = Config.just(ConfigSources.classpath("application.yaml"));
        server.config(config.get("server"));
    }

    @SetUpRoute
    static void setUpRoute(HttpRouting.Builder routing) {
        routing.get("/orders/{id}", (request, response) -> {
            APPLICATION_INVOCATIONS.incrementAndGet();
            response.send("order");
        }).get("/orders-old", (request, response) -> {
            APPLICATION_INVOCATIONS.incrementAndGet();
            response.send("legacy-order");
        });
    }

    @BeforeEach
    void resetApplicationInvocations() {
        APPLICATION_INVOCATIONS.set(0);
    }

    @AfterEach
    void stopActiveRuns() {
        JsonArray runs = control.get(RUNS).request(JsonArray.class).entity();
        for (int index = 0; index < runs.size(); index++) {
            String id = runs.get(index).orElseThrow().asObject().stringValue("id").orElseThrow();
            control.delete(RUNS + "/" + id).request();
        }
    }

    @Test
    void isolatesSocketsAndExecutesRunLifecycle() {
        assertThat(application.get(RUNS).request().status(), is(Status.NOT_FOUND_404));
        assertThat(control.get("/orders/42").request().status(), is(Status.NOT_FOUND_404));

        JsonObject created = postRun(control);
        String id = created.stringValue("id").orElseThrow();
        assertThat(created.stringValue("state").orElseThrow(), is("RUNNING"));
        assertThat(created.stringValue("actor").orElseThrow(), is("anonymous-local"));

        assertThat(application.get("/orders/42").request(String.class).status(),
                   is(Status.SERVICE_UNAVAILABLE_503));
        assertThat(APPLICATION_INVOCATIONS.get(), is(0));
        assertThat(application.get("/orders-old").request(String.class).status(), is(Status.OK_200));
        assertThat(APPLICATION_INVOCATIONS.get(), is(1));

        JsonObject fetched = control.get(RUNS + "/" + id).request(JsonObject.class).entity();
        assertThat(fetched.objectValue("counters").orElseThrow().longValue("activated").orElseThrow(), is(1L));

        assertThat(control.delete(RUNS + "/" + id).request(JsonObject.class).status(), is(Status.OK_200));
        assertThat(application.get("/orders/42").request(String.class).status(), is(Status.OK_200));
        assertThat(APPLICATION_INVOCATIONS.get(), is(2));
    }

    @Test
    void executesDeterministicProbabilityActivation() {
        JsonObject created = postRun(control,
                                     runPlan(42,
                                             "stage",
                                             "synthetic",
                                             "{\"type\":\"probability\",\"probability\":0.5}"));
        String id = created.stringValue("id").orElseThrow();

        Status[] expected = {
                Status.OK_200,
                Status.OK_200,
                Status.OK_200,
                Status.OK_200,
                Status.SERVICE_UNAVAILABLE_503,
                Status.OK_200,
                Status.OK_200,
                Status.SERVICE_UNAVAILABLE_503
        };
        for (Status status : expected) {
            assertThat(application.get("/orders/42").request(String.class).status(), is(status));
        }

        JsonObject fetched = control.get(RUNS + "/" + id).request(JsonObject.class).entity();
        JsonObject counters = fetched.objectValue("counters").orElseThrow();
        assertThat(counters.longValue("matched").orElseThrow(), is(8L));
        assertThat(counters.longValue("activated").orElseThrow(), is(2L));
        assertThat(counters.longValue("skippedActivation").orElseThrow(), is(6L));
        assertThat(APPLICATION_INVOCATIONS.get(), is(6));
    }

    private static JsonObject postRun(Http1Client control) {
        return postRun(control, runPlan(148_894, "reject-orders", "orders-503", "{\"type\":\"always\"}"));
    }

    private static JsonObject runPlan(long seed, String stageName, String disruptionName, String activation) {
        return JsonParser.create("""
                {
                  "name": "orders-unavailable",
                  "maximumDuration": "PT30S",
                  "seed": %d,
                  "stages": [{
                    "name": "%s",
                    "duration": "PT10S",
                    "disruptions": [{
                      "name": "%s",
                      "scope": {
                        "type": "inbound-http",
                        "methods": ["GET"],
                        "path": {"match": "prefix", "value": "/orders"}
                      },
                      "activation": %s,
                      "effect": {
                        "type": "synthetic-http-response",
                        "status": 503,
                        "body": "failure"
                      },
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(seed, stageName, disruptionName, activation)).readJsonObject();
    }

    private static JsonObject postRun(Http1Client control, JsonObject plan) {
        var response = control.post(RUNS)
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(plan, JsonObject.class);
        assertThat(response.status(), is(Status.CREATED_201));
        return response.entity();
    }
}
