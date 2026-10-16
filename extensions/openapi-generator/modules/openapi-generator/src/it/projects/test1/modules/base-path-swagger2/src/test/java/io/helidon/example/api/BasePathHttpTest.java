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

import io.helidon.http.Status;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.testing.junit5.ServerTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Exercises annotation-processed routes with a spec base path, including shared and root paths.
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
        for (String path : new String[] {"/v1/widgets", "/v1/widgets/widget-1", "/v1/health", "/v1/ready", "/v1"}) {
            try (var response = client.get(path).request()) {
                assertThat(path, response.status(), is(Status.INTERNAL_SERVER_ERROR_500));
            }
        }
    }

    /**
     * Dropping or applying the spec prefix twice must not expose an alternate route.
     */
    @Test
    void unprefixedAndDoublePrefixedRoutesAreAbsent() {
        for (String path : new String[] {"/widgets", "/widgets/widget-1", "/health", "/ready", "/v1/v1/widgets"}) {
            try (var response = client.get(path).request()) {
                assertThat(path, response.status(), is(Status.NOT_FOUND_404));
            }
        }
    }
}
