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
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.testing.junit5.ServerTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@ServerTest
class CascadingValidationHttpTest {

    private final Http1Client client;

    CascadingValidationHttpTest(Http1Client client) {
        this.client = client;
    }

    @Test
    void invalidNestedRequestIsRejectedBeforeEndpointLogic() {
        try (var response = client.post("/validate")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("{\"intermediate\":{\"leaf\":{\"code\":\"x\",\"label\":\"y\"}}}")) {
            assertThat(response.status(), is(Status.BAD_REQUEST_400));
        }
    }

    @Test
    void validNestedRequestReachesGeneratedEndpointLogic() {
        try (var response = client.post("/validate")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("{\"intermediate\":{\"leaf\":{\"code\":\"valid\",\"label\":\"valid\"}}}")) {
            // The generated endpoint stub deliberately throws UnsupportedOperationException.
            assertThat(response.status(), is(Status.INTERNAL_SERVER_ERROR_500));
        }
    }
}
