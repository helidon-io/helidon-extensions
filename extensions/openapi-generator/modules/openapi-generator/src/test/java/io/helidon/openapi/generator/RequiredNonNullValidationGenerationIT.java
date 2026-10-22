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
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Required presence and nullability must independently participate in native validation. */
class RequiredNonNullValidationGenerationIT {
    @TempDir
    Path outputDir;

    /** OpenAPI 3 nullable properties remain nullable, including required nullable properties. */
    @Test
    void generatesOpenApiRequiredNonNullValidation() throws Exception {
        verify("required-nonnull-validation.yaml");
    }

    /** Swagger 2 x-nullable must have the same behavior as OpenAPI 3 nullable. */
    @Test
    void generatesSwaggerRequiredNonNullValidation() throws Exception {
        verify("required-nonnull-swagger2-validation.yaml");
    }

    /** Required-only union members must fail clearly rather than bypass native subtype validation. */
    @Test
    void rejectsRequiredOnlyUnionRequestValidation() throws Exception {
        Path resource = Paths.get(getClass().getClassLoader().getResource("composed-schemas.yaml").toURI());
        String fixture = Files.readString(resource);
        for (String union : List.of("Pet", "Contact", "Problem", "NullablePet")) {
            Path input = outputDir.resolve(union + ".yaml");
            Files.writeString(input, fixture.replace("\n              $ref: '#/components/schemas/Cat'",
                                                    "\n              $ref: '#/components/schemas/" + union + "'"));
            var config = new CodegenConfigurator()
                    .setGeneratorName("helidon-declarative")
                    .setInputSpec(input.toString())
                    .setOutputDir(outputDir.resolve(union).toString())
                    .addAdditionalProperty("helidonVersion", "4.5.0");
            RuntimeException error = assertThrows(RuntimeException.class,
                                                  () -> new DefaultGenerator().opts(config.toClientOptInput()).generate());
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause.getMessage(), containsString("Unsupported cascading validation request entity"));
            assertThat(cause.getMessage(), containsString(union));
        }
    }

    /** Verifies the real rendered accessor annotations and transitive request boundary. */
    private void verify(String fixture) throws Exception {
        Path input = Paths.get(getClass().getClassLoader().getResource(fixture).toURI());
        var config = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(input.toString())
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.5.0")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model");
        new DefaultGenerator().opts(config.toClientOptInput()).generate();
        String leaf = read("model/RequiredLeaf.java");
        assertThat(leaf, containsString("@Validation.Validated"));
        for (String declaration : List.of("String moduleName", "Integer count", "Boolean enabled",
                                          "List<String> labels", "Map<String, String> attributes")) {
            assertThat(leaf, containsString("@Validation.NotNull\n    public " + declaration + "()"));
        }
        for (String name : List.of("nullableLabel", "optionalLabel", "optionalPlain")) {
            int getter = leaf.indexOf(" " + name + "()");
            int previousMethod = leaf.lastIndexOf('}', getter);
            assertThat(leaf.substring(previousMethod + 1, getter), not(containsString("@Validation.NotNull")));
        }
        String envelope = read("model/RequiredEnvelope.java");
        assertThat(envelope, containsString("@Validation.Validated"));
        assertThat(envelope, containsString("@Validation.NotNull"));
        assertThat(envelope, containsString("@Validation.Valid\n    public RequiredLeaf details()"));
        assertThat(read("api/RequiredFieldsApi.java"),
                   containsString("@Validation.Valid @Http.Entity RequiredEnvelope"));
    }

    /** Reads generated Java without modifying generated output. */
    private String read(String relative) throws Exception {
        return Files.readString(outputDir.resolve("src/main/java/io/helidon/example/" + relative))
                .replace("\r\n", "\n");
    }
}
