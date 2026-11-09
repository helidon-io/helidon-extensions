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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscriminatorEnumReferenceGenerationIT {
    private static final String BASE = "CreateSoftwareSourceDetails";
    private static final String CUSTOM = "CreateCustomSoftwareSourceDetails";
    private static final String VENDOR = "CreateVendorSoftwareSourceDetails";

    @TempDir
    Path tempDir;

    @Test
    void referencedEnumsUseTypedInitializersAccessorsAndBuilderChecks() throws Exception {
        Path output = generate(input -> { });
        String child = model(output, CUSTOM);

        assertThat(model(output, BASE), containsString("public abstract SoftwareSourceType softwareSourceType();"));
        assertThat(child,
                   containsString("SoftwareSourceType softwareSourceType = SoftwareSourceType.fromValue(\"CUSTOM\")"));
        assertThat(child, containsString("return SoftwareSourceType.fromValue(\"CUSTOM\");"));
        assertThat(child, containsString("if (!SoftwareSourceType.fromValue(\"CUSTOM\").equals(softwareSourceType))"));
        assertThat(child, not(containsString(BASE + ".SoftwareSourceType")));
        assertThat(model(output, VENDOR), containsString("return SoftwareSourceType.fromValue(\"VENDOR\");"));
    }

    @Test
    void inlineEnumsRetainTheirOwningTypeDespiteUnrelatedEnumNames() throws Exception {
        Path output = generate(input -> {
            schemas(input).remove("SoftwareSourceChoice");
            schemas(input).get(BASE).addProperty("softwareSourceType", new StringSchema()._enum(List.of("CUSTOM", "VENDOR")));
            input.getComponents().addSchemas("SoftwareSourceTypeEnum", new StringSchema()._enum(List.of("OTHER")));
            input.getComponents().addSchemas("UnrelatedHolder", new ObjectSchema()
                    .addProperty("mode", new Schema<>().$ref("#/components/schemas/SoftwareSourceTypeEnum")));
        });

        assertThat(model(output, CUSTOM), containsString("return " + BASE + ".SoftwareSourceTypeEnum.CUSTOM;"));
    }

    @Test
    void stringsStayStringsDespiteAnUnrelatedReferencedEnum() throws Exception {
        Path output = generate(input -> schemas(input).get(BASE).addProperty("softwareSourceType", new StringSchema()));

        assertThat(model(output, BASE), containsString("public abstract String softwareSourceType();"));
        assertThat(model(output, CUSTOM), containsString("return \"CUSTOM\";"));
    }

    @Test
    void referencedEnumAliasesPreserveQuotesAndBackslashes() throws Exception {
        String alias = "custom\"mode\\path";
        Path output = generate(input -> {
            schemas(input).get("SoftwareSourceType").setEnum(List.of(alias, "VENDOR"));
            schemas(input).remove("SoftwareSourceChoice");
            schemas(input).get(BASE).getDiscriminator().setMapping(new LinkedHashMap<>(Map.of(
                    alias, "#/components/schemas/" + CUSTOM,
                    "VENDOR", "#/components/schemas/" + VENDOR)));
        });

        assertThat(model(output, CUSTOM),
                   containsString("return SoftwareSourceType.fromValue(" + JavaStringLiterals.toJavaStringLiteral(alias) + ");"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema-driven", "metadata", "readOnlyProperty"})
    void invalidAliasesAreCollectedInEveryGlobalRepresentation(String representation) {
        assertInvalidAliases(false, Map.of("discriminatorRepresentation", representation));
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata", "readOnlyProperty"})
    void invalidAliasesAreCollectedWithSchemaRepresentationOverrides(String representation) {
        String global = "metadata".equals(representation) ? "readOnlyProperty" : "metadata";
        assertInvalidAliases(true, Map.of("discriminatorRepresentation", global));
    }

    @Test
    void invalidUnionAliasesAreReportedBeforeAccessorConflicts() {
        String customAlias = "PRIVATE_CUSTOM_ALIAS";
        String vendorAlias = "PRIVATE_VENDOR_ALIAS";
        RuntimeException error = assertThrows(RuntimeException.class, () -> generate(input -> {
            schemas(input).get("SoftwareSourceChoice").getDiscriminator().setMapping(new LinkedHashMap<>(Map.of(
                    customAlias, "#/components/schemas/" + CUSTOM,
                    vendorAlias, "#/components/schemas/" + VENDOR)));
        }));
        assertAliasErrors(error, "SoftwareSourceChoice", customAlias, vendorAlias);
    }

    @Test
    void conflictingDeclaredAliasesDoNotDiscloseAccessorExpressions() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> generate(input -> {
            schemas(input).get("SoftwareSourceChoice").getDiscriminator().setMapping(new LinkedHashMap<>(Map.of(
                    "VENDOR", "#/components/schemas/" + CUSTOM,
                    "CUSTOM", "#/components/schemas/" + VENDOR)));
        }));
        assertThat(rootMessage(error), containsString("Contradictory discriminator accessor 'softwareSourceType'"));
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            assertThat(cause.getMessage(), not(containsString("CUSTOM")));
            assertThat(cause.getMessage(), not(containsString("VENDOR")));
            assertThat(cause.getMessage(), not(containsString("valueExpression")));
        }
    }

    private void assertInvalidAliases(boolean schemaOverride, Map<String, Object> options) {
        String customAlias = "PRIVATE_CUSTOM_ALIAS";
        String vendorAlias = "PRIVATE_VENDOR_ALIAS";
        RuntimeException error = assertThrows(RuntimeException.class, () -> generate(input -> {
            // Keep the union so both composition paths are exercised in the same generation run.
            schemas(input).get(BASE).getDiscriminator().setMapping(new LinkedHashMap<>(Map.of(
                    customAlias, "#/components/schemas/" + CUSTOM,
                    vendorAlias, "#/components/schemas/" + VENDOR)));
            if (schemaOverride) {
                String representation = "metadata".equals(options.get("discriminatorRepresentation"))
                        ? "readOnlyProperty" : "metadata";
                schemas(input).get(BASE).addExtension("x-helidon-discriminator-representation", representation);
                schemas(input).get("SoftwareSourceChoice")
                        .addExtension("x-helidon-discriminator-representation", representation);
            }
        }, options));
        assertAliasErrors(error, BASE, customAlias, vendorAlias);
    }

    private void assertAliasErrors(Throwable error, String schema, String customAlias, String vendorAlias) {
        String message = rootMessage(error);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            assertThat(cause.getMessage(), not(containsString(customAlias)));
            assertThat(cause.getMessage(), not(containsString(vendorAlias)));
            assertThat(cause.getMessage(), not(containsString("valueExpression")));
            assertThat(cause.getMessage(), not(containsString("Contradictory discriminator accessor")));
        }
        assertThat(message, containsString(schema));
        assertThat(message, containsString("softwareSourceType"));
        assertThat(message, containsString(CUSTOM));
        assertThat(message, containsString(VENDOR));
        assertThat(message, containsString("must be declared by enum"));
        assertThat(message.lines().filter(line -> line.contains("must be declared by enum")).count(), is(2L));
    }

    private Path generate(Consumer<OpenAPI> customize) throws Exception {
        return generate(customize, Map.of());
    }

    private Path generate(Consumer<OpenAPI> customize, Map<String, Object> options) throws Exception {
        Path spec = Path.of(getClass().getResource("/discriminator-enum-ref-allof.yaml").toURI());
        Path output = tempDir.resolve("generated");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(spec.toString())
                .setOutputDir(output.toString())
                .addAdditionalProperty("helidonVersion", "4.5.0");
        options.forEach(configurator::addAdditionalProperty);
        var input = configurator.toClientOptInput();
        customize.accept(input.getOpenAPI());
        new DefaultGenerator().opts(input).generate();
        return output;
    }

    @SuppressWarnings("rawtypes")
    private Map<String, Schema> schemas(OpenAPI input) {
        return input.getComponents().getSchemas();
    }

    private String model(Path output, String name) throws IOException {
        return Files.readString(output.resolve("src/main/java/io/helidon/example/model/" + name + ".java"));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
