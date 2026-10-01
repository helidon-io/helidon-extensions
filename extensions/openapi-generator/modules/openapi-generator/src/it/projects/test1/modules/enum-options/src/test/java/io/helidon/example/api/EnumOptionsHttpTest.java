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

@ServerTest
class EnumOptionsHttpTest {
    private final Http1Client client;

    EnumOptionsHttpTest(Http1Client client) {
        this.client = client;
    }

    @Test
    void mixedCaseModelAndParameterEnumsReachResource() {
        try (var response = client.get("/probe").queryParam("mode", "AUTHZ")
                .queryParam("sortBy", "DISPLAYNAME").request()) {
            assertThat(response.status(), is(Status.OK_200));
            assertThat(response.as(String.class), is("authZ:displayName"));
        }
    }

    @Test
    void omittedEnumsReachResource() {
        try (var response = client.get("/probe").request()) {
            assertThat(response.status(), is(Status.OK_200));
            assertThat(response.as(String.class), is("absent:absent"));
        }
    }

    @Test
    void invalidEnumsAreRejected() {
        for (String parameter : new String[] {"mode", "sortBy"}) {
            try (var response = client.get("/probe").queryParam(parameter, "unknown").request()) {
                assertThat(response.status(), is(Status.BAD_REQUEST_400));
            }
        }
    }
}
