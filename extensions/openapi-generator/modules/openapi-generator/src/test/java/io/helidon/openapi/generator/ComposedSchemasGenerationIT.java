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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        writeJsonBindingRoundTripTest();
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
        assertThat(content, containsString("case \"cat&special\" -> deserializeCat(jsonObject);"));
        assertThat(content, containsString(".set(\"kind\", \"cat&special\")"));
        assertThat(content, not(containsString("cat&amp;special")));
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
    void structuralOneOfUsesPropertyConstraints() throws IOException {
        String content = read(modelFile("ConstraintChoice.java"));
        assertThat(content, containsString("public interface ConstraintChoice"));
        assertThat(content, containsString("new UnionConstraint(\"value\", JsonValueType.STRING, false, "
                                                   + "new String[] {\"small\"}"));
        assertThat(content, containsString("new UnionConstraint(\"value\", JsonValueType.STRING, false, "
                                                   + "new String[] {\"large\"}"));
    }

    @Test
    void apiMethodsUseComposedSchemaTypes() throws IOException {
        String content = read(apiFile("ComposedApi.java"));
        assertThat(content, containsString("Pet savePet("));
        assertThat(content, containsString("Contact saveContact("));
        assertThat(content, containsString("Extended getExtended("));
        assertThat(content, containsString("Problem saveProblem("));
        assertThat(content, containsString("ConstraintChoice saveConstraintChoice("));
    }

    @Test
    void unmappedDiscriminatorDefaultsToSchemaName() throws IOException {
        String content = read(modelFile("Problem.java"));
        assertThat(content, containsString("case \"Error\" -> deserializeApiError(jsonObject);"));
        assertThat(content, containsString(".set(\"kind\", \"Error\")"));
        assertThat(content, not(containsString(".set(\"kind\", \"ApiError\")")));
    }

    @Test
    void generatedProjectBuildsWithMavenWhenEnabled() throws IOException, InterruptedException {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    @Test
    void generatedJsonBindingRoundTripsWhenEnabled() throws IOException, InterruptedException {
        GeneratedProjectBuildSupport.assertMavenTestSucceeds(outputDir);
    }

    @Test
    void nonObjectUnionMembersFailClearly(@TempDir Path tempDir) throws IOException {
        Path spec = tempDir.resolve("unsupported-union.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info:
                  title: Unsupported Union API
                  version: 1.0.0
                paths: {}
                components:
                  schemas:
                    StringValue:
                      type: string
                    NumberValue:
                      type: integer
                      format: int32
                    PrimitiveChoice:
                      oneOf:
                        - $ref: '#/components/schemas/StringValue'
                        - $ref: '#/components/schemas/NumberValue'
                """);

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(spec.toString())
                .setOutputDir(tempDir.resolve("generated").toString())
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        RuntimeException exception = assertThrows(RuntimeException.class,
                                                  () -> new DefaultGenerator()
                                                          .opts(configurator.toClientOptInput())
                                                          .generate());

        assertThat(exception.getMessage(), containsString("Unsupported oneOf member"));
        assertThat(exception.getMessage(), containsString("PrimitiveChoice"));
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

    private static void writeJsonBindingRoundTripTest() throws IOException {
        Path testFile = outputDir.resolve("src/test/java/io/helidon/example/model/ComposedJsonBindingTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, """
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

                package io.helidon.example.model;

                import io.helidon.json.binding.JsonBinding;
                import org.junit.jupiter.api.Test;

                import static org.hamcrest.CoreMatchers.containsString;
                import static org.hamcrest.CoreMatchers.instanceOf;
                import static org.hamcrest.CoreMatchers.is;
                import static org.hamcrest.MatcherAssert.assertThat;

                class ComposedJsonBindingTest {

                    private final JsonBinding jsonBinding = JsonBinding.create();

                    @Test
                    void oneOfDiscriminatorRoundTrip() {
                        Cat cat = new Cat();
                        cat.setWhiskers(7);

                        String json = jsonBinding.serialize((Pet) cat, Pet.class);
                        assertThat(json, containsString("\\\"kind\\\":\\\"cat&special\\\""));
                        assertThat(json, containsString("\\\"whiskers\\\":7"));

                        Pet pet = jsonBinding.deserialize("{\\\"kind\\\":\\\"cat&special\\\",\\\"whiskers\\\":7}", Pet.class);
                        assertThat(pet, instanceOf(Cat.class));
                        assertThat(((Cat) pet).getWhiskers(), is(7));
                    }

                    @Test
                    void anyOfStructuralRoundTrip() {
                        EmailContact emailContact = new EmailContact();
                        emailContact.setEmail("user@example.com");

                        String json = jsonBinding.serialize((Contact) emailContact, Contact.class);
                        assertThat(json, containsString("\\\"email\\\":\\\"user@example.com\\\""));

                        Contact contact = jsonBinding.deserialize("{\\\"email\\\":\\\"user@example.com\\\"}", Contact.class);
                        assertThat(contact, instanceOf(EmailContact.class));
                        assertThat(((EmailContact) contact).getEmail(), is("user@example.com"));
                    }

                    @Test
                    void constrainedOneOfStructuralDeserializesUniqueBranch() {
                        ConstraintChoice small = jsonBinding.deserialize("{\\\"value\\\":\\\"small\\\"}",
                                                                         ConstraintChoice.class);
                        assertThat(small, instanceOf(SmallCode.class));

                        ConstraintChoice large = jsonBinding.deserialize("{\\\"value\\\":\\\"large\\\"}",
                                                                         ConstraintChoice.class);
                        assertThat(large, instanceOf(LargeCode.class));

                        ScoreChoice lowScore = jsonBinding.deserialize("{\\\"score\\\":9}", ScoreChoice.class);
                        assertThat(lowScore, instanceOf(LowScore.class));

                        ScoreChoice highScore = jsonBinding.deserialize("{\\\"score\\\":10}", ScoreChoice.class);
                        assertThat(highScore, instanceOf(HighScore.class));

                        PatternChoice alphaCode = jsonBinding.deserialize("{\\\"code\\\":\\\"ABC\\\"}",
                                                                          PatternChoice.class);
                        assertThat(alphaCode, instanceOf(AlphaCode.class));

                        PatternChoice numericCode = jsonBinding.deserialize("{\\\"code\\\":\\\"123\\\"}",
                                                                            PatternChoice.class);
                        assertThat(numericCode, instanceOf(NumericCode.class));
                    }
                }
                """);
    }
}
