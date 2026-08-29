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

import java.util.Map;

import io.helidon.config.Config;
import io.helidon.extensions.chaos.ChaosConfig;
import io.helidon.extensions.chaos.ChaosServerFeature;
import io.helidon.http.Status;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import io.helidon.webserver.testing.junit5.SetUpServer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ServerTest
class ChaosDisabledFeatureIT {
    private static final String RUNS = "/chaos/v1/runs";

    private final Http1Client client;
    private final WebServer server;

    ChaosDisabledFeatureIT(Http1Client client, WebServer server) {
        this.client = client;
        this.server = server;
    }

    @SetUpServer
    static void setUpServer(WebServerConfig.Builder server) {
        server.config(Config.empty())
                .sockets(Map.of())
                .featuresDiscoverServices(false)
                .addFeature(ChaosServerFeature.create(ChaosConfig.builder().buildPrototype()));
    }

    @SetUpRoute
    static void setUpRoute(HttpRouting.Builder routing) {
        routing.get("/ready", (request, response) -> response.send("ready"));
    }

    @Test
    void disabledFeatureDoesNotRequireChaosSockets() {
        assertThat(client.get("/ready").request().status(), is(Status.OK_200));
        assertThat(client.get(RUNS).request().status(), is(Status.NOT_FOUND_404));
        assertThat(server.prototype().sockets().containsKey("chaos-control"), is(false));
    }
}
