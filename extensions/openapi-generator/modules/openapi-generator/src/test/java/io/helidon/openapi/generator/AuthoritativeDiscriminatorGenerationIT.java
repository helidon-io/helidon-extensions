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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthoritativeDiscriminatorGenerationIT {

    @TempDir
    Path tempDir;

    @Test
    void schemaDrivenDefaultsUseDeclaredReadOnlyPropertyAndMetadataOnly() throws Exception {
        Path output = generate("default", null);
        String base = model(output, "DeclaredBase");
        String cat = model(output, "DeclaredCat");
        String metadataChoice = model(output, "MetadataChoice");

        assertThat(base, containsString("public abstract class DeclaredBase"));
        assertThat(base, containsString("@Json.Subtype(alias = \"weird.alias\", value = DeclaredCat.class)"));
        assertThat(base, containsString("@Json.Subtype(alias = \"MixedCase-2\", value = DeclaredDog.class)"));
        assertThat(base, containsString("@Json.Subtype(alias = \"DeclaredBird\", value = DeclaredBird.class)"));
        assertThat(base, containsString("public abstract String kind();"));
        assertThat(base, not(containsString("private String kind;")));
        assertThat(base, not(containsString("void kind(")));
        assertThat(base, not(containsString("static BuilderBase<?, ? extends DeclaredBase> builder()")));
        assertThat(cat, containsString("return \"weird.alias\";"));
        assertThat(cat, not(containsString("void kind(")));

        assertThat(metadataChoice, containsString("@Json.Polymorphic(key = \"category\")"));
        assertThat(metadataChoice,
                   containsString("@Json.Subtype(alias = \"alpha.v1\", value = MetadataAlpha.class)"));
        assertThat(metadataChoice,
                   containsString("@Json.Subtype(alias = \"MetadataBeta\", value = MetadataBeta.class)"));
        assertThat(metadataChoice, not(containsString("String category();")));
        assertThat(metadataChoice, not(containsString("JsonConverter")));
    }

    @Test
    void globalModesAndSchemaOverridesUseDocumentedPrecedence() throws Exception {
        Path metadata = generate("metadata", "metadata");
        assertThat(model(metadata, "DeclaredBase"), not(containsString("String kind();")));
        assertThat(model(metadata, "ForcedReadOnly"), containsString("String type();"));

        Path readOnly = generate("read-only", "readOnlyProperty");
        assertThat(model(readOnly, "MetadataChoice"), containsString("String category();"));
        assertThat(model(readOnly, "ForcedMetadata"), not(containsString("String type();")));
        assertThat(model(readOnly, "ForcedMetadataCat"), not(containsString("private String type;")));
    }

    @Test
    void ambiguousUnresolvedAndSelfMappingsFailBeforeRendering() throws Exception {
        RuntimeException ambiguous = generateFailure("ambiguous", """
                Cat:
                  type: object
                  properties:
                    cat:
                      type: string
                Dog:
                  type: object
                  properties:
                    dog:
                      type: string
                Choice:
                  oneOf:
                    - $ref: '#/components/schemas/Cat'
                    - $ref: '#/components/schemas/Dog'
                  discriminator:
                    propertyName: kind
                    mapping:
                      first: '#/components/schemas/Cat'
                      second: '#/components/schemas/Cat'
                """);
        assertThat(rootMessage(ambiguous), containsString("multiple explicit aliases [first, second]"));

        RuntimeException unresolved = generateFailure("unresolved", """
                Cat:
                  type: object
                  properties:
                    cat:
                      type: string
                Choice:
                  oneOf:
                    - $ref: '#/components/schemas/Cat'
                  discriminator:
                    propertyName: kind
                    mapping:
                      missing: '#/components/schemas/DoesNotExist'
                """);
        assertThat(rootMessage(unresolved), containsString("does not resolve to exactly one concrete subtype"));

        RuntimeException self = generateFailure("self", """
                Base:
                  type: object
                  discriminator:
                    propertyName: kind
                    mapping:
                      base: '#/components/schemas/Base'
                      cat: '#/components/schemas/Cat'
                Cat:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      properties:
                        value:
                          type: string
                """);
        String message = rootMessage(self);
        assertThat(message, containsString("Unsupported discriminator self-mapping"));
        assertThat(message, containsString("#/components/schemas/Base"));
        assertThat(message, containsString("discriminator property 'kind'"));
        assertThat(message, containsString("alias 'base'"));
        assertThat(message, containsString("target '#/components/schemas/Base'"));
        assertThat(message, containsString("input specification '"));
        assertThat(message, containsString("Define a concrete subtype"));
        assertFalse(Files.exists(tempDir.resolve("self-generated/src/main/java")));
    }

    @Test
    void regenerationIsByteStable() throws Exception {
        Path first = generate("stable-first", null);
        Path second = generate("stable-second", null);
        Path firstRoot = first.resolve("src/main/java");
        Path secondRoot = second.resolve("src/main/java");
        List<Path> firstFiles;
        List<Path> secondFiles;
        try (var stream = Files.walk(firstRoot)) {
            firstFiles = stream.filter(Files::isRegularFile).sorted().toList();
        }
        try (var stream = Files.walk(secondRoot)) {
            secondFiles = stream.filter(Files::isRegularFile).sorted().toList();
        }
        assertEquals(firstFiles.stream().map(firstRoot::relativize).toList(),
                     secondFiles.stream().map(secondRoot::relativize).toList());
        for (Path file : firstFiles) {
            Path relative = firstRoot.relativize(file);
            assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(secondRoot.resolve(relative)),
                              relative.toString());
        }
    }

    private Path generate(String name, String representation) throws Exception {
        URL resource = getClass().getClassLoader().getResource("authoritative-discriminators.yaml");
        Path spec = Paths.get(resource.toURI()).toAbsolutePath();
        return generate(name, spec, representation);
    }

    private Path generate(String name, Path spec, String representation) {
        Path output = tempDir.resolve(name + "-generated");
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(spec.toString())
                .setOutputDir(output.toString())
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");
        if (representation != null) {
            configurator.addAdditionalProperty("discriminatorRepresentation", representation);
        }
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        return output;
    }

    private RuntimeException generateFailure(String name, String schemas) throws IOException {
        Path spec = tempDir.resolve(name + ".yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info:
                  title: Invalid discriminator
                  version: 1.0.0
                paths: {}
                components:
                  schemas:
                """ + schemas.indent(6));
        return assertThrows(RuntimeException.class, () -> generate(name, spec, null));
    }

    private String model(Path output, String name) throws IOException {
        return Files.readString(output.resolve("src/main/java/io/helidon/example/model/" + name + ".java"));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
