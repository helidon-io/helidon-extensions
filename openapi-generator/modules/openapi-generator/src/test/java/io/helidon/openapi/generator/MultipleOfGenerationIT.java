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
 * Integration test for OpenAPI {@code multipleOf} validation generation on parameters.
 */
class MultipleOfGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = MultipleOfGenerationIT.class
                .getClassLoader()
                .getResource("multiple-of-features.yaml");
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
    void endpointIntegerParamHasMultipleOfValidation() throws IOException {
        assertThat(read(apiFile("RulesEndpoint.java")),
                   containsString("@Validation.Integer.MultipleOf(5)"));
    }

    @Test
    void endpointLongParamHasMultipleOfValidation() throws IOException {
        assertThat(read(apiFile("RulesEndpoint.java")),
                   containsString("@Validation.Long.MultipleOf(10L)"));
    }

    @Test
    void endpointNumberParamHasMultipleOfValidation() throws IOException {
        assertThat(read(apiFile("RulesEndpoint.java")),
                   containsString("@Validation.Number.MultipleOf(\"0.25\")"));
    }

    @Test
    void apiInterfaceIntegerParamHasMultipleOfValidation() throws IOException {
        assertThat(read(apiFile("RulesApi.java")),
                   containsString("@Validation.Integer.MultipleOf(5)"));
    }

    @Test
    void apiInterfaceLongParamHasMultipleOfValidation() throws IOException {
        assertThat(read(apiFile("RulesApi.java")),
                   containsString("@Validation.Long.MultipleOf(10L)"));
    }

    @Test
    void apiInterfaceNumberParamHasMultipleOfValidation() throws IOException {
        assertThat(read(apiFile("RulesApi.java")),
                   containsString("@Validation.Number.MultipleOf(\"0.25\")"));
    }

    private File apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath()).replace("\r\n", "\n");
    }
}
