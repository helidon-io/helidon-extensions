/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.jackson.jackson3.tests.builder.codegen;

import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class JacksonSerializationTest {
    private static final String SERIALIZED_VALUE = """
            {"encrypted":true,"workflowId":47,"payload_size":42}""";
    private static final String SERIALIZED_VALUE_WITH_NULL = """
            {"encrypted":true,"workflowId":47,"payload_size":42,"label":null}""";
    private static final String SERIALIZED_VALUE_WITH_LABEL = """
            {"encrypted":true,"workflowId":47,"payload_size":42,"label":"some-label"}""";
    static ObjectMapper mapper;

    @BeforeAll
    static void beforeAll() {
        mapper = JsonMapper.builderWithJackson2Defaults().build();
    }

    @Test
    void testDeserialization() throws JacksonException {
        DemoWorkflowArguments result = mapper.readValue(SERIALIZED_VALUE, DemoWorkflowArguments.class);
        assertThat(result.getSize(), is(42L));
        assertThat(result.getWorkflowId(), is(47));
        assertThat(result.isEncrypted(), is(true));
        assertThat(result.getLabel(), is(Optional.empty()));
    }

    @Test
    void testDeserializationWithNull() throws JacksonException {
        DemoWorkflowArguments result = mapper.readValue(SERIALIZED_VALUE_WITH_NULL, DemoWorkflowArguments.class);
        assertThat(result.getSize(), is(42L));
        assertThat(result.getWorkflowId(), is(47));
        assertThat(result.isEncrypted(), is(true));
        assertThat(result.getLabel(), is(Optional.empty()));
    }

    @Test
    void testDeserializationWithLabel() throws JacksonException {
        DemoWorkflowArguments result = mapper.readValue(SERIALIZED_VALUE_WITH_LABEL, DemoWorkflowArguments.class);
        assertThat(result.getSize(), is(42L));
        assertThat(result.getWorkflowId(), is(47));
        assertThat(result.isEncrypted(), is(true));
        assertThat(result.getLabel(), is(Optional.of("some-label")));
    }

    @Test
    void testImplicitBeanProperties() {
        String json = "{\"count\":42}";
        PlainBean result = mapper.readValue(json, PlainBean.class);
        assertThat(result.getCount(), is(42));

        String encoded = mapper.writeValueAsString(result);
        assertThat(mapper.readTree(encoded), is(mapper.readTree(json)));
        assertThat(mapper.readValue(encoded, PlainBean.class).getCount(), is(42));
    }

    @Test
    void testDeserializationOnlyImplicitBeanProperty() {
        DeserializeOnly result = mapper.readValue("{\"count\":42}", DeserializeOnly.class);
        assertThat(result.getCount(), is(42));
    }

    @Test
    void testCreatePropertyRoundTrip() {
        String json = "{\"create\":\"required-value\"}";
        CreateProperty result = mapper.readValue(json, CreateProperty.class);
        assertThat(result.create(), is("required-value"));

        String encoded = mapper.writeValueAsString(result);
        assertThat(mapper.readTree(encoded), is(mapper.readTree(json)));
        assertThat(mapper.readValue(encoded, CreateProperty.class), is(result));
    }

    @Test
    void testPrivateSettersWithPresentOptional() {
        String json = "{\"count\":42,\"label\":\"private-value\"}";
        assertPrivateSettersRoundTrip(json, Optional.of("private-value"), json);
    }

    @Test
    void testPrivateSettersWithMissingOptional() {
        assertPrivateSettersRoundTrip("{\"count\":42}", Optional.of("default-private-label"),
                                     "{\"count\":42,\"label\":\"default-private-label\"}");
    }

    @Test
    void testPrivateSettersWithNullOptional() {
        String json = "{\"count\":42,\"label\":null}";
        assertPrivateSettersRoundTrip(json, Optional.empty(), json);
    }

    @Test
    void testSerialization() throws JacksonException {
        var instance = DemoWorkflowArguments.builder()
                .setEncrypted(true)
                .setSize(42)
                .setWorkflowId(47)
                .build();

        String result = mapper.writeValueAsString(instance);

        assertThat(mapper.readTree(result), is(mapper.readTree(SERIALIZED_VALUE_WITH_NULL)));
        var restored = mapper.readValue(result, DemoWorkflowArguments.class);
        assertThat(restored.getSize(), is(instance.getSize()));
        assertThat(restored.getWorkflowId(), is(instance.getWorkflowId()));
        assertThat(restored.isEncrypted(), is(instance.isEncrypted()));
        assertThat(restored.getLabel(), is(instance.getLabel()));
    }

    private void assertPrivateSettersRoundTrip(String json, Optional<String> expectedLabel, String expectedJson) {
        PrivateSetters result = mapper.readValue(json, PrivateSetters.class);
        assertThat(result.getCount(), is(42));
        assertThat(result.getLabel(), is(expectedLabel));

        String encoded = mapper.writeValueAsString(result);
        assertThat(mapper.readTree(encoded), is(mapper.readTree(expectedJson)));
        assertThat(mapper.readValue(encoded, PrivateSetters.class), is(result));
    }
}
