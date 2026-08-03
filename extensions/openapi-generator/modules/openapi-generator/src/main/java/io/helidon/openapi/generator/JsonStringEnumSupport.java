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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.OperationsMap;

final class JsonStringEnumSupport {
    private final BiFunction<String, String, String> enumVarNamer;
    private Set<String> httpParameterEnumSchemas = Set.of();
    private Map<String, List<String>> inlineRequestEntityEnumValues = Map.of();
    private Set<String> inlineRequestEntityEnumCollections = Set.of();

    JsonStringEnumSupport(BiFunction<String, String, String> enumVarNamer) {
        this.enumVarNamer = enumVarNamer;
    }

    void preprocess(OpenAPI openAPI, String inputSpec) {
        captureInlineRequestEntityEnums(openAPI, inputSpec);
        httpParameterEnumSchemas = findHttpParameterEnumSchemas(openAPI);
    }

    void markHttpParameterEnum(String schemaName, CodegenModel model) {
        if (httpParameterEnumSchemas.contains(schemaName)) {
            model.vendorExtensions.put("x-http-parameter-enum", Boolean.TRUE);
        }
    }

    void restoreInlineRequestEntityEnum(String operationId, CodegenOperation codegenOperation) {
        if (operationId == null) {
            return;
        }
        List<String> enumValues = inlineRequestEntityEnumValues.getOrDefault(operationId, List.of());
        if (enumValues.isEmpty()) {
            return;
        }
        restoreInlineRequestEntityEnum(codegenOperation,
                                       enumValues,
                                       inlineRequestEntityEnumCollections.contains(operationId));
    }

    void prepareInlineRequestEntityEnum(CodegenOperation operation, CodegenParameter parameter) {
        if (!parameter.isBodyParam) {
            return;
        }
        List<String> values = inlineRequestEntityEnumValues.get(operation.operationId);
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<String, Object> allowableValues = stringAllowableValues(values);
        if (inlineRequestEntityEnumCollections.contains(operation.operationId) && parameter.items != null) {
            parameter.items.isEnum = true;
            parameter.items.isString = true;
            parameter.items.allowableValues = allowableValues;
        } else {
            parameter.isEnum = true;
            parameter.isString = true;
            parameter.allowableValues = allowableValues;
        }
    }

    void promoteInlineOperationEnum(Map<String, Map<String, Object>> operationEnums,
                                    String apiClassname,
                                    CodegenOperation operation,
                                    CodegenParameter parameter,
                                    UnaryOperator<String> javaName) {
        CodegenProperty item = parameter.isArray ? parameter.items : null;
        boolean itemEnum = item != null && item.isEnum && !item.isEnumRef && item.isString;
        boolean directEnum = !parameter.isArray && parameter.isEnum && !parameter.isEnumRef && parameter.isString;
        Map<String, Object> allowableValues = itemEnum ? item.allowableValues : parameter.allowableValues;
        if ((!itemEnum && !directEnum) || enumValues(allowableValues).isEmpty()) {
            return;
        }

        String enumName = javaName.apply(operation.operationId)
                + javaName.apply(parameter.paramName)
                + "Enum";
        String bareType = parameter.vendorExtensions.containsKey("x-bare-type")
                ? parameter.vendorExtensions.get("x-bare-type").toString()
                : parameter.dataType;
        String promotedBareType;
        if (parameter.isArray) {
            String itemType = item.dataType == null ? "String" : item.dataType;
            promotedBareType = bareType.replace(itemType, enumName);
        } else {
            promotedBareType = enumName;
        }
        if (parameter.vendorExtensions.containsKey("x-optional")) {
            parameter.vendorExtensions.put("x-bare-type", promotedBareType);
            parameter.dataType = "Optional<" + promotedBareType + ">";
        } else {
            parameter.dataType = promotedBareType;
        }
        parameter.datatypeWithEnum = parameter.dataType;

        boolean httpMapper = parameter.isPathParam || parameter.isQueryParam
                || parameter.isHeaderParam || parameter.isCookieParam;
        Map<String, Object> definition = enumDefinition(enumName,
                                                        apiClassname + "Api." + enumName,
                                                        apiClassname + "Api" + enumName + "JsonConverter",
                                                        allowableValues,
                                                        httpMapper);
        operationEnums.putIfAbsent(enumName, definition);
    }

    void applyOperationEnums(OperationsMap result, Map<String, Map<String, Object>> operationEnums) {
        if (operationEnums.isEmpty()) {
            return;
        }
        result.put("x-operation-string-enums", new ArrayList<>(operationEnums.values()));
        result.put("x-has-operation-string-enums", Boolean.TRUE);
        if (operationEnums.values().stream().anyMatch(definition -> definition.containsKey("httpMapper"))) {
            result.put("x-has-http-enum-mappers", Boolean.TRUE);
        }
    }

