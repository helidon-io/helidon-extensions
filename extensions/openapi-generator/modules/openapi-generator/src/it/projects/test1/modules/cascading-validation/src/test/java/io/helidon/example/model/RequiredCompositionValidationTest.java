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

import io.helidon.service.registry.Services;
import io.helidon.validation.TypeValidation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks programmatic and property validation independently of JSON presence checks. */
class RequiredCompositionValidationTest {
    private final TypeValidation validation = Services.get(TypeValidation.class);

    @Test
    void bindsValidComposedObjectsBeforeValidation() {
        var binding = io.helidon.json.binding.JsonBinding.create();
        assertAll(() -> binding.deserialize("{\"kind\":\"NULLABLE_CHILD\",\"code\":\"strict\"}", NullableCodeChild.class),
                  () -> binding.deserialize("{\"code\":\"inherited\"}", StrengthenedCodeChild.class),
                  () -> binding.deserialize("{\"code\":\"grandchild\"}", RequiredCodeGrandchild.class),
                  () -> binding.deserialize("{\"code\":null}", NullableCodeControlChild.class),
                  () -> binding.deserialize("{\"code\":null}", RequiredNullableCodeChild.class),
                  () -> binding.deserialize("{\"details\":{\"value\":\"object\"}}", NullableObjectChild.class));
    }

    @Test
    void validatesRequiredAncestorThroughRuntimeSubtype() {
        NullableCodeChild child = new NullableCodeChild();
        assertFalse(validation.validate(RequiredCodeBase.class, child).valid());
        assertFalse(validation.validate(RequiredCodeBase.class, child, "code").valid());
        assertFalse(validation.validateProperty(RequiredCodeBase.class, "code", null).valid());
        child.code("");
        assertTrue(validation.validate(RequiredCodeBase.class, child).valid());
    }

    @Test
    void validatesInheritedChildAndGrandchildRequirements() {
        assertFalse(validation.validate(StrengthenedCodeChild.class, new StrengthenedCodeChild()).valid());
        assertFalse(validation.validate(RequiredCodeGrandchild.class, new RequiredCodeGrandchild()).valid());
        assertFalse(validation.validateProperty(StrengthenedCodeChild.class, "code", null).valid());
        assertFalse(validation.validateProperty(RequiredCodeGrandchild.class, "code", null).valid());
    }

    @Test
    void retainsRequiredObjectAccessorAndNativeCascade() {
        NullableObjectChild child = new NullableObjectChild();
        assertFalse(validation.validate(NullableObjectChild.class, child).valid());
        CompositionLeaf leaf = new CompositionLeaf();
        child.details(leaf);
        assertFalse(validation.validate(NullableObjectChild.class, child).valid());
        leaf.value("valid");
        assertTrue(validation.validate(NullableObjectChild.class, child).valid());
    }

    @Test
    void retainsOptionalParentAndSiblingContracts() {
        assertTrue(validation.validate(OptionalCodeBase.class, new OptionalCodeBase()).valid());
        assertTrue(validation.validate(OptionalCodeSibling.class, new OptionalCodeSibling()).valid());
        assertTrue(validation.validateProperty(OptionalCodeBase.class, "code", null).valid());
        assertTrue(validation.validateProperty(OptionalCodeSibling.class, "code", null).valid());
    }
}
