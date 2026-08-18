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
class MappedRequestValidationHttpTest {

    private final Http1Client client;

    MappedRequestValidationHttpTest(Http1Client client) {
        this.client = client;
    }

    @Test
    void invalidMappedArrayRequestIsRejected() {
        try (var response = client.post("/validate-array")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("[{\"code\":\"x\"}]")) {
            assertThat(response.status(), is(Status.BAD_REQUEST_400));
        }
    }

    @Test
    void validMappedArrayRequestReachesEndpointLogic() {
        try (var response = client.post("/validate-array")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("[{\"code\":\"valid\"}]")) {
            assertThat(response.status(), is(Status.INTERNAL_SERVER_ERROR_500));
        }
    }

    @Test
    void invalidTopLevelMappedOptionalRequestIsRejected() {
        try (var response = client.post("/validate-optional")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("{\"code\":\"x\"}")) {
            assertThat(response.status(), is(Status.BAD_REQUEST_400));
        }
    }

    @Test
    void validTopLevelMappedOptionalRequestReachesEndpointLogic() {
        try (var response = client.post("/validate-optional")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("{\"code\":\"valid\"}")) {
            assertThat(response.status(), is(Status.INTERNAL_SERVER_ERROR_500));
        }
    }

    @Test
    void invalidOptionalInsideMappedRequestDtoIsRejected() {
        try (var response = client.post("/validate-mapped-containers")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("{\"optionalLeaf\":{\"code\":\"x\"},"
                                + "\"leafArray\":[{\"code\":\"valid\"}]}")) {
            assertThat(response.status(), is(Status.BAD_REQUEST_400));
        }
    }

    @Test
    void invalidArrayInsideMappedRequestDtoIsRejected() {
        try (var response = client.post("/validate-mapped-containers")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("{\"optionalLeaf\":{\"code\":\"valid\"},"
                                + "\"leafArray\":[{\"code\":\"x\"}]}")) {
            assertThat(response.status(), is(Status.BAD_REQUEST_400));
        }
    }

    @Test
    void validMappedRequestDtoReachesEndpointLogic() {
        try (var response = client.post("/validate-mapped-containers")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit("{\"optionalLeaf\":{\"code\":\"valid\"},"
                                + "\"leafArray\":[{\"code\":\"valid\"}]}")) {
            assertThat(response.status(), is(Status.INTERNAL_SERVER_ERROR_500));
        }
    }
}
