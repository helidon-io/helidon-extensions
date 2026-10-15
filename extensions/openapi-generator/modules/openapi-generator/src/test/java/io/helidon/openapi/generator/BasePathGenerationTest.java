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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.swagger.parser.OpenAPIParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Verifies base path and operation path annotations in generated HTTP contracts.
 */
class BasePathGenerationTest {
    private static final Pattern PATH_ANNOTATION = Pattern.compile("@Http\\.Path\\(\"([^\"]*)\"\\)");

    @TempDir
    Path directory;

    /**
     * Both specification formats must retain their base path, with configuration taking precedence.
     *
     * @param format specification format
     * @param serverPath base path supplied by the specification
     * @param option option name, or no override
     * @param override configured base path
     * @param expected expected class-level path for the widget operations
     * @throws IOException if generated sources cannot be read
     */
    @ParameterizedTest
    @CsvSource(value = {
            "swagger2|/v1|||/v1/widgets",
            "openapi3|/v1|||/v1/widgets",
            "openapi3|/v1/|||/v1/widgets",
            "openapi3|/|||/widgets",
            "openapi3||||/widgets",
            "swagger2|/|||/widgets",
            "swagger2||||/widgets",
            "swagger2|/v1|serverBasePath|/custom|/custom/widgets",
            "openapi3|/v1|serverBasePath|/custom/|/custom/widgets",
            "openapi3|/v1|serverBasePath|custom|/custom/widgets",
            "openapi3|/v1|serverBasePath|/|/widgets",
            "swagger2|/v1|serveBasePath|/legacy|/legacy/widgets"
    }, delimiter = '|')
    void retainsBasePath(String format, String serverPath, String option, String override, String expected)
            throws IOException {
        generate(format, serverPath, option == null ? Map.of() : Map.of(option, override), false);
        assertPaths("Widgets", List.of(expected, "/{widgetId}"));
        String prefix = expected.substring(0, expected.length() - "/widgets".length());
        assertPaths("Status", List.of(prefix.isEmpty() ? "/" : prefix, "/health", "/ready"));
        assertPaths("Root", List.of(prefix.isEmpty() ? "/" : prefix));
    }

    /**
     * Clients inherit the same prefixed contract as the server, without another copy of the prefix.
     *
     * @param format specification format
     * @throws IOException if generated sources cannot be read
     */
    @ParameterizedTest
    @CsvSource({"swagger2", "openapi3"})
    void clientUsesSharedContract(String format) throws IOException {
        generate(format, "/v1", Map.of(), true);
        assertPaths("Widgets", List.of("/v1/widgets", "/{widgetId}"));
        String client = Files.readString(directory.resolve("src/main/java/io/helidon/example/api/WidgetsClient.java"));
        assertThat(client.contains("extends WidgetsApi"), is(true));
        assertThat(PATH_ANNOTATION.matcher(client).find(), is(false));
    }

    /**
     * Server variable defaults must resolve before extracting the route prefix.
     *
     * @throws IOException if generated sources cannot be read
     */
    @Test
    void resolvesServerVariableDefaults() throws IOException {
        generateSpec("""
                openapi: 3.0.3
                info: {title: Variable server, version: '1'}
                servers:
                  - url: https://{host}/{version}
                    variables:
                      host: {default: example.com}
                      version: {default: v1}
                paths:
                  /widgets:
                    get:
                      tags: [Widgets]
                      operationId: listWidgets
                      responses:
                        '204': {description: OK}
                """, Map.of(), false);
        assertPaths("Widgets", List.of("/v1/widgets"));
    }

    /**
     * The current option takes precedence over its legacy alias when both are supplied.
     *
     * @throws IOException if generated sources cannot be read
     */
    @Test
    void currentOptionTakesPrecedenceOverLegacyAlias() throws IOException {
        generate("swagger2", "/v1", Map.of("serverBasePath", "/current", "serveBasePath", "/legacy"), false);
        assertPaths("Widgets", List.of("/current/widgets", "/{widgetId}"));
    }

    /**
     * Generates contracts through the public parser and generator pipeline, including Swagger 2 conversion.
     *
     * @param format specification format
     * @param serverPath base path in the input
     * @param options generator overrides
     * @param client whether to generate clients
     */
    private void generate(String format, String serverPath, Map<String, String> options, boolean client) {
        String header = switch (format) {
            case "swagger2" -> "swagger: '2.0'\nhost: localhost\nschemes: [http]\n"
                    + (serverPath == null ? "" : "basePath: " + serverPath + "\n");
            case "openapi3" -> "openapi: 3.0.3\n"
                    + (serverPath == null ? "" : "servers:\n  - url: https://example.com" + serverPath + "\n");
            default -> throw new IllegalArgumentException(format);
        };
        String spec = header + "info: {title: Base path regression, version: '1'}\npaths:\n";
        for (var operation : List.of(
                List.of("/widgets", "Widgets", "listWidgets"),
                List.of("/widgets/{widgetId}", "Widgets", "getWidget"),
                List.of("/health", "Status", "health"),
                List.of("/ready", "Status", "ready"),
                List.of("/", "Root", "root"))) {
            spec += "  " + operation.get(0) + ":\n    get:\n      tags: [" + operation.get(1) + "]\n"
                    + "      operationId: " + operation.get(2) + "\n";
            if (operation.get(0).contains("{")) {
                spec += "      parameters:\n        - name: widgetId\n          in: path\n          required: true\n"
                        + (format.equals("swagger2") ? "          type: string\n"
                                : "          schema: {type: string}\n");
            }
            spec += "      responses:\n        '204': {description: OK}\n";
        }
        generateSpec(spec, options, client);
    }

    /**
     * Renders API sources from a parsed specification without generating unrelated supporting files.
     *
     * @param spec specification text
     * @param options generator overrides
     * @param client whether to generate clients
     */
    private void generateSpec(String spec, Map<String, String> options, boolean client) {
        var parsed = new OpenAPIParser().readContents(spec, null, null);
        assertThat(parsed.getMessages().toString(), parsed.getOpenAPI(), notNullValue());
        var codegen = new HelidonDeclarativeCodegen();
        codegen.setOutputDir(directory.toString());
        codegen.additionalProperties().put("helidonVersion", "4.5.0");
        codegen.additionalProperties().put("generateClient", client);
        codegen.additionalProperties().putAll(options);
        var generator = new DefaultGenerator();
        generator.setGeneratorPropertyDefault("supportingFiles", "false");
        generator.setGeneratorPropertyDefault("apis", "true");
        generator.opts(new ClientOptInput().config(codegen).openAPI(parsed.getOpenAPI())).generate();
    }

    /**
     * Checks the public annotation contract, including method suffixes and a single class prefix.
     *
     * @param tag API tag
     * @param paths expected class-level path followed by any method-level paths
     * @throws IOException if the generated interface cannot be read
     */
    private void assertPaths(String tag, List<String> paths) throws IOException {
        String source = Files.readString(directory.resolve("src/main/java/io/helidon/example/api/" + tag + "Api.java"));
        var actual = PATH_ANNOTATION.matcher(source).results().map(result -> result.group(1)).toList();
        assertThat(tag + " annotation paths", actual, is(paths));
    }
}
