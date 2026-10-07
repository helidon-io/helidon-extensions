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

import java.util.stream.Stream;

import io.helidon.json.JsonParser;
import io.helidon.json.binding.JsonBinding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class MixedAccessorBindingTest {

    private final JsonBinding jsonBinding = JsonBinding.create();

    @Test
    void isPrefixedPropertyRetainsLabelAndWireName() {
        for (Boolean enabled : new Boolean[] {true, false, null}) {
            IsExample value = IsExample.builder().label("hello").isEnabled(enabled).build();
            String expected = enabled == null ? "{\"label\":\"hello\"}"
                    : "{\"label\":\"hello\",\"isEnabled\":" + enabled + "}";
            String json = jsonBinding.serialize(value);
            assertJson(json, expected);
            IsExample restored = jsonBinding.deserialize(json, IsExample.class);
            assertThat(restored.label(), is("hello"));
            assertThat(restored.isEnabled(), is(enabled));
        }
    }

    @Test
    void getPrefixedPropertyRetainsLabelAndWireName() {
        for (String description : new String[] {"details", null}) {
            GetExample value = GetExample.builder().label("hello").getDescription(description).build();
            String expected = description == null ? "{\"label\":\"hello\"}"
                    : "{\"label\":\"hello\",\"getDescription\":\"details\"}";
            String json = jsonBinding.serialize(value);
            assertJson(json, expected);
            GetExample restored = jsonBinding.deserialize(json, GetExample.class);
            assertThat(restored.label(), is("hello"));
            assertThat(restored.getDescription(), is(description));
        }
    }

    @ParameterizedTest
    @MethodSource("mixedValues")
    void mixedPropertiesRoundTrip(Boolean enabled, String description, String expected) {
        MixedExample value = MixedExample.builder()
                .label("hello")
                .isEnabled(enabled)
                .getDescription(description)
                .build();
        String json = jsonBinding.serialize(value);
        assertJson(json, expected);
        MixedExample restored = jsonBinding.deserialize(json, MixedExample.class);
        assertThat(restored.label(), is("hello"));
        assertThat(restored.isEnabled(), is(enabled));
        assertThat(restored.getDescription(), is(description));
    }

    @Test
    void explicitNullPropertiesDeserializeWithSchemaWireNames() {
        String json = "{\"label\":\"hello\",\"isEnabled\":null,\"getDescription\":null}";
        MixedExample restored = jsonBinding.deserialize(json, MixedExample.class);
        assertThat(restored.label(), is("hello"));
        assertThat(restored.isEnabled(), is((Boolean) null));
        assertThat(restored.getDescription(), is((String) null));
        assertJson(jsonBinding.serialize(restored), "{\"label\":\"hello\"}");
    }

    private static Stream<Arguments> mixedValues() {
        return Stream.of(
                Arguments.of(true, "details", "{\"label\":\"hello\",\"isEnabled\":true,\"getDescription\":\"details\"}"),
                Arguments.of(false, "details", "{\"label\":\"hello\",\"isEnabled\":false,\"getDescription\":\"details\"}"),
                Arguments.of(null, "details", "{\"label\":\"hello\",\"getDescription\":\"details\"}"),
                Arguments.of(true, null, "{\"label\":\"hello\",\"isEnabled\":true}"),
                Arguments.of(null, null, "{\"label\":\"hello\"}"));
    }

    private static void assertJson(String actual, String expected) {
        assertThat(JsonParser.create(actual).readJsonObject(), is(JsonParser.create(expected).readJsonObject()));
    }
}
