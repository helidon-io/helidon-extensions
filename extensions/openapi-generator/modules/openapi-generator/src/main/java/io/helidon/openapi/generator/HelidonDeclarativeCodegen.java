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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.servers.Server;
import org.openapitools.codegen.CodegenComposedSchemas;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.CodegenResponse;
import org.openapitools.codegen.CodegenType;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.AbstractJavaCodegen;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;

import static org.openapitools.codegen.utils.StringUtils.camelize;

/**
 * openapi-generator SPI implementation that generates Helidon SE 4.x declarative code.
 *
 * <p>Register via SPI: {@code META-INF/services/org.openapitools.codegen.CodegenConfig}
 * pointing to this class. Use with {@code -g helidon-declarative}.</p>
 *
 * <p>Generates per tag group:</p>
 * <ul>
 *   <li>{Tag}Api.java — {@code @Http.Path} interface (shared contract)</li>
 *   <li>{Tag}Endpoint.java — {@code @RestServer.Endpoint @Service.Singleton} implementation</li>
 *   <li>{Tag}Client.java — {@code @RestClient.Endpoint} interface (if generateClient=true)</li>
 *   <li>{Tag}Exception.java — RuntimeException subclass (if generateErrorHandler=true)</li>
 *   <li>{Tag}ErrorHandler.java — ErrorHandler implementation (if generateErrorHandler=true)</li>
 * </ul>
 * Plus per model: {Model}.java (Helidon build-time JSON binding POJO)
 * Plus supporting files: pom.xml, build.gradle, settings.gradle, Main.java,
 * application.yaml, logging.properties.
 */
public class HelidonDeclarativeCodegen extends AbstractJavaCodegen {

    static final String OPT_HELIDON_VERSION = "helidonVersion";
    static final String OPT_GENERATE_CLIENT = "generateClient";
    static final String OPT_GENERATE_ERROR_HANDLER = "generateErrorHandler";
    static final String OPT_SERVER_OPENAPI = "serverOpenApi";
    static final String OPT_SERVER_BASE_PATH = "serverBasePath";
    static final String OPT_LEGACY_SERVE_OPENAPI = "serveOpenApi";
    static final String OPT_LEGACY_SERVE_BASE_PATH = "serveBasePath";
    static final String OPT_CORS_ENABLED = "corsEnabled";
    static final String OPT_FT_ENABLED = "ftEnabled";
    static final String OPT_TRACING_ENABLED = "tracingEnabled";
    static final String OPT_METRICS_ENABLED = "metricsEnabled";
    static final String OPT_AVOID_OPTIONAL_LIST_PARAMS = "avoidOptionalListParams";

    private String helidonVersion = "4.4.1";
    private boolean generateClient = true;
    private boolean generateErrorHandler = true;
    private boolean serverOpenApi = true;
    private String serverBasePath = "";
    private boolean corsEnabled = false;
    private boolean ftEnabled = false;
    private boolean tracingEnabled = false;
    private boolean metricsEnabled = false;
    private boolean avoidOptionalListParams = false;
    private List<SecurityRequirement> globalSecurityRequirements = List.of();
    private OpenAPI currentOpenAPI;

