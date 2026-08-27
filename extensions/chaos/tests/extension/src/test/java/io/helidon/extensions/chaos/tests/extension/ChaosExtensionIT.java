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
import io.helidon.extensions.chaos.ChaosConfig;
import io.helidon.extensions.chaos.ChaosSecurityConfig;
import io.helidon.extensions.chaos.ChaosServerFeature;
import io.helidon.http.Status;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosExtensionIT {

    private static final String CONTROL_SOCKET = "chaos-control";
    private static final String RUNS = "/chaos/v1/runs";

    @Test
    void isolatesSocketsAndExecutesRunLifecycle() {
        AtomicInteger applicationInvocations = new AtomicInteger();
        WebServer server = startServer(applicationInvocations);
        Http1Client application = client(server.port());
        Http1Client control = client(server.port(CONTROL_SOCKET));
        try {
            assertThat(application.get(RUNS).request().status(), is(Status.NOT_FOUND_404));
            assertThat(control.get("/orders/42").request().status(), is(Status.NOT_FOUND_404));

            JsonObject created = postRun(control);
            String id = created.stringValue("id").orElseThrow();
            assertThat(created.stringValue("state").orElseThrow(), is("RUNNING"));
            assertThat(created.stringValue("actor").orElseThrow(), is("anonymous-local"));

            assertThat(application.get("/orders/42").request(String.class).status(),
                       is(Status.SERVICE_UNAVAILABLE_503));
            assertThat(applicationInvocations.get(), is(0));
            assertThat(application.get("/orders-old").request(String.class).status(), is(Status.OK_200));
            assertThat(applicationInvocations.get(), is(1));

            JsonObject fetched = control.get(RUNS + "/" + id).request(JsonObject.class).entity();
            assertThat(fetched.objectValue("counters").orElseThrow().longValue("activated").orElseThrow(), is(1L));

            assertThat(control.delete(RUNS + "/" + id).request(JsonObject.class).status(), is(Status.OK_200));
            assertThat(application.get("/orders/42").request(String.class).status(), is(Status.OK_200));
            assertThat(applicationInvocations.get(), is(2));
        } finally {
            application.closeResource();
            control.closeResource();
            server.stop();
        }
    }

    @Test
    void restartDoesNotReconstructRuns() {
        WebServer first = startServer(new AtomicInteger());
        Http1Client firstControl = client(first.port(CONTROL_SOCKET));
        try {
            postRun(firstControl);
            assertThat(firstControl.get(RUNS).request(JsonArray.class).entity().size(), is(1));
        } finally {
            firstControl.closeResource();
            first.stop();
        }

        WebServer second = startServer(new AtomicInteger());
        Http1Client secondControl = client(second.port(CONTROL_SOCKET));
        try {
            assertThat(secondControl.get(RUNS).request(JsonArray.class).entity().size(), is(0));
        } finally {
            secondControl.closeResource();
            second.stop();
        }
    }

    @Test
    void executesDeterministicProbabilityActivation() {
        AtomicInteger applicationInvocations = new AtomicInteger();
        WebServer server = startServer(applicationInvocations);
        Http1Client application = client(server.port());
        Http1Client control = client(server.port(CONTROL_SOCKET));
        try {
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
            assertThat(applicationInvocations.get(), is(6));
        } finally {
            application.closeResource();
            control.closeResource();
            server.stop();
        }
    }

    @Test
    void disabledFeatureDoesNotRequireChaosSockets() {
        WebServer server = WebServer.builder()
                .featuresDiscoverServices(false)
                .host("127.0.0.1")
                .port(0)
                .addFeature(ChaosServerFeature.create(ChaosConfig.builder().buildPrototype()))
                .routing(rules -> rules.get("/ready", (request, response) -> response.send("ready")))
                .build()
                .start();
        Http1Client client = client(server.port());
        try {
            assertThat(client.get("/ready").request().status(), is(Status.OK_200));
            assertThat(client.get(RUNS).request().status(), is(Status.NOT_FOUND_404));
        } finally {
            client.closeResource();
            server.stop();
        }
    }

    @Test
    void unsafeSocketConfigurationsFailBeforeStartup() {
        var wildcard = WebServer.builder()
                .featuresDiscoverServices(false)
                .host("127.0.0.1")
                .port(0)
                .putSocket(CONTROL_SOCKET, socket -> socket.host("0.0.0.0").port(0))
                .addFeature(ChaosServerFeature.create(enabledConfig(CONTROL_SOCKET)));
        assertThat(wildcard.sockets().get(CONTROL_SOCKET).host(), is("0.0.0.0"));
        assertThat(wildcard.sockets().get(CONTROL_SOCKET).address().isAnyLocalAddress(), is(true));
        assertFailsStartup(wildcard);

        assertFailsStartup(WebServer.builder()
                                   .featuresDiscoverServices(false)
                                   .host("127.0.0.1")
                                   .port(0)
                                   .addFeature(ChaosServerFeature.create(enabledConfig("missing-control"))));

        assertThrows(IllegalArgumentException.class,
                     () -> ChaosConfig.builder()
                             .enabled(true)
                             .controlSocket(WebServer.DEFAULT_SOCKET_NAME)
                             .addApplicationSocket(WebServer.DEFAULT_SOCKET_NAME)
                             .security(ChaosSecurityConfig.builder()
                                               .allowUnauthenticatedLoopback(true)
                                               .build())
                             .buildPrototype());
    }

    private static WebServer startServer(AtomicInteger applicationInvocations) {
        Config config = Config.just(ConfigSources.classpath("application.yaml"));
        return WebServer.builder()
                .config(config.get("server"))
                .routing(rules -> rules
                        .get("/orders/{id}", (request, response) -> {
                            applicationInvocations.incrementAndGet();
                            response.send("order");
                        })
                        .get("/orders-old", (request, response) -> {
                            applicationInvocations.incrementAndGet();
                            response.send("legacy-order");
                        }))
                .build()
                .start();
    }

    private static ChaosConfig enabledConfig(String controlSocket) {
        return ChaosConfig.builder()
                .enabled(true)
                .controlSocket(controlSocket)
                .addApplicationSocket(WebServer.DEFAULT_SOCKET_NAME)
                .security(ChaosSecurityConfig.builder()
                                  .allowUnauthenticatedLoopback(true)
                                  .build())
                .buildPrototype();
    }

    private static void assertFailsStartup(io.helidon.webserver.WebServerConfig.Builder serverBuilder) {
        assertThat(serverBuilder.features().stream()
                           .filter(ChaosServerFeature.class::isInstance)
                           .map(ChaosServerFeature.class::cast)
                           .anyMatch(feature -> feature.prototype().enabled()),
                   is(true));
        assertThrows(IllegalStateException.class, serverBuilder::build);
    }

    private static Http1Client client(int port) {
        return Http1Client.builder()
                .baseUri("http://127.0.0.1:" + port)
                .build();
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
