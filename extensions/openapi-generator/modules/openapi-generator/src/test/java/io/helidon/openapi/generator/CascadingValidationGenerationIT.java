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
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Source-generation coverage for cascading Helidon validation.
 */
class CascadingValidationGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        generate(outputDir, null);
    }

    @Test
    void computesTransitiveValidationFixedPoint() throws IOException {
        assertValidated("ConstrainedLeaf.java");
        assertValidated("ValidationIntermediate.java");
        assertValidated("ValidationRoot.java");
        assertValidated("RecursiveNode.java");
    }

    @Test
    void annotatesDirectAndSupportedContainerBoundaries() throws IOException {
        String intermediate = read(modelFile(outputDir, "ValidationIntermediate.java"));
        assertThat(intermediate, containsString("@Validation.Valid\n    public ConstrainedLeaf leaf()"));
        assertThat(intermediate, not(containsString("private @Validation.Valid")));

        String root = read(modelFile(outputDir, "ValidationRoot.java"));
        assertThat(root, containsString("public List<@Validation.Valid ConstrainedLeaf> leaves()"));
        assertThat(root, containsString("public Set<@Validation.Valid ConstrainedLeaf> uniqueLeaves()"));
        assertThat(root, containsString("public Map<String, @Validation.Valid ConstrainedLeaf> leavesByName()"));
        assertThat(root, containsString("public List<List<@Validation.Valid ConstrainedLeaf>> nestedLeaves()"));

        String recursive = read(modelFile(outputDir, "RecursiveNode.java"));
        assertThat(recursive, containsString("public List<@Validation.Valid RecursiveNode> children()"));
    }

    @Test
    void annotatesRequestEntityInContractAndEndpoint() throws IOException {
        String expected = "@Validation.Valid @Http.Entity ValidationRoot validationRoot";
        assertThat(read(apiFile(outputDir, "ValidationApi.java")), containsString(expected));
        assertThat(read(apiFile(outputDir, "ValidationEndpoint.java")), containsString(expected));
    }

    @Test
    void addsValidationBuildDependencies() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml")), containsString("helidon-validation"));
        assertThat(read(outputDir.resolve("pom.xml")), containsString("helidon-webserver-validation"));
        assertThat(read(outputDir.resolve("build.gradle")),
                   containsString("io.helidon.webserver:helidon-webserver-validation"));
    }

    @Test
    void supportsCollectionAndOptionalMappings() throws Exception {
        Path collectionOutput = outputDir.resolve("collection");
        generate(collectionOutput, "Collection");
        assertThat(read(modelFile(collectionOutput, "ValidationRoot.java")),
                   containsString("Collection<@Validation.Valid ConstrainedLeaf> leaves()"));

        Path optionalOutput = outputDir.resolve("optional");
        generate(optionalOutput, "Optional");
        assertThat(read(modelFile(optionalOutput, "ValidationRoot.java")),
                   containsString("Optional<@Validation.Valid ConstrainedLeaf> leaves()"));
    }

    @Test
    void rejectsArbitraryIterableMappingsBeforeRendering() {
        Path iterableOutput = outputDir.resolve("iterable");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                                                       () -> generate(iterableOutput, "Iterable"));
        assertThat(error.getMessage(), containsString("schema 'RecursiveNode', property 'children'"));
        assertThat(error.getMessage(), containsString("mapped Java type 'Iterable<RecursiveNode>'"));
        assertThat(error.getMessage(), containsString("participating generated model type(s) [RecursiveNode]"));
        assertThat(error.getMessage(), containsString("Cascading cannot be guaranteed"));
        assertThat(error.getMessage(), containsString("List, Set, Collection, Optional, Map values, or an array"));
        assertThat(Files.exists(modelFile(iterableOutput, "RecursiveNode.java")),
                   org.hamcrest.CoreMatchers.is(false));
    }

    @Test
    void repeatGenerationIsByteStable() throws Exception {
        Path secondOutput = outputDir.resolve("second");
        generate(secondOutput, null);
        assertThat(read(modelFile(secondOutput, "ValidationRoot.java")),
                   org.hamcrest.CoreMatchers.is(read(modelFile(outputDir, "ValidationRoot.java"))));
        assertThat(read(apiFile(secondOutput, "ValidationApi.java")),
                   org.hamcrest.CoreMatchers.is(read(apiFile(outputDir, "ValidationApi.java"))));
    }

    private static void assertValidated(String fileName) throws IOException {
        assertThat(read(modelFile(outputDir, fileName)), containsString("@Validation.Validated"));
    }

    private static void generate(Path target, String arrayMapping) throws Exception {
        URL resource = CascadingValidationGenerationIT.class.getClassLoader()
                .getResource("cascading-validation.yaml");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(Paths.get(resource.toURI()).toAbsolutePath().toString())
                .setOutputDir(target.toString())
                .addAdditionalProperty("helidonVersion", "4.5.0")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");
        if (arrayMapping != null) {
            configurator.addTypeMapping("array", arrayMapping);
        }
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    private static Path modelFile(Path root, String name) {
        return root.resolve("src/main/java/io/helidon/example/model/" + name);
    }

    private static Path apiFile(Path root, String name) {
        return root.resolve("src/main/java/io/helidon/example/api/" + name);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }
}