    /**
     * Creates a new generator with default options and template mappings.
     */
    public HelidonDeclarativeCodegen() {
        super();

        outputFolder = "generated-code/helidon-declarative";
        // Setting the fields directly configures where templates are resolved from
        templateDir = "helidon-declarative";
        embeddedTemplateDir = "helidon-declarative";

        apiPackage = "io.helidon.example.api";
        modelPackage = "io.helidon.example.model";
        invokerPackage = "io.helidon.example";

        // Default model name mapping: "Error" clashes with java.lang.Error
        modelNameMapping.put("Error", "ApiError");

        // Clear doc/test templates inherited from AbstractJavaCodegen — not generated
        modelDocTemplateFiles.clear();
        apiDocTemplateFiles.clear();
        apiTestTemplateFiles.clear();
        modelTestTemplateFiles.clear();

        // Templates for per-API-tag files
        apiTemplateFiles.clear();  // clear any defaults first
        apiTemplateFiles.put("api.mustache", "Endpoint.java");
        apiTemplateFiles.put("api-interface.mustache", "Api.java");

        // Template for per-model files
        modelTemplateFiles.put("model.mustache", ".java");
        // Unit test template for generated API classes
        apiTestTemplateFiles.put("api-test.mustache", ".java");

        // Supporting files (processed as Mustache templates)
        supportingFiles.add(new SupportingFile("pom.xml.mustache", "", "pom.xml"));
        supportingFiles.add(new SupportingFile("build.gradle.mustache", "", "build.gradle"));
        supportingFiles.add(new SupportingFile("settings.gradle.mustache", "", "settings.gradle"));
        supportingFiles.add(new SupportingFile("application.yaml.mustache",
                "src/main/resources", "application.yaml"));
        supportingFiles.add(new SupportingFile("application-test.yaml.mustache",
                "src/test/resources", "application-test.yaml"));
        supportingFiles.add(new SupportingFile("logging.properties.mustache",
                "src/main/resources", "logging.properties"));

        // Generator options
        addOption(OPT_HELIDON_VERSION,
                "Helidon version written into the generated pom.xml",
                helidonVersion);
        addOption(OPT_GENERATE_CLIENT,
                "Generate @RestClient.Endpoint interface per tag",
                String.valueOf(generateClient));
        addOption(OPT_GENERATE_ERROR_HANDLER,
                "Generate Exception + ErrorHandler classes per tag",
                String.valueOf(generateErrorHandler));
        addOption(OPT_SERVER_OPENAPI,
                "Copy spec to META-INF/openapi.yaml and add helidon-openapi dependency",
                String.valueOf(serverOpenApi));
        addOption(OPT_SERVER_BASE_PATH,
                "Base path prefix to add in front of all endpoint paths (e.g. /v1)",
                serverBasePath);
        addOption(OPT_CORS_ENABLED,
                "Add @Cors.Defaults to every endpoint class (enables CORS via application.yaml configuration)",
                String.valueOf(corsEnabled));
        addOption(OPT_FT_ENABLED,
                "Add @Ft.Retry to every generated REST client interface (enables automatic retries)",
                String.valueOf(ftEnabled));
        addOption(OPT_TRACING_ENABLED,
                "Add @Tracing.Traced to every endpoint class (creates spans for all endpoint methods)",
                String.valueOf(tracingEnabled));
        addOption(OPT_METRICS_ENABLED,
                "Add @Metrics.Timed to every endpoint method (records invocation timing)",
                String.valueOf(metricsEnabled));
        addOption(OPT_AVOID_OPTIONAL_LIST_PARAMS,
                "Use bare List<T> instead of Optional<List<T>> for optional query list parameters",
                String.valueOf(avoidOptionalListParams));
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Override
    public String getName() {
        return "helidon-declarative";
    }

    @Override
    public CodegenType getTag() {
        return CodegenType.SERVER;
    }

    @Override
    public String getHelp() {
        return "Generates a Helidon SE 4.x declarative server using @RestServer.Endpoint annotations.";
    }

    // -------------------------------------------------------------------------
    // Option processing
    // -------------------------------------------------------------------------

    @Override
    public void processOpts() {
        super.processOpts();

        if (additionalProperties.containsKey(OPT_HELIDON_VERSION)) {
            helidonVersion = additionalProperties.get(OPT_HELIDON_VERSION).toString();
        }
        if (additionalProperties.containsKey(OPT_GENERATE_CLIENT)) {
            generateClient = Boolean.parseBoolean(
                    additionalProperties.get(OPT_GENERATE_CLIENT).toString());
        }
        if (additionalProperties.containsKey(OPT_GENERATE_ERROR_HANDLER)) {
            generateErrorHandler = Boolean.parseBoolean(
                    additionalProperties.get(OPT_GENERATE_ERROR_HANDLER).toString());
        }
        if (additionalProperties.containsKey(OPT_SERVER_OPENAPI)) {
            serverOpenApi = Boolean.parseBoolean(
                    additionalProperties.get(OPT_SERVER_OPENAPI).toString());
        } else if (additionalProperties.containsKey(OPT_LEGACY_SERVE_OPENAPI)) {
            serverOpenApi = Boolean.parseBoolean(
                    additionalProperties.get(OPT_LEGACY_SERVE_OPENAPI).toString());
        }
        if (additionalProperties.containsKey(OPT_SERVER_BASE_PATH)) {
            serverBasePath = additionalProperties.get(OPT_SERVER_BASE_PATH).toString();
        } else if (additionalProperties.containsKey(OPT_LEGACY_SERVE_BASE_PATH)) {
            serverBasePath = additionalProperties.get(OPT_LEGACY_SERVE_BASE_PATH).toString();
        }
        if (additionalProperties.containsKey(OPT_CORS_ENABLED)) {
            corsEnabled = Boolean.parseBoolean(
                    additionalProperties.get(OPT_CORS_ENABLED).toString());
        }
        if (additionalProperties.containsKey(OPT_FT_ENABLED)) {
            ftEnabled = Boolean.parseBoolean(
                    additionalProperties.get(OPT_FT_ENABLED).toString());
        }
        if (additionalProperties.containsKey(OPT_TRACING_ENABLED)) {
            tracingEnabled = Boolean.parseBoolean(
                    additionalProperties.get(OPT_TRACING_ENABLED).toString());
        }
        if (additionalProperties.containsKey(OPT_METRICS_ENABLED)) {
            metricsEnabled = Boolean.parseBoolean(
                    additionalProperties.get(OPT_METRICS_ENABLED).toString());
        }
        if (additionalProperties.containsKey(OPT_AVOID_OPTIONAL_LIST_PARAMS)) {
            avoidOptionalListParams = Boolean.parseBoolean(
                    additionalProperties.get(OPT_AVOID_OPTIONAL_LIST_PARAMS).toString());
        }

        // Expose options to all templates via additionalProperties
        additionalProperties.put("helidonVersion", helidonVersion);
        additionalProperties.put("generateClient", generateClient);
        additionalProperties.put("generateErrorHandler", generateErrorHandler);
        additionalProperties.put("serverOpenApi", serverOpenApi);
        additionalProperties.put("serverBasePath", serverBasePath);
        additionalProperties.put("corsEnabled", corsEnabled);
        additionalProperties.put("ftEnabled", ftEnabled);
        additionalProperties.put("tracingEnabled", tracingEnabled);
        additionalProperties.put("metricsEnabled", metricsEnabled);
        additionalProperties.put("avoidOptionalListParams", avoidOptionalListParams);

        // Conditionally add per-tag template files
        if (generateClient) {
            apiTemplateFiles.put("restClient.mustache", "Client.java");
        }
        if (generateErrorHandler) {
            apiTemplateFiles.put("apiException.mustache", "Exception.java");
            apiTemplateFiles.put("errorHandler.mustache", "ErrorHandler.java");
        }

        if (serverOpenApi) {
            supportingFiles.add(new SupportingFile(
                    "openapi.yaml.mustache", "src/main/resources/META-INF", "openapi.yaml"));
        }

        // Main.java location depends on the (possibly user-supplied) invokerPackage
        String mainFolder = "src/main/java/" + invokerPackage.replace('.', '/');
        supportingFiles.add(new SupportingFile("Main.java.mustache", mainFolder, "Main.java"));
    }

    // -------------------------------------------------------------------------
    // Naming: use plain camelCase (no "Api" suffix) so classname = "Pets", not "PetsApi"
    // -------------------------------------------------------------------------

    @Override
    public String toApiName(String name) {
        if (name == null || name.isEmpty()) {
            return "Default";
        }
        return camelize(sanitizeName(name));
    }

    /**
     * Custom filename per template so the output naming matches the plan.
     * <ul>
     *   <li>api.mustache          → {Tag}Endpoint.java</li>
     *   <li>api-interface.mustache → {Tag}Api.java</li>
     *   <li>restClient.mustache   → {Tag}Client.java</li>
     *   <li>apiException.mustache → {Tag}Exception.java</li>
     *   <li>errorHandler.mustache → {Tag}ErrorHandler.java</li>
     * </ul>
     */
    @Override
    public String apiFilename(String templateName, String tag) {
        String base = camelize(sanitizeName(tag));
        String folder = apiFileFolder();
        return switch (templateName) {
            case "api.mustache"           -> folder + File.separator + base + "Endpoint.java";
            case "api-interface.mustache" -> folder + File.separator + base + "Api.java";
            case "restClient.mustache"    -> folder + File.separator + base + "Client.java";
            case "apiException.mustache"  -> folder + File.separator + base + "Exception.java";
            case "errorHandler.mustache"  -> folder + File.separator + base + "ErrorHandler.java";
            default                       -> super.apiFilename(templateName, tag);
        };
    }

    @Override
    public String apiTestFilename(String templateName, String tag) {
        String base = camelize(sanitizeName(tag));
        String folder = apiTestFileFolder();
        return switch (templateName) {
            case "api-test.mustache" -> folder + File.separator + base + "EndpointTest.java";
            default -> super.apiTestFilename(templateName, tag);
        };
    }

    // -------------------------------------------------------------------------
    // Spec pre-processing: extract server base path
    // -------------------------------------------------------------------------

    @Override
    public void preprocessOpenAPI(io.swagger.v3.oas.models.OpenAPI openAPI) {
        super.preprocessOpenAPI(openAPI);
        currentOpenAPI = openAPI;
        globalSecurityRequirements = openAPI.getSecurity() == null
                ? List.of()
                : new ArrayList<>(openAPI.getSecurity());

        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            String serverUrl = openAPI.getServers().get(0).getUrl();
            try {
                URI uri = new URI(serverUrl);
                String path = uri.getPath();
                if (path != null && !path.isEmpty() && !"/".equals(path)) {
                    // Only set serverBasePath from URL if not explicitly configured
                    if (serverBasePath.isEmpty()) {
                        additionalProperties.put("serverBasePath", path);
                    }
                }
            } catch (Exception ignored) {
                // Malformed URL — skip
            }
        }

        // Serialize the spec so the openapi.yaml.mustache template can write it to
        // src/main/resources/META-INF/openapi.yaml (picked up by helidon-openapi at runtime)
        if (serverOpenApi) {
            try {
                String yamlContent = io.swagger.v3.core.util.Yaml.pretty()
                        .writeValueAsString(openAPI);
                additionalProperties.put("x-openapi-spec-yaml", yamlContent);
            } catch (Exception ignored) {
                // Serialization failed — spec file will be skipped
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-model: strip swagger 1.x annotation imports (not on Helidon SE classpath)
    // -------------------------------------------------------------------------

    @Override
    @SuppressWarnings("rawtypes")
    public CodegenModel fromModel(String name, Schema schema) {
        CodegenModel model = super.fromModel(name, schema);
        // AbstractJavaCodegen adds "ApiModel" / "ApiModelProperty" shorthand names to
        // codegenModel.imports when annotationLibrary == SWAGGER2.  These resolve via
        // importMapping to io.swagger.annotations.* which is not on the Helidon classpath.
        model.imports.remove("ApiModel");
        model.imports.remove("ApiModelProperty");
        model.imports.remove("Schema");   // also remove OpenAPI 3 schema annotation shorthand
        model.imports.remove("JsonInclude");
        model.imports.remove("JsonProperty");
        model.imports.remove("JsonNullable");   // openApiNullable wrapper — not used in our template
        applyComposedModelMetadata(model);
        return model;
    }

    // -------------------------------------------------------------------------
    // Per-operation enrichment
    // -------------------------------------------------------------------------

    @Override
    public CodegenOperation fromOperation(String path,
                                          String httpMethod,
                                          Operation operation,
                                          List<Server> servers) {
        CodegenOperation op = super.fromOperation(path, httpMethod, operation, servers);

        // HTTP method annotation string (e.g. "@Http.GET")
        op.vendorExtensions.put("x-http-annotation", "@Http." + httpMethod.toUpperCase());
        if (op.operationId != null && !op.operationId.isEmpty()) {
            op.vendorExtensions.put("x-operation-id-capitalized",
                    Character.toUpperCase(op.operationId.charAt(0)) + op.operationId.substring(1));
        } else {
            op.vendorExtensions.put("x-operation-id-capitalized", "Operation");
        }

        // Determine the @Http.Consumes media type constant for this operation
        if (op.hasConsumes && op.consumes != null && !op.consumes.isEmpty()) {
            String mediaType = op.consumes.get(0).get("mediaType");
            if ("multipart/form-data".equals(mediaType)) {
                op.vendorExtensions.put("x-consumes-value", "MediaTypes.MULTIPART_FORM_DATA_VALUE");
                op.vendorExtensions.put("x-is-multipart", Boolean.TRUE);
            } else if ("application/x-www-form-urlencoded".equals(mediaType)) {
                op.vendorExtensions.put("x-consumes-value", "MediaTypes.APPLICATION_FORM_URLENCODED_VALUE");
                op.vendorExtensions.put("x-is-form-urlencoded", Boolean.TRUE);
            } else {
                op.vendorExtensions.put("x-consumes-value", toMediaTypeExpression(mediaType));
            }
        } else {
            op.vendorExtensions.put("x-consumes-value", "MediaTypes.APPLICATION_JSON_VALUE");
        }
        if (op.hasProduces && op.produces != null && !op.produces.isEmpty()) {
            op.vendorExtensions.put("x-produces-value",
                    toMediaTypeExpression(op.produces.get(0).get("mediaType")));
        }

        // Collapse form params into a single typed body parameter (Helidon has no @Http.FormParam)
        if (op.formParams != null && !op.formParams.isEmpty()) {
            boolean isMultipart = op.vendorExtensions.containsKey("x-is-multipart");
            String formParamNames = op.formParams.stream()
                    .map(p -> p.paramName)
                    .collect(Collectors.joining(", "));
            op.vendorExtensions.put("x-form-param-names", formParamNames);

            CodegenParameter synthetic = new CodegenParameter();
            synthetic.paramName = "formBody";
            synthetic.dataType = isMultipart ? "ReadableEntity" : "Parameters";
            synthetic.isBodyParam = true;
            synthetic.required = true;

            List<CodegenParameter> nonFormParams = new ArrayList<>(
                    op.allParams.stream().filter(p -> !p.isFormParam).collect(Collectors.toList()));
            nonFormParams.add(synthetic);
            op.allParams = nonFormParams;
            op.bodyParam = synthetic;
            op.formParams.clear();
        }

        // Mark optional query and header parameters so the templates can wrap them in Optional<>.
        // When configured, optional query list params remain plain List<T>.
        for (CodegenParameter param : op.allParams) {
            if ((param.isQueryParam || param.isHeaderParam) && !param.required) {
                boolean avoidOptionalListParam = param.isQueryParam && avoidOptionalListParams && param.isArray;
                if (!avoidOptionalListParam) {
                    param.vendorExtensions.put("x-optional", Boolean.TRUE);
                    param.vendorExtensions.put("x-bare-type", param.dataType);
                    param.dataType = "Optional<" + param.dataType + ">";
                }
            }
        }

        // Success status override (e.g. 201 for createPets)
        if (operation.getResponses() != null) {
            operation.getResponses().forEach((code, response) -> {
                if ("default".equals(code)) return;
                try {
                    int statusCode = Integer.parseInt(code);
                    if (statusCode > 200 && statusCode < 300) {
                        op.vendorExtensions.put("x-status-code", statusCode);
                    }
                    // Response headers on 2xx → @RestServer.Header (static) or @RestServer.ComputedHeader (dynamic)
                    if (statusCode >= 200 && statusCode < 300
                            && response.getHeaders() != null
                            && !response.getHeaders().isEmpty()) {
                        List<Map<String, String>> staticHeaders = new ArrayList<>();
                        List<Map<String, String>> computedHeaders = new ArrayList<>();
                        response.getHeaders().forEach((headerName, header) -> {
                            Object defaultVal = header.getSchema() != null
                                    ? header.getSchema().getDefault() : null;
                            if (defaultVal != null) {
                                Map<String, String> h = new HashMap<>();
                                h.put("name", headerName);
                                h.put("value", defaultVal.toString());
                                staticHeaders.add(h);
                            } else {
                                String fnName = headerNameToFunctionName(headerName);
                                Map<String, String> h = new HashMap<>();
                                h.put("name", headerName);
                                h.put("functionName", fnName);
                                h.put("className", Character.toUpperCase(fnName.charAt(0)) + fnName.substring(1));
                                computedHeaders.add(h);
                            }
                        });
                        if (!staticHeaders.isEmpty()) {
                            op.vendorExtensions.put("x-has-static-headers", Boolean.TRUE);
                            op.vendorExtensions.put("x-static-headers", staticHeaders);
                        }
                        if (!computedHeaders.isEmpty()) {
                            op.vendorExtensions.put("x-has-computed-headers", Boolean.TRUE);
                            op.vendorExtensions.put("x-computed-headers", computedHeaders);
                        }
                        if (!staticHeaders.isEmpty() || !computedHeaders.isEmpty()) {
                            op.vendorExtensions.put("x-has-response-headers", Boolean.TRUE);
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // Non-numeric code — skip
                }
            });
        }

        // Security roles (scopes) from operation-level security requirements
        List<SecurityRequirement> effectiveSecurity = operation.getSecurity() != null
                ? operation.getSecurity()
                : globalSecurityRequirements;
        if (effectiveSecurity != null && !effectiveSecurity.isEmpty()) {
            List<String> roles = new ArrayList<>();
            effectiveSecurity.forEach(req ->
                    req.forEach((scheme, scopes) -> roles.addAll(scopes)));
            if (!roles.isEmpty()) {
                op.vendorExtensions.put("x-security-roles", roles);
                op.vendorExtensions.put("x-has-security-roles", Boolean.TRUE);
                // Pre-format as Java annotation value: single → "role", multiple → {"r1", "r2"}
                String rolesValue = roles.size() == 1
                        ? "\"" + roles.get(0) + "\""
                        : "{" + roles.stream()
                                .map(r -> "\"" + r + "\"")
                                .collect(Collectors.joining(", ")) + "}";
                op.vendorExtensions.put("x-roles-annotation-value", rolesValue);
            }
        }

        return op;
    }

    // -------------------------------------------------------------------------
    // Per-tag post-processing: compute paths, error model, optional-param flags
    // -------------------------------------------------------------------------

    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs,
                                                         List<ModelMap> allModels) {
        OperationsMap result = super.postProcessOperationsWithModels(objs, allModels);
        OperationMap ops = result.getOperations();
        List<CodegenOperation> opList = ops.getOperation();
        if (opList == null || opList.isEmpty()) {
            return result;
        }

        // Compute the common path prefix for the @Http.Path class annotation.
        // NOTE: "basePath" is already used by additionalProperties (full server URL) and
        // would be overridden when DefaultGenerator merges additionalProperties into the
        // template context. Use a Helidon-specific key instead.
        String commonPath = computeCommonPath(opList);
        result.put("helidonBasePath", commonPath);

        // Per-operation: method-level sub-path and other enrichments
        boolean anyComputedHeaders = false;
        boolean anyOptionalQuery = false;
        boolean anySecurityRoles = false;
        boolean anyParamValidation = false;
        boolean anyFormOperations = false;
        boolean anyMultipartOperations = false;
        String errorModel = null;

        for (CodegenOperation op : opList) {
            // Sub-path (part after commonPath)
            String fullPath = op.path;
            String subPath = fullPath.startsWith(commonPath)
                    ? fullPath.substring(commonPath.length())
                    : fullPath;
            if (!subPath.isEmpty()) {
                op.vendorExtensions.put("x-method-path", subPath);
                op.vendorExtensions.put("x-has-method-path", Boolean.TRUE);
            }

            // Ensure returnType is never null (use "void" for no-body responses)
            if (op.returnType == null || op.returnType.isEmpty()) {
                op.returnType = "void";
            }
            op.vendorExtensions.put("x-return-type", op.returnType);
            op.vendorExtensions.put("x-is-void", "void".equals(op.returnType));

            // Accumulate flags
            if (op.vendorExtensions.containsKey("x-has-computed-headers")) {
                anyComputedHeaders = true;
            }
            if (op.allParams.stream().anyMatch(p -> p.vendorExtensions.containsKey("x-optional"))) {
                anyOptionalQuery = true;
            }
            // Parameter-level validation annotations
            for (CodegenParameter param : op.allParams) {
                List<Map<String, Object>> paramValidations = buildParamValidationAnnotations(param);
                if (!paramValidations.isEmpty()) {
                    param.vendorExtensions.put("x-validation-annotations", paramValidations);
                    anyParamValidation = true;
                }
            }
            if (op.vendorExtensions.containsKey("x-is-form-urlencoded")) anyFormOperations = true;
            if (op.vendorExtensions.containsKey("x-is-multipart")) anyMultipartOperations = true;

            if (op.vendorExtensions.containsKey("x-has-security-roles")) {
                anySecurityRoles = true;
                // SecurityContext needs a leading comma when other params already appear
                boolean needsLeadingComma = !op.allParams.isEmpty();
                op.vendorExtensions.put("x-needs-leading-comma-for-security", needsLeadingComma);
            }

            // Find error model from non-2xx responses
            if (errorModel == null) {
                for (CodegenResponse resp : op.responses) {
                    if ((resp.is4xx || resp.is5xx || resp.isDefault) && resp.dataType != null) {
                        errorModel = resp.dataType;
                        break;
                    }
                }
            }
        }

        result.put("hasComputedHeaders", anyComputedHeaders);
        result.put("hasOptionalQueryParams", anyOptionalQuery);
        result.put("hasSecurityRoles", anySecurityRoles);
        result.put("hasParamValidation", anyParamValidation);
        result.put("hasFormOperations", anyFormOperations);
        result.put("hasMultipartOperations", anyMultipartOperations);
        if (anyParamValidation) {
            // Needed by pom.xml.mustache when only parameters (not models) use @Validation.*
            additionalProperties.put("hasValidation", Boolean.TRUE);
        }

        // Also expose classname in operations context for templates that need it
        String tagBaseName = opList.get(0).baseName != null && !opList.get(0).baseName.isBlank()
                ? opList.get(0).baseName
                : (result.getOperations().get("baseName") != null
                        ? result.getOperations().get("baseName").toString()
                        : "Default");
        String classname = toApiName(tagBaseName);
        result.put("classname", classname);
        result.put("classnameLowercase", classname.toLowerCase());

        // Collect unique computed header function stubs (deduped by functionName).
        // We derive this from x-computed-headers directly to avoid relying on boolean flags.
        Map<String, Map<String, String>> byFnName = new LinkedHashMap<>();
        for (CodegenOperation op : opList) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> hdrs =
                    (List<Map<String, String>>) op.vendorExtensions.get("x-computed-headers");
            if (hdrs != null) {
                for (Map<String, String> h : hdrs) {
                    byFnName.putIfAbsent(h.get("functionName"), h);
                }
            }
        }
        if (!byFnName.isEmpty()) {
            anyComputedHeaders = true;
            List<Map<String, String>> headerFunctions = new ArrayList<>(byFnName.values());
            result.put("allComputedHeaders", headerFunctions);
            // Generate one file per computed header function implementation.
            generateComputedHeaderFunctionFiles(classname, headerFunctions);
        }
        result.put("errorModel", errorModel != null ? errorModel : "Object");
        if (anySecurityRoles) {
            // Visible to supporting-file templates (pom.xml, application.yaml) which only
            // see additionalProperties, not the per-tag OperationsMap.
            additionalProperties.put("hasSecurity", Boolean.TRUE);
        }

        return result;
    }

    private void generateComputedHeaderFunctionFiles(String apiClassName, List<Map<String, String>> headerFunctions) {
        String apiFolder = outputFolder + File.separator + sourceFolder + File.separator + apiPackage.replace('.', '/');
        for (Map<String, String> header : headerFunctions) {
            String classSuffix = header.get("className");
            String functionName = header.get("functionName");
            String headerName = header.get("name");
            if (classSuffix == null || functionName == null || headerName == null) {
                continue;
            }

            String className = apiClassName + classSuffix;
            Path file = Path.of(apiFolder, className + ".java");
            String content = String.format(
                    "package %s;%n%n"
                            + "import java.util.Optional;%n%n"
                            + "import io.helidon.http.Header;%n"
                            + "import io.helidon.http.HeaderName;%n"
                            + "import io.helidon.http.Http;%n"
                            + "import io.helidon.service.registry.Service;%n%n"
                            + "/**%n"
                            + " * Computes the {@code %s} response header.%n"
                            + " */%n"
                            + "@Service.Singleton%n"
                            + "@Service.Named(\"%s\")%n"
                            + "public class %s implements Http.HeaderFunction {%n%n"
                            + "    @Override%n"
                            + "    public Optional<Header> apply(HeaderName headerName) {%n"
                            + "        // TODO: compute the %s response header value, or return Optional.empty() to omit it%n"
                            + "        return Optional.empty();%n"
                            + "    }%n"
                            + "}%n",
                    apiPackage, headerName, functionName, className, headerName);

            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate computed header function class: " + file, e);
            }
        }
    }

    /**
     * Find the longest common path prefix shared by all operations in a tag group.
     * Stops before any path-parameter segment ({...}).
     */
    private String computeCommonPath(List<CodegenOperation> ops) {
        if (ops.isEmpty()) return "/";

        String first = ops.get(0).path;
        String[] firstParts = first.split("/", -1);

        // Find how many leading segments all operations share
        int commonSegments = firstParts.length;
        for (CodegenOperation op : ops) {
            String[] parts = op.path.split("/", -1);
            int match = 0;
            for (int i = 0; i < Math.min(commonSegments, parts.length); i++) {
                if (firstParts[i].equals(parts[i])) {
                    match = i + 1;
                } else {
                    break;
                }
            }
            commonSegments = Math.min(commonSegments, match);
        }

        // Build the common path, stopping before any path-parameter segments
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commonSegments; i++) {
            if (firstParts[i].isEmpty()) continue;      // skip the leading ""
            if (firstParts[i].startsWith("{")) break;   // stop at path params
            sb.append("/").append(firstParts[i]);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    // -------------------------------------------------------------------------
    // Per-model post-processing: mark required properties and validation constraints
    // -------------------------------------------------------------------------

    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> objs) {
        Map<String, ModelsMap> result = super.postProcessAllModels(objs);
        boolean anyValidation = false;
        for (Map.Entry<String, ModelsMap> entry : result.entrySet()) {
            ModelsMap modelsMap = entry.getValue();
            for (ModelMap modelContainer : modelsMap.getModels()) {
                var model = modelContainer.getModel();
                if (Boolean.TRUE.equals(model.vendorExtensions.get("x-is-composed-wrapper"))) {
                    continue;
                }
                boolean modelHasValidation = false;

                for (CodegenProperty prop : model.vars) {
                    // Mark required properties for @Json.Required
                    if (prop.required) {
                        prop.vendorExtensions.put("x-json-required", Boolean.TRUE);
                    }

                    // Build @Validation.* annotations from OpenAPI constraints
                    List<Map<String, Object>> validationAnnotations = buildValidationAnnotations(prop);
                    if (!validationAnnotations.isEmpty()) {
                        prop.vendorExtensions.put("x-validation-annotations", validationAnnotations);
                        modelHasValidation = true;
                    }

                    // Format default value as a Java literal for field initializer
                    String javaDefault = formatDefaultValue(prop);
                    if (javaDefault != null) {
                        prop.vendorExtensions.put("x-default-value", javaDefault);
                    }
                }

                if (modelHasValidation) {
                    model.vendorExtensions.put("x-has-validations", Boolean.TRUE);
                    // The modelsMap-level "imports" list is already resolved to FQNs before
                    // postProcessAllModels() runs — add directly to it so the template picks it up.
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> importsList =
                            (List<Map<String, String>>) modelsMap.get("imports");
                    if (importsList != null) {
                        importsList.add(new HashMap<>(Map.of("import", "io.helidon.validation.Validation")));
                    }
                    anyValidation = true;
                }
            }
        }
        if (anyValidation) {
            additionalProperties.put("hasValidation", Boolean.TRUE);
        }
        return result;
    }

    /**
     * Converts a response header name to a camelCase {@code @RestServer.ComputedHeader} function name.
     * e.g. {@code "x-next"} → {@code "xNextHeaderFn"}, {@code "Cache-Control"} → {@code "cacheControlHeaderFn"}.
     */
    private String headerNameToFunctionName(String headerName) {
        String[] parts = headerName.split("-");
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                if (parts[i].length() > 1) sb.append(parts[i].substring(1).toLowerCase());
            }
        }
        sb.append("HeaderFn");
        return sb.toString();
    }

