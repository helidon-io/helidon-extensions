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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
}
