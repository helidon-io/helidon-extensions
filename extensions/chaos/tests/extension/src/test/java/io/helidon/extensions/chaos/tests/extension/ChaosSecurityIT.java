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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webclient.http1.Http1ClientRequest;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpServer;
import io.helidon.webserver.testing.junit5.Socket;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ServerTest
class ChaosSecurityIT {

    private static final String CONTROL_SOCKET = "chaos-control";
    private static final String RUNS = "/chaos/v1/runs";

    private final Http1Client control;

    ChaosSecurityIT(@Socket(CONTROL_SOCKET) Http1Client control) {
        this.control = control;
    }

    @SetUpServer
    static void setUpServer(WebServerConfig.Builder server) {
        Config config = Config.just(ConfigSources.classpath("security-application.yaml"));
        server.config(config.get("server"));
    }

    @Test
    void protectsEveryControlRouteWithAuthenticationAndOperatorRole() {
        assertAccess(() -> control.get(RUNS), Status.OK_200);
        JsonObject created = assertCreateAccess(control);
        assertThat(created.stringValue("actor").orElseThrow(), is("operator"));
        String run = RUNS + "/" + created.stringValue("id").orElseThrow();

        assertAccess(() -> control.get(run), Status.OK_200);
        assertAccess(() -> control.delete(run), Status.OK_200);
    }

    private static void assertAccess(Supplier<Http1ClientRequest> request, Status operatorStatus) {
        assertThat(request.get().request().status(), is(Status.UNAUTHORIZED_401));
        assertThat(observer(request.get()).request().status(), is(Status.FORBIDDEN_403));
        assertThat(operator(request.get()).request().status(), is(operatorStatus));
    }

    private static JsonObject assertCreateAccess(Http1Client control) {
        assertThat(control.post(RUNS)
                           .contentType(MediaTypes.APPLICATION_JSON)
                           .submit(plan())
                           .status(),
                   is(Status.UNAUTHORIZED_401));
        assertThat(observer(control.post(RUNS).contentType(MediaTypes.APPLICATION_JSON))
                           .submit(plan())
                           .status(),
                   is(Status.FORBIDDEN_403));
        var response = operator(control.post(RUNS).contentType(MediaTypes.APPLICATION_JSON))
                .submit(plan(), String.class);
        assertThat(response.status(), is(Status.CREATED_201));
        return JsonParser.create(response.entity()).readJsonObject();
    }

    private static Http1ClientRequest observer(Http1ClientRequest request) {
        return basic(request, "observer", "observer-password");
    }

    private static Http1ClientRequest operator(Http1ClientRequest request) {
        return basic(request, "operator", "operator-password");
    }

    private static Http1ClientRequest basic(Http1ClientRequest request, String login, String password) {
        String value = Base64.getEncoder()
                .encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        return request.header(HeaderNames.AUTHORIZATION, "Basic " + value);
    }

    private static JsonObject plan() {
        return JsonParser.create("""
                {
                  "name": "security-contract",
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
