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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.codegen.CodegenType;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link HelidonDeclarativeCodegen} — verifies naming conventions,
 * metadata, and template registration without running the full generation pipeline.
 */
class HelidonDeclarativeCodegenTest {

    private HelidonDeclarativeCodegen codegen;

    @BeforeEach
    void setUp() {
        codegen = new HelidonDeclarativeCodegen();
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    void getNameReturnsCorrectId() {
        assertThat(codegen.getName(), is("helidon-declarative"));
    }

    @Test
    void getTagIsServer() {
        assertThat(codegen.getTag(), is(CodegenType.SERVER));
    }

    @Test
    void getHelpIsNotBlank() {
        assertThat(codegen.getHelp() != null && !codegen.getHelp().isBlank(), is(true));
    }

    // -------------------------------------------------------------------------
    // toApiName
    // -------------------------------------------------------------------------

    @Test
    void toApiNameSimpleTagReturnsCamelCase() {
        assertThat(codegen.toApiName("pets"), is("Pets"));
    }

    @Test
    void toApiNameEmptyTagReturnsDefault() {
        assertThat(codegen.toApiName(""), is("Default"));
    }

    @Test
    void toApiNameNullTagReturnsDefault() {
        assertThat(codegen.toApiName(null), is("Default"));
    }

    @Test
    void toApiNameHyphenatedTagReturnsCamelCase() {
        assertThat(codegen.toApiName("pet-store"), is("PetStore"));
    }

    @Test
    void toApiNameMultiWordTagReturnsCamelCase() {
        assertThat(codegen.toApiName("store orders"), is("StoreOrders"));
    }

    // -------------------------------------------------------------------------
    // toModelName
    // -------------------------------------------------------------------------

    @Test
    void toModelNameErrorMappedToApiError() {
        // "Error" clashes with java.lang.Error — must be remapped
        assertThat(codegen.toModelName("Error"), is("ApiError"));
    }

    @Test
    void toModelNamePetUnchanged() {
        assertThat(codegen.toModelName("Pet"), is("Pet"));
    }

    // -------------------------------------------------------------------------
    // apiFilename
    // -------------------------------------------------------------------------

    @Test
    void apiFilenameApiMustacheProducesEndpointJava() {
        String filename = codegen.apiFilename("api.mustache", "pets");
        assertThat(filename.endsWith("PetsEndpoint.java"), is(true));
    }

    @Test
    void apiFilenameApiInterfaceMustacheProducesApiJava() {
        String filename = codegen.apiFilename("api-interface.mustache", "pets");
        assertThat(filename.endsWith("PetsApi.java"), is(true));
    }

    @Test
    void apiFilenameRestClientMustacheProducesClientJava() {
        String filename = codegen.apiFilename("restClient.mustache", "pets");
        assertThat(filename.endsWith("PetsClient.java"), is(true));
    }

    @Test
    void apiFilenameApiExceptionMustacheProducesExceptionJava() {
        String filename = codegen.apiFilename("apiException.mustache", "pets");
        assertThat(filename.endsWith("PetsException.java"), is(true));
    }

    @Test
    void apiFilenameErrorHandlerMustacheProducesErrorHandlerJava() {
        String filename = codegen.apiFilename("errorHandler.mustache", "pets");
        assertThat(filename.endsWith("PetsErrorHandler.java"), is(true));
    }

    // -------------------------------------------------------------------------
    // Template registration
    // -------------------------------------------------------------------------

    @Test
    void constructorRegistersApiAndApiInterfaceTemplates() {
        assertThat(codegen.apiTemplateFiles().containsKey("api.mustache"), is(true));
        assertThat(codegen.apiTemplateFiles().containsKey("api-interface.mustache"), is(true));
    }

    @Test
    void constructorRegistersModelTemplate() {
        assertThat(codegen.modelTemplateFiles().containsKey("model.mustache"), is(true));
    }

    @Test
    void constructorClearsDocTemplatesAndRegistersUnitTestTemplates() {
        assertThat(codegen.modelDocTemplateFiles().isEmpty(), is(true));
        assertThat(codegen.apiDocTemplateFiles().isEmpty(), is(true));
        assertThat(".java".equals(codegen.apiTestTemplateFiles().get("api-test.mustache")), is(true));
        assertThat(codegen.modelTestTemplateFiles().isEmpty(), is(true));
    }

    @Test
    void javaStringLiteralEscapesUncommonControlCharacters() {
        String value = "a" + (char) 0 + (char) 0x1B + "b";

        assertThat(JavaStringLiterals.toJavaStringLiteral(value),
                   is("\"a" + "\\u0000" + "\\u001b" + "b\""));
    }

    @Test
    void rawSpecReadRejectsUnsupportedUriSchemes() {
        IOException exception = assertThrows(IOException.class,
                                             () -> InputSpecContentReader.read("jar:https://example.com/spec.yaml"));
        assertThat(exception.getMessage(), containsString("Unsupported input spec URI scheme"));
    }

    // -------------------------------------------------------------------------
    // Security role annotation value formatting
    // -------------------------------------------------------------------------

    @Test
    void singleSecurityRoleFormattedAsQuotedString() {
        // x-roles-annotation-value for a single role: "admin"
        // Verify via fromOperation by checking the vendor extension directly
        io.swagger.v3.oas.models.Operation op = new io.swagger.v3.oas.models.Operation();
        op.addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement()
                .addList("basicAuth", java.util.List.of("admin")));
        org.openapitools.codegen.CodegenOperation cop =
                codegen.fromOperation("/items", "get", op, java.util.List.of());
        assertThat("\"admin\"".equals(cop.vendorExtensions.get("x-roles-annotation-value")), is(true));
    }

    @Test
    void multipleSecurityRolesFormattedAsArray() {
        io.swagger.v3.oas.models.Operation op = new io.swagger.v3.oas.models.Operation();
        op.addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement()
                .addList("basicAuth", java.util.List.of("admin", "moderator")));
        org.openapitools.codegen.CodegenOperation cop =
                codegen.fromOperation("/items", "delete", op, java.util.List.of());
        assertThat("{\"admin\", \"moderator\"}".equals(cop.vendorExtensions.get("x-roles-annotation-value")),
                   is(true));
    }

    @Test
    void noSecurityNoSecurityVendorExtensions() {
        io.swagger.v3.oas.models.Operation op = new io.swagger.v3.oas.models.Operation();
        org.openapitools.codegen.CodegenOperation cop =
                codegen.fromOperation("/items", "get", op, java.util.List.of());
        assertThat(cop.vendorExtensions.containsKey("x-has-security-roles"), is(false));
        assertThat(cop.vendorExtensions.containsKey("x-roles-annotation-value"), is(false));
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    @Test
    void defaultPackagesMatchExpectedValues() {
        assertThat(codegen.apiPackage(), is("io.helidon.example.api"));
        assertThat(codegen.modelPackage(), is("io.helidon.example.model"));
        assertThat(codegen.getInvokerPackage(), is("io.helidon.example"));
    }
}
