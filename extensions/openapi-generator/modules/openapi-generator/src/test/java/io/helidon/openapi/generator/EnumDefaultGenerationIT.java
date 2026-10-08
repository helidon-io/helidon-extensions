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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;

class EnumDefaultGenerationIT {
    @TempDir
    Path directory;

    @Test
    void enumDefaultsRemainJavaExpressions() throws Exception {
        var resource = getClass().getClassLoader().getResource("enum-defaults.yaml");
        var configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(Path.of(resource.toURI()).toString())
                .setOutputDir(directory.toString())
                .addAdditionalProperty("helidonVersion", "4.5.0")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model");
        var generator = new DefaultGenerator();
        generator.setGeneratorPropertyDefault("supportingFiles", "false");
        generator.setGeneratorPropertyDefault("apis", "false");
        generator.setGeneratorPropertyDefault("models", "true");
        generator.opts(configurator.toClientOptInput()).generate();

        String item = Files.readString(directory.resolve("src/main/java/io/helidon/example/model/Item.java"));
        assertThat(item, containsString("private Mode typedMode = Mode.NONE;"));
        assertThat(item, containsString("private Mode mode = Mode.NONE;"));
        assertThat(item, containsString("private InlineModeEnum inlineMode = InlineModeEnum.NONE;"));
        assertThat(item, containsString("private Integer count = 7;"));
        assertThat(item, containsString("private Boolean enabled = true;"));
        assertThat(item, containsString("private String label;"));
    }
}
