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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Integration test for non-JSON response media types.
 */
class MediaTypesGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        var resource = MediaTypesGenerationIT.class.getClassLoader().getResource("media-types.yaml");
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-se-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.4.0")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    @Test
    void apiInterfaceUsesHelidonConstantForPlainTextProduces() throws IOException {
        assertThat(read(apiFile("ReportsApi.java")),
                   containsString("@Http.Produces(MediaTypes.TEXT_PLAIN_VALUE)"));
    }

    @Test
    void apiInterfaceUsesStringLiteralFallbackForCustomProducesType() throws IOException {
        assertThat(read(apiFile("ReportsApi.java")),
                   containsString("@Http.Produces(\"text/csv\")"));
    }

    @Test
    void plainTextOperationDoesNotFallBackToJsonProduces() throws IOException {
        String content = read(apiFile("ReportsApi.java"));
        assertThat(content,
                   not(containsString("@Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)\n    String getPlainReport(")));
    }

    @Test
    void generatedProjectBuildsWithMaven() throws Exception {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    private Path apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name);
    }

    private String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
