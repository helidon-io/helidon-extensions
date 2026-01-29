/*
 * Copyright (c) 2021, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.neo4j.examples.declarative;

import io.helidon.http.Status;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webclient.http1.Http1ClientResponse;
import io.helidon.webserver.testing.junit5.ServerTest;

import jakarta.json.JsonArray;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Main test class for Neo4j Helidon SE application.
 */
@ServerTest
public class MainTest {

    private final Http1Client webClient;

    public MainTest(Http1Client webClient) {
        this.webClient = webClient;
    }

    @Test
    public void testHealth() {
        try (Http1ClientResponse response = webClient.get("/observe/health").request()) {
            assertThat(response.status(), is(Status.NO_CONTENT_204));
        }
    }

    @Test
    void testMovies() {
        JsonArray result = webClient.get("/api/movies").requestEntity(JsonArray.class);
        assertThat(result.getJsonObject(0).getString("title"), containsString("The Matrix"));
    }
}