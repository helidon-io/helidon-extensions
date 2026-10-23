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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Preserves conjunctive required and nullable constraints across allOf inheritance. */
class AllOfRequiredValidationGenerationIT {
    @TempDir
    Path outputDir;
    @Test
    void preservesRequiredNonNullableAncestorWhenChildAllowsNull() throws Exception {
        generate("""
                Base:
                  type: object
                  required: [kind, code]
                  discriminator:
                    propertyName: kind
                  properties:
                    kind:
                      type: string
                    code:
                      type: string
                Child:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        code:
                          type: string
                          nullable: true
                """);
        assertNotNullGetter("Child", "code");
    }
    @Test
    void retainsRequiredListOnAllOfMemberWithoutPropertyRedeclaration() throws Exception {
        generate("""
                Base:
                  type: object
                  properties:
                    code:
                      type: string
                Child:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      required: [code]
                """);
        assertNotNullGetter("Child", "code");
    }

    @Test
    void doesNotLeakChildRequiredConstraintIntoParentOrSibling() throws Exception {
        generate("""
                Base:
                  type: object
                  properties:
                    code:
                      type: string
                      minLength: 1
                ARequiredChild:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      required: [code]
                ZOptionalChild:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                """);
        assertNotNullGetter("ARequiredChild", "code");
        for (String model : new String[] {"Base", "ZOptionalChild"}) {
            String java = read(model);
            assertThat(model, java, not(containsString("@Validation.NotNull")));
            assertThat(model, java, containsString("@Validation.String.Length(min = 1)"));
        }
    }
    @Test
    void retainsNullableWhenEveryAllOfDeclarationAllowsNull() throws Exception {
        generate("""
                Base:
                  type: object
                  required: [code]
                  properties:
                    code:
                      type: string
                      nullable: true
                Child:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        code:
                          type: string
                          nullable: true
                """);
        assertThat(read("Child"), not(containsString("@Validation.NotNull")));
    }
    @Test
    void retainsRequirementsThroughGrandchildren() throws Exception {
        generate("""
                Base:
                  type: object
                  properties:
                    code:
                      type: string
                Child:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      required: [code]
                Grandchild:
                  allOf:
                    - $ref: '#/components/schemas/Child'
                    - type: object
                """);
        assertNotNullGetter("Child", "code");
        assertNotNullGetter("Grandchild", "code");
    }

    @Test
    void preservesRequiredObjectTypeWhenChildAllowsNull() throws Exception {
        generate("""
                Leaf:
                  type: object
                  required: [value]
                  properties:
                    value: {type: string}
                Base:
                  type: object
                  required: [details]
                  properties:
                    details: {$ref: '#/components/schemas/Leaf'}
                Child:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        details:
                          allOf:
                            - $ref: '#/components/schemas/Leaf'
                          nullable: true
                """);
        assertNotNullGetter("Child", "details");
        assertThat(read("Child"), containsString("public Leaf details()"));
        assertThat(read("Child"), not(containsString("Optional<")));
    }

