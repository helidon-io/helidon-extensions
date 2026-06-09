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

package io.helidon.extensions.toml.examples.webserver;

import io.helidon.config.Config;
import io.helidon.config.ConfigValues;
import io.helidon.http.Status;
import io.helidon.service.registry.Services;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ServerTest
class MainTest {
    private final Http1Client client;

    MainTest(Http1Client client) {
        this.client = client;
    }

    @SetUpRoute
    static void setupRoute(HttpRouting.Builder routing) {
        Main.routing(Services.get(Config.class), routing);
    }

    @Test
    void testHello() {
        var response = client.get("/hello").request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity(), is("Hello from test TOML"));
    }

    @Test
    void testApplicationToml() {
        Config config = Config.create();

        assertThat(config.get("server.host").asString(), is(ConfigValues.simpleValue("localhost")));
        assertThat(config.get("server.port").asInt(), is(ConfigValues.simpleValue(8080)));
        assertThat(config.get("app.greeting").asString(), is(ConfigValues.simpleValue("Hello from test TOML")));
    }
}
