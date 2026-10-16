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
 * Exercises inherited server paths, scoped overrides, and reserved characters in generated routes.
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
     */
    @Test
    void prefixedRoutesReachGeneratedEndpoints() {
        for (String path : new String[] {
                "/api/v1/widgets", "/api/v1/widgets/widget-1", "/api/v1/health", "/api/v1/ready", "/api/v1"}) {
            try (var response = client.get(path).request()) {
                assertThat(path, response.status(), is(Status.INTERNAL_SERVER_ERROR_500));
            }
        }
    }

    /**
     * Scoped prefixes must reach their stubs even when a tag contains several different prefixes.
     * Reserved characters must survive both class-level and method-level path annotations.
     */
    @Test
    void scopedAndReservedRoutesReachGeneratedEndpoints() {
        for (String path : new String[] {"/api/v1/global", "/path", "/a&b/operation", "/a&b/reserved"}) {
            try (var response = client.get(path).request()) {
                assertThat(path, response.status(), is(Status.INTERNAL_SERVER_ERROR_500));
            }
        }
    }

    /**
     * Published server variables must advertise the effective configured value that reaches a handler.
     */
    @Test
    void publishedServerVariablesMatchRoutes() {
        try (var response = client.get("/openapi").accept(MediaTypes.APPLICATION_JSON).request()) {
            assertThat(response.status(), is(Status.OK_200));
            var document = JsonParser.create(response.as(String.class)).readJsonObject();
            var server = document.arrayValue("servers").orElseThrow().get(0).orElseThrow().asObject();
            var version = server.objectValue("variables").orElseThrow().objectValue("version").orElseThrow();
            String value = version.stringValue("default").orElseThrow();
            assertThat(value, is("v1"));
            String url = server.stringValue("url").orElseThrow().replace("{version}", value);
            try (var route = client.get("/" + url + "widgets").request()) {
                assertThat(route.status(), is(Status.INTERNAL_SERVER_ERROR_500));
            }
        }
    }

    /**
     * Dropping or applying the spec prefix twice must not expose an alternate route.
     */
    @Test
    void unprefixedAndDoublePrefixedRoutesAreAbsent() {
        for (String path : new String[] {
                "/widgets", "/widgets/widget-1", "/health", "/ready", "/api/v1/api/v1/widgets",
                "/api/v0/widgets", "/api/v1/path", "/api/v1/operation", "/unused/operation", "/a&amp;b/reserved"}) {
            try (var response = client.get(path).request()) {
                assertThat(path, response.status(), is(Status.NOT_FOUND_404));
            }
        }
    }
}
