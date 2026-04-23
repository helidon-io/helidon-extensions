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
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Characterization tests for composed schemas.
 *
 * <p>The generator preserves {@code oneOf} and {@code anyOf} as wrapper models
 * with custom JSON converters and typed accessors, while {@code allOf} is
 * merged into a single POJO with combined properties.</p>
 */
class ComposedSchemaGenerationIT {

    @TempDir
    static Path outputRoot;

    private static Path oneOfOutputDir;
    private static Path anyOfOutputDir;
    private static Path allOfOutputDir;

    @BeforeAll
    static void generate() throws Exception {
        oneOfOutputDir = generateProject("oneof-composed.yaml", "oneof");
        anyOfOutputDir = generateProject("anyof-composed.yaml", "anyof");
        allOfOutputDir = generateProject("allof-composed.yaml", "allof");
    }

    @Test
    void oneOfGeneratesWrapperWithVariantFactoriesAndAccessors() throws IOException {
        String content = read(modelFile(oneOfOutputDir, "PetUnion.java"));
        assertThat(content, containsString("@Json.Converter(PetUnion.PetUnionJsonConverter.class)"));
        assertThat(content, containsString("public final class PetUnion"));
        assertThat(content, containsString("private final JsonValue value;"));
        assertThat(content, containsString("public static PetUnion ofCat(Cat value)"));
        assertThat(content, containsString("public Optional<Cat> asCat()"));
        assertThat(content, containsString("public static boolean matchesJsonValue(JsonValue jsonValue)"));
    }

    @Test
    void oneOfDoesNotFlattenVariantPropertiesIntoWrapperFields() throws IOException {
        String content = read(modelFile(oneOfOutputDir, "PetUnion.java"));
        assertThat(content, org.hamcrest.CoreMatchers.not(containsString("private String name;")));
        assertThat(content, org.hamcrest.CoreMatchers.not(containsString("private Integer lives;")));
        assertThat(content, org.hamcrest.CoreMatchers.not(containsString("private String breed;")));
    }

    @Test
    void anyOfGeneratesWrapperWithVariantFactoriesAndAccessors() throws IOException {
        String content = read(modelFile(anyOfOutputDir, "FlexiblePet.java"));
        assertThat(content, containsString("@Json.Converter(FlexiblePet.FlexiblePetJsonConverter.class)"));
        assertThat(content, containsString("public final class FlexiblePet"));
        assertThat(content, containsString("private final JsonValue value;"));
        assertThat(content, containsString("public static FlexiblePet ofCatBits(CatBits value)"));
        assertThat(content, containsString("public Optional<DogBits> asDogBits()"));
        assertThat(content, containsString("return matches >= 1;"));
    }

    @Test
    void allOfGeneratesSinglePojoWithCombinedProperties() throws IOException {
        String content = read(modelFile(allOfOutputDir, "CombinedPet.java"));
        assertThat(content, containsString("class CombinedPet"));
        assertThat(content, containsString("@Json.Required"));
        assertThat(content, containsString("private String name;"));
        assertThat(content, containsString("private String breed;"));
    }

    @Test
    void oneOfGeneratedProjectBuildsWithMaven() throws Exception {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(oneOfOutputDir);
    }

    @Test
    void anyOfGeneratedProjectBuildsWithMaven() throws Exception {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(anyOfOutputDir);
    }

    @Test
    void allOfGeneratedProjectBuildsWithMaven() throws Exception {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(allOfOutputDir);
    }

    private static Path generateProject(String resourceName, String directoryName) throws Exception {
        URL resource = ComposedSchemaGenerationIT.class.getClassLoader().getResource(resourceName);
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();
        Path outputDir = outputRoot.resolve(directoryName);

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        return outputDir;
    }

    private Path modelFile(Path outputDir, String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + name);
    }

    private String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }
}
