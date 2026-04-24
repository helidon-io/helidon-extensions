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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

class DiscriminatorEnumAllOfGenerationIT {

    @TempDir
    Path tempDir;

    @Test
    void explicitEnumDiscriminatorSubtypeSeedsEnumConstant() throws Exception {
        Path outputDir = generate("discriminator-enum-repro.yaml");
        String content = read(modelFile(outputDir, "RegionHealthCheckCategoryDetails.java"));
        assertThat(content, containsString("public RegionHealthCheckCategoryDetails()"));
        assertThat(content, containsString("setCategory(MqlCheckDetails.CategoryEnum.REGION_HEALTH_CHECK);"));
        assertThat(content, not(containsString("setCategory(\"REGION_HEALTH_CHECK\")")));
    }

    @Test
    void baseDiscriminatorRemainsEnumTyped() throws Exception {
        Path outputDir = generate("discriminator-enum-repro.yaml");
        String content = read(modelFile(outputDir, "MqlCheckDetails.java"));
        assertThat(content, containsString("private CategoryEnum category;"));
        assertThat(content, containsString("public void setCategory(CategoryEnum category)"));
        assertThat(content, containsString("public enum CategoryEnum"));
    }

    @Test
    void mappedEnumDiscriminatorSubtypeUsesActualDiscriminatorValue() throws Exception {
        Path outputDir = generate("discriminator-enum-mapping-repro.yaml");

        String parent = read(modelFile(outputDir, "ConditionShapeDetails.java"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"CHANGE_FREEZE\", value = ChangeFreezeConditionShape.class)"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"TIME_WINDOW_CONSTRAINTS\", value = TimeWindowConstraintsConditionShape.class)"));

        String changeFreeze = read(modelFile(outputDir, "ChangeFreezeConditionShape.java"));
        assertThat(changeFreeze, containsString("setConditionShape(ConditionShapeDetails.ConditionShapeEnum.CHANGE_FREEZE);"));
        assertThat(changeFreeze, not(containsString("CHANGE_FREEZE_CONDITION_SHAPE")));

        String timeWindow = read(modelFile(outputDir, "TimeWindowConstraintsConditionShape.java"));
        assertThat(timeWindow, containsString("setConditionShape(ConditionShapeDetails.ConditionShapeEnum.TIME_WINDOW_CONSTRAINTS);"));
        assertThat(timeWindow, not(containsString("TIME_WINDOW_CONSTRAINTS_CONDITION_SHAPE")));
    }

