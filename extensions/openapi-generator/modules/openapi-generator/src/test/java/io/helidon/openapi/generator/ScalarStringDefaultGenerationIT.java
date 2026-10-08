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

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;

class ScalarStringDefaultGenerationIT {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"string-defaults.yaml", "string defaults.yaml", "string défauts.yaml"})
    void scalarStringDefaultsRemainJavaExpressions(String filename) throws Exception {
        var resource = getClass().getClassLoader().getResource("scalar-string-defaults.yaml");
        Path inputSpec = directory.resolve(filename);
        Files.copy(Path.of(resource.toURI()), inputSpec);
        Path outputDir = directory.resolve("generated");
        var configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(inputSpec.toUri().toASCIIString())
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.5.0")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model");
        var generator = new DefaultGenerator();
        generator.setGeneratorPropertyDefault("supportingFiles", "false");
        generator.setGeneratorPropertyDefault("apis", "false");
        generator.setGeneratorPropertyDefault("models", "true");
        generator.opts(configurator.toClientOptInput()).generate();

        String defaults = Files.readString(outputDir.resolve("src/main/java/io/helidon/example/model/StringDefaults.java"));
        assertThat(defaults, containsString("private String plain = \"plain\";"));
        assertThat(defaults, containsString("private String empty = \"\";"));
        assertThat(defaults, containsString("private String quoted = \"say \\\"hello\\\"\";"));
        assertThat(defaults, containsString("private String backslash = \"C:\\\\temp\\\\file\";"));
        assertThat(defaults, containsString("private String multiline = \"line1\\nline2\\tend\";"));
        assertThat(defaults, containsString("private String absent;"));
    }
}
