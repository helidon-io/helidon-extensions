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

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Integration test — runs the full openapi-generator pipeline against
 * {@code petstore.yaml} and asserts that the expected files are generated
 * with correct content.
 */
class PetstoreGenerationIT {

    @TempDir
    static Path outputDir;

    /** Path to petstore.yaml bundled in test resources. */
    private static String specPath;

    @BeforeAll
    static void generate() throws Exception {
        URL resource = PetstoreGenerationIT.class
                .getClassLoader()
                .getResource("petstore.yaml");
        specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();

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

    // -------------------------------------------------------------------------
    // File existence
    // -------------------------------------------------------------------------

    @Test
    void pomXmlIsGenerated() {
        assertThat(Files.exists(outputDir.resolve("pom.xml")), is(true));
    }

    @Test
    void buildGradleIsGenerated() {
        assertThat(Files.exists(outputDir.resolve("build.gradle")), is(true));
    }

    @Test
    void settingsGradleIsGenerated() {
        assertThat(Files.exists(outputDir.resolve("settings.gradle")), is(true));
    }

    @Test
    void mainJavaIsGenerated() {
        assertThat(javaFile("io/helidon/example/Main.java").exists(), is(true));
    }

    @Test
    void applicationYamlIsGenerated() {
        assertThat(resourceFile("application.yaml").exists(), is(true));
    }

    @Test
    void loggingPropertiesIsGenerated() {
        assertThat(resourceFile("logging.properties").exists(), is(true));
    }

    @Test
    void petsEndpointIsGenerated() {
        assertThat(apiFile("PetsEndpoint.java").exists(), is(true));
    }

    @Test
    void petsApiIsGenerated() {
        assertThat(apiFile("PetsApi.java").exists(), is(true));
    }

    @Test
    void petsClientIsGenerated() {
        assertThat(apiFile("PetsClient.java").exists(), is(true));
    }

    @Test
    void petsExceptionIsGenerated() {
        assertThat(apiFile("PetsException.java").exists(), is(true));
    }

    @Test
    void petsErrorHandlerIsGenerated() {
        assertThat(apiFile("PetsErrorHandler.java").exists(), is(true));
    }

    @Test
    void petModelIsGenerated() {
        assertThat(modelFile("Pet.java").exists(), is(true));
    }

    @Test
    void apiErrorModelIsGenerated() {
        // "Error" schema → renamed to "ApiError"
        assertThat(modelFile("ApiError.java").exists(), is(true));
    }

    @Test
    void petsEndpointUnitTestIsGenerated() {
        assertThat(apiTestFile("PetsEndpointTest.java").exists(), is(true));
    }

    // -------------------------------------------------------------------------
    // PetsEndpoint.java content
    // -------------------------------------------------------------------------

    @Test
    void endpointHasRestServerEndpointAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")), containsString("@RestServer.Endpoint"));
    }

