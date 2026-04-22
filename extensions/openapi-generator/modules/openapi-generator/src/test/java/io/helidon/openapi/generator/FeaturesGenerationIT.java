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
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Integration test — verifies gap features: deprecated, Javadoc, enums,
 * default values, nullable, and parameter-level validation.
 */
class FeaturesGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = FeaturesGenerationIT.class
                .getClassLoader()
                .getResource("features.yaml");
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

    // -------------------------------------------------------------------------
    // deprecated: true → @Deprecated on operation method
    // -------------------------------------------------------------------------

    @Test
    void apiInterfaceDeprecatedOperationHasDeprecatedAnnotation() throws IOException {
        assertThat(read(apiFile("ThingsApi.java")),
                   containsString("@Deprecated"));
    }

    @Test
    void endpointImplementsApiContract() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")),
                   containsString("implements ThingsApi"));
    }

    // -------------------------------------------------------------------------
    // summary / description → Javadoc
    // -------------------------------------------------------------------------

    @Test
    void endpointOperationHasJavadocFromSummary() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")),
                   containsString("List all things"));
    }

    @Test
    void endpointOperationHasJavadocFromDescription() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")),
                   containsString("Returns a paginated list of things"));
    }

    @Test
    void apiInterfaceOperationHasJavadocFromSummary() throws IOException {
        assertThat(read(apiFile("ThingsApi.java")),
                   containsString("List all things"));
    }

    // -------------------------------------------------------------------------
    // Enum property → inner enum class in model
    // -------------------------------------------------------------------------

    @Test
    void modelEnumPropertyHasInnerEnumClass() throws IOException {
        String content = read(modelFile("Thing.java"));
        assertThat(content, containsString("public enum StatusEnum"));
    }

    @Test
    void modelEnumPropertyHasEnumConstants() throws IOException {
        String content = read(modelFile("Thing.java"));
        assertThat(content, containsString("ACTIVE"));
        assertThat(content, containsString("INACTIVE"));
        assertThat(content, containsString("PENDING"));
    }

    @Test
    void modelEnumPropertyFieldUsesEnumType() throws IOException {
        assertThat(read(modelFile("Thing.java")),
                   containsString("StatusEnum status"));
    }

    // -------------------------------------------------------------------------
    // Default values → field initializers
    // -------------------------------------------------------------------------

    @Test
    void modelStringEnumDefaultHasInitializer() throws IOException {
        // status has default: active → StatusEnum.ACTIVE
        assertThat(read(modelFile("Thing.java")),
                   containsString("StatusEnum.ACTIVE"));
    }

    @Test
    void modelIntegerDefaultHasInitializer() throws IOException {
        // count has default: 0
        assertThat(read(modelFile("Thing.java")),
                   containsString("= 0"));
    }

    @Test
    void modelDoubleDefaultHasInitializer() throws IOException {
        // score has default: 1.0
        assertThat(read(modelFile("Thing.java")),
                   containsString("= 1.0"));
    }

    // -------------------------------------------------------------------------
    // Property Javadoc from description
    // -------------------------------------------------------------------------

    @Test
    void modelPropertyHasJavadocFromDescription() throws IOException {
        assertThat(read(modelFile("Thing.java")),
                   containsString("Unique identifier"));
    }

    // -------------------------------------------------------------------------
    // Parameter-level validation annotations
    // -------------------------------------------------------------------------

    @Test
    void endpointStringParamHasLengthValidation() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")),
                   containsString("@Validation.String.Length(min = 1, value = 20)"));
    }

    @Test
    void endpointIntParamHasMinMaxValidation() throws IOException {
        String content = read(apiFile("ThingsEndpoint.java"));
        assertThat(content, containsString("@Validation.Integer.Min(1)"));
        assertThat(content, containsString("@Validation.Integer.Max(100)"));
    }

    @Test
    void endpointIntParamHasExclusiveMinMaxValidation() throws IOException {
        String content = read(apiFile("ThingsEndpoint.java"));
        assertThat(content, containsString("@Validation.Integer.Min(11)"));
        assertThat(content, containsString("@Validation.Integer.Max(19)"));
    }

    @Test
    void endpointOptionalListParamUsesOptionalListTypeByDefault() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")),
                   containsString("@Http.QueryParam(\"tags\") Optional<List<String>> tags"));
    }

    @Test
    void endpointParamValidationImportsValidation() throws IOException {
        assertThat(read(apiFile("ThingsEndpoint.java")),
                   containsString("import io.helidon.validation.Validation;"));
    }

    @Test
    void apiInterfaceStringParamHasLengthValidation() throws IOException {
        assertThat(read(apiFile("ThingsApi.java")),
                   containsString("@Validation.String.Length(min = 1, value = 20)"));
    }

    @Test
    void apiInterfaceHasValidatedAnnotationWhenParamValidationIsPresent() throws IOException {
        assertThat(read(apiFile("ThingsApi.java")),
                   containsString("@Validation.Validated"));
    }

    @Test
    void apiInterfaceIntParamHasExclusiveMinMaxValidation() throws IOException {
        String content = read(apiFile("ThingsApi.java"));
        assertThat(content, containsString("@Validation.Integer.Min(11)"));
        assertThat(content, containsString("@Validation.Integer.Max(19)"));
    }

    @Test
    void apiInterfaceOptionalListParamUsesOptionalListTypeByDefault() throws IOException {
        assertThat(read(apiFile("ThingsApi.java")),
                   containsString("@Http.QueryParam(\"tags\") Optional<List<String>> tags"));
    }

    @Test
    void pomHasValidationDependencyForParameterValidation() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()),
                   containsString("helidon-validation"));
    }

    @Test
    void buildGradleHasValidationDependencyForParameterValidation() throws IOException {
        assertThat(read(outputDir.resolve("build.gradle").toFile()),
                   containsString("io.helidon.validation:helidon-validation"));
    }

    @Test
    void generatedProjectBuildsWithMaven() throws Exception {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private File apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name).toFile();
    }

    private File modelFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath()).replace("\r\n", "\n");
    }
}
