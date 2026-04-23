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

class DiscriminatorEnumAllOfGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = DiscriminatorEnumAllOfGenerationIT.class
                .getClassLoader()
                .getResource("discriminator-enum-repro.yaml");
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
    void enumDiscriminatorSubtypeSeedsEnumConstant() throws IOException {
        String content = read(modelFile("RegionHealthCheckCategoryDetails.java"));
        assertThat(content, containsString("public RegionHealthCheckCategoryDetails()"));
        assertThat(content, containsString("setCategory(MqlCheckDetails.CategoryEnum.REGION_HEALTH_CHECK);"));
        assertThat(content, not(containsString("setCategory(\"REGION_HEALTH_CHECK\")")));
    }

    @Test
    void baseDiscriminatorRemainsEnumTyped() throws IOException {
        String content = read(modelFile("MqlCheckDetails.java"));
        assertThat(content, containsString("private CategoryEnum category;"));
        assertThat(content, containsString("public void setCategory(CategoryEnum category)"));
        assertThat(content, containsString("public enum CategoryEnum"));
    }

    private static File modelFile(String fileName) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + fileName).toFile();
    }

    private static String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
