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

package io.helidon.extensions.jackson.jackson3.tests.builder.codegen.runner;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import io.helidon.extensions.jackson.jackson3.tests.builder.codegen.CreateProperty;
import io.helidon.extensions.jackson.jackson3.tests.builder.codegen.DemoWorkflowArguments;
import io.helidon.extensions.jackson.jackson3.tests.builder.codegen.DeserializeOnly;
import io.helidon.extensions.jackson.jackson3.tests.builder.codegen.GenericValue;
import io.helidon.extensions.jackson.jackson3.tests.builder.codegen.PlainBean;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

public final class BuilderModulePathMain {
    private BuilderModulePathMain() {
    }

    public static void main(String[] args) {
        Module model = DemoWorkflowArguments.class.getModule();
        Module jackson = ObjectMapper.class.getModule();
        String modelPackage = DemoWorkflowArguments.class.getPackageName();
        if (!model.isNamed() || !jackson.isNamed()) {
            throw new AssertionError("Model and Jackson must both run as named modules");
        }
        if (!model.isExported(modelPackage, jackson) || model.isOpen(modelPackage, jackson)) {
            throw new AssertionError("Model package must be exported and must not be open to Jackson");
        }

        boolean strict = args.length == 1 && args[0].equals("strict-access");
        if (args.length > 0 && !strict) {
            throw new AssertionError("Expected no argument or strict-access");
        }
        if (strict && !jackson.canRead(model)) {
            throw new AssertionError("Strict-access mode requires Jackson to read the model module");
        }
        var mapper = JsonMapper.builderWithJackson2Defaults();
        if (strict) {
            mapper.disable(MapperFeature.CAN_OVERRIDE_ACCESS_MODIFIERS);
        }
        roundTrip(mapper.build());

        var snakeCaseMapper = JsonMapper.builderWithJackson2Defaults()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        if (strict) {
            snakeCaseMapper.disable(MapperFeature.CAN_OVERRIDE_ACCESS_MODIFIERS);
        }
        genericRoundTrip(snakeCaseMapper.build(), "display_name");
        System.out.println("PASS bean and generic models without opens");
    }

    private static void roundTrip(ObjectMapper mapper) {
        String json = """
                {"encrypted":true,"workflowId":47,"payload_size":42,"label":"named-module"}
                """;
        var original = mapper.readValue(json, DemoWorkflowArguments.class);
        assertEquals(42L, original.getSize(), "bean-style size");
        assertEquals(47, original.getWorkflowId(), "bean-style workflow ID");
        assertEquals(true, original.isEncrypted(), "bean-style encrypted flag");
        assertEquals(Optional.of("named-module"), original.getLabel(), "bean-style label");
        String encoded = mapper.writeValueAsString(original);
        assertEquals(mapper.readTree(json), mapper.readTree(encoded), "bean-style serialization");
        var restored = mapper.readValue(encoded, DemoWorkflowArguments.class);
        assertEquals(original, restored, "bean-style round trip");

        var missingLabel = mapper.readValue("""
                {"encrypted":true,"workflowId":47,"payload_size":42}
                """, DemoWorkflowArguments.class);
        assertEquals(Optional.empty(), missingLabel.getLabel(), "missing bean-style optional");
        var nullLabel = mapper.readValue("""
                {"encrypted":true,"workflowId":47,"payload_size":42,"label":null}
                """, DemoWorkflowArguments.class);
        assertEquals(Optional.empty(), nullLabel.getLabel(), "null bean-style optional");
        var emptyRestored = mapper.readValue(mapper.writeValueAsString(nullLabel), DemoWorkflowArguments.class);
        assertEquals(nullLabel, emptyRestored, "empty bean-style optional round trip");

        String countJson = "{\"count\":42}";
        var plainBean = mapper.readValue(countJson, PlainBean.class);
        assertEquals(42, plainBean.getCount(), "implicit bean-style property");
        String plainJson = mapper.writeValueAsString(plainBean);
        assertEquals(mapper.readTree(countJson), mapper.readTree(plainJson), "implicit bean-style serialization");
        var plainRestored = mapper.readValue(plainJson, PlainBean.class);
        assertEquals(plainBean, plainRestored, "implicit bean-style round trip");

        var deserializeOnly = mapper.readValue(countJson, DeserializeOnly.class);
        assertEquals(42, deserializeOnly.getCount(), "deserialize-only implicit bean-style property");

        String createJson = "{\"create\":\"required-value\"}";
        var createProperty = mapper.readValue(createJson, CreateProperty.class);
        assertEquals("required-value", createProperty.create(), "property named create");
        String encodedCreate = mapper.writeValueAsString(createProperty);
        assertEquals(mapper.readTree(createJson), mapper.readTree(encodedCreate), "create property serialization");
        var restoredCreate = mapper.readValue(encodedCreate, CreateProperty.class);
        assertEquals(createProperty, restoredCreate, "create property round trip");

        genericRoundTrip(mapper, "displayName");
    }

    private static void genericRoundTrip(ObjectMapper mapper, String propertyName) {
        var type = new TypeReference<GenericValue<BigDecimal>>() { };
        var generic = mapper.readValue("{\"value\":12.25}", type);
        assertEquals(new BigDecimal("12.25"), generic.value(), "generic value type");
        assertEquals(Optional.of("default-label"), generic.displayName(), "missing optional retains its default");
        assertEquals("first", generic.message(), "first potentially colliding property default");
        assertEquals("second", generic.jacksonMessage(), "second potentially colliding property default");
        String genericJson = mapper.writeValueAsString(generic);
        assertEquals(mapper.readTree("\"default-label\""), mapper.readTree(genericJson).get(propertyName),
                     "implicit optional serialization name");
        var genericRestored = mapper.readValue(genericJson, type);
        assertEquals(generic, genericRestored, "generic round trip");

        var cleared = mapper.readValue("{\"value\":12.25,\"" + propertyName + "\":null}", type);
        assertEquals(Optional.empty(), cleared.displayName(), "explicit null clears the default optional");
        var clearedRestored = mapper.readValue(mapper.writeValueAsString(cleared), type);
        assertEquals(cleared, clearedRestored, "cleared default optional round trip");

        var present = mapper.readValue("{\"value\":12.25,\"" + propertyName + "\":\"custom-label\"}", type);
        assertEquals(Optional.of("custom-label"), present.displayName(), "present optional replaces its default");
        var presentRestored = mapper.readValue(mapper.writeValueAsString(present), type);
        assertEquals(present, presentRestored, "present default optional round trip");

        var manual = GenericValue.<BigDecimal>builder()
                .value(new BigDecimal("12.25"))
                .jacksonMessage("custom")
                .build();
        assertEquals("first", manual.message(), "regular setter leaves the other property unchanged");
        assertEquals("custom", manual.jacksonMessage(), "regular setter updates its own property");
        var manualRestored = mapper.readValue(mapper.writeValueAsString(manual), type);
        assertEquals(manual, manualRestored, "potentially colliding property round trip");

        try {
            GenericValue.<BigDecimal>builder().displayName((String) null);
            throw new AssertionError("The regular builder setter must still reject null");
        } catch (NullPointerException expected) {
            // Jackson's null handling must not relax the ordinary builder API.
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}
