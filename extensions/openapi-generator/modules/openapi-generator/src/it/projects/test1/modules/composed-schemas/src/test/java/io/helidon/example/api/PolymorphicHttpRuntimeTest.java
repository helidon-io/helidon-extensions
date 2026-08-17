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

package io.helidon.example.api;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.example.model.Pet;
import io.helidon.http.Status;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@ServerTest
class PolymorphicHttpRuntimeTest {

    private final Http1Client client;

    PolymorphicHttpRuntimeTest(Http1Client client) {
        this.client = client;
    }

    @SetUpRoute
    static void setupRoute(HttpRouting.Builder routing) {
        routing.post("/polymorphic-echo", (request, response) ->
                response.send(request.content().as(Pet.class)));
    }

    @Test
    void generatedPolymorphicModelCrossesHttpRequestAndResponseBoundaries() {
        try (var response = client.post("/polymorphic-echo")
                .contentType(MediaTypes.APPLICATION_JSON)
                .accept(MediaTypes.APPLICATION_JSON)
                .submit("{\"kind\":\"cat$special\",\"whiskers\":7}")) {
            assertThat(response.status(), is(Status.OK_200));
            String json = response.as(String.class);
            assertThat(json, containsString("\"kind\":\"cat$special\""));
            assertThat(json, containsString("\"whiskers\":7"));
            assertThat(occurrences(json, "\"kind\""), is(1));
        }
    }

    private static int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