    boolean prepareTopLevelModel(CodegenModel model) {
        if (!model.isEnum || !model.isString) {
            return false;
        }
        Map<String, Object> definition = enumDefinition(model.classname,
                                                        model.classname,
                                                        model.classname + "JsonConverter",
                                                        model.allowableValues,
                                                        Boolean.TRUE.equals(model.vendorExtensions
                                                                                    .get("x-http-parameter-enum")));
        if (definition != null) {
            model.vendorExtensions.put("x-top-level-string-enum", definition);
            model.vendorExtensions.put("x-has-string-enums", Boolean.TRUE);
            if (definition.containsKey("httpMapper")) {
                model.vendorExtensions.put("x-has-http-enum-mappers", Boolean.TRUE);
            }
        }
        return true;
    }

    List<CodegenProperty> prepareInlineModelEnums(CodegenModel model) {
        List<CodegenProperty> properties = renderVars(model);
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (CodegenProperty property : properties) {
            CodegenProperty enumProperty = inlineStringEnumProperty(property);
            if (enumProperty == null || enumProperty.datatypeWithEnum == null
                    || enumProperty.datatypeWithEnum.isBlank()) {
                continue;
            }
            String enumName = enumProperty.datatypeWithEnum;
            property.vendorExtensions.put("x-enum-name", enumName);
            Map<String, Object> definition = enumDefinition(enumName,
                                                            model.classname + "." + enumName,
                                                            model.classname + enumName + "JsonConverter",
                                                            enumProperty.allowableValues,
                                                            false);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        if (!definitions.isEmpty()) {
            model.vendorExtensions.put("x-inline-string-enums", definitions);
            model.vendorExtensions.put("x-has-string-enums", Boolean.TRUE);
        }
        return properties;
    }

    private Set<String> findHttpParameterEnumSchemas(OpenAPI openAPI) {
        if (openAPI.getPaths() == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        openAPI.getPaths().values().forEach(pathItem -> {
            collectHttpParameterEnumSchemas(openAPI, pathItem.getParameters(), result);
            pathItem.readOperations().forEach(operation ->
                    collectHttpParameterEnumSchemas(openAPI, operation.getParameters(), result));
        });
        return Set.copyOf(result);
    }

    private void collectHttpParameterEnumSchemas(OpenAPI openAPI,
                                                 List<Parameter> parameters,
                                                 Set<String> result) {
        if (parameters == null) {
            return;
        }
        for (Parameter parameter : parameters) {
            Parameter resolved = parameter;
            if (parameter.get$ref() != null && parameter.get$ref().startsWith("#/components/parameters/")
                    && openAPI.getComponents() != null && openAPI.getComponents().getParameters() != null) {
                String parameterName = refName(parameter.get$ref());
                resolved = openAPI.getComponents().getParameters().getOrDefault(parameterName, parameter);
            }
            collectEnumSchemaRefs(resolved.getSchema(), result);
            if (resolved.getContent() != null) {
                resolved.getContent().values().forEach(mediaType -> collectEnumSchemaRefs(mediaType.getSchema(), result));
            }
        }
    }

    private void collectEnumSchemaRefs(Schema<?> schema, Set<String> result) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null && schema.get$ref().startsWith("#/components/schemas/")) {
            result.add(refName(schema.get$ref()));
        }
        collectEnumSchemaRefs(schema.getItems(), result);
        if (schema.getAdditionalProperties() instanceof Schema<?> additionalProperties) {
            collectEnumSchemaRefs(additionalProperties, result);
        }
    }

    private void captureInlineRequestEntityEnums(OpenAPI openAPI, String inputSpec) {
        if (openAPI.getPaths() == null) {
            inlineRequestEntityEnumValues = Map.of();
            inlineRequestEntityEnumCollections = Set.of();
            return;
        }
        Map<String, List<String>> valuesByOperation = new LinkedHashMap<>();
        Set<String> collections = new LinkedHashSet<>();
        openAPI.getPaths().values().forEach(pathItem -> pathItem.readOperations().forEach(operation ->
                captureInlineRequestEntityEnum(operation, valuesByOperation, collections)));
        captureRawInlineRequestEntityEnums(inputSpec, valuesByOperation, collections);
        inlineRequestEntityEnumValues = Map.copyOf(valuesByOperation);
        inlineRequestEntityEnumCollections = Set.copyOf(collections);
    }

    private void captureInlineRequestEntityEnum(Operation operation,
                                                Map<String, List<String>> valuesByOperation,
                                                Set<String> collections) {
        if (operation.getOperationId() == null || operation.getRequestBody() == null
                || operation.getRequestBody().getContent() == null
                || operation.getRequestBody().getContent().isEmpty()) {
            return;
        }
        Schema<?> schema = operation.getRequestBody().getContent().values().iterator().next().getSchema();
        if (schema == null || schema.get$ref() != null) {
            return;
        }
        Schema<?> enumSchema = schema.getItems() == null ? schema : schema.getItems();
        if (enumSchema.get$ref() == null && enumSchema.getEnum() != null && !enumSchema.getEnum().isEmpty()) {
            valuesByOperation.put(operation.getOperationId(), enumSchema.getEnum().stream()
                    .map(Object::toString)
                    .toList());
            if (schema.getItems() != null) {
                collections.add(operation.getOperationId());
            }
        }
    }

