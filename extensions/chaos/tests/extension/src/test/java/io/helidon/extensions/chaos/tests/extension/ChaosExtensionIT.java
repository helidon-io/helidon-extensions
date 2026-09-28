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

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLHandshakeException;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.http.HeaderNames;
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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        }).get("/order-redirect", (request, response) -> {
            APPLICATION_INVOCATIONS.incrementAndGet();
            response.status(Status.TEMPORARY_REDIRECT_307);
            response.header(HeaderNames.LOCATION, "/orders/redirected");
            response.send();
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

    @Test
    void executesLatencyBeforeApplicationRouting() {
        JsonObject plan = runPlan(42,
                                  "stage",
                                  "latency",
                                  "{\"type\":\"always\"}",
                                  "{\"type\":\"latency\",\"delay\":\"PT0.1S\"}");
        JsonObject created = postRun(control, plan);
        JsonObject effect = created.objectValue("plan").orElseThrow()
                .arrayValue("stages").orElseThrow().get(0).orElseThrow().asObject()
                .arrayValue("disruptions").orElseThrow().get(0).orElseThrow().asObject()
                .objectValue("effect").orElseThrow();
        assertThat(effect.stringValue("jitter").orElseThrow(), is("PT0S"));

        long started = System.nanoTime();
        var response = application.get("/orders/42").request(String.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity(), is("order"));
        assertThat(elapsed.compareTo(Duration.ofMillis(90)) >= 0, is(true));
        assertThat(APPLICATION_INVOCATIONS.get(), is(1));
    }

    @Test
    void executesOutboundWebClientLatencyThroughAutomaticDiscovery() {
        var baseUri = application.prototype().baseUri().orElseThrow();
        JsonObject created = postRun(control, outboundLatencyPlan(baseUri.scheme(), baseUri.host(), baseUri.port()));
        String id = created.stringValue("id").orElseThrow();
        JsonObject disruption = created.objectValue("plan").orElseThrow()
                .arrayValue("stages").orElseThrow().get(0).orElseThrow().asObject()
                .arrayValue("disruptions").orElseThrow().get(0).orElseThrow().asObject();
        JsonObject scope = disruption.objectValue("scope").orElseThrow();
        JsonObject effect = disruption.objectValue("effect").orElseThrow();
        assertThat(scope.stringValue("type").orElseThrow(), is("outbound-http"));
        assertThat(scope.stringValue("scheme").orElseThrow(), is(baseUri.scheme()));
        assertThat(scope.stringValue("host").orElseThrow(), is(baseUri.host()));
        assertThat(scope.longValue("port").orElseThrow(), is((long) baseUri.port()));
        assertThat(scope.objectValue("path").orElseThrow().stringValue("value").orElseThrow(), is("/orders"));
        assertThat(effect.stringValue("type").orElseThrow(), is("latency"));
        assertThat(effect.stringValue("delay").orElseThrow(), is("PT0.1S"));
        assertThat(effect.stringValue("jitter").orElseThrow(), is("PT0S"));

        long started = System.nanoTime();
        var response = application.get("/orders/42").request(String.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity(), is("order"));
        assertThat(elapsed.compareTo(Duration.ofMillis(90)) >= 0, is(true));
        assertThat(APPLICATION_INVOCATIONS.get(), is(1));
        assertOutboundCounters(id, 1);

        var nonMatch = application.get("/orders-old").request(String.class);
        assertThat(nonMatch.status(), is(Status.OK_200));
        assertThat(nonMatch.entity(), is("legacy-order"));
        assertThat(APPLICATION_INVOCATIONS.get(), is(2));
        assertOutboundCounters(id, 1);

        var redirected = application.get("/order-redirect").followRedirects(true).request(String.class);
        assertThat(redirected.status(), is(Status.OK_200));
        assertThat(redirected.entity(), is("order"));
        assertThat(APPLICATION_INVOCATIONS.get(), is(4));
        assertOutboundCounters(id, 2);
    }

    @Test
    void returnsOutboundSyntheticResponseWithoutInvokingDestination() {
        var baseUri = application.prototype().baseUri().orElseThrow();
        JsonObject created = postRun(control,
                                     outboundSyntheticResponsePlan(baseUri.scheme(), baseUri.host(), baseUri.port()));
        String id = created.stringValue("id").orElseThrow();

        var response = application.get("/orders/42").request(String.class);

        assertThat(response.status(), is(Status.SERVICE_UNAVAILABLE_503));
        assertThat(response.headers().first(HeaderNames.RETRY_AFTER).orElseThrow(), is("3"));
        assertThat(response.headers().contentType().orElseThrow().mediaType().text(), is("application/problem+json"));
        assertThat(response.entity(), is("Inventory unavailable"));
        assertThat(APPLICATION_INVOCATIONS.get(), is(0));
        assertOutboundCounters(id, 1);
    }

    @Test
    void throwsOutboundConnectFailureWithoutInvokingDestination() {
        var baseUri = application.prototype().baseUri().orElseThrow();
        JsonObject created = postRun(control,
                                     outboundConnectFailurePlan(baseUri.scheme(), baseUri.host(), baseUri.port()));
        String id = created.stringValue("id").orElseThrow();

        UncheckedIOException exception = assertThrows(UncheckedIOException.class,
                                                      () -> application.get("/orders/42").request(String.class));

        assertThat(exception.getCause(), instanceOf(ConnectException.class));
        assertThat(exception.getCause().getMessage(), is("Connection refused by chaos disruption"));
        assertThat(APPLICATION_INVOCATIONS.get(), is(0));
        assertOutboundCounters(id, 1);
    }

    @Test
    void throwsOutboundDnsFailureWithoutInvokingDestination() {
        var baseUri = application.prototype().baseUri().orElseThrow();
        JsonObject created = postRun(control,
                                     outboundDnsFailurePlan(baseUri.scheme(), baseUri.host(), baseUri.port()));
        String id = created.stringValue("id").orElseThrow();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                           () -> application.get("/orders/42").request(String.class));

        assertThat(exception.getMessage(), is("Failed to get address for host " + baseUri.host()));
        assertThat(APPLICATION_INVOCATIONS.get(), is(0));
        assertOutboundCounters(id, 1);
    }

    @Test
    void throwsOutboundTlsHandshakeFailureWithoutInvokingDestination() {
        var baseUri = application.prototype().baseUri().orElseThrow();
        JsonObject created = postRun(control,
                                     outboundTlsHandshakeFailurePlan(baseUri.host(), baseUri.port()));
        String id = created.stringValue("id").orElseThrow();
        Http1Client httpsClient = Http1Client.builder()
                .baseUri("https://" + baseUri.host() + ":" + baseUri.port())
                .build();

        UncheckedIOException exception = assertThrows(UncheckedIOException.class,
                                                      () -> httpsClient.get("/orders/42").request(String.class));

        assertThat(exception.getMessage(), is("Failed to execute SSL handshake"));
        assertThat(exception.getCause(), instanceOf(SSLHandshakeException.class));
        assertThat(exception.getCause().getMessage(), is("TLS handshake failed due to chaos disruption"));
        assertThat(APPLICATION_INVOCATIONS.get(), is(0));
        assertOutboundCounters(id, 1);
    }

    @Test
    void throwsOutboundResponseTimeoutWithoutInvokingDestination() {
        var baseUri = application.prototype().baseUri().orElseThrow();
        JsonObject created = postRun(control,
                                     outboundResponseTimeoutPlan(baseUri.scheme(),
                                                                 baseUri.host(),
                                                                 baseUri.port(),
                                                                 "PT0.1S"));
        String id = created.stringValue("id").orElseThrow();
        JsonObject effect = created.objectValue("plan").orElseThrow()
                .arrayValue("stages").orElseThrow().get(0).orElseThrow().asObject()
                .arrayValue("disruptions").orElseThrow().get(0).orElseThrow().asObject()
                .objectValue("effect").orElseThrow();
        assertThat(effect.stringValue("type").orElseThrow(), is("response-timeout"));
        assertThat(effect.stringValue("duration").orElseThrow(), is("PT0.1S"));
        assertThat(effect.stringValue("jitter").orElseThrow(), is("PT0S"));
        long started = System.nanoTime();

        UncheckedIOException exception = assertThrows(UncheckedIOException.class,
                                                      () -> application.get("/orders/42").request(String.class));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(exception.getCause(), instanceOf(SocketTimeoutException.class));
        assertThat(exception.getCause().getMessage(), is("Response timed out due to chaos disruption"));
        assertThat(elapsed.compareTo(Duration.ofMillis(90)) >= 0, is(true));
        assertThat(APPLICATION_INVOCATIONS.get(), is(0));
        assertOutboundCounters(id, 1);
    }

    @Test
    void enforcesConfiguredMaximumResponseTimeout() {
        var baseUri = application.prototype().baseUri().orElseThrow();
        JsonObject plan = outboundResponseTimeoutPlan(baseUri.scheme(),
                                                      baseUri.host(),
                                                      baseUri.port(),
                                                      "PT0.151S");

        var response = control.post(RUNS)
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(plan, String.class);

        assertThat(response.status(), is(Status.UNPROCESSABLE_CONTENT_422));
        JsonObject violation = JsonParser.create(response.entity()).readJsonObject()
                .arrayValue("violations").orElseThrow()
                .get(0).orElseThrow().asObject();
        assertThat(violation.stringValue("path").orElseThrow(),
                   is("/stages/0/disruptions/0/effect/duration"));
        assertThat(violation.stringValue("code").orElseThrow(), is("response-timeout-limit"));
    }

    @Test
    void executesDeterministicWeightedChoice() {
        JsonObject plan = runPlan(42,
                                  "stage",
                                  "weighted",
                                  "{\"type\":\"always\"}",
                                  """
                                          {
                                            "type": "weighted-choice",
                                            "outcomes": [
                                              {
                                                "weight": 3,
                                                "effect": {
                                                  "type": "synthetic-http-response",
                                                  "status": 503
                                                }
                                              },
                                              {
                                                "weight": 1,
                                                "effect": {
                                                  "type": "latency",
                                                  "delay": "PT0.001S"
                                                }
                                              }
                                            ]
                                          }
                                          """);
        JsonObject created = postRun(control, plan);
        JsonObject effect = created.objectValue("plan").orElseThrow()
                .arrayValue("stages").orElseThrow().get(0).orElseThrow().asObject()
                .arrayValue("disruptions").orElseThrow().get(0).orElseThrow().asObject()
                .objectValue("effect").orElseThrow();
        JsonArray outcomes = effect.arrayValue("outcomes").orElseThrow();
        assertThat(effect.stringValue("type").orElseThrow(), is("weighted-choice"));
        assertThat(outcomes.size(), is(2));
        assertThat(outcomes.get(0).orElseThrow().asObject().longValue("weight").orElseThrow(), is(3L));
        assertThat(outcomes.get(1).orElseThrow().asObject().objectValue("effect").orElseThrow()
                           .stringValue("jitter").orElseThrow(), is("PT0S"));

        Status[] expected = {
                Status.SERVICE_UNAVAILABLE_503,
                Status.SERVICE_UNAVAILABLE_503,
                Status.SERVICE_UNAVAILABLE_503,
                Status.OK_200,
                Status.OK_200,
                Status.SERVICE_UNAVAILABLE_503,
                Status.SERVICE_UNAVAILABLE_503,
                Status.SERVICE_UNAVAILABLE_503
        };
        for (Status status : expected) {
            assertThat(application.get("/orders/42").request(String.class).status(), is(status));
        }
        assertThat(APPLICATION_INVOCATIONS.get(), is(2));
    }

    @Test
    void executesOrderedStagesAndPassiveRecovery() throws InterruptedException {
        JsonObject created = postRun(control, sequencePlan());
        String id = created.stringValue("id").orElseThrow();
        assertThat(created.objectValue("currentStage").orElseThrow().stringValue("name").orElseThrow(),
                   is("slow-orders"));

        long started = System.nanoTime();
        var slowResponse = application.get("/orders/42").request(String.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(slowResponse.status(), is(Status.OK_200));
        assertThat(elapsed.compareTo(Duration.ofMillis(40)) >= 0, is(true));
        assertThat(APPLICATION_INVOCATIONS.get(), is(1));

        awaitStage(id, "orders-outage");
        assertThat(application.get("/orders/42").request(String.class).status(),
                   is(Status.SERVICE_UNAVAILABLE_503));
        assertThat(APPLICATION_INVOCATIONS.get(), is(1));

        awaitStage(id, "recovery");
        assertThat(application.get("/orders/42").request(String.class).status(), is(Status.OK_200));
        assertThat(APPLICATION_INVOCATIONS.get(), is(2));

        JsonObject completed = awaitState(id, "COMPLETED");
        assertThat(completed.containsKey("currentStage"), is(false));
    }

    private static JsonObject postRun(Http1Client control) {
        return postRun(control, runPlan(148_894, "reject-orders", "orders-503", "{\"type\":\"always\"}"));
    }

    private static JsonObject runPlan(long seed, String stageName, String disruptionName, String activation) {
        return runPlan(seed,
                       stageName,
                       disruptionName,
                       activation,
                       "{\"type\":\"synthetic-http-response\",\"status\":503,\"body\":\"failure\"}");
    }

    private static JsonObject runPlan(long seed,
                                      String stageName,
                                      String disruptionName,
                                      String activation,
                                      String effect) {
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
                      "effect": %s,
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(seed, stageName, disruptionName, activation, effect)).readJsonObject();
    }

    private static JsonObject outboundLatencyPlan(String scheme, String host, int port) {
        return JsonParser.create("""
                {
                  "name": "outbound-orders-latency",
                  "maximumDuration": "PT30S",
                  "seed": 42,
                  "stages": [{
                    "name": "outbound-latency",
                    "duration": "PT10S",
                    "disruptions": [{
                      "name": "slow-outbound-orders",
                      "scope": {
                        "type": "outbound-http",
                        "methods": ["GET"],
                        "scheme": "%s",
                        "host": "%s",
                        "port": %d,
                        "path": {"match": "prefix", "value": "/orders"}
                      },
                      "activation": {"type": "always"},
                      "effect": {"type": "latency", "delay": "PT0.1S"},
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(scheme, host, port)).readJsonObject();
    }

    private static JsonObject outboundSyntheticResponsePlan(String scheme, String host, int port) {
        return JsonParser.create("""
                {
                  "name": "outbound-inventory-unavailable",
                  "maximumDuration": "PT30S",
                  "seed": 42,
                  "stages": [{
                    "name": "outbound-unavailable",
                    "duration": "PT10S",
                    "disruptions": [{
                      "name": "reject-outbound-orders",
                      "scope": {
                        "type": "outbound-http",
                        "methods": ["GET"],
                        "scheme": "%s",
                        "host": "%s",
                        "port": %d,
                        "path": {"match": "exact", "value": "/orders/42"}
                      },
                      "activation": {"type": "always"},
                      "effect": {
                        "type": "synthetic-http-response",
                        "status": 503,
                        "headers": {"Retry-After": "3"},
                        "mediaType": "application/problem+json",
                        "body": "Inventory unavailable"
                      },
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(scheme, host, port)).readJsonObject();
    }

    private static JsonObject outboundConnectFailurePlan(String scheme, String host, int port) {
        return JsonParser.create("""
                {
                  "name": "outbound-inventory-connect-failure",
                  "maximumDuration": "PT30S",
                  "seed": 42,
                  "stages": [{
                    "name": "outbound-connect-failure",
                    "duration": "PT10S",
                    "disruptions": [{
                      "name": "fail-outbound-orders-connect",
                      "scope": {
                        "type": "outbound-http",
                        "methods": ["GET"],
                        "scheme": "%s",
                        "host": "%s",
                        "port": %d,
                        "path": {"match": "exact", "value": "/orders/42"}
                      },
                      "activation": {"type": "always"},
                      "effect": {"type": "connect-failure"},
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(scheme, host, port)).readJsonObject();
    }

    private static JsonObject outboundDnsFailurePlan(String scheme, String host, int port) {
        return JsonParser.create("""
                {
                  "name": "outbound-inventory-dns-failure",
                  "maximumDuration": "PT30S",
                  "seed": 42,
                  "stages": [{
                    "name": "outbound-dns-failure",
                    "duration": "PT10S",
                    "disruptions": [{
                      "name": "fail-outbound-orders-dns",
                      "scope": {
                        "type": "outbound-http",
                        "methods": ["GET"],
                        "scheme": "%s",
                        "host": "%s",
                        "port": %d,
                        "path": {"match": "exact", "value": "/orders/42"}
                      },
                      "activation": {"type": "always"},
                      "effect": {"type": "dns-failure"},
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(scheme, host, port)).readJsonObject();
    }

    private static JsonObject outboundTlsHandshakeFailurePlan(String host, int port) {
        return JsonParser.create("""
                {
                  "name": "outbound-inventory-tls-handshake-failure",
                  "maximumDuration": "PT30S",
                  "seed": 42,
                  "stages": [{
                    "name": "outbound-tls-handshake-failure",
                    "duration": "PT10S",
                    "disruptions": [{
                      "name": "fail-outbound-orders-tls-handshake",
                      "scope": {
                        "type": "outbound-http",
                        "methods": ["GET"],
                        "scheme": "https",
                        "host": "%s",
                        "port": %d,
                        "path": {"match": "exact", "value": "/orders/42"}
                      },
                      "activation": {"type": "always"},
                      "effect": {"type": "tls-handshake-failure"},
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(host, port)).readJsonObject();
    }

    private static JsonObject outboundResponseTimeoutPlan(String scheme,
                                                          String host,
                                                          int port,
                                                          String duration) {
        return JsonParser.create("""
                {
                  "name": "outbound-inventory-response-timeout",
                  "maximumDuration": "PT30S",
                  "seed": 42,
                  "stages": [{
                    "name": "outbound-response-timeout",
                    "duration": "PT10S",
                    "disruptions": [{
                      "name": "timeout-outbound-orders-response",
                      "scope": {
                        "type": "outbound-http",
                        "methods": ["GET"],
                        "scheme": "%s",
                        "host": "%s",
                        "port": %d,
                        "path": {"match": "exact", "value": "/orders/42"}
                      },
                      "activation": {"type": "always"},
                      "effect": {"type": "response-timeout", "duration": "%s"},
                      "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                    }]
                  }]
                }
                """.formatted(scheme, host, port, duration)).readJsonObject();
    }

    private static JsonObject sequencePlan() {
        return JsonParser.create("""
                {
                  "name": "orders-sequence",
                  "maximumDuration": "PT5S",
                  "seed": 42,
                  "stages": [
                    {
                      "name": "slow-orders",
                      "duration": "PT1S",
                      "disruptions": [{
                        "name": "orders-latency",
                        "scope": {
                          "type": "inbound-http",
                          "methods": ["GET"],
                          "path": {"match": "prefix", "value": "/orders"}
                        },
                        "activation": {"type": "always"},
                        "effect": {"type": "latency", "delay": "PT0.05S"},
                        "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                      }]
                    },
                    {
                      "name": "orders-outage",
                      "duration": "PT1S",
                      "disruptions": [{
                        "name": "orders-503",
                        "scope": {
                          "type": "inbound-http",
                          "methods": ["GET"],
                          "path": {"match": "prefix", "value": "/orders"}
                        },
                        "activation": {"type": "always"},
                        "effect": {"type": "synthetic-http-response", "status": 503},
                        "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
                      }]
                    },
                    {
                      "name": "recovery",
                      "duration": "PT1S",
                      "disruptions": []
                    }
                  ]
                }
                """).readJsonObject();
    }

    private JsonObject awaitStage(String id, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        JsonObject run;
        do {
            run = control.get(RUNS + "/" + id).request(JsonObject.class).entity();
            String current = run.objectValue("currentStage")
                    .flatMap(stage -> stage.stringValue("name"))
                    .orElse("");
            if (expected.equals(current)) {
                return run;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Run did not reach stage " + expected + ": " + run);
    }

    private JsonObject awaitState(String id, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        JsonObject run;
        do {
            run = control.get(RUNS + "/" + id).request(JsonObject.class).entity();
            if (expected.equals(run.stringValue("state").orElseThrow())) {
                return run;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Run did not reach state " + expected + ": " + run);
    }

    private static JsonObject postRun(Http1Client control, JsonObject plan) {
        var response = control.post(RUNS)
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(plan, JsonObject.class);
        assertThat(response.status(), is(Status.CREATED_201));
        return response.entity();
    }

    private void assertOutboundCounters(String id, long expected) {
        JsonObject counters = control.get(RUNS + "/" + id).request(JsonObject.class).entity()
                .objectValue("counters").orElseThrow();
        assertThat(counters.longValue("matched").orElseThrow(), is(expected));
        assertThat(counters.longValue("activated").orElseThrow(), is(expected));
        assertThat(counters.longValue("completed").orElseThrow(), is(expected));
        assertThat(counters.longValue("inFlight").orElseThrow(), is(0L));
    }
}
