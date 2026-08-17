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

package io.helidon.example.model;

import java.util.List;

import io.helidon.example.api.ModesApi;
import io.helidon.json.binding.JsonBinding;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Swagger2ContractRuntimeTest {

    private final JsonBinding jsonBinding = JsonBinding.create();

    @Test
    void swagger2EnumsRemainTypedAndPreserveWireValues() {
        assertThat(ModesApi.InspectModeModeEnum.fromValue("authZ"),
                   is(ModesApi.InspectModeModeEnum.AUTH_Z));

        Envelope envelope = new Envelope();
        envelope.mode(Mode.fromValue("authZ"));
        envelope.inlineMode(Envelope.InlineModeEnum.fromValue("on-hold"));
        envelope.modes(List.of(Mode.fromValue("fast-mode"), Mode.fromValue("legacy.value")));

        String json = jsonBinding.serialize(envelope, Envelope.class);
        assertThat(json, containsString("\"mode\":\"authZ\""));
        assertThat(json, containsString("\"inlineMode\":\"on-hold\""));
        assertThat(json, containsString("\"modes\":[\"fast-mode\",\"legacy.value\"]"));

        Envelope restored = jsonBinding.deserialize(json, Envelope.class);
        assertThat(restored.mode(), is(Mode.AUTH_Z));
        assertThat(restored.inlineMode(), is(Envelope.InlineModeEnum.ON_HOLD));
        assertThat(restored.modes(), is(List.of(Mode.FAST_MODE, Mode.LEGACY_VALUE)));
        assertThrows(RuntimeException.class, () -> jsonBinding.deserialize("\"AUTH_Z\"", Mode.class));
    }

    @Test
    void swagger2AllOfDiscriminatorIsAuthoritative() {
        ProjectScope scope = new ProjectScope();
        scope.projectId("project-1");

        String json = jsonBinding.serialize((ApplicableScope) scope, ApplicableScope.class);
        assertThat(occurrences(json, "\"scopeType\""), is(1));
        assertThat(json, containsString("\"scopeType\":\"PROJECT_SCOPE\""));

        ApplicableScope restored = jsonBinding.deserialize(json, ApplicableScope.class);
        assertThat(restored, instanceOf(ProjectScope.class));
        assertThat(((ProjectScope) restored).projectId(), is("project-1"));
        assertThrows(RuntimeException.class,
                     () -> jsonBinding.deserialize("{\"scopeType\":\"UNKNOWN\"}", ApplicableScope.class));
    }

    private int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
