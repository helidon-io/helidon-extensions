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
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

class DiscriminatorAllOfGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = DiscriminatorAllOfGenerationIT.class
                .getClassLoader()
                .getResource("discriminator-allof.yaml");
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
    void discriminatorBaseHasPolymorphicAnnotations() throws IOException {
        String content = read(modelFile("CreateConditionShapeDetails.java"));
        assertThat(content, containsString("@Json.Polymorphic(key = \"conditionShape\")"));
        assertThat(content, containsString("@Json.Subtype(alias = \"CHANGE_FREEZE\", value = CreateChangeFreezeConditionShape.class)"));
    }

    @Test
    void discriminatorAllOfSubtypeExtendsBase() throws IOException {
        String content = read(modelFile("CreateChangeFreezeConditionShape.java"));
        assertThat(content, containsString("public class CreateChangeFreezeConditionShape extends CreateConditionShapeDetails"));
        assertThat(content, containsString("private ChangeFreezeDetails changeFreezeDetails;"));
        assertThat(content, not(containsString("private String conditionShape;")));
        assertThat(content, not(containsString("private List<ApplicableScope> applicableScopes;")));
    }

    @Test
    void discriminatorAllOfSubtypeInitializesInheritedDiscriminator() throws IOException {
        String content = read(modelFile("CreateChangeFreezeConditionShape.java"));
        assertThat(content, containsString("public CreateChangeFreezeConditionShape()"));
        assertThat(content, containsString("setConditionShape(\"CHANGE_FREEZE\");"));
    }

    private static File modelFile(String fileName) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + fileName).toFile();
    }

    private static String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
