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
 * Integration test — verifies that {@code ftEnabled=true} adds {@code @Ft.Retry} to
 * generated REST client interfaces and includes {@code helidon-fault-tolerance} in pom.xml.
 */
class FtGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = FtGenerationIT.class
                .getClassLoader()
                .getResource("petstore.yaml");
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example")
                .addAdditionalProperty("ftEnabled", "true");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    @Test
    void clientHasFtRetryAnnotation() throws IOException {
        assertThat(read(apiFile("PetsClient.java")),
                   containsString("@Ft.Retry"));
    }

    @Test
    void clientImportsFt() throws IOException {
        assertThat(read(apiFile("PetsClient.java")),
                   containsString("import io.helidon.faulttolerance.Ft;"));
    }

    @Test
    void pomHasFaultToleranceDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()),
                   containsString("helidon-fault-tolerance"));
    }

    @Test
    void buildGradleHasFaultToleranceDependency() throws IOException {
        assertThat(read(outputDir.resolve("build.gradle").toFile()),
                   containsString("io.helidon.fault-tolerance:helidon-fault-tolerance"));
    }

    @Test
    void generatedProjectBuildsWithMaven() throws Exception {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    private File apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath()).replace("\r\n", "\n");
    }
}
