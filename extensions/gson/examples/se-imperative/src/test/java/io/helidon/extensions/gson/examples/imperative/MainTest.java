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

package io.helidon.extensions.gson.examples.imperative;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.http.Status;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpServer;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@ServerTest
class MainTest {
    private static final String RESET_GREETING = "{\"greeting\":\"Hello\"}";
    private static final String UPDATED_GREETING = "{\"greeting\":\"Ahoj\",\"punctuation\":\"!\"}";

    private final Http1Client client;

    MainTest(Http1Client client) {
        this.client = client;
    }

    @SetUpServer
    static void configureServer(WebServerConfig.Builder server) {
        Main.configureServer(server, Config.create());
    }

    @Test
    void testHelloWorld() {
        var response = client.get("/hello")
                .accept(MediaTypes.APPLICATION_JSON)
                .request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity(), is("{\"greeting\":\"Hello\",\"name\":\"World\",\"punctuation\":null}"));
    }

    @Test
    void testHelloNamed() {
        var response = client.get("/hello/Reader")
                .accept(MediaTypes.APPLICATION_JSON)
                .request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity(), is("{\"greeting\":\"Hello\",\"name\":\"Reader\",\"punctuation\":null}"));
    }

    @Test
    void testUpdateGreeting() {
        try {
            var updateResponse = client.post("/hello")
                    .contentType(MediaTypes.APPLICATION_JSON)
                    .accept(MediaTypes.APPLICATION_JSON)
                    .submit(UPDATED_GREETING, String.class);

            assertThat(updateResponse.status(), is(Status.OK_200));
            assertThat(updateResponse.entity(), is("{\"greeting\":\"Ahoj\",\"name\":\"World\",\"punctuation\":\"!\"}"));

            var helloResponse = client.get("/hello/Reader")
                    .accept(MediaTypes.APPLICATION_JSON)
                    .request(String.class);

            assertThat(helloResponse.status(), is(Status.OK_200));
            assertThat(helloResponse.entity(), is("{\"greeting\":\"Ahoj\",\"name\":\"Reader\",\"punctuation\":\"!\"}"));
        } finally {
            client.post("/hello")
                    .contentType(MediaTypes.APPLICATION_JSON)
                    .accept(MediaTypes.APPLICATION_JSON)
                    .submit(RESET_GREETING)
                    .close();
        }
    }
}
