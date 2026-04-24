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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

class DiscriminatorEnumAllOfGenerationIT {

    @TempDir
    Path tempDir;

    @Test
    void explicitEnumDiscriminatorSubtypeSeedsEnumConstant() throws Exception {
        Path outputDir = generate("discriminator-enum-repro.yaml");
        String content = read(modelFile(outputDir, "RegionHealthCheckCategoryDetails.java"));
        assertThat(content, containsString("public RegionHealthCheckCategoryDetails()"));
        assertThat(content, containsString("setCategory(MqlCheckDetails.CategoryEnum.REGION_HEALTH_CHECK);"));
        assertThat(content, not(containsString("setCategory(\"REGION_HEALTH_CHECK\")")));
    }

    @Test
    void baseDiscriminatorRemainsEnumTyped() throws Exception {
        Path outputDir = generate("discriminator-enum-repro.yaml");
        String content = read(modelFile(outputDir, "MqlCheckDetails.java"));
        assertThat(content, containsString("private CategoryEnum category;"));
        assertThat(content, containsString("public void setCategory(CategoryEnum category)"));
        assertThat(content, containsString("public enum CategoryEnum"));
    }

    @Test
    void mappedEnumDiscriminatorSubtypeUsesActualDiscriminatorValue() throws Exception {
        Path outputDir = generate("discriminator-enum-mapping-repro.yaml");

        String parent = read(modelFile(outputDir, "ConditionShapeDetails.java"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"CHANGE_FREEZE\", value = ChangeFreezeConditionShape.class)"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"TIME_WINDOW_CONSTRAINTS\", value = TimeWindowConstraintsConditionShape.class)"));

        String changeFreeze = read(modelFile(outputDir, "ChangeFreezeConditionShape.java"));
        assertThat(changeFreeze, containsString("setConditionShape(ConditionShapeDetails.ConditionShapeEnum.CHANGE_FREEZE);"));
        assertThat(changeFreeze, not(containsString("CHANGE_FREEZE_CONDITION_SHAPE")));

        String timeWindow = read(modelFile(outputDir, "TimeWindowConstraintsConditionShape.java"));
        assertThat(timeWindow, containsString("setConditionShape(ConditionShapeDetails.ConditionShapeEnum.TIME_WINDOW_CONSTRAINTS);"));
        assertThat(timeWindow, not(containsString("TIME_WINDOW_CONSTRAINTS_CONDITION_SHAPE")));
    }

    @Test
    void mappedEnumDiscriminatorGeneratedProjectBuilds() throws Exception {
        Path outputDir = generate("discriminator-enum-mapping-repro.yaml");
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    @Test
    void mappedEnumDiscriminatorSubtypeUsesActualDiscriminatorValueInSimpleHierarchy() throws Exception {
        Path outputDir = generate("discriminator-enum-mapping-repro-2.yaml");

        String parent = read(modelFile(outputDir, "UserConfig.java"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"INSTANT\", value = UserConfigInstantValue.class)"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"STRING\", value = UserConfigStringValue.class)"));

        String stringValue = read(modelFile(outputDir, "UserConfigStringValue.java"));
        assertThat(stringValue, containsString("setType(UserConfig.TypeEnum.STRING);"));
        assertThat(stringValue, not(containsString("USER_CONFIG_STRING_VALUE")));

        String instantValue = read(modelFile(outputDir, "UserConfigInstantValue.java"));
        assertThat(instantValue, containsString("setType(UserConfig.TypeEnum.INSTANT);"));
        assertThat(instantValue, not(containsString("USER_CONFIG_INSTANT_VALUE")));
    }

    @Test
    void mappedEnumDiscriminatorGeneratedProjectBuildsInSimpleHierarchy() throws Exception {
        Path outputDir = generate("discriminator-enum-mapping-repro-2.yaml");
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    private Path generate(String resourceName) throws Exception {
        URL resource = DiscriminatorEnumAllOfGenerationIT.class
                .getClassLoader()
                .getResource(resourceName);
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();
        Path outputDir = tempDir.resolve(resourceName.replace(".yaml", ""));

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        return outputDir;
    }

    private static File modelFile(Path outputDir, String fileName) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + fileName).toFile();
    }

    private static String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
