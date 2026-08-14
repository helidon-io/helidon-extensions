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
    void allOfModelBuilderExtendsParentBuilder() throws IOException {
        String content = read(modelFile("Extended.java"));
        assertThat(content, containsString("public static BuilderBase<?, ? extends Extended> builder()"));
        assertThat(content, containsString("public static class Builder extends BuilderBase<Builder, Extended>"));
        assertThat(content, containsString("extends Base.BuilderBase<B, T>"));
        assertThat(content, containsString("public Builder name(String name)"));
        assertThat(content, containsString("public Extended build()"));
    }

    @Test
    void abstractIntermediateAllOfModelDoesNotInstantiateItself() throws IOException {
        String content = read(modelFile("LayeredIntermediate.java"));
        assertThat(content, containsString("public abstract class LayeredIntermediate extends LayeredBase"));
        assertThat(content, containsString("class BuilderBase<B extends BuilderBase<B, T>"));
        assertThat(content, not(containsString("new LayeredIntermediate()")));
        assertThat(content, not(containsString("class Builder extends BuilderBase<Builder, LayeredIntermediate>")));

        String leaf = read(modelFile("LayeredLeaf.java"));
        assertThat(leaf, containsString("public class LayeredLeaf extends LayeredIntermediate"));
        assertThat(leaf, containsString("super(new LayeredLeaf())"));

        String base = read(modelFile("LayeredBase.java"));
        assertThat(base, containsString("@Json.Subtype(alias = \"leaf\", value = LayeredLeaf.class)"));
        assertThat(base, not(containsString("value = LayeredIntermediate.class")));
    }

    @Test
    void oneOfSchemaGeneratesInterface() throws IOException {
        String content = read(modelFile("Pet.java"));
        assertThat(content, containsString("public interface Pet"));
        assertThat(content, containsString("@Json.Converter(Pet.PetJsonConverter.class)"));
        assertThat(content, containsString("case \"cat$special\" -> deserializeCat(jsonObject)"));
        assertThat(content, containsString("String kind();"));
        assertThat(content, not(containsString("@Json.Polymorphic")));
    }

    @Test
    void oneOfMembersImplementGeneratedInterface() throws IOException {
        String cat = read(modelFile("Cat.java"));
        assertThat(cat, containsString("implements"));
        assertThat(cat, containsString("Pet"));
        assertThat(cat, containsString("return \"cat$special\";"));
        assertThat(cat, not(containsString("@Json.Ignore\n    public String kind()")));
        assertThat(cat, not(containsString("private String kind;")));
        assertThat(cat, not(containsString("void kind(")));

        String dog = read(modelFile("Dog.java"));
        assertThat(dog, containsString("implements"));
        assertThat(dog, containsString("Pet"));
        assertThat(dog, containsString("return \"dog\";"));
    }

    @Test
    void anyOfSchemaGeneratesInterface() throws IOException {
        String content = read(modelFile("Contact.java"));
        assertThat(content, containsString("public interface Contact"));
        assertThat(content, not(containsString("@Json.Entity")));
        assertThat(content, containsString("final class ContactJsonConverter implements JsonConverter<Contact>"));
        assertThat(content, containsString("final class ContactJsonBindingFactory implements JsonBindingFactory<Contact>"));
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
        assertThat(content, containsString("NullablePet saveNullablePet("));
        assertThat(content, containsString("ConstraintChoice saveConstraintChoice("));
    }

    @Test
    void unmappedDiscriminatorDefaultsToSchemaName() throws IOException {
        String content = read(modelFile("Problem.java"));
        assertThat(content, containsString("case \"Error\" -> deserializeApiError(jsonObject)"));
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
                import static org.junit.jupiter.api.Assertions.assertThrows;

                class ComposedJsonBindingTest {

                    private final JsonBinding jsonBinding = JsonBinding.create();

                    @Test
                    void oneOfDiscriminatorRoundTrip() {
                        Cat cat = new Cat();
                        cat.whiskers(7);

                        String json = jsonBinding.serialize((Pet) cat, Pet.class);
                        assertThat(occurrences(json, "\\\"kind\\\""), is(1));
                        assertThat(json, containsString("\\\"kind\\\":\\\"cat$special\\\""));
                        assertThat(json, containsString("\\\"whiskers\\\":7"));

                        Pet pet = jsonBinding.deserialize("{\\\"kind\\\":\\\"cat$special\\\",\\\"whiskers\\\":7}", Pet.class);
                        assertThat(pet, instanceOf(Cat.class));
                        assertThat(((Cat) pet).whiskers(), is(7));
                    }

                    @Test
                    void directMemberBindingPreservesDiscriminator() {
                        Cat cat = new Cat();
                        cat.whiskers(7);

                        String json = jsonBinding.serialize(cat, Cat.class);
                        assertThat(occurrences(json, "\\\"kind\\\""), is(1));
                        assertThat(json, containsString("\\\"kind\\\":\\\"cat$special\\\""));

                        Cat restored = jsonBinding.deserialize(
                                "{\\\"kind\\\":\\\"cat$special\\\",\\\"whiskers\\\":7}", Cat.class);
                        assertThat(restored.kind(), is("cat$special"));
                        assertThat(restored.whiskers(), is(7));

                        assertThrows(RuntimeException.class,
                                     () -> jsonBinding.deserialize(
                                             "{\\\"kind\\\":\\\"dog\\\",\\\"whiskers\\\":7}", Cat.class));
                    }

                    @Test
                    void everyOneOfMemberRoundTripsWithItsCanonicalAlias() {
                        Dog dog = new Dog();
                        dog.bark(true);

                        String json = jsonBinding.serialize((Pet) dog, Pet.class);
                        assertThat(occurrences(json, "\\\"kind\\\""), is(1));
                        assertThat(json, containsString("\\\"kind\\\":\\\"dog\\\""));

                        Pet restored = jsonBinding.deserialize(json, Pet.class);
                        assertThat(restored, instanceOf(Dog.class));
                        assertThat(((Dog) restored).bark(), is(true));
                    }

                    @Test
                    void metadataDiscriminatorIsRequired() {
                        assertThrows(RuntimeException.class,
                                     () -> jsonBinding.deserialize("{\\\"alpha\\\":\\\"value\\\"}",
                                                                  MetadataChoice.class));
                    }

                    @Test
                    void everyMetadataMemberRoundTripsWithItsCanonicalAlias() {
                        MetadataAlpha alpha = new MetadataAlpha();
                        alpha.alpha("a");
                        String alphaJson = jsonBinding.serialize((MetadataChoice) alpha, MetadataChoice.class);
                        assertThat(alphaJson, containsString("\\\"category\\\":\\\"alpha.v1\\\""));
                        assertThat(jsonBinding.deserialize(alphaJson, MetadataChoice.class),
                                   instanceOf(MetadataAlpha.class));

                        MetadataBeta beta = new MetadataBeta();
                        beta.beta("b");
                        String betaJson = jsonBinding.serialize((MetadataChoice) beta, MetadataChoice.class);
                        assertThat(betaJson, containsString("\\\"category\\\":\\\"MetadataBeta\\\""));
                        assertThat(jsonBinding.deserialize(betaJson, MetadataChoice.class),
                                   instanceOf(MetadataBeta.class));
                    }

                    @Test
                    void layeredAllOfRoundTripsThroughRootBase() {
                        LayeredLeaf leaf = new LayeredLeaf();
                        leaf.middle("middle");
                        leaf.leaf("leaf-value");

                        String json = jsonBinding.serialize((LayeredBase) leaf, LayeredBase.class);
                        assertThat(occurrences(json, "\\\"kind\\\""), is(1));
                        assertThat(json, containsString("\\\"kind\\\":\\\"leaf\\\""));

                        LayeredBase restored = jsonBinding.deserialize(json, LayeredBase.class);
                        assertThat(restored, instanceOf(LayeredLeaf.class));
                        assertThat(((LayeredLeaf) restored).middle(), is("middle"));
                        assertThat(((LayeredLeaf) restored).leaf(), is("leaf-value"));
                    }

                    @Test
                    void anyOfStructuralRoundTrip() {
                        EmailContact emailContact = new EmailContact();
                        emailContact.email("user@example.com");

                        String json = jsonBinding.serialize((Contact) emailContact, Contact.class);
                        assertThat(json, containsString("\\\"email\\\":\\\"user@example.com\\\""));

                        Contact contact = jsonBinding.deserialize("{\\\"email\\\":\\\"user@example.com\\\"}", Contact.class);
                        assertThat(contact, instanceOf(EmailContact.class));
                        assertThat(((EmailContact) contact).email(), is("user@example.com"));
                    }

                    @Test
                    void allOfBuilderCanSetParentAndChildProperties() {
                        Extended extended = Extended.builder()
                                .id("extended-1")
                                .name("extended-name")
                                .build();

                        assertThat(extended.id(), is("extended-1"));
                        assertThat(extended.name(), is("extended-name"));
                    }

                    @Test
                    void nullableUnionSupportsJsonNull() {
                        NullablePet pet = jsonBinding.deserialize("null", NullablePet.class);
                        assertThat(pet, is((NullablePet) null));

                        String json = jsonBinding.serialize((NullablePet) null, NullablePet.class);
                        assertThat(json, is("null"));
                    }

                    @Test
                    void constrainedOneOfStructuralDeserializesUniqueBranch() {
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

                    private int occurrences(String value, String token) {
                        return (value.length() - value.replace(token, "").length()) / token.length();
                    }
                }
                """);
    }
}
