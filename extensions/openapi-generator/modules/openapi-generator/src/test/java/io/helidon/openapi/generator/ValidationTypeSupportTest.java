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

package io.helidon.openapi.generator;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationTypeSupportTest {
    private static final Set<String> MODELS = Set.of("ConstrainedLeaf", "MapKeyModel");
    private static final Set<String> PARTICIPATING = Set.of("ConstrainedLeaf");

    @Test
    void annotatesArrayElementsInsideNestedSupportedContainers() {
        String type = "Map<MapKeyModel, List<Optional<ConstrainedLeaf[]>>>";

        assertThat(ValidationTypeSupport.annotatedType(type, MODELS, PARTICIPATING, "io.example.model"),
                   is("Map<MapKeyModel, List<Optional<io.example.model.@Validation.Valid ConstrainedLeaf[]>>>"));
    }

    @Test
    void supportsFullyQualifiedModelTypes() {
        String type = "java.util.Map<java.lang.String, io.example.ConstrainedLeaf[]>";

        assertThat(ValidationTypeSupport.annotatedType(type, MODELS, PARTICIPATING, "io.example.model"),
                   is("java.util.Map<java.lang.String, io.example.@Validation.Valid ConstrainedLeaf[]>"));
    }

    @Test
    void excludesMapKeysFromTheValidationGraph() {
        String type = "Map<ConstrainedLeaf, String>";

        assertThat(ValidationTypeSupport.referencedModels(type, MODELS), is(Set.of()));
        assertThat(ValidationTypeSupport.annotatedType(type, MODELS, PARTICIPATING, "io.example.model"), is(type));
    }

    @Test
    void identifiesNestedCustomContainers() {
        var unsupported = ValidationTypeSupport.unsupportedContainer(
                        "List<CustomValues<ConstrainedLeaf>>", MODELS, PARTICIPATING)
                .orElseThrow();

        assertThat(unsupported.rawType(), is("CustomValues"));
        assertThat(unsupported.participatingModels(), is(Set.of("ConstrainedLeaf")));
    }

    @Test
    void rejectsMalformedMappedTypesWithContext() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                                                       () -> ValidationTypeSupport.referencedModels(
                                                               "List<ConstrainedLeaf", MODELS));

        assertThat(error.getMessage(), containsString("Cannot analyze mapped Java type 'List<ConstrainedLeaf'"));
        assertThat(error.getMessage(), containsString("Expected '>'"));
    }
}
