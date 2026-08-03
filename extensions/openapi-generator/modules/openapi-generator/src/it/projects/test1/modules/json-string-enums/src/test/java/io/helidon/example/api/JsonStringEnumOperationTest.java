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

import io.helidon.json.binding.JsonBinding;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonStringEnumOperationTest {

    @Test
    void exactHttpMapperUsesWireValue() {
        var mapper = new EnumsApiInspectModeRouteModeEnumJsonConverter();

        assertThat(mapper.map("fast-mode"), is(EnumsApi.InspectModeRouteModeEnum.FAST_MODE));
        assertThat(mapper.map("authZ"), is(EnumsApi.InspectModeRouteModeEnum.AUTH_Z));
        assertThat(mapper.map("authZ").toString(), is("authZ"));
        assertThrows(IllegalArgumentException.class, () -> mapper.map("AUTH_Z"));
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
