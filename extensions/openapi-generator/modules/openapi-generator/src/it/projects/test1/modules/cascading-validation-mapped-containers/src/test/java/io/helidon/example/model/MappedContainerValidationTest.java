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

import java.util.Optional;

import io.helidon.service.registry.Services;
import io.helidon.validation.ConstraintViolation;
import io.helidon.validation.TypeValidation;
import io.helidon.validation.ValidationResponse;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class MappedContainerValidationTest {

    private final TypeValidation validation = Services.get(TypeValidation.class);

    @Test
    void cascadesIntoOptionalMappedModel() {
        MappedContainers containers = containers(leaf("x"));

        ValidationResponse response = validation.validate(MappedContainers.class, containers);

        assertThat(response.valid(), is(false));
        assertThat(response.violations().stream()
                           .map(MappedContainerValidationTest::path)
                           .anyMatch(path -> path.contains("optionalLeaf") && path.contains("code")),
                   is(true));
    }

    @Test
    void cascadesIntoJavaArrayMappedModel() {
        MappedContainers containers = containers(leaf("x"));

        ValidationResponse response = validation.validate(MappedContainers.class, containers);

        assertThat(response.valid(), is(false));
        assertThat(response.violations().stream()
                           .map(MappedContainerValidationTest::path)
                           .anyMatch(path -> path.contains("leafArray") && path.contains("code")),
                   is(true));
    }

    @Test
    void acceptsValidMappedContainers() {
        MappedContainers containers = containers(leaf("valid"));

        assertThat(validation.validate(MappedContainers.class, containers).valid(), is(true));
    }

    private static MappedContainers containers(ConstrainedLeaf leaf) {
        MappedContainers result = new MappedContainers();
        result.optionalLeaf(Optional.of(leaf));
        result.leafArray(new ConstrainedLeaf[] {leaf});
        return result;
    }

    private static ConstrainedLeaf leaf(String code) {
        ConstrainedLeaf result = new ConstrainedLeaf();
        result.code(code);
        return result;
    }

    private static String path(ConstraintViolation violation) {
        return violation.location().stream()
                .map(element -> element.location() + ":" + element.name())
                .reduce("", (left, right) -> left + "/" + right);
    }
}