    @Test
    void rejectsRequiredNarrowingOfInheritedOptionalModel() {
        for (String declaration : new String[] {"", "properties: {details: {$ref: '#/components/schemas/Leaf'}}"}) {
            RuntimeException error = assertThrows(RuntimeException.class, () -> generate("""
                    Leaf:
                      type: object
                      required: [value]
                      properties:
                        value: {type: string}
                    Base:
                      type: object
                      properties:
                        details: {$ref: '#/components/schemas/Leaf'}
                    Child:
                      allOf:
                        - $ref: '#/components/schemas/Base'
                        - type: object
                          required: [details]
                    """ + (declaration.isEmpty() ? "" : declaration.indent(6))));
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause.getMessage(), containsString("Unsupported allOf required narrowing"));
            assertThat(cause.getMessage(), containsString("schema 'Child', property 'details'"));
        }
    }

    @Test
    void retainsNullableInheritedPresenceWithoutAddingNotNull() throws Exception {
        generate("""
                Base:
                  type: object
                  properties:
                    code: {type: string, nullable: true}
                Child:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      required: [code]
                """);
        assertThat(read("Child"), containsString("@Json.Required"));
        assertThat(read("Child"), not(containsString("@Validation.NotNull")));
        assertThat(read("Base"), not(containsString("@Json.Required")));
    }

    @Test
    void retainsSwaggerAllOfRequirementsAndNullableIntersection() throws Exception {
        generate("""
                Base:
                  type: object
                  required: [code]
                  properties:
                    code: {type: string}
                Child:
                  allOf:
                    - $ref: '#/definitions/Base'
                    - type: object
                      properties:
                        code: {type: string, x-nullable: true}
                OptionalBase:
                  type: object
                  properties:
                    code: {type: string}
                RequiredChild:
                  allOf:
                    - $ref: '#/definitions/OptionalBase'
                    - type: object
                      required: [code]
                """, true);
        assertNotNullGetter("Child", "code");
        assertNotNullGetter("RequiredChild", "code");
    }


    @Test
    void failsClearlyWhenOriginalSwaggerRequirementsCannotBeRecovered() {
        var api = new io.swagger.v3.oas.models.OpenAPI()
                .extensions(java.util.Map.of("x-original-swagger-version", "2.0"))
                .components(new io.swagger.v3.oas.models.Components().addSchemas("Child",
                        new io.swagger.v3.oas.models.media.Schema<>().allOf(java.util.List.of(
                                new io.swagger.v3.oas.models.media.Schema<>().type("object")))));
        Path unavailable = outputDir.resolve("private-input-token.yaml");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AllOfValidationSupport.snapshot(api, unavailable.toString()));
        assertThat(error.getMessage(), containsString("Cannot recover original Swagger allOf required lists"));
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            assertThat(cause.getMessage(), not(containsString("private-input-token")));
        }
    }


    @Test
    void retainsOriginalJsonNamesForRequiredEscapedJavaProperties() throws Exception {
        generate("""
                Base:
                  type: object
                  properties:
                    object: {type: string, nullable: true}
                Child:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      required: [object]
                Direct:
                  type: object
                  required: [object, display-name]
                  properties:
                    object: {type: string}
                    display-name: {type: string}
                """);
        assertThat(read("Direct"), containsString("@Json.Property(\"object\")"));
        assertThat(read("Direct"), containsString("@Json.Property(\"display-name\")"));
        assertThat(read("Child"), containsString("@Json.Property(\"object\")"));
        assertThat(read("Child"), containsString("@Json.Required"));
        assertThat(read("Base"), not(containsString("@Json.Required")));
    }

    private void generate(String schemas) throws Exception {
        generate(schemas, false);
    }

    private void generate(String schemas, boolean swagger) throws Exception {
        Path spec = outputDir.resolve("input.yaml");
        String document = swagger ? """
                swagger: '2.0'
                info:
                  title: allOf required review
                  version: 1.0.0
                paths: {}
                definitions:
                """ + schemas.indent(2) : """
                openapi: 3.0.3
                info:
                  title: allOf required review
                  version: 1.0.0
                paths: {}
                components:
                  schemas:
                """ + schemas.indent(4);
        Files.writeString(spec, document);
        var config = new CodegenConfigurator().setGeneratorName("helidon-declarative")
                .setInputSpec(spec.toString()).setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.5.6")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model");
        new DefaultGenerator().opts(config.toClientOptInput()).generate();
    }

    private String read(String model) throws Exception {
        return Files.readString(outputDir.resolve("src/main/java/io/helidon/example/model/" + model + ".java"));
    }

    private void assertNotNullGetter(String model, String property) throws Exception {
        String java = read(model);
        int getter = java.indexOf(" " + property + "()");
        assertTrue(getter >= 0, "Expected a validating accessor for " + model + "." + property);
        int previousMethod = java.lastIndexOf('}', getter);
        assertThat(java.substring(previousMethod + 1, getter), containsString("@Validation.NotNull"));
    }
}