    @Test
    void endpointHasServiceSingletonAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")), containsString("@Service.Singleton"));
    }

    @Test
    void endpointDoesNotDuplicateHttpPathAnnotation() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")), not(containsString("@Http.Path(\"/pets\")")));
    }

    @Test
    void endpointImplementsSharedApi() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")), containsString("implements PetsApi"));
    }

    @Test
    void apiInterfaceListPetsHasGetAnnotation() throws IOException {
        assertThat(read(apiFile("PetsApi.java")), containsString("@Http.GET"));
    }

    @Test
    void apiInterfaceCreatePetsHasPostAndStatus201() throws IOException {
        String content = read(apiFile("PetsApi.java"));
        assertThat(content, containsString("@Http.POST"));
        assertThat(content, containsString("@RestServer.Status(201)"));
    }

    @Test
    void endpointListPetsHasOptionalLimitParam() throws IOException {
        String content = read(apiFile("PetsEndpoint.java"));
        assertThat(content, containsString("Optional<Integer>"));
        assertThat(content, containsString("@Http.QueryParam(\"limit\")"));
    }

    @Test
    void endpointListPetsHasComputedHeaderAnnotation() throws IOException {
        // listPets has x-next response header → @RestServer.ComputedHeader annotation
        String content = read(apiFile("PetsEndpoint.java"));
        assertThat(content, containsString("@RestServer.ComputedHeader(name = \"x-next\""));
        assertThat(content, not(containsString("ServerResponse")));
    }

    @Test
    void computedHeaderFunctionIsGeneratedAsOwnFile() throws IOException {
        String content = read(apiFile("PetsXNextHeaderFn.java"));
        assertThat(content, containsString("@Service.Named(\"xNextHeaderFn\")"));
        assertThat(content, containsString("class PetsXNextHeaderFn implements Http.HeaderFunction"));
        assertThat(content, containsString("Optional<Header> apply(HeaderName headerName)"));
    }

    @Test
    void computedHeaderFunctionImportsHeaderTypes() throws IOException {
        String content = read(apiFile("PetsXNextHeaderFn.java"));
        assertThat(content, containsString("import io.helidon.http.Header;"));
        assertThat(content, containsString("import io.helidon.http.HeaderName;"));
    }

    @Test
    void endpointShowPetByIdHasPathParam() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")), containsString("@Http.PathParam(\"petId\")"));
    }

    @Test
    void endpointListPetsReturnsListOfPet() throws IOException {
        assertThat(read(apiFile("PetsEndpoint.java")), containsString("List<Pet>"));
    }

    // -------------------------------------------------------------------------
    // PetsApi.java content
    // -------------------------------------------------------------------------

    @Test
    void apiInterfaceIsAnInterface() throws IOException {
        assertThat(read(apiFile("PetsApi.java")), containsString("public interface PetsApi"));
    }

    @Test
    void apiInterfaceHasHttpPathAnnotation() throws IOException {
        assertThat(read(apiFile("PetsApi.java")), containsString("@Http.Path(\"/pets\")"));
    }

    // -------------------------------------------------------------------------
    // Generated unit test content
    // -------------------------------------------------------------------------

    @Test
    void endpointUnitTestInstantiatesEndpoint() throws IOException {
        String content = read(apiTestFile("PetsEndpointTest.java"));
        assertThat(content, containsString("class PetsEndpointTest"));
        assertThat(content, containsString("void testListPets()"));
        assertThat(content, containsString("void testCreatePets()"));
        assertThat(content, containsString("void testShowPetById()"));
        assertThat(content, anyOf(
                containsString("@ServerTest"),
                containsString("assertThat(new PetsEndpoint(), notNullValue())")));
    }

    // -------------------------------------------------------------------------
    // Pet.java model content
    // -------------------------------------------------------------------------

    @Test
    void petModelHasCorrectPackage() throws IOException {
        assertThat(read(modelFile("Pet.java")), containsString("package io.helidon.example.model;"));
    }

    @Test
    void petModelHasJsonEntityAnnotation() throws IOException {
        assertThat(read(modelFile("Pet.java")), containsString("@Json.Entity"));
    }

    @Test
    void petModelRequiredFieldsHaveJsonRequired() throws IOException {
        String content = read(modelFile("Pet.java"));
        // id and name are required in petstore.yaml
        assertThat(content, containsString("@Json.Required"));
    }

    @Test
    void petModelHasIdNameTagFields() throws IOException {
        String content = read(modelFile("Pet.java"));
        assertThat(content, containsString("Long id"));
        assertThat(content, containsString("String name"));
        assertThat(content, containsString("String tag"));
    }

    @Test
    void petModelHasGettersAndSetters() throws IOException {
        String content = read(modelFile("Pet.java"));
        assertThat(content, containsString("getId()"));
        assertThat(content, containsString("setId("));
        assertThat(content, containsString("getName()"));
        assertThat(content, containsString("setName("));
    }

    @Test
    void petModelNoSwaggerImports() throws IOException {
        String content = read(modelFile("Pet.java"));
        assertThat(content, not(containsString("io.swagger.annotations")));
        assertThat(content, not(containsString("ApiModel")));
        assertThat(content, not(containsString("ApiModelProperty")));
    }

    // -------------------------------------------------------------------------
    // ApiError.java model content
    // -------------------------------------------------------------------------

    @Test
    void apiErrorModelIsNamedApiError() throws IOException {
        assertThat(read(modelFile("ApiError.java")), containsString("public class ApiError"));
    }

    @Test
    void apiErrorModelHasCodeAndMessageFields() throws IOException {
        String content = read(modelFile("ApiError.java"));
        assertThat(content, containsString("Integer code"));
        assertThat(content, containsString("String message"));
    }

    // -------------------------------------------------------------------------
    // pom.xml content
    // -------------------------------------------------------------------------

    @Test
    void pomXmlContainsHelidonVersion() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()), containsString("4.4.1"));
    }

    @Test
    void pomXmlContainsHelidonWebserver() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()), containsString("helidon-webserver"));
    }

    @Test
    void pomXmlContainsJsonBindingDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()), containsString("helidon-http-media-json-binding"));
    }

    @Test
    void pomXmlContainsJUnitDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()),
                   containsString("<artifactId>junit-jupiter</artifactId>"));
    }

    @Test
    void pomXmlContainsServerTestDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()),
                   containsString("<artifactId>helidon-webserver-testing-junit5</artifactId>"));
    }

    @Test
    void pomXmlContainsWebclientApiDependency() throws IOException {
        assertThat(read(outputDir.resolve("pom.xml").toFile()),
                   containsString("<artifactId>helidon-webclient-api</artifactId>"));
    }

    @Test
    void pomXmlDisablesShadePlugin() throws IOException {
        String pom = read(outputDir.resolve("pom.xml").toFile());
        assertThat(pom, containsString("<artifactId>maven-shade-plugin</artifactId>"));
        assertThat(pom, containsString("<skip>true</skip>"));
    }

    @Test
    void buildGradleContainsApplicationPlugin() throws IOException {
        assertThat(read(outputDir.resolve("build.gradle").toFile()),
                   containsString("id 'application'"));
    }

    @Test
    void buildGradleContainsHelidonWebserver() throws IOException {
        assertThat(read(outputDir.resolve("build.gradle").toFile()),
                   containsString("io.helidon.webserver:helidon-webserver"));
    }

    @Test
    void buildGradleConfiguresAnnotationProcessorBundle() throws IOException {
        assertThat(read(outputDir.resolve("build.gradle").toFile()),
                   containsString("io.helidon.bundles:helidon-bundles-apt"));
    }

    @Test
    void buildGradleConfiguresMainClass() throws IOException {
        assertThat(read(outputDir.resolve("build.gradle").toFile()),
                   containsString("mainClass = 'io.helidon.example.Main'"));
    }

    @Test
    void clientHasDeclarativeEndpointAnnotation() throws IOException {
        assertThat(read(apiFile("PetsClient.java")),
                   containsString("@RestClient.Endpoint(\"${app.client.endpoint:http://localhost:8080}\")"));
    }

    // -------------------------------------------------------------------------
    // Main.java content
    // -------------------------------------------------------------------------

    @Test
    void mainJavaHasGenerateBindingAnnotation() throws IOException {
        assertThat(read(javaFile("io/helidon/example/Main.java")), containsString("@Service.GenerateBinding"));
    }

    @Test
    void generatedProjectBuildsWithMaven() throws Exception {
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    @Test
    void generatedProjectBuildsWithGradle() throws Exception {
        GeneratedProjectBuildSupport.assertGradleBuildSucceeds(outputDir);
    }

    @Test
    void generatedProjectTestsWithGradle() throws Exception {
        GeneratedProjectBuildSupport.assertGradleTestSucceeds(outputDir);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private File apiFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/api/" + name).toFile();
    }

    private File modelFile(String name) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + name).toFile();
    }

    private File apiTestFile(String name) {
        return outputDir.resolve("src/test/java/io/helidon/example/api/" + name).toFile();
    }

    @Test
    void openapiYamlIsGenerated() {
        assertThat(Files.exists(outputDir.resolve("src/main/resources/META-INF/openapi.yaml")), is(true));
    }

    @Test
    void openapiYamlContainsSpecContent() throws IOException {
        String content = Files.readString(
                outputDir.resolve("src/main/resources/META-INF/openapi.yaml"));
        assertThat(content, containsString("openapi:"));
        assertThat(content, containsString("paths:"));
        assertThat(content, containsString("/pets"));
        assertThat(content, not(containsString("null")));
    }

    @Test
    void applicationTestYamlIsGeneratedWithServerTestEndpoint() throws IOException {
        String content = Files.readString(
                outputDir.resolve("src/test/resources/application-test.yaml"));
        assertThat(content, containsString("app:"));
        assertThat(content, containsString("endpoint: \"http://localhost:${test.server.port}\""));
    }

    private File javaFile(String relativePath) {
        return outputDir.resolve("src/main/java/" + relativePath).toFile();
    }

    private File resourceFile(String name) {
        return outputDir.resolve("src/main/resources/" + name).toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath()).replace("\r\n", "\n");
    }
}
