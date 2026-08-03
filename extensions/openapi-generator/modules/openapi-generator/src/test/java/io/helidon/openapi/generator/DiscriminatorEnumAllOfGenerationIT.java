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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscriminatorEnumAllOfGenerationIT {

    @TempDir
    Path tempDir;

    @Test
    void exactMappedEnumAliasesProduceDerivedEnumAccessors() throws Exception {
        Path output = generate("discriminator-enum-mapping-repro.yaml");
        String parent = read(output, "ConditionShapeDetails");
        String changeFreeze = read(output, "ChangeFreezeConditionShape");
        String timeWindow = read(output, "TimeWindowConstraintsConditionShape");

        assertThat(parent, containsString("public abstract ConditionShapeEnum conditionShape();"));
        assertThat(parent,
                   containsString("@Json.Subtype(alias = \"CHANGE_FREEZE\", value = ChangeFreezeConditionShape.class)"));
        assertThat(parent, not(containsString("private ConditionShapeEnum conditionShape;")));
        assertThat(changeFreeze,
                   containsString("return ConditionShapeDetails.ConditionShapeEnum.CHANGE_FREEZE;"));
        assertThat(timeWindow,
                   containsString("return ConditionShapeDetails.ConditionShapeEnum.TIME_WINDOW_CONSTRAINTS;"));
        assertThat(changeFreeze, not(containsString("void conditionShape(")));
    }

    @Test
    void exactMappedEnumAliasesWorkInSimpleHierarchy() throws Exception {
        Path output = generate("discriminator-enum-mapping-repro-2.yaml");
        assertThat(read(output, "UserConfig"), containsString("public abstract TypeEnum type();"));
        assertThat(read(output, "UserConfigStringValue"),
                   containsString("return UserConfig.TypeEnum.STRING;"));
        assertThat(read(output, "UserConfigInstantValue"),
                   containsString("return UserConfig.TypeEnum.INSTANT;"));
    }

    @Test
    void legacySubtypeAliasInferenceIsNotApplied() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                        () -> generate("discriminator-enum-repro.yaml"));
        assertThat(exception.getMessage(), containsString("Unable to resolve discriminator value"));
        assertThat(exception.getMessage(), containsString("RegionHealthCheckCategoryDetails"));
        assertThat(exception.getMessage(), containsString("property 'category'"));
    }

    private Path generate(String resourceName) throws Exception {
        URL resource = getClass().getClassLoader().getResource(resourceName);
        Path spec = Paths.get(resource.toURI()).toAbsolutePath();
        Path output = tempDir.resolve(resourceName.replace(".yaml", ""));
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(spec.toString())
                .setOutputDir(output.toString())
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        return output;
    }

    private String read(Path output, String model) throws IOException {
        return Files.readString(output.resolve("src/main/java/io/helidon/example/model/" + model + ".java"));
    }
}
