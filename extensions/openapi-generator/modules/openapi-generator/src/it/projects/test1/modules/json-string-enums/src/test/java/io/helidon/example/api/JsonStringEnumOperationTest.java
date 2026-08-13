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

import io.helidon.common.GenericType;
import io.helidon.common.mapper.Mappers;
import io.helidon.http.BadRequestException;
import io.helidon.http.Status;
import io.helidon.json.binding.JsonBinding;
import io.helidon.service.registry.Services;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ServerTest
class JsonStringEnumOperationTest {
    private final Http1Client client;

    JsonStringEnumOperationTest(Http1Client client) {
        this.client = client;
    }

    @SetUpRoute
    static void setupRoute(HttpRouting.Builder routing) {
        var mappers = Services.get(Mappers.class);
        var targetType = GenericType.create(EnumsApi.InspectModeRouteModeEnum.class);
        routing.post("/mapped-enum/{value}", (request, response) -> {
            var value = request.path().pathParameters().first("value")
                    .map(it -> mappers.map(it,
                                           GenericType.STRING,
                                           targetType,
                                           failure -> new BadRequestException("Invalid enum value.", failure),
                                           "http",
                                           "path"))
                    .orElseThrow();
            response.send(value.toString());
        });
    }

    @Test
    void exactHttpMapperUsesWireValue() {
        var mapper = new EnumsApiInspectModeRouteModeEnumJsonConverter();

        assertThat(mapper.map("fast-mode"), is(EnumsApi.InspectModeRouteModeEnum.FAST_MODE));
        assertThat(mapper.map("authZ"), is(EnumsApi.InspectModeRouteModeEnum.AUTH_Z));
        assertThat(mapper.map("authZ").toString(), is("authZ"));
        assertThrows(IllegalArgumentException.class, () -> mapper.map("AUTH_Z"));
        assertThrows(NullPointerException.class, () -> mapper.map(null));
    }

    @Test
    void invalidHttpEnumValueIsRejectedAsBadRequest() {
        try (var response = client.post("/mapped-enum/not-a-mode").request()) {
            assertThat(response.status(), is(Status.BAD_REQUEST_400));
        }
    }

    @Test
    void requestEntityRoundTripsWithJsonConverterButNoHttpMapper() {
        JsonBinding jsonBinding = JsonBinding.create();

        assertThat(jsonBinding.serialize(EnumsApi.InspectModeBodyEnum.REQUEST_QUICK,
                                         EnumsApi.InspectModeBodyEnum.class),
                   is("\"request-quick\""));
        assertThat(jsonBinding.deserialize("\"requestAuthZ\"", EnumsApi.InspectModeBodyEnum.class),
                   is(EnumsApi.InspectModeBodyEnum.REQUEST_AUTH_Z));
    }
}
