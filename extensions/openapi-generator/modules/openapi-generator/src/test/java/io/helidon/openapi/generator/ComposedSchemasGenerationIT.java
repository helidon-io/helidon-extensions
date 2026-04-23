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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

class ComposedSchemasGenerationIT {

    @TempDir
    static Path outputDir;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = ComposedSchemasGenerationIT.class
                .getClassLoader()
                .getResource("composed-schemas.yaml");
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }

    @Test
    void allOfModelExtendsReferencedParent() throws IOException {
        assertThat(read(modelFile("Extended.java")), containsString("public class Extended extends Base"));
    }

    @Test
    void allOfModelRendersOnlyLocalProperties() throws IOException {
        String content = read(modelFile("Extended.java"));
        assertThat(content, containsString("private String name;"));
        assertThat(content, not(containsString("private String id;")));
    }

    @Test
    void oneOfSchemaGeneratesInterface() throws IOException {
        String content = read(modelFile("Pet.java"));
        assertThat(content, containsString("public interface Pet"));
        assertThat(content, not(containsString("@Json.Entity")));
        assertThat(content, containsString("@Json.Converter(Pet.PetJsonConverter.class)"));
        assertThat(content, containsString("final class PetJsonConverter implements JsonConverter<Pet>"));
        assertThat(content, containsString("String discriminatorValue = jsonObject.stringValue(\"kind\").orElse(null);"));
        assertThat(content, containsString(".set(\"kind\", \"cat\")"));
    }

    @Test
    void oneOfMembersImplementGeneratedInterface() throws IOException {
        assertThat(read(modelFile("Cat.java")), containsString("public class Cat implements Pet"));
        assertThat(read(modelFile("Dog.java")), containsString("public class Dog implements Pet"));
    }

    @Test
    void anyOfSchemaGeneratesInterface() throws IOException {
        String content = read(modelFile("Contact.java"));
        assertThat(content, containsString("public interface Contact"));
        assertThat(content, not(containsString("@Json.Entity")));
        assertThat(content, containsString("final class ContactJsonConverter implements JsonConverter<Contact>"));
        assertThat(content, containsString("return deserializeStructurally(jsonObject);"));
        assertThat(content, containsString("throw new IllegalArgumentException(\"Ambiguous anyOf match for Contact\")"));
    }

    @Test
    void anyOfMembersImplementGeneratedInterface() throws IOException {
        assertThat(read(modelFile("EmailContact.java")), containsString("public class EmailContact implements Contact"));
        assertThat(read(modelFile("PhoneContact.java")), containsString("public class PhoneContact implements Contact"));
    }

    @Test
    void apiMethodsUseComposedSchemaTypes() throws IOException {
        String content = read(apiFile("ComposedApi.java"));
        assertThat(content, containsString("Pet savePet("));
        assertThat(content, containsString("Contact saveContact("));
        assertThat(content, containsString("Extended getExtended("));
    }

    @Test
    void generatedProjectBuildsWithMavenWhenEnabled() throws IOException, InterruptedException {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    private static File apiFile(String fileName) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + fileName).toFile();
    }

    private static File modelFile(String fileName) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + fileName).toFile();
    }

    private static String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
