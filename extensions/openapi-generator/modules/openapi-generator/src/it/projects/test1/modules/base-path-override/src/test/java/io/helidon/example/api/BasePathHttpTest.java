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
import io.helidon.http.Status;
import io.helidon.json.JsonParser;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.testing.junit5.ServerTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Exercises an explicit prefix containing a reserved character across all server scopes.
 */
@ServerTest
class BasePathHttpTest {
    private final Http1Client client;

    /**
     * Uses the HTTP client connected to the generated application.
     *
     * @param client client for the test server
     */
    BasePathHttpTest(Http1Client client) {
        this.client = client;
    }

    /**
     * Prefixed requests must reach the generated stubs, whose bodies deliberately throw.
     * A missing route would return 404 instead of the stub's 500 response.
     * The configured prefix replaces global, path-item, and operation-level server paths.
     */
    @Test
    void prefixedRoutesReachGeneratedEndpoints() {
        for (String path : new String[] {
                "/custom&v2/widgets", "/custom&v2/widgets/widget-1", "/custom&v2/health", "/custom&v2/ready", "/custom&v2",
                "/custom&v2/global", "/custom&v2/path", "/custom&v2/operation", "/custom&v2/reserved"}) {
            try (var response = client.get(path).request()) {
                assertThat(path, response.status(), is(Status.INTERNAL_SERVER_ERROR_500));
            }
        }
    }

    /**
     * The HTTP OpenAPI endpoint must advertise the same prefix that reaches generated handlers.
     */
    @Test
    void publishedOpenApiMatchesRoutes() {
        try (var response = client.get("/openapi").accept(MediaTypes.APPLICATION_JSON).request()) {
            assertThat(response.status(), is(Status.OK_200));
            var document = JsonParser.create(response.as(String.class)).readJsonObject();
            String server = document.arrayValue("servers").orElseThrow().get(0).orElseThrow()
                    .asObject().stringValue("url").orElseThrow();
            assertThat(server, is("/custom&v2"));
            try (var route = client.get(server + "/widgets").request()) {
                assertThat(route.status(), is(Status.INTERNAL_SERVER_ERROR_500));
            }
        }
    }

    /**
     * Original, repeated, and HTML-escaped prefixes must not expose alternate routes.
     */
    @Test
    void unprefixedAndDoublePrefixedRoutesAreAbsent() {
        for (String path : new String[] {
                "/widgets", "/widgets/widget-1", "/health", "/ready", "/api/v0/widgets", "/custom&v2/custom&v2/widgets",
                "/custom&amp;v2/widgets", "/path", "/a&b/operation", "/a&b/reserved"}) {
            try (var response = client.get(path).request()) {
                assertThat(path, response.status(), is(Status.NOT_FOUND_404));
            }
        }
    }
}
