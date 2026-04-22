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
 * Integration test for security code generation.
 * Uses a spec that has operations with security requirements to verify
 * that @RoleValidator.Roles, SecurityContext, and security dependencies are generated.
 */
class SecurityGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        var resource = SecurityGenerationIT.class.getClassLoader().getResource("secured-api.yaml");
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    // -------------------------------------------------------------------------
    // Endpoint: SecurityContext and API contract implementation
    // -------------------------------------------------------------------------

    @Test
    void endpointImplementsApiContract() throws IOException {
        assertThat(read(apiFile("ItemsEndpoint.java")),
                   containsString("implements ItemsApi"));
    }

    @Test
    void endpointSecuredMethodHasSecurityContextParam() throws IOException {
        // createItem is secured → SecurityContext injected as unannotated param
        assertThat(read(apiFile("ItemsEndpoint.java")),
                   containsString("SecurityContext securityContext"));
    }

    @Test
    void endpointMultiRoleMethodHasArrayAnnotation() throws IOException {
        // deleteItem has security: [{basicAuth: [admin, moderator]}] on the API interface
        assertThat(read(apiFile("ItemsApi.java")),
                   containsString("@RoleValidator.Roles({\"admin\", \"moderator\"})"));
    }

    @Test
    void apiInterfaceSecuredMethodHasRoleValidatorAnnotation() throws IOException {
        // createItem has security: [{basicAuth: [admin]}]
        assertThat(read(apiFile("ItemsApi.java")),
                   containsString("@RoleValidator.Roles(\"admin\")"));
    }

    @Test
    void endpointHasSecurityImports() throws IOException {
        String content = read(apiFile("ItemsEndpoint.java"));
        assertThat(content, containsString("import io.helidon.security.SecurityContext;"));
    }

    // -------------------------------------------------------------------------
    // Interface: @RoleValidator.Roles, no SecurityContext
    // -------------------------------------------------------------------------

    @Test
    void interfaceSecuredMethodHasRoleValidatorAnnotation() throws IOException {
        assertThat(read(apiFile("ItemsApi.java")),
                   containsString("@RoleValidator.Roles(\"admin\")"));
    }

    @Test
    void interfaceHasNoSecurityContextParam() throws IOException {
        // SecurityContext is server-side only — must not appear in the interface
        assertThat(read(apiFile("ItemsApi.java")),
                   not(containsString("SecurityContext")));
    }

    @Test
    void interfaceHasRoleValidatorImport() throws IOException {
        assertThat(read(apiFile("ItemsApi.java")),
                   containsString("import io.helidon.security.abac.role.RoleValidator;"));
    }

    // -------------------------------------------------------------------------
    // pom.xml: security dependencies
    // -------------------------------------------------------------------------

    @Test
    void pomHasHelidonSecurityDependency() throws IOException {
        String content = read(outputDir.resolve("pom.xml").toFile().toPath());
        assertThat(content, containsString("helidon-security"));
        assertThat(content, containsString("helidon-webserver-security"));
        assertThat(content, containsString("helidon-security-abac-role"));
        assertThat(content, containsString("helidon-security-providers-abac"));
    }

    @Test
    void buildGradleHasHelidonSecurityDependencies() throws IOException {
        String content = read(outputDir.resolve("build.gradle"));
        assertThat(content, containsString("io.helidon.security:helidon-security"));
        assertThat(content, containsString("io.helidon.webserver:helidon-webserver-security"));
        assertThat(content, containsString("io.helidon.security.abac:helidon-security-abac-role"));
        assertThat(content, containsString("io.helidon.security.providers:helidon-security-providers-abac"));
    }

    // -------------------------------------------------------------------------
    // application.yaml: security config section
    // -------------------------------------------------------------------------

    @Test
    void applicationYamlHasSecurityShowsRequiredComment() throws IOException {
        assertThat(read(outputDir.resolve("src/main/resources/application.yaml")),
                   containsString("required: one or more operations use @RoleValidator.Roles"));
    }

    @Test
    void generatedProjectBuildsWithMaven() throws Exception {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private java.io.File apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name).toFile();
    }

    private String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private String read(java.io.File file) throws IOException {
        return Files.readString(file.toPath()).replace("\r\n", "\n");
    }
}
