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
 * Integration test for global OpenAPI security requirements.
 * Verifies that top-level security defaults are inherited by operations unless explicitly overridden.
 */
class GlobalSecurityGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        var resource = GlobalSecurityGenerationIT.class.getClassLoader().getResource("global-secured-api.yaml");
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-se-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    @Test
    void interfaceInheritedSecuredMethodHasRoleValidatorAnnotation() throws IOException {
        assertThat(read(apiFile("ItemsApi.java")),
                   containsString("@RoleValidator.Roles(\"admin\")\n    List<Item> listItems("));
    }

    @Test
    void endpointInheritedSecuredMethodHasSecurityContextParam() throws IOException {
        assertThat(read(apiFile("ItemsEndpoint.java")),
                   containsString("public List<Item> listItems(SecurityContext securityContext)"));
    }

    @Test
    void explicitlyUnsecuredOperationDoesNotInheritGlobalSecurity() throws IOException {
        String content = read(apiFile("ItemsApi.java"));
        assertThat(content,
                   not(containsString("@RoleValidator.Roles(\"admin\")\n    Item createPublicItem(")));
    }

    @Test
    void generatedProjectStillIncludesSecurityDependencies() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml")),
                   containsString("helidon-security"));
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
