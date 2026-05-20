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

class SerializationLibraryGenerationIT {

    @TempDir
    Path outputDir;

    @Test
    void jsonbSerializationLibraryUsesJsonbAnnotationsAndMedia() throws Exception {
        generate("petstore.yaml", "jsonb");

        String pet = read(modelFile("Pet.java"));
        assertThat(pet, containsString("import jakarta.json.bind.annotation.JsonbProperty;"));
        assertThat(pet, containsString("@JsonbProperty(\"name\")"));
        assertThat(pet, not(containsString("io.helidon.json.binding.Json")));
        assertThat(pet, not(containsString("com.fasterxml.jackson.annotation.JsonProperty")));

        String pom = read(outputDir.resolve("pom.xml"));
        assertThat(pom, containsString("helidon-http-media-jsonb"));
        assertThat(pom, not(containsString("helidon-http-media-json-binding")));
        assertThat(pom, not(containsString("helidon-http-media-jackson")));

        String gradle = read(outputDir.resolve("build.gradle"));
        assertThat(gradle, containsString("io.helidon.http.media:helidon-http-media-jsonb"));
        assertThat(gradle, not(containsString("io.helidon.http.media:helidon-http-media-json-binding")));
    }

    @Test
    void jacksonSerializationLibraryUsesJacksonAnnotationsAndMedia() throws Exception {
        generate("petstore.yaml", "jackson");

        String pet = read(modelFile("Pet.java"));
        assertThat(pet, containsString("import com.fasterxml.jackson.annotation.JsonProperty;"));
        assertThat(pet, containsString("@JsonProperty(value = \"name\", required = true)"));
        assertThat(pet, not(containsString("io.helidon.json.binding.Json")));
        assertThat(pet, not(containsString("jakarta.json.bind.annotation.JsonbProperty")));

        String pom = read(outputDir.resolve("pom.xml"));
        assertThat(pom, containsString("helidon-http-media-jackson"));
        assertThat(pom, not(containsString("helidon-http-media-json-binding")));
        assertThat(pom, not(containsString("helidon-http-media-jsonb")));

        String gradle = read(outputDir.resolve("build.gradle"));
        assertThat(gradle, containsString("io.helidon.http.media:helidon-http-media-jackson"));
        assertThat(gradle, not(containsString("io.helidon.http.media:helidon-http-media-json-binding")));
    }

    @Test
    void jsonbSerializationLibraryRejectsComposedSchemas() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                          () -> generate("discriminator-oneof-explicit-value.yaml", "jsonb"));

        assertThat(exception.getMessage(), containsString("serializationLibrary=jsonb does not support composed schema"));
    }

    @Test
    void jacksonSerializationLibraryAnnotatesDiscriminatorUnions() throws Exception {
        generate("discriminator-oneof-explicit-value.yaml", "jackson");

        String model = read(modelFile("ApprovalInvalidationTypeDetails.java"));
        assertThat(model, containsString("import com.fasterxml.jackson.annotation.JsonSubTypes;"));
        assertThat(model, containsString("import com.fasterxml.jackson.annotation.JsonTypeInfo;"));
        assertThat(model, containsString("@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, "
                                                 + "include = JsonTypeInfo.As.EXISTING_PROPERTY, "
                                                 + "property = \"approvalInvalidationType\", visible = true)"));
        assertThat(model, containsString("@JsonSubTypes.Type(value = AuthorDefinedExpiryDetails.class, "
                                                 + "name = \"AUTHOR_DEFINED_TIME_EXPIRY\")"));
        assertThat(model, containsString("@JsonSubTypes.Type(value = NonExpiringDetails.class, "
                                                 + "name = \"NON_EXPIRING\")"));
    }

    @Test
    void nonHelidonSerializationLibraryRejectsStructuralUnions() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                          () -> generate("composed-schemas.yaml", "jackson"));

        assertThat(exception.getMessage(), containsString("requires a discriminator for composed schema"));
        assertThat(exception.getMessage(), containsString("structural oneOf/anyOf unions are supported only with "
                                                                  + "serializationLibrary=helidon"));
    }

    private void generate(String specName, String serializationLibrary) throws Exception {
        URL resource = SerializationLibraryGenerationIT.class
                .getClassLoader()
                .getResource(specName);
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("serializationLibrary", serializationLibrary)
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    private Path modelFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + name);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }
}
