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
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonStringEnumGenerationIT {

    @TempDir
    static Path tempDir;

    private static Path outputDir;
    private static Path regeneratedDir;

    @BeforeAll
    static void generate() throws Exception {
        outputDir = tempDir.resolve("generated");
        regeneratedDir = tempDir.resolve("regenerated");
        generate(outputDir);
        generate(regeneratedDir);
    }

    @Test
    void topLevelEnumPreservesWireValuesAndRegistersExactServices() throws IOException {
        String mode = read(outputDir.resolve("src/main/java/io/helidon/example/model/Mode.java"));

        assertThat(mode, containsString("public enum Mode"));
        assertThat(mode, containsString("(\"fast-mode\")"));
        assertThat(mode, containsString("(\"authZ\")"));
        assertThat(mode, containsString("(\"legacy.value\")"));
        assertThat(mode, containsString("public String value()"));
        assertThat(mode, containsString("public static Mode fromValue(String value)"));
        assertThat(mode, containsString("implements JsonConverter<Mode>, Mapper<String, Mode>"));
        assertThat(mode, not(containsString("MapperProvider")));
    }

    @Test
    void inlineReferencedAndCollectionEnumsRemainTyped() throws IOException {
        String envelope = read(outputDir.resolve("src/main/java/io/helidon/example/model/EnumEnvelope.java"));

        assertThat(envelope, containsString("private InlineModeEnum inlineMode"));
        assertThat(envelope, containsString("private Mode mode"));
        assertThat(envelope, containsString("private List<Mode> modes"));
        assertThat(envelope, containsString("private List<InlineModesEnum> inlineModes"));
        assertThat(envelope, containsString("public enum InlineModeEnum"));
        assertThat(envelope, containsString("(\"on-hold\")"));
        assertThat(envelope, containsString("public enum InlineModesEnum"));
        assertThat(envelope, containsString("(\"batchAuthZ\")"));
        assertThat(envelope, containsString("implements JsonConverter<EnumEnvelope.InlineModeEnum>"));
        assertThat(envelope, containsString("implements JsonConverter<EnumEnvelope.InlineModesEnum>"));
    }

    @Test
    void operationParametersAndRequestEntityUseGeneratedEnums() throws IOException {
        String api = read(outputDir.resolve("src/main/java/io/helidon/example/api/EnumsApi.java"));

        assertThat(api, containsString("InspectModeRouteModeEnum routeMode"));
        assertThat(api, containsString("Optional<Mode> mode"));
        assertThat(api, containsString("Optional<List<Mode>> modes"));
        assertThat(api, containsString("InspectModeXTraceModeEnum xTraceMode"));
        assertThat(api, containsString("@Http.Entity InspectModeBodyEnum body"));
        assertThat(api, containsString("implements JsonConverter<EnumsApi.InspectModeRouteModeEnum>, "
                                               + "Mapper<String, EnumsApi.InspectModeRouteModeEnum>"));
        assertThat(api, containsString("implements JsonConverter<EnumsApi.InspectModeBodyEnum>"));
        assertThat(api, not(containsString("Mapper<String, EnumsApi.InspectModeBodyEnum>")));
    }

    @Test
    void regenerationIsByteStable() throws IOException {
        Path sourceRoot = outputDir.resolve("src/main/java");
        Path regeneratedRoot = regeneratedDir.resolve("src/main/java");
        List<Path> sources;
        try (var stream = Files.walk(sourceRoot)) {
            sources = stream.filter(Files::isRegularFile).sorted().toList();
        }

        List<Path> regeneratedSources;
        try (var stream = Files.walk(regeneratedRoot)) {
            regeneratedSources = stream.filter(Files::isRegularFile).sorted().toList();
        }
        assertEquals(sources.stream().map(sourceRoot::relativize).toList(),
                     regeneratedSources.stream().map(regeneratedRoot::relativize).toList());
        for (Path source : sources) {
            Path relative = sourceRoot.relativize(source);
            assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(regeneratedRoot.resolve(relative)),
                              relative.toString());
        }
    }

    private static void generate(Path destination) throws Exception {
        URL resource = JsonStringEnumGenerationIT.class.getClassLoader().getResource("json-string-enums.yaml");
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(specPath)
                .setOutputDir(destination.toString())
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }
}
