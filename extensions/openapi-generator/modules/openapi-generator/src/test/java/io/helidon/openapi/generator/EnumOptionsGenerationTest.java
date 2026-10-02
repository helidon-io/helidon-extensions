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
import java.util.List;

import javax.tools.ToolProvider;

import com.sun.source.util.JavacTask;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnumOptionsGenerationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"model", "nested", "parameter"})
    void rejectsAmbiguousUnescapedWireValues(String location) {
        var error = assertThrows(RuntimeException.class,
                                () -> generate(location, List.of("\t\u03a3", "\t\u03c3"), true, false));
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root.getMessage(), containsString("ambiguous wire values"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "nested", "parameter"})
    void acceptsDistinctNormalizedWireValues(String location) {
        generate(location, List.of("\t\u03a3", "\t\u03c2"), true, false);
    }

    @Test
    void defaultComparisonRemainsCaseSensitive() throws Exception {
        generate("model", List.of("ready", "READY"), false, false);
        assertThat(modelSource("Mode"), containsString("switch (value)"));
    }

    @Test
    void modelOnlyServicesDoNotOverwriteSchemaNames() throws Exception {
        var codegen = generate("model", List.of("ready"), true, true);
        assertThat(modelSource("Mode"), containsString("ModeJsonServices2.ModeJsonConverter.class"));
        assertThat(modelSource("ModeJsonServices2"), containsString("Mapper<String, Mode>"));
        assertThat(modelSource("ModeJsonServices"), containsString("public class ModeJsonServices"));
        assertThat(Files.exists(directory.resolve("src/main/java/io/helidon/example/Main.java")), is(false));
        assertThat(Path.of(codegen.modelFilename("modelRegistryServices.mustache", "Mode")).getFileName().toString(),
                   is("ModeJsonServices2.java"));
        assertThat(codegen.modelFilename("modelRegistryServices.mustache", "Mode", directory.toString()),
                   is(directory.resolve("ModeJsonServices2.java").toString()));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = Files.walk(directory.resolve("src/main/java"));
                var fileManager = compiler.getStandardFileManager(null, null, null)) {
            var sources = fileManager.getJavaFileObjectsFromPaths(files.filter(path -> path.toString().endsWith(".java"))
                                                                         .toList());
            var task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("-proc:none"), null, sources);
            for (var unit : task.parse()) {
                assertThat(unit.getSourceFile().getName(), unit.getTypeDecls().size(), is(1));
            }
        }
    }

    @Test
    void reusedGeneratorCanDisableBothOptions() {
        var codegen = generate("model", List.of("ready"), true, true);
        codegen.additionalProperties().put("enumCaseInsensitive", false);
        codegen.additionalProperties().put("modelRegistryServices", false);
        codegen.processOpts();
        assertThat(codegen.additionalProperties().get("enumCaseInsensitive"), is(false));
        assertThat(codegen.modelTemplateFiles().containsKey("modelRegistryServices.mustache"), is(false));
    }

    @Test
    void missingServiceAllocationReportsModelName() {
        var codegen = new HelidonDeclarativeCodegen();
        var error = assertThrows(IllegalStateException.class,
                                () -> codegen.modelFilename("modelRegistryServices.mustache", "Missing"));
        assertThat(error.getMessage(), containsString("Missing"));
        assertThat(error.getMessage(), containsString("postProcessAllModels"));
    }

    private String modelSource(String name) throws Exception {
        return Files.readString(directory.resolve("src/main/java/io/helidon/example/model/" + name + ".java"));
    }

    private HelidonDeclarativeCodegen generate(String location, List<String> values, boolean insensitive, boolean services) {
        var schema = new StringSchema()._enum(values);
        var components = new Components().addSchemas("ModeJsonServices",
                                                     new ObjectSchema().addProperty("name", new StringSchema()));
        var operation = new Operation().operationId("inspect").addTagsItem("Modes")
                .responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("OK")));
        switch (location) {
            case "model" -> components.addSchemas("Mode", schema);
            case "nested" -> components.addSchemas("Envelope", new ObjectSchema().addProperty("mode", schema));
            case "parameter" -> operation.addParametersItem(new QueryParameter().name("mode").schema(schema));
            default -> throw new IllegalArgumentException(location);
        }
        var api = new OpenAPI().info(new Info().title("Enum options").version("1"))
                .components(components).paths(new Paths().addPathItem("/modes", new PathItem().get(operation)));
        var codegen = new HelidonDeclarativeCodegen();
        codegen.setOutputDir(directory.toString());
        codegen.additionalProperties().put("helidonVersion", "4.5.0");
        codegen.additionalProperties().put("enumCaseInsensitive", insensitive);
        codegen.additionalProperties().put("modelRegistryServices", services);
        var generator = new DefaultGenerator();
        generator.setGeneratorPropertyDefault("supportingFiles", "false");
        generator.setGeneratorPropertyDefault("apis", services ? "false" : "true");
        generator.setGeneratorPropertyDefault("models", "true");
        generator.opts(new ClientOptInput().config(codegen).openAPI(api)).generate();
        return codegen;
    }
}
