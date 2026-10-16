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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class PatternValidationGenerationIT {
    private static final String JAVA_LITERAL = "\"(?:\\\\.|[^\"\\\\])*\"";

    @TempDir
    static Path outputDir;

    private static String model;
    private static String api;

    @TempDir
    Path compilationDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = PatternValidationGenerationIT.class.getClassLoader().getResource("pattern-validation.json");
        Path inputSpec = outputDir.resolve("regex patterns é.json");
        Files.copy(Path.of(resource.toURI()), inputSpec);
        generate(inputSpec, outputDir);
        model = Files.readString(outputDir.resolve("src/main/java/io/helidon/example/model/Payload.java"));
        api = Files.readString(outputDir.resolve("src/main/java/io/helidon/example/api/DefaultApi.java"));
    }

    @ParameterizedTest
    @MethodSource("patterns")
    void propertyPatternPreservesRegexSemantics(PatternCase pattern) throws Exception {
        String expression = "@Validation\\.String\\.Pattern\\((" + JAVA_LITERAL + ")\\)\\s+public String "
                + Pattern.quote(pattern.name()) + "\\(\\)";
        assertRegexSemantics(pattern, annotationLiteral(model, expression));
    }

    @ParameterizedTest
    @MethodSource("patterns")
    void parameterPatternPreservesRegexSemantics(PatternCase pattern) throws Exception {
        String expression = "@Validation\\.String\\.Pattern\\((" + JAVA_LITERAL + ")\\)\\s+@Http\\.QueryParam\\(\""
                + Pattern.quote(pattern.name()) + "\"\\)";
        assertRegexSemantics(pattern, annotationLiteral(api, expression));
    }

    @Test
    void unconstrainedPropertyKeepsAbsentPattern() {
        var property = new HelidonDeclarativeCodegen().fromProperty("plain", new StringSchema());
        assertThat(property.pattern, is(nullValue()));
    }

    @Test
    void consistentEnumWireValuesRemainValid() throws Exception {
        URL resource = getClass().getClassLoader().getResource("enum-pattern-validation.json");
        Path inputSpec = compilationDir.resolve("enum patterns.json");
        Files.copy(Path.of(resource.toURI()), inputSpec);
        Path generated = compilationDir.resolve("generated");
        generate(inputSpec, generated);
        String enumModel = Files.readString(generated.resolve("src/main/java/io/helidon/example/model/Payload.java"));
        String enumApi = Files.readString(generated.resolve("src/main/java/io/helidon/example/api/DefaultApi.java"));
        assertThat(enumModel, containsString("\"1/s\""));
        assertThat(enumModel, containsString("\"100/s\""));
        assertThat(enumApi, containsString("Optional<ListItemsRateEnum> rate"));
    }

    private static Stream<PatternCase> patterns() {
        return Stream.of(
                new PatternCase("rate", "^[1-9]\\d*/s$", "100/s", "0/s"),
                new PatternCase("quoted", "^a\"b$", "a\"b", "ab"),
                new PatternCase("lineBreak", "^a\nb$", "a\nb", "a\\nb"),
                new PatternCase("backslash", "^a\\\\b$", "a\\b", "ab"),
                new PatternCase("unicode", "^café$", "café", "cafe"),
                new PatternCase("dotted", "^[A-Z]+\\.[0-9]+$", "X.12", "Xx12"),
                new PatternCase("tab", "^a\tb$", "a\tb", "ab"),
                new PatternCase("carriage", "^a\rb$", "a\rb", "ab"));
    }

    private static void generate(Path inputSpec, Path targetDir) {
        var configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(inputSpec.toUri().toASCIIString())
                .setOutputDir(targetDir.toString())
                .addAdditionalProperty("helidonVersion", "4.5.0")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api");
        var generator = new DefaultGenerator();
        generator.setGeneratorPropertyDefault("supportingFiles", "false");
        generator.setGeneratorPropertyDefault("models", "true");
        generator.setGeneratorPropertyDefault("apis", "true");
        generator.opts(configurator.toClientOptInput()).generate();
    }

    private static String annotationLiteral(String source, String expression) {
        var matcher = Pattern.compile(expression).matcher(source);
        assertThat("Generated native validation annotation matches " + expression, matcher.find(), is(true));
        return matcher.group(1);
    }

    private void assertRegexSemantics(PatternCase pattern, String literal) throws Exception {
        String regex = compileLiteral(literal);
        assertThat(Pattern.matches(pattern.regex(), pattern.valid()), is(true));
        assertThat(Pattern.matches(pattern.regex(), pattern.invalid()), is(false));
        assertThat(Pattern.matches(regex, pattern.valid()), is(true));
        assertThat(Pattern.matches(regex, pattern.invalid()), is(false));
    }

    private String compileLiteral(String literal) throws Exception {
        Path source = compilationDir.resolve("GeneratedPattern.java");
        Files.writeString(source, "public final class GeneratedPattern { public static String value() { return "
                + literal + "; } }", StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler, notNullValue());
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "-d", compilationDir.toString()), null,
                    files.getJavaFileObjects(source.toFile()));
            boolean compiled = task.call();
            assertThat(diagnostics.getDiagnostics().toString(), compiled, is(true));
        }
        try (var loader = new URLClassLoader(new URL[] {compilationDir.toUri().toURL()}, null)) {
            return (String) loader.loadClass("GeneratedPattern").getMethod("value").invoke(null);
        }
    }

    private record PatternCase(String name, String regex, String valid, String invalid) {
    }
}