    private void captureRawInlineRequestEntityEnums(String inputSpec,
                                                     Map<String, List<String>> valuesByOperation,
                                                     Set<String> collections) {
        if (inputSpec == null || inputSpec.isBlank()) {
            return;
        }
        try {
            String specContent = InputSpecContentReader.read(inputSpec);
            ObjectMapper mapper = looksLikeJson(inputSpec, specContent) ? Json.mapper() : Yaml.mapper();
            JsonNode paths = mapper.readTree(specContent).path("paths");
            paths.fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields().forEachRemaining(methodEntry ->
                    captureRawInlineRequestEntityEnum(methodEntry.getValue(), valuesByOperation, collections)));
        } catch (IOException | RuntimeException ignored) {
            // The parsed OpenAPI model remains the source of truth when raw input cannot be read.
        }
    }

    private void captureRawInlineRequestEntityEnum(JsonNode operation,
                                                   Map<String, List<String>> valuesByOperation,
                                                   Set<String> collections) {
        String operationId = operation.path("operationId").asText(null);
        JsonNode content = operation.path("requestBody").path("content");
        if (operationId == null || !content.isObject() || content.isEmpty()) {
            return;
        }
        JsonNode schema = content.elements().next().path("schema");
        JsonNode enumSchema = schema.has("items") ? schema.path("items") : schema;
        JsonNode enumNode = enumSchema.path("enum");
        if (!enumNode.isArray() || enumNode.isEmpty()) {
            return;
        }
        List<String> values = new ArrayList<>();
        enumNode.forEach(value -> values.add(value.asText()));
        valuesByOperation.put(operationId, List.copyOf(values));
        if (schema.has("items")) {
            collections.add(operationId);
        }
    }

    private void restoreInlineRequestEntityEnum(CodegenOperation codegenOperation,
                                                List<String> enumValues,
                                                boolean collection) {
        Map<String, Object> allowableValues = stringAllowableValues(enumValues);
        List<CodegenParameter> bodyParameters = new ArrayList<>();
        if (codegenOperation.bodyParam != null) {
            bodyParameters.add(codegenOperation.bodyParam);
        }
        if (codegenOperation.allParams != null) {
            codegenOperation.allParams.stream()
                    .filter(parameter -> parameter.isBodyParam && parameter != codegenOperation.bodyParam)
                    .forEach(bodyParameters::add);
        }
        for (CodegenParameter bodyParameter : bodyParameters) {
            if (!collection) {
                bodyParameter.isEnum = true;
                bodyParameter.isString = true;
                bodyParameter.allowableValues = allowableValues;
            } else if (bodyParameter.items != null) {
                bodyParameter.items.isEnum = true;
                bodyParameter.items.isString = true;
                bodyParameter.items.allowableValues = allowableValues;
            }
        }
    }

    private Map<String, Object> stringAllowableValues(List<String> values) {
        List<Map<String, String>> enumVars = new ArrayList<>();
        for (String wireValue : values) {
            enumVars.add(Map.of("name", enumVarNamer.apply(wireValue, "String"),
                                "value", JavaStringLiterals.toJavaStringLiteral(wireValue)));
        }
        return Map.of("enumVars", enumVars);
    }

    private CodegenProperty inlineStringEnumProperty(CodegenProperty property) {
        CodegenProperty candidate = property.isArray ? property.items : property;
        if (candidate == null || !candidate.isEnum || candidate.isEnumRef || !candidate.isString) {
            return null;
        }
        return enumValues(candidate.allowableValues).isEmpty() ? null : candidate;
    }

    @SuppressWarnings("unchecked")
    private List<CodegenProperty> renderVars(CodegenModel model) {
        Object renderVars = model.vendorExtensions.get("x-render-vars");
        return renderVars instanceof List<?> vars ? (List<CodegenProperty>) vars : model.vars;
    }

    private Map<String, Object> enumDefinition(String enumName,
                                               String qualifiedType,
                                               String converterName,
                                               Map<String, Object> allowableValues,
                                               boolean httpMapper) {
        List<Map<String, String>> values = enumValues(allowableValues);
        if (values.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enumName", enumName);
        result.put("qualifiedType", qualifiedType);
        result.put("converterName", converterName);
        result.put("values", values);
        if (httpMapper) {
            result.put("httpMapper", Boolean.TRUE);
        }
        return result;
    }

    private List<Map<String, String>> enumValues(Map<String, Object> allowableValues) {
        if (allowableValues == null || !(allowableValues.get("enumVars") instanceof List<?> enumVars)) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (Object enumVar : enumVars) {
            if (!(enumVar instanceof Map<?, ?> values)) {
                continue;
            }
            Object name = values.get("name");
            Object value = values.get("value");
            if (name != null && value != null) {
                result.add(Map.of("name", name.toString(), "value", value.toString()));
            }
        }
        return result;
    }

    private String refName(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private boolean looksLikeJson(String specLocation, String specContent) {
        String trimmed = specContent.stripLeading();
        return specLocation.endsWith(".json") || trimmed.startsWith("{") || trimmed.startsWith("[");
    }
}
