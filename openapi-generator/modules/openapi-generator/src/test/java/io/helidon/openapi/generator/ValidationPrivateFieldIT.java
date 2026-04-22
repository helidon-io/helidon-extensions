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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Regression coverage for validation annotations on generated getter methods.
 */
class ValidationPrivateFieldIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = ValidationPrivateFieldIT.class
                .getClassLoader()
                .getResource("validation-private-field.yaml");
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    @Test
    void guardrailsDetailsModelIsGenerated() {
        assertThat(modelFile("CreateGuardrailsOperationDetails.java").isFile(), is(true));
    }

    @Test
    void sourceDetailsModelIsGenerated() {
        assertThat(modelFile("CreateOperationSourceDetails.java").isFile(), is(true));
    }

    @Test
    void guardrailsDetailsModelHasValidatedAnnotation() throws IOException {
        assertThat(read(modelFile("CreateGuardrailsOperationDetails.java")),
                   containsString("@Validation.Validated"));
    }

    @Test
    void requiredStringValidationIsAppliedToGetter() throws IOException {
        assertThat(read(modelFile("CreateGuardrailsOperationDetails.java")),
                   containsString("""
                           @Validation.String.Length(min = 1, value = 255)
                               public String getCompartmentId() {
                           """.stripIndent().trim()));
    }

    @Test
    void optionalStringValidationIsAppliedToGetter() throws IOException {
        assertThat(read(modelFile("CreateGuardrailsOperationDetails.java")),
                   containsString("""
                           @Validation.String.Length(min = 1, value = 255)
                               public String getName() {
                           """.stripIndent().trim()));
    }

    @Test
    void privateFieldIsNotAnnotatedWithValidationLength() throws IOException {
        assertThat(read(modelFile("CreateGuardrailsOperationDetails.java")),
                   not(containsString("""
                           @Validation.String.Length(min = 1, value = 255)
                               @Json.Required
                               private String compartmentId;
                           """.stripIndent().trim())));
    }

    @Test
    void generatedProjectBuildsWithMaven() throws Exception {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    private File modelFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath()).replace("\r\n", "\n");
    }
}
