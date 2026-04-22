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
 * Integration test — validates that OpenAPI schema constraints are mapped to
 * Helidon {@code @Validation.*} annotations in generated model classes.
 */
class ValidationGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = ValidationGenerationIT.class
                .getClassLoader()
                .getResource("validated.yaml");
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
    // @Validation.Validated on the class
    // -------------------------------------------------------------------------

    @Test
    void itemModelHasValidatedAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Validated"));
    }

    @Test
    void itemModelImportsValidation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("import io.helidon.validation.Validation;"));
    }

    // -------------------------------------------------------------------------
    // String constraints: name (minLength=1, maxLength=100, pattern)
    // -------------------------------------------------------------------------

    @Test
    void itemModelNameHasLengthAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.String.Length(min = 1, value = 100)"));
    }

    @Test
    void itemModelNameHasPatternAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.String.Pattern("));
    }

    // -------------------------------------------------------------------------
    // Integer constraints: quantity (minimum=0, maximum=1000)
    // -------------------------------------------------------------------------

    @Test
    void itemModelQuantityHasMinAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Integer.Min(0)"));
    }

    @Test
    void itemModelQuantityHasMultipleOfAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Integer.MultipleOf(5)"));
    }

    @Test
    void itemModelQuantityHasMaxAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Integer.Max(1000)"));
    }

    @Test
    void itemModelBatchIdHasExclusiveLongMinAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Long.Min(101L)"));
    }

    @Test
    void itemModelBatchIdHasLongMultipleOfAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Long.MultipleOf(10L)"));
    }

    @Test
    void itemModelBatchIdHasExclusiveLongMaxAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Long.Max(999L)"));
    }

    // -------------------------------------------------------------------------
    // Number constraints: price (minimum=0.01)
    // -------------------------------------------------------------------------

    @Test
    void itemModelPriceHasNumberMinAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Number.Min(\"0.01\")"));
    }

    @Test
    void itemModelPriceHasNumberMultipleOfAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Number.MultipleOf(\"0.25\")"));
    }

    // -------------------------------------------------------------------------
    // Array constraints: tags (minItems=1, maxItems=20)
    // -------------------------------------------------------------------------

    @Test
    void itemModelTagsHasSizeAnnotation() throws IOException {
        assertThat(read(modelFile("Item.java")),
                   containsString("@Validation.Collection.Size(min = 1, value = 20)"));
    }

    // -------------------------------------------------------------------------
    // pom.xml: helidon-validation dependency
    // -------------------------------------------------------------------------

    @Test
    void pomHasValidationDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()),
                   containsString("helidon-validation"));
    }

    @Test
    void buildGradleHasValidationDependency() throws IOException {
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

    private File modelFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath()).replace("\r\n", "\n");
    }
}
