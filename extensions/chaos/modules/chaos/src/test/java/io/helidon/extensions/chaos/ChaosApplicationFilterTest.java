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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webclient.api.ClientResponseTyped;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.EXACT;
import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.PREFIX;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ServerTest
class ChaosApplicationFilterTest {
    private static final AtomicInteger APPLICATION_INVOCATIONS = new AtomicInteger();
    private static final HeaderName APPLICATION_HEADER = HeaderNames.create("X-Application");
    private static ChaosRunEngine engine;

    private final WebClient client;

    ChaosApplicationFilterTest(WebClient client) {
        this.client = client;
    }

    @SetUpRoute
    static void setUpRoute(HttpRouting.Builder routing) {
        engine = ChaosRunEngine.create(ChaosLimitsConfig.builder().build());
        routing.addFilter(new ChaosApplicationFilter(engine))
                .get("/orders/42", ChaosApplicationFilterTest::application)
                .get("/orders-old", ChaosApplicationFilterTest::application)
                .post("/orders/42", ChaosApplicationFilterTest::application);
    }

    @BeforeEach
    void resetRuns() {
        engine.list().stream()
                .filter(run -> run.state() == ChaosRunState.RUNNING)
                .forEach(run -> engine.stop(run.id()));
        APPLICATION_INVOCATIONS.set(0);
    }

    @Test
    void prefixMatchSendsSyntheticResponseWithoutInvokingApplication() {
        engine.create(plan(PREFIX, "/orders", Set.of("GET"), 20, 2), "alice");

        ClientResponseTyped<byte[]> disrupted = client.get("/orders/42").request(byte[].class);
        assertThat(disrupted.status(), is(Status.SERVICE_UNAVAILABLE_503));
        assertThat(disrupted.entity(), is("failure".getBytes(StandardCharsets.UTF_8)));
        assertThat(disrupted.headers().first(HeaderNames.RETRY_AFTER).orElseThrow(), is("1"));
        assertThat(disrupted.headers().contentType().orElseThrow().mediaType(), is(MediaTypes.TEXT_PLAIN));
        assertThat(disrupted.headers().contentLength().orElseThrow(), is(7L));
        disrupted.close();
        assertThat(APPLICATION_INVOCATIONS.get(), is(0));

        ClientResponseTyped<String> adjacent = client.get("/orders-old").request(String.class);
        assertThat(adjacent.status(), is(Status.OK_200));
        assertThat(adjacent.entity(), is("application"));
        adjacent.close();
        assertThat(APPLICATION_INVOCATIONS.get(), is(1));
    }

    @Test
    void exactMatchDoesNotSelectChildPath() {
        engine.create(plan(EXACT, "/orders", Set.of("GET"), 20, 2), "alice");

        ClientResponseTyped<String> response = client.get("/orders/42").request(String.class);
        assertThat(response.status(), is(Status.OK_200));
        response.close();
        assertThat(APPLICATION_INVOCATIONS.get(), is(1));
    }

    @Test
    void nonMatchingMethodReachesApplication() {
        engine.create(plan(PREFIX, "/orders", Set.of("GET"), 20, 2), "alice");

        ClientResponseTyped<String> response = client.post("/orders/42").request(String.class);
        assertThat(response.status(), is(Status.OK_200));
        response.close();
        assertThat(APPLICATION_INVOCATIONS.get(), is(1));
    }

    @Test
    void exhaustedActivationBudgetFallsThrough() {
        ChaosRunView created = engine.create(plan(PREFIX, "/orders", Set.of("GET"), 1, 1), "alice");

        ClientResponseTyped<String> first = client.get("/orders/42").request(String.class);
        assertThat(first.status(), is(Status.SERVICE_UNAVAILABLE_503));
        first.close();
        ClientResponseTyped<String> second = client.get("/orders/42").request(String.class);
        assertThat(second.status(), is(Status.OK_200));
        second.close();

        ChaosRunView view = engine.get(created.id()).orElseThrow();
        assertThat(view.matched(), is(2L));
        assertThat(view.activated(), is(1L));
        assertThat(view.skippedBudget(), is(1L));
        assertThat(view.completed(), is(1L));
        assertThat(APPLICATION_INVOCATIONS.get(), is(1));
    }

    private static void application(ServerRequest request, ServerResponse response) {
        APPLICATION_INVOCATIONS.incrementAndGet();
        response.header(APPLICATION_HEADER, "reached");
        response.send("application");
    }

    private static ChaosRunPlan plan(ChaosHttpScope.PathMatch pathMatch,
                                     String path,
                                     Set<String> methods,
                                     long maximumActivations,
                                     int maximumConcurrent) {
        ChaosSyntheticResponse effect = new ChaosSyntheticResponse(503,
                                                                    Map.of("Retry-After", "1"),
                                                                    Optional.of(MediaTypes.TEXT_PLAIN),
                                                                    "failure".getBytes(StandardCharsets.UTF_8));
        return plan(pathMatch, path, methods, maximumActivations, maximumConcurrent, effect, "synthetic");
    }

    private static ChaosRunPlan plan(ChaosHttpScope.PathMatch pathMatch,
                                     String path,
                                     Set<String> methods,
                                     long maximumActivations,
                                     int maximumConcurrent,
                                     ChaosEffect effect,
                                     String disruptionName) {
        ChaosHttpScope scope = new ChaosHttpScope(methods, pathMatch, path);
        ChaosBudget budget = new ChaosBudget(maximumActivations, maximumConcurrent);
        ChaosRunPlan.ChaosDisruption disruption =
                new ChaosRunPlan.ChaosDisruption(disruptionName, scope, ChaosActivation.always(), effect, budget);
        ChaosRunPlan.ChaosStage stage =
                new ChaosRunPlan.ChaosStage("stage", Duration.ofSeconds(30), disruption);
        return new ChaosRunPlan("run", Duration.ofMinutes(1), 42, stage);
    }
}
