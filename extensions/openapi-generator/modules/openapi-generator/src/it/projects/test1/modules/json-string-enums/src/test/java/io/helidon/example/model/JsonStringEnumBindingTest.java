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
import java.util.stream.IntStream;

import io.helidon.json.binding.JsonBinding;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonStringEnumBindingTest {

    private final JsonBinding jsonBinding = JsonBinding.create();

    @Test
    void topLevelEnumRoundTripsExactWireValueAndNull() {
        assertThat(jsonBinding.serialize(Mode.FAST_MODE, Mode.class), is("\"fast-mode\""));
        assertThat(jsonBinding.serialize(Mode.AUTH_Z, Mode.class), is("\"authZ\""));
        assertThat(jsonBinding.deserialize("\"legacy.value\"", Mode.class), is(Mode.LEGACY_VALUE));
        assertThat(jsonBinding.serialize((Mode) null, Mode.class), is("null"));
        assertThat(jsonBinding.deserialize("null", Mode.class), is((Mode) null));
    }

    @Test
    void modelPropertiesAndCollectionsRoundTripThroughHelidonJson() {
        EnumEnvelope envelope = new EnumEnvelope();
        envelope.inlineMode(EnumEnvelope.InlineModeEnum.ON_HOLD);
        envelope.mode(Mode.AUTH_Z);
        envelope.modes(List.of(Mode.FAST_MODE, Mode.LEGACY_VALUE));
        envelope.inlineModes(List.of(EnumEnvelope.InlineModesEnum.BATCH_FAST,
                                     EnumEnvelope.InlineModesEnum.BATCH_AUTH_Z));

        String json = jsonBinding.serialize(envelope, EnumEnvelope.class);
        assertThat(json, is("{\"inlineMode\":\"on-hold\",\"mode\":\"authZ\","
                                    + "\"modes\":[\"fast-mode\",\"legacy.value\"],"
                                    + "\"inlineModes\":[\"batch-fast\",\"batchAuthZ\"]}"));

        EnumEnvelope restored = jsonBinding.deserialize(json, EnumEnvelope.class);
        assertThat(restored.inlineMode(), is(EnumEnvelope.InlineModeEnum.ON_HOLD));
        assertThat(restored.mode(), is(Mode.AUTH_Z));
        assertThat(restored.modes(), is(List.of(Mode.FAST_MODE, Mode.LEGACY_VALUE)));
        assertThat(restored.inlineModes(), is(List.of(EnumEnvelope.InlineModesEnum.BATCH_FAST,
                                                      EnumEnvelope.InlineModesEnum.BATCH_AUTH_Z)));
    }

    @Test
    void unknownWireValueIsRejected() {
        assertThrows(RuntimeException.class, () -> jsonBinding.deserialize("\"FAST_MODE\"", Mode.class));
        assertThrows(IllegalArgumentException.class, () -> Mode.fromValue("FAST_MODE"));
    }

    @Test
    void adversarialWireValuesRoundTripExactly() {
        List<String> values = List.of("", "quote\"value", "back\\slash", "line\nbreak", "snowman-☃");

        for (String value : values) {
            EscapedMode mode = EscapedMode.fromValue(value);
            String json = jsonBinding.serialize(mode, EscapedMode.class);
            assertThat(jsonBinding.deserialize(json, EscapedMode.class), is(mode));
            assertThat(jsonBinding.deserialize(json, EscapedMode.class).value(), is(value));
        }

        var envelope = new EnumEnvelope();
        envelope.escapedInline(EnumEnvelope.EscapedInlineEnum.LINE_BREAK);
        String inlineJson = jsonBinding.serialize(envelope, EnumEnvelope.class);
        EnumEnvelope restored = jsonBinding.deserialize(inlineJson, EnumEnvelope.class);
        assertThat(restored.escapedInline().value(), is("line\nbreak"));
    }

    @Test
    void sharedBindingConvertsAlternatingValuesConcurrently() {
        List<Mode> converted = IntStream.range(0, 2_000)
                .parallel()
                .mapToObj(index -> index % 2 == 0 ? "\"fast-mode\"" : "\"authZ\"")
                .map(json -> jsonBinding.deserialize(json, Mode.class))
                .toList();

        for (int i = 0; i < converted.size(); i++) {
            assertThat(converted.get(i), is(i % 2 == 0 ? Mode.FAST_MODE : Mode.AUTH_Z));
        }
    }

    @Test
    void modelNamedServiceRoundTripsWithoutRegistryTypeCollision() {
        Service service = new Service();
        service.state(Service.StateEnum.READY_NOW);

        String json = jsonBinding.serialize(service, Service.class);

        assertThat(json, is("{\"state\":\"ready-now\"}"));
        assertThat(jsonBinding.deserialize(json, Service.class).state(), is(Service.StateEnum.READY_NOW));
    }
}
