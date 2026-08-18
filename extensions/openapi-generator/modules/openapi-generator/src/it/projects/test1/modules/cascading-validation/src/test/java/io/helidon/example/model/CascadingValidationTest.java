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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.helidon.service.registry.Services;
import io.helidon.validation.ConstraintViolation;
import io.helidon.validation.TypeValidation;
import io.helidon.validation.ValidationResponse;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

class CascadingValidationTest {

    private final TypeValidation validation = Services.get(TypeValidation.class);

    @Test
    void cascadesAcrossAllGeneratedShapesAndRetainsViolations() {
        ConstrainedLeaf invalidLeaf = leaf("x", "y");
        ValidationIntermediate intermediate = new ValidationIntermediate();
        intermediate.leaf(invalidLeaf);

        ValidationRoot root = new ValidationRoot();
        root.intermediate(intermediate);
        root.nullableLeaf(Optional.of(invalidLeaf));
        root.leaves(List.of(invalidLeaf));
        root.uniqueLeaves(Set.of(invalidLeaf));
        root.leavesByName(Map.of("production", invalidLeaf));
        root.nestedLeaves(List.of(List.of(invalidLeaf)));

        ValidationResponse response = validation.validate(ValidationRoot.class, root);

        assertThat(response.valid(), is(false));
        assertThat(response.violations().size(), greaterThanOrEqualTo(10));
        String paths = response.violations().stream()
                .map(CascadingValidationTest::path)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(paths, containsString("intermediate"));
        assertThat(paths, containsString("nullableLeaf"));
        assertThat(paths, containsString("leaf"));
        assertThat(paths, containsString("leaves"));
        assertThat(paths, containsString("uniqueLeaves"));
        assertThat(paths, containsString("leavesByName"));
        assertThat(paths, containsString("nestedLeaves"));
        assertThat(paths, containsString("element"));
        assertThat(paths, containsString("value"));
        assertThat(paths, containsString("code"));
        assertThat(paths, containsString("label"));
    }

    @Test
    void acceptsValidNestedGraph() {
        ConstrainedLeaf validLeaf = leaf("valid", "valid");
        ValidationIntermediate intermediate = new ValidationIntermediate();
        intermediate.leaf(validLeaf);
        ValidationRoot root = new ValidationRoot();
        root.intermediate(intermediate);
        root.nullableLeaf(Optional.of(validLeaf));
        root.leaves(List.of(validLeaf));
        root.uniqueLeaves(Set.of(validLeaf));
        root.leavesByName(Map.of("production", validLeaf));
        root.nestedLeaves(List.of(List.of(validLeaf)));

        assertThat(validation.validate(ValidationRoot.class, root).valid(), is(true));
    }

    @Test
    void acceptsAbsentNullableDirectModel() {
        ValidationIntermediate intermediate = new ValidationIntermediate();
        intermediate.leaf(leaf("valid", "valid"));
        ValidationRoot root = new ValidationRoot();
        root.intermediate(intermediate);

        assertThat(root.nullableLeaf(), is(Optional.empty()));
        assertThat(validation.validate(ValidationRoot.class, root).valid(), is(true));
    }

    @Test
    void validatesInheritedAllOfConstraintOnConcreteChild() {
        ConstrainedChild child = childWithRequiredModels();
        child.inheritedCode("x");

        ValidationResponse response = validation.validate(ConstrainedChild.class, child);

        assertThat(response.valid(), is(false));
        assertThat(response.violations().stream()
                           .map(CascadingValidationTest::path)
                           .anyMatch(path -> path.contains("inheritedCode")),
                   is(true));
    }

    @Test
    void validatesLocalAndInheritedConstraintsOnSameAllOfProperty() {
        ConstrainedChild child = childWithRequiredModels();
        child.inheritedCode("12345678901");

        ValidationResponse response = validation.validate(ConstrainedChild.class, child);

        assertThat(response.valid(), is(false));
        assertThat(response.violations().stream()
                           .map(CascadingValidationTest::path)
                           .anyMatch(path -> path.contains("inheritedCode")),
                   is(true));
    }

    @Test
    void validatesInheritedNullableDirectModelWhenPresent() {
        ConstrainedChild child = childWithRequiredModels();
        child.inheritedCode("valid");
        child.nestedLeaf(Optional.of(leaf("x", "y")));

        ValidationResponse response = validation.validate(ConstrainedChild.class, child);

        assertThat(response.valid(), is(false));
        assertThat(response.violations().stream()
                           .map(CascadingValidationTest::path)
                           .anyMatch(path -> path.contains("nestedLeaf") && path.contains("code")),
                   is(true));
    }

    @Test
    void validatesChildLocalNullableDirectModelBuiltWithGeneratedBuilder() {
        ConstrainedLeaf validLeaf = leaf("valid", "valid");
        ConstrainedChild child = new ConstrainedChild.Builder()
                .requiredNestedLeaf(validLeaf)
                .requiredChildLeaf(validLeaf)
                .childLeaf(Optional.of(leaf("x", "y")))
                .build();

        ValidationResponse response = validation.validate(ConstrainedChild.class, child);

        assertThat(response.valid(), is(false));
        assertThat(response.violations().stream()
                           .map(CascadingValidationTest::path)
                           .anyMatch(path -> path.contains("childLeaf") && path.contains("code")),
                   is(true));
    }

    private static ConstrainedChild childWithRequiredModels() {
        ConstrainedLeaf validLeaf = leaf("valid", "valid");
        ConstrainedChild child = new ConstrainedChild();
        child.requiredNestedLeaf(validLeaf);
        child.requiredChildLeaf(validLeaf);
        return child;
    }

    private static ConstrainedLeaf leaf(String code, String label) {
        ConstrainedLeaf result = new ConstrainedLeaf();
        result.code(code);
        result.label(label);
        return result;
    }

    private static String path(ConstraintViolation violation) {
        return violation.location().stream()
                .map(element -> element.location() + ":" + element.name())
                .reduce("", (left, right) -> left + "/" + right);
    }
}