    /**
     * Converts an OpenAPI media type string into a Java expression suitable for {@code @Http.Consumes}
     * or {@code @Http.Produces}. Known Helidon constants are used when available; otherwise a string
     * literal is emitted.
     */
    private String toMediaTypeExpression(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return "MediaTypes.APPLICATION_JSON_VALUE";
        }
        return switch (mediaType) {
            case "application/json" -> "MediaTypes.APPLICATION_JSON_VALUE";
            case "application/x-www-form-urlencoded" -> "MediaTypes.APPLICATION_FORM_URLENCODED_VALUE";
            case "multipart/form-data" -> "MediaTypes.MULTIPART_FORM_DATA_VALUE";
            case "text/plain" -> "MediaTypes.TEXT_PLAIN_VALUE";
            case "application/octet-stream" -> "MediaTypes.APPLICATION_OCTET_STREAM_VALUE";
            case "text/event-stream" -> "MediaTypes.TEXT_EVENT_STREAM_VALUE";
            default -> "\"" + mediaType.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        };
    }

    /**
     * Maps OpenAPI schema constraints on a property to Helidon {@code @Validation.*} annotations.
     */
    private List<Map<String, Object>> buildValidationAnnotations(CodegenProperty prop) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (prop.isString) {
            // minLength / maxLength → @Validation.String.Length
            if (prop.minLength != null || prop.maxLength != null) {
                List<String> attrs = new ArrayList<>();
                if (prop.minLength != null) attrs.add("min = " + prop.minLength);
                if (prop.maxLength != null) attrs.add("value = " + prop.maxLength);
                result.add(Map.of("annotation",
                        "@Validation.String.Length(" + String.join(", ", attrs) + ")"));
            }
            // pattern → @Validation.String.Pattern
            if (prop.pattern != null && !prop.pattern.isEmpty()) {
                String escaped = prop.pattern.replace("\\", "\\\\").replace("\"", "\\\"");
                result.add(Map.of("annotation", "@Validation.String.Pattern(\"" + escaped + "\")"));
            }
        } else if (prop.isInteger) {
            addIntegerBounds(result, prop.minimum, prop.exclusiveMinimum, prop.maximum, prop.exclusiveMaximum);
            Integer multipleOf = toIntMultipleOfValue(prop.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Integer.MultipleOf(" + multipleOf + ")"));
            }
        } else if (prop.isLong) {
            addLongBounds(result, prop.minimum, prop.exclusiveMinimum, prop.maximum, prop.exclusiveMaximum);
            Long multipleOf = toLongMultipleOfValue(prop.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Long.MultipleOf(" + multipleOf + "L)"));
            }
        } else if (prop.isNumber || prop.isFloat || prop.isDouble) {
            // minimum / maximum → @Validation.Number.Min / Max (string-valued)
            if (prop.minimum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Min(\"" + prop.minimum + "\")"));
            }
            if (prop.maximum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Max(\"" + prop.maximum + "\")"));
            }
            String multipleOf = toNumberMultipleOfValue(prop.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Number.MultipleOf(\"" + multipleOf + "\")"));
            }
        }

        // minItems / maxItems → @Validation.Collection.Size (independent of element type)
        if (prop.isArray && (prop.minItems != null || prop.maxItems != null)) {
            List<String> attrs = new ArrayList<>();
            if (prop.minItems != null) attrs.add("min = " + prop.minItems);
            if (prop.maxItems != null) attrs.add("value = " + prop.maxItems);
            result.add(Map.of("annotation",
                    "@Validation.Collection.Size(" + String.join(", ", attrs) + ")"));
        }

        return result;
    }

    /**
     * Formats a property's default value as a Java literal for use in a field initializer.
     * Returns {@code null} when no useful initializer can be produced (e.g. arrays).
     */
    private String formatDefaultValue(CodegenProperty prop) {
        if (prop.defaultValue == null || prop.defaultValue.isEmpty()) {
            return null;
        }
        String val = prop.defaultValue;
        if (prop.isEnum) {
            // Upstream AbstractJavaCodegen already formats the default as "TypeName.CONSTANT"
            return val;
        }
        if (prop.isString) {
            // Escape backslashes and double-quotes, then wrap in quotes
            return "\"" + val.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (prop.isLong) {
            return val + "L";
        }
        if (prop.isFloat) {
            return val + "f";
        }
        if (prop.isArray || prop.isMap) {
            return null;  // skip — complex initialization
        }
        return val;  // integer, double, boolean — value as-is
    }

    /**
     * Maps OpenAPI schema constraints on a parameter to Helidon {@code @Validation.*} annotations.
     * Mirrors {@link #buildValidationAnnotations(CodegenProperty)} but operates on {@link CodegenParameter}.
     */
    private List<Map<String, Object>> buildParamValidationAnnotations(CodegenParameter param) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (param.isString) {
            if (param.minLength != null || param.maxLength != null) {
                List<String> attrs = new ArrayList<>();
                if (param.minLength != null) attrs.add("min = " + param.minLength);
                if (param.maxLength != null) attrs.add("value = " + param.maxLength);
                result.add(Map.of("annotation",
                        "@Validation.String.Length(" + String.join(", ", attrs) + ")"));
            }
            if (param.pattern != null && !param.pattern.isEmpty()) {
                String escaped = param.pattern.replace("\\", "\\\\").replace("\"", "\\\"");
                result.add(Map.of("annotation", "@Validation.String.Pattern(\"" + escaped + "\")"));
            }
        } else if (param.isInteger) {
            addIntegerBounds(result, param.minimum, param.exclusiveMinimum, param.maximum, param.exclusiveMaximum);
            Integer multipleOf = toIntMultipleOfValue(param.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Integer.MultipleOf(" + multipleOf + ")"));
            }
        } else if (param.isLong) {
            addLongBounds(result, param.minimum, param.exclusiveMinimum, param.maximum, param.exclusiveMaximum);
            Long multipleOf = toLongMultipleOfValue(param.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Long.MultipleOf(" + multipleOf + "L)"));
            }
        } else if (param.isNumber || param.isFloat || param.isDouble) {
            if (param.minimum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Min(\"" + param.minimum + "\")"));
            }
            if (param.maximum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Max(\"" + param.maximum + "\")"));
            }
            String multipleOf = toNumberMultipleOfValue(param.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Number.MultipleOf(\"" + multipleOf + "\")"));
            }
        }

        if (param.isArray && (param.minItems != null || param.maxItems != null)) {
            List<String> attrs = new ArrayList<>();
            if (param.minItems != null) attrs.add("min = " + param.minItems);
            if (param.maxItems != null) attrs.add("value = " + param.maxItems);
            result.add(Map.of("annotation",
                    "@Validation.Collection.Size(" + String.join(", ", attrs) + ")"));
        }

        return result;
    }

    private void addIntegerBounds(List<Map<String, Object>> result,
                                  String minimum,
                                  boolean exclusiveMinimum,
                                  String maximum,
                                  boolean exclusiveMaximum) {
        Integer minBound = toIntMinimumBound(minimum, exclusiveMinimum);
        if (minBound != null) {
            result.add(Map.of("annotation", "@Validation.Integer.Min(" + minBound + ")"));
        }

        Integer maxBound = toIntMaximumBound(maximum, exclusiveMaximum);
        if (maxBound != null) {
            result.add(Map.of("annotation", "@Validation.Integer.Max(" + maxBound + ")"));
        }
    }

    private void addLongBounds(List<Map<String, Object>> result,
                               String minimum,
                               boolean exclusiveMinimum,
                               String maximum,
                               boolean exclusiveMaximum) {
        Long minBound = toLongMinimumBound(minimum, exclusiveMinimum);
        if (minBound != null) {
            result.add(Map.of("annotation", "@Validation.Long.Min(" + minBound + "L)"));
        }

        Long maxBound = toLongMaximumBound(maximum, exclusiveMaximum);
        if (maxBound != null) {
            result.add(Map.of("annotation", "@Validation.Long.Max(" + maxBound + "L)"));
        }
    }

    private Integer toIntMinimumBound(String value, boolean exclusive) {
        Long bound = parseIntegralMinimumBound(value, exclusive);
        if (bound == null || bound < Integer.MIN_VALUE || bound > Integer.MAX_VALUE) {
            return null;
        }
        return bound.intValue();
    }

    private Integer toIntMaximumBound(String value, boolean exclusive) {
        Long bound = parseIntegralMaximumBound(value, exclusive);
        if (bound == null || bound < Integer.MIN_VALUE || bound > Integer.MAX_VALUE) {
            return null;
        }
        return bound.intValue();
    }

    private Long toLongMinimumBound(String value, boolean exclusive) {
        return parseIntegralMinimumBound(value, exclusive);
    }

    private Long toLongMaximumBound(String value, boolean exclusive) {
        return parseIntegralMaximumBound(value, exclusive);
    }

    private Integer toIntMultipleOfValue(Number value) {
        Long multipleOf = parseIntegralMultipleOf(value);
        if (multipleOf == null || multipleOf < Integer.MIN_VALUE || multipleOf > Integer.MAX_VALUE) {
            return null;
        }
        return multipleOf.intValue();
    }

    private Long toLongMultipleOfValue(Number value) {
        return parseIntegralMultipleOf(value);
    }

    private String toNumberMultipleOfValue(Number value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long parseIntegralMinimumBound(String value, boolean exclusive) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal numeric = new BigDecimal(value);
            BigDecimal rounded = exclusive
                    ? numeric.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE)
                    : numeric.setScale(0, RoundingMode.CEILING);
            return rounded.longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private Long parseIntegralMaximumBound(String value, boolean exclusive) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal numeric = new BigDecimal(value);
            BigDecimal rounded = exclusive
                    ? numeric.setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE)
                    : numeric.setScale(0, RoundingMode.FLOOR);
            return rounded.longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private Long parseIntegralMultipleOf(Number value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private void applyComposedModelMetadata(CodegenModel model) {
        CodegenComposedSchemas composedSchemas = model.getComposedSchemas();
        if (composedSchemas == null) {
            return;
        }

        String composedKind;
        List<CodegenProperty> variants;
        if (composedSchemas.getOneOf() != null && !composedSchemas.getOneOf().isEmpty()) {
            composedKind = "oneOf";
            variants = composedSchemas.getOneOf();
        } else if (composedSchemas.getAnyOf() != null && !composedSchemas.getAnyOf().isEmpty()) {
            composedKind = "anyOf";
            variants = composedSchemas.getAnyOf();
        } else {
            return;
        }

        List<Map<String, Object>> composedVariants = new ArrayList<>();
        Set<String> usedSuffixes = new LinkedHashSet<>();
        for (int i = 0; i < variants.size(); i++) {
            CodegenProperty variant = variants.get(i);
            composedVariants.add(buildComposedVariantMetadata(model, variant, i, usedSuffixes));
        }
        markLast(composedVariants);

        model.vendorExtensions.put("x-is-composed-wrapper", Boolean.TRUE);
        model.vendorExtensions.put("x-composed-kind", composedKind);
        model.vendorExtensions.put("x-is-oneof-wrapper", "oneOf".equals(composedKind));
        model.vendorExtensions.put("x-is-anyof-wrapper", "anyOf".equals(composedKind));
        model.vendorExtensions.put("x-composed-variants", composedVariants);
    }

    private Map<String, Object> buildComposedVariantMetadata(CodegenModel model,
                                                             CodegenProperty variant,
                                                             int index,
                                                             Set<String> usedSuffixes) {
        Map<String, Object> result = new HashMap<>();
        String rawTypeName = variant.complexType != null && !variant.complexType.isBlank()
                ? variant.complexType
                : variant.dataType;
        String methodSuffix = uniqueVariantMethodSuffix(rawTypeName, index, usedSuffixes);
        String schemaName = schemaNameForVariant(variant);
        String typeConstantName = "TYPE_" + methodSuffix.toUpperCase();
        SchemaMatchInfo matchInfo = schemaMatchInfo(variant, schemaName);

        result.put("dataType", variant.dataType);
        result.put("methodSuffix", methodSuffix);
        result.put("typeConstantName", typeConstantName);
        result.put("matcherMethodName", "matches" + methodSuffix);
        result.put("factoryMethodName", "of" + methodSuffix);
        result.put("predicateMethodName", "is" + methodSuffix);
        result.put("accessorMethodName", "as" + methodSuffix);
        result.put("displayName", rawTypeName);
        result.put("jsonType", matchInfo.jsonType());
        result.put("integralNumber", matchInfo.integralNumber());
        result.put("nullable", matchInfo.nullable());
        result.put("usesComposedMatcher", matchInfo.usesComposedMatcher());
        result.put("isObjectVariant", "OBJECT".equals(matchInfo.jsonType()) && !matchInfo.usesComposedMatcher());
        result.put("explicitAdditionalPropertiesFalse", matchInfo.explicitAdditionalPropertiesFalse());
        String discriminatorProperty = discriminatorProperty(model);
        List<String> discriminatorAliases = discriminatorAliases(model, schemaName);
        result.put("discriminatorProperty", discriminatorProperty);
        result.put("hasDiscriminatorProperty", discriminatorProperty != null);
        result.put("discriminatorAliases", toTemplateStrings(discriminatorAliases));
        result.put("hasDiscriminatorAliases", !discriminatorAliases.isEmpty());
        result.put("requiredProperties", toTemplateStrings(matchInfo.requiredProperties()));
        result.put("hasRequiredProperties", !matchInfo.requiredProperties().isEmpty());
        result.put("knownProperties", toTemplateStrings(matchInfo.knownProperties()));
        result.put("hasKnownProperties", !matchInfo.knownProperties().isEmpty());
        return result;
    }

    private List<Map<String, String>> toTemplateStrings(List<String> values) {
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, String> entry = new HashMap<>();
            entry.put("value", values.get(i));
            entry.put("-last", String.valueOf(i == values.size() - 1));
            result.add(entry);
        }
        return result;
    }

    private void markLast(List<Map<String, Object>> entries) {
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).put("-last", i == entries.size() - 1);
        }
    }

    private String uniqueVariantMethodSuffix(String rawTypeName, int index, Set<String> usedSuffixes) {
        String candidate = camelize(sanitizeName(rawTypeName == null ? "" : rawTypeName));
        if (candidate == null || candidate.isBlank()) {
            candidate = "Variant" + (index + 1);
        }

        String unique = candidate;
        int suffix = 2;
        while (!usedSuffixes.add(unique)) {
            unique = candidate + suffix;
            suffix++;
        }
        return unique;
    }

    private String schemaNameForVariant(CodegenProperty variant) {
        if (variant.getRef() != null && !variant.getRef().isBlank()) {
            return variant.getRef().substring(variant.getRef().lastIndexOf('/') + 1);
        }
        if (variant.complexType != null && !variant.complexType.isBlank()
                && currentOpenAPI != null
                && currentOpenAPI.getComponents() != null
                && currentOpenAPI.getComponents().getSchemas() != null
                && currentOpenAPI.getComponents().getSchemas().containsKey(variant.complexType)) {
            return variant.complexType;
        }
        return null;
    }

    private SchemaMatchInfo schemaMatchInfo(CodegenProperty variant, String schemaName) {
        if (schemaName != null) {
            Schema schema = resolveSchemaByName(schemaName);
            if (schema != null) {
                return schemaMatchInfo(schemaName, schema, new LinkedHashSet<>());
            }
        }

        if (variant.isArray) {
            return new SchemaMatchInfo("ARRAY", false, false, false,
                    null, List.of(), List.of());
        }
        if (variant.isBoolean) {
            return new SchemaMatchInfo("BOOLEAN", false, false, false,
                    null, List.of(), List.of());
        }
        if (variant.isInteger || variant.isLong || variant.isShort || variant.isUnboundedInteger) {
            return new SchemaMatchInfo("NUMBER", true, false, false,
                    null, List.of(), List.of());
        }
        if (variant.isNumber || variant.isFloat || variant.isDouble || variant.isDecimal) {
            return new SchemaMatchInfo("NUMBER", false, false, false,
                    null, List.of(), List.of());
        }
        if (variant.isString || variant.isDate || variant.isDateTime || variant.isUuid || variant.isUri) {
            return new SchemaMatchInfo("STRING", false, false, false,
                    null, List.of(), List.of());
        }
        if (variant.isMap || variant.isFreeFormObject) {
            return new SchemaMatchInfo("OBJECT", false, false, false,
                    null, List.of(), List.of());
        }
        return new SchemaMatchInfo("UNKNOWN", false, false, false,
                null, List.of(), List.of());
    }

    private SchemaMatchInfo schemaMatchInfo(String schemaName, Schema schema, Set<String> seenSchemas) {
        Schema resolvedSchema = dereferenceSchema(schema, seenSchemas);
        if (resolvedSchema == null) {
            return new SchemaMatchInfo("UNKNOWN", false, false, false,
                    null, List.of(), List.of());
        }

        boolean nullable = Boolean.TRUE.equals(resolvedSchema.getNullable());
        if (isUnionSchema(resolvedSchema)) {
            return new SchemaMatchInfo("UNKNOWN", false, nullable, true,
                    null, List.of(), List.of());
        }

        if (isObjectSchema(resolvedSchema)) {
            ObjectShape objectShape = objectShape(resolvedSchema, new LinkedHashSet<>(seenSchemas));
            return new SchemaMatchInfo("OBJECT",
                    false,
                    nullable,
                    false,
                    null,
                    List.of(),
                    new ArrayList<>(objectShape.requiredProperties()),
                    new ArrayList<>(objectShape.knownProperties()),
                    objectShape.explicitAdditionalPropertiesFalse());
        }

        String type = resolvedSchema.getType();
        if ("array".equals(type)) {
            return new SchemaMatchInfo("ARRAY", false, nullable, false,
                    null, List.of(), List.of());
        }
        if ("boolean".equals(type)) {
            return new SchemaMatchInfo("BOOLEAN", false, nullable, false,
                    null, List.of(), List.of());
        }
        if ("integer".equals(type)) {
            return new SchemaMatchInfo("NUMBER", true, nullable, false,
                    null, List.of(), List.of());
        }
        if ("number".equals(type)) {
            return new SchemaMatchInfo("NUMBER", false, nullable, false,
                    null, List.of(), List.of());
        }
        if ("string".equals(type)) {
            return new SchemaMatchInfo("STRING", false, nullable, false,
                    null, List.of(), List.of());
        }

        return new SchemaMatchInfo("UNKNOWN", false, nullable, false,
                null, List.of(), List.of());
    }

    private Schema dereferenceSchema(Schema schema, Set<String> seenSchemas) {
        if (schema == null || currentOpenAPI == null || currentOpenAPI.getComponents() == null
                || currentOpenAPI.getComponents().getSchemas() == null) {
            return schema;
        }

        if (schema.get$ref() == null || schema.get$ref().isBlank()) {
            return schema;
        }

        String schemaName = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
        if (!seenSchemas.add(schemaName)) {
            return schema;
        }

        return currentOpenAPI.getComponents().getSchemas().getOrDefault(schemaName, schema);
    }

    private Schema resolveSchemaByName(String schemaName) {
        if (currentOpenAPI == null || currentOpenAPI.getComponents() == null
                || currentOpenAPI.getComponents().getSchemas() == null) {
            return null;
        }
        return currentOpenAPI.getComponents().getSchemas().get(schemaName);
    }

    private boolean isUnionSchema(Schema schema) {
        if (!(schema instanceof ComposedSchema composedSchema)) {
            return false;
        }
        return (composedSchema.getOneOf() != null && !composedSchema.getOneOf().isEmpty())
                || (composedSchema.getAnyOf() != null && !composedSchema.getAnyOf().isEmpty());
    }

    private boolean isObjectSchema(Schema schema) {
        if (schema == null) {
            return false;
        }
        if ("object".equals(schema.getType())) {
            return true;
        }
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            return true;
        }
        if (schema instanceof ComposedSchema composedSchema
                && composedSchema.getAllOf() != null
                && !composedSchema.getAllOf().isEmpty()) {
            return true;
        }
        return schema.getAdditionalProperties() != null;
    }

    private ObjectShape objectShape(Schema schema, Set<String> seenSchemas) {
        Schema resolvedSchema = dereferenceSchema(schema, seenSchemas);
        if (resolvedSchema == null) {
            return new ObjectShape(Set.of(), Set.of(), false);
        }

        Set<String> requiredProperties = new LinkedHashSet<>();
        Set<String> knownProperties = new LinkedHashSet<>();
        boolean explicitAdditionalPropertiesFalse = Boolean.FALSE.equals(resolvedSchema.getAdditionalProperties());

        if (resolvedSchema.getRequired() != null) {
            requiredProperties.addAll(resolvedSchema.getRequired());
        }
        if (resolvedSchema.getProperties() != null) {
            knownProperties.addAll(resolvedSchema.getProperties().keySet());
        }

        if (resolvedSchema instanceof ComposedSchema composedSchema
                && composedSchema.getAllOf() != null) {
            for (Schema nestedSchema : composedSchema.getAllOf()) {
                ObjectShape nestedShape = objectShape(nestedSchema, new LinkedHashSet<>(seenSchemas));
                requiredProperties.addAll(nestedShape.requiredProperties());
                knownProperties.addAll(nestedShape.knownProperties());
                explicitAdditionalPropertiesFalse =
                        explicitAdditionalPropertiesFalse || nestedShape.explicitAdditionalPropertiesFalse();
            }
        }

        return new ObjectShape(requiredProperties, knownProperties, explicitAdditionalPropertiesFalse);
    }

    private String discriminatorProperty(CodegenModel model) {
        if (model.discriminator == null) {
            return null;
        }
        if (model.discriminator.getPropertyBaseName() != null && !model.discriminator.getPropertyBaseName().isBlank()) {
            return model.discriminator.getPropertyBaseName();
        }
        return model.discriminator.getPropertyName();
    }

    private List<String> discriminatorAliases(CodegenModel model, String schemaName) {
        if (model.discriminator == null || schemaName == null) {
            return List.of();
        }

        Set<String> aliases = new LinkedHashSet<>();
        Map<String, String> mapping = model.discriminator.getMapping();
        if (mapping != null) {
            String schemaRef = "#/components/schemas/" + schemaName;
            mapping.forEach((alias, target) -> {
                if (schemaName.equals(target) || schemaRef.equals(target)) {
                    aliases.add(alias);
                }
            });
        }
        if (aliases.isEmpty()) {
            aliases.add(schemaName);
        }
        return new ArrayList<>(aliases);
    }

    private record SchemaMatchInfo(String jsonType,
                                   boolean integralNumber,
                                   boolean nullable,
                                   boolean usesComposedMatcher,
                                   String discriminatorProperty,
                                   List<String> discriminatorAliases,
                                   List<String> requiredProperties,
                                   List<String> knownProperties,
                                   boolean explicitAdditionalPropertiesFalse) {
        private SchemaMatchInfo(String jsonType,
                                boolean integralNumber,
                                boolean nullable,
                                boolean usesComposedMatcher,
                                String discriminatorProperty,
                                List<String> requiredProperties,
                                List<String> knownProperties) {
            this(jsonType, integralNumber, nullable, usesComposedMatcher,
                    discriminatorProperty, List.of(), requiredProperties, knownProperties, false);
        }
    }

    private record ObjectShape(Set<String> requiredProperties,
                               Set<String> knownProperties,
                               boolean explicitAdditionalPropertiesFalse) {
    }
}