    @Test
    void mappedEnumDiscriminatorGeneratedProjectBuilds() throws Exception {
        Path outputDir = generate("discriminator-enum-mapping-repro.yaml");
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    @Test
    void mappedEnumDiscriminatorSubtypeUsesActualDiscriminatorValueInSimpleHierarchy() throws Exception {
        Path outputDir = generate("discriminator-enum-mapping-repro-2.yaml");

        String parent = read(modelFile(outputDir, "UserConfig.java"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"INSTANT\", value = UserConfigInstantValue.class)"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"STRING\", value = UserConfigStringValue.class)"));

        String stringValue = read(modelFile(outputDir, "UserConfigStringValue.java"));
        assertThat(stringValue, containsString("setType(UserConfig.TypeEnum.STRING);"));
        assertThat(stringValue, not(containsString("USER_CONFIG_STRING_VALUE")));

        String instantValue = read(modelFile(outputDir, "UserConfigInstantValue.java"));
        assertThat(instantValue, containsString("setType(UserConfig.TypeEnum.INSTANT);"));
        assertThat(instantValue, not(containsString("USER_CONFIG_INSTANT_VALUE")));
    }

    @Test
    void mappedEnumDiscriminatorGeneratedProjectBuildsInSimpleHierarchy() throws Exception {
        Path outputDir = generate("discriminator-enum-mapping-repro-2.yaml");
        GeneratedProjectBuildSupport.assertMavenPackageSucceeds(outputDir);
    }

    @Test
    void explicitDiscriminatorValueWinsOverSubtypeSchemaName() throws Exception {
        Path outputDir = generate("discriminator-enum-explicit-value-repro.yaml");

        String parent = read(modelFile(outputDir, "ApprovalInvalidationTypeDetails.java"));
        assertThat(parent, containsString("AUTHOR_DEFINED_TIME_EXPIRY"));

        String explicitSubtype = read(modelFile(outputDir, "ConditionAuthorDefinedExpiringApprovalInvalidationTypeDetails.java"));
        assertThat(explicitSubtype, containsString("setApprovalInvalidationType("
                + "ApprovalInvalidationTypeDetails.ApprovalInvalidationTypeEnum.AUTHOR_DEFINED_TIME_EXPIRY);"));
        assertThat(explicitSubtype,
                not(containsString("CONDITION_AUTHOR_DEFINED_EXPIRING_APPROVAL_INVALIDATION_TYPE_DETAILS")));
    }

    @Test
    void explicitDiscriminatorValuePreservesPhonebookNormalization() throws Exception {
        Path outputDir = generate("discriminator-enum-phonebook-repro.yaml");

        String parent = read(modelFile(outputDir, "ApplicableScope.java"));
        assertThat(parent, containsString("SERVICE_PHONEBOOK_SCOPE"));

        String subtype = read(modelFile(outputDir, "ServicePhoneBookScope.java"));
        assertThat(subtype, containsString("setScopeType(ApplicableScope.ScopeTypeEnum.SERVICE_PHONEBOOK_SCOPE);"));
        assertThat(subtype, not(containsString("SERVICE_PHONE_BOOK_SCOPE")));
    }

    @Test
    void explicitDiscriminatorValuePreservesPrerequisitesCanonicalization() throws Exception {
        Path outputDir = generate("discriminator-enum-prerequisites-repro.yaml");

        String parent = read(modelFile(outputDir, "ConditionShapeDetails.java"));
        assertThat(parent, containsString("CM_PREREQUISITES_VIOLATION"));

        String subtype = read(modelFile(outputDir, "CmPreRequisiteViolationConditionShape.java"));
        assertThat(subtype, containsString("setConditionShape("
                + "ConditionShapeDetails.ConditionShapeEnum.CM_PREREQUISITES_VIOLATION);"));
        assertThat(subtype, not(containsString("CM_PRE_REQUISITE_VIOLATION_CONDITION_SHAPE")));
    }

    @Test
    void oneOfParentConstructorUsesExplicitDiscriminatorValue() throws Exception {
        Path outputDir = generate("discriminator-oneof-explicit-value.yaml");

        String parent = read(modelFile(outputDir, "ApprovalInvalidationTypeDetails.java"));
        assertThat(parent, containsString("AUTHOR_DEFINED_TIME_EXPIRY"));

        String subtype = read(modelFile(outputDir, "AuthorDefinedExpiryDetails.java"));
        assertThat(subtype, containsString("setApprovalInvalidationType("
                + "ApprovalInvalidationTypeDetails.ApprovalInvalidationTypeEnum.AUTHOR_DEFINED_TIME_EXPIRY);"));
        assertThat(subtype, not(containsString("AUTHOR_DEFINED_EXPIRY_DETAILS")));
    }

    @Test
    void oneOfParentConstructorPreservesCompoundWordNormalization() throws Exception {
        Path outputDir = generate("discriminator-oneof-compound-word.yaml");

        String parent = read(modelFile(outputDir, "ApplicableScope.java"));
        assertThat(parent, containsString("SERVICE_PHONEBOOK_SCOPE"));

        String subtype = read(modelFile(outputDir, "ServicePhoneBookScope.java"));
        assertThat(subtype, containsString("setScopeType(ApplicableScope.ScopeTypeEnum.SERVICE_PHONEBOOK_SCOPE);"));
        assertThat(subtype, not(containsString("SERVICE_PHONE_BOOK_SCOPE")));
    }

    @Test
    void oneOfParentConstructorIgnoresLegacyClassNameDrift() throws Exception {
        Path outputDir = generate("discriminator-oneof-name-drift.yaml");

        String parent = read(modelFile(outputDir, "ConditionShapeDetails.java"));
        assertThat(parent, containsString("CM_PREREQUISITES_VIOLATION"));

        String subtype = read(modelFile(outputDir, "CmPreRequisiteViolationConditionShape.java"));
        assertThat(subtype, containsString("setConditionShape("
                + "ConditionShapeDetails.ConditionShapeEnum.CM_PREREQUISITES_VIOLATION);"));
        assertThat(subtype, not(containsString("CM_PRE_REQUISITE_VIOLATION_CONDITION_SHAPE")));
    }

    @Test
    void anyOfParentDeclaredViaAllOfUsesCanonicalExplicitDiscriminatorValue() throws Exception {
        Path outputDir = generate("discriminator-anyof-allof-base-explicit-value.yaml");

        String parent = read(modelFile(outputDir, "ApprovalInvalidationTypeDetails.java"));
        assertThat(parent, containsString("public class ApprovalInvalidationTypeDetails"));
        assertThat(parent, containsString("AUTHOR_DEFINED_TIME_EXPIRY"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"AUTHOR_DEFINED_TIME_EXPIRY\", value = AuthorDefinedExpiryDetails.class)"));

        String subtype = read(modelFile(outputDir, "AuthorDefinedExpiryDetails.java"));
        assertThat(subtype, containsString("public class AuthorDefinedExpiryDetails extends ApprovalInvalidationTypeDetails"));
        assertThat(subtype, containsString("setApprovalInvalidationType("
                + "ApprovalInvalidationTypeDetails.ApprovalInvalidationTypeEnum.AUTHOR_DEFINED_TIME_EXPIRY);"));
        assertThat(subtype, not(containsString("AUTHOR_DEFINED_EXPIRY_DETAILS")));
    }

    @Test
    void anyOfParentDeclaredViaAllOfPreservesCompoundWordEnumValue() throws Exception {
        Path outputDir = generate("discriminator-anyof-allof-base-phonebook.yaml");

        String parent = read(modelFile(outputDir, "ApplicableScope.java"));
        assertThat(parent, containsString("public class ApplicableScope"));
        assertThat(parent, containsString("SERVICE_PHONEBOOK_SCOPE"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"SERVICE_PHONEBOOK_SCOPE\", value = ServicePhoneBookScope.class)"));

        String subtype = read(modelFile(outputDir, "ServicePhoneBookScope.java"));
        assertThat(subtype, containsString("public class ServicePhoneBookScope extends ApplicableScope"));
        assertThat(subtype, containsString("setScopeType(ApplicableScope.ScopeTypeEnum.SERVICE_PHONEBOOK_SCOPE);"));
        assertThat(subtype, not(containsString("SERVICE_PHONE_BOOK_SCOPE")));
    }

    @Test
    void shorthandDiscriminatorValueSupportsNameDrift() throws Exception {
        Path outputDir = generate("discriminator-shorthand-name-drift.yaml", false);

        String parent = read(modelFile(outputDir, "ConditionShapeDetails.java"));
        assertThat(parent, containsString("CM_PREREQUISITES_VIOLATION"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"CM_PREREQUISITES_VIOLATION\", value = CmPreRequisiteViolationConditionShape.class)"));

        String subtype = read(modelFile(outputDir, "CmPreRequisiteViolationConditionShape.java"));
        assertThat(subtype, containsString("setConditionShape("
                + "ConditionShapeDetails.ConditionShapeEnum.CM_PREREQUISITES_VIOLATION);"));
        assertThat(subtype, not(containsString("CM_PRE_REQUISITE_VIOLATION_CONDITION_SHAPE")));
    }

    @Test
    void shorthandDiscriminatorValueSupportsCompoundWordNormalization() throws Exception {
        Path outputDir = generate("discriminator-shorthand-compound-word.yaml", false);

        String parent = read(modelFile(outputDir, "ApplicableScope.java"));
        assertThat(parent, containsString("SERVICE_PHONEBOOK_SCOPE"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"SERVICE_PHONEBOOK_SCOPE\", value = ServicePhoneBookScope.class)"));

        String subtype = read(modelFile(outputDir, "ServicePhoneBookScope.java"));
        assertThat(subtype, containsString("setScopeType(ApplicableScope.ScopeTypeEnum.SERVICE_PHONEBOOK_SCOPE);"));
        assertThat(subtype, not(containsString("SERVICE_PHONE_BOOK_SCOPE")));
    }

    @Test
    void shorthandDiscriminatorValueSupportsExplicitValueDifferentFromClassName() throws Exception {
        Path outputDir = generate("discriminator-shorthand-explicit-value.yaml", false);

        String parent = read(modelFile(outputDir, "ApprovalInvalidationTypeDetails.java"));
        assertThat(parent, containsString("AUTHOR_DEFINED_TIME_EXPIRY"));
        assertThat(parent, containsString("@Json.Subtype(alias = \"AUTHOR_DEFINED_TIME_EXPIRY\", value = AuthorDefinedExpiryDetails.class)"));

        String subtype = read(modelFile(outputDir, "AuthorDefinedExpiryDetails.java"));
        assertThat(subtype, containsString("setApprovalInvalidationType("
                + "ApprovalInvalidationTypeDetails.ApprovalInvalidationTypeEnum.AUTHOR_DEFINED_TIME_EXPIRY);"));
        assertThat(subtype, not(containsString("AUTHOR_DEFINED_EXPIRY_DETAILS")));
    }

    private Path generate(String resourceName) throws Exception {
        return generate(resourceName, true);
    }

    private Path generate(String resourceName, boolean validateSpec) throws Exception {
        URL resource = DiscriminatorEnumAllOfGenerationIT.class
                .getClassLoader()
                .getResource(resourceName);
        String specPath = Paths.get(resource.toURI()).toAbsolutePath().toString();
        Path outputDir = tempDir.resolve(resourceName.replace(".yaml", ""));

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("helidon-declarative")
                .setInputSpec(specPath)
                .setOutputDir(outputDir.toString())
                .setValidateSpec(validateSpec)
                .addAdditionalProperty("helidonVersion", "4.4.1")
                .addAdditionalProperty("apiPackage", "io.helidon.example.api")
                .addAdditionalProperty("modelPackage", "io.helidon.example.model")
                .addAdditionalProperty("invokerPackage", "io.helidon.example");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
        return outputDir;
    }

    private static File modelFile(Path outputDir, String fileName) {
        return outputDir.resolve("src/main/java/io/helidon/example/model/" + fileName).toFile();
    }

    private static String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
