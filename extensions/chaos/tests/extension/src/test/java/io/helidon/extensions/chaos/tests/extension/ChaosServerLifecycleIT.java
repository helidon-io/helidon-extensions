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

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.http.Status;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class ChaosServerLifecycleIT {
    private static final String CONTROL_SOCKET = "chaos-control";
    private static final String RUNS = "/chaos/v1/runs";

    @Test
    void restartDoesNotReconstructRuns() {
        // This contract requires two distinct server lifecycles; @ServerTest owns one per test class.
        WebServer first = startServer();
        Http1Client firstControl = client(first.port(CONTROL_SOCKET));
        try {
            postRun(firstControl);
            assertThat(firstControl.get(RUNS).request(JsonArray.class).entity().size(), is(1));
        } finally {
            firstControl.closeResource();
            first.stop();
        }

        WebServer second = startServer();
        Http1Client secondControl = client(second.port(CONTROL_SOCKET));
        try {
            assertThat(secondControl.get(RUNS).request(JsonArray.class).entity().size(), is(0));
        } finally {
            secondControl.closeResource();
            second.stop();
        }
    }

    private static WebServer startServer() {
        Config config = Config.just(ConfigSources.classpath("application.yaml"));
        return WebServer.builder()
                .config(config.get("server"))
                .build()
                .start();
    }

    private static Http1Client client(int port) {
        return Http1Client.builder()
                .baseUri("http://127.0.0.1:" + port)
                .build();
    }

    private static void postRun(Http1Client control) {
        var response = control.post(RUNS)
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(plan(), JsonObject.class);
        assertThat(response.status(), is(Status.CREATED_201));
    }

    private static JsonObject plan() {
        return JsonParser.create("""
                {
                  "name": "restart-contract",
                  "maximumDuration": "PT30S",
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
                      "effect": {"type": "synthetic-http-response", "status": 503},
                      "budget": {"maximumActivations": 1, "maximumConcurrent": 1}
                    }]
                  }]
                }
                """).readJsonObject();
    }
}
