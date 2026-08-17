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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.OperationsMap;

/**
 * Prepares exact-wire-value string enums and their Helidon JSON and HTTP mappings.
 */
final class JsonStringEnumSupport {
    private final BiFunction<String, String, String> enumVarNamer;
    private final UnaryOperator<String> modelNamer;
    private final Set<String> allocatedModelTypes = new LinkedHashSet<>();
    private final Set<String> allocatedApiTypes = new LinkedHashSet<>();
    private Set<String> httpParameterEnumSchemas = Set.of();
    private Map<String, List<String>> topLevelStringEnumValues = Map.of();
    private Map<String, List<String>> inlineRequestEntityEnumValues = Map.of();
    private Set<String> inlineRequestEntityEnumCollections = Set.of();

    JsonStringEnumSupport(BiFunction<String, String, String> enumVarNamer,
                          UnaryOperator<String> modelNamer) {
        this.enumVarNamer = enumVarNamer;
        this.modelNamer = modelNamer;
    }

    void preprocess(OpenAPI openAPI) {
        allocatedModelTypes.clear();
        allocatedApiTypes.clear();
        if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            openAPI.getComponents().getSchemas().keySet().stream()
                    .map(modelNamer)
                    .forEach(allocatedModelTypes::add);
            Map<String, List<String>> valuesBySchema = new LinkedHashMap<>();
            openAPI.getComponents().getSchemas().forEach((name, schema) -> {
                if ("string".equals(schema.getType()) && schema.getEnum() != null && !schema.getEnum().isEmpty()) {
                    valuesBySchema.put(name,
                                       declaredStringEnumValues("schema '" + name + "'", schema.getEnum()));
                }
            });
            topLevelStringEnumValues = Map.copyOf(valuesBySchema);
        } else {
            topLevelStringEnumValues = Map.of();
        }
        captureInlineRequestEntityEnums(openAPI);
        httpParameterEnumSchemas = findHttpParameterEnumSchemas(openAPI);
    }

    void markHttpParameterEnum(String schemaName, CodegenModel model) {
        if (httpParameterEnumSchemas.contains(schemaName)) {
            model.vendorExtensions.put("x-http-parameter-enum", Boolean.TRUE);
        }
    }

    void restoreInlineRequestEntityEnum(String path, String httpMethod, CodegenOperation codegenOperation) {
        String operationKey = operationKey(path, httpMethod);
        List<String> enumValues = inlineRequestEntityEnumValues.getOrDefault(operationKey, List.of());
        if (enumValues.isEmpty()) {
            return;
        }
        restoreInlineRequestEntityEnum(codegenOperation,
                                       enumValues,
                                       inlineRequestEntityEnumCollections.contains(operationKey));
        codegenOperation.vendorExtensions.put("x-inline-request-entity-enum-values", enumValues);
        if (inlineRequestEntityEnumCollections.contains(operationKey)) {
            codegenOperation.vendorExtensions.put("x-inline-request-entity-enum-collection", Boolean.TRUE);
        }
    }

    void prepareInlineRequestEntityEnum(CodegenOperation operation, CodegenParameter parameter) {
        if (!parameter.isBodyParam) {
            return;
        }
        Object capturedValues = operation.vendorExtensions.get("x-inline-request-entity-enum-values");
        if (!(capturedValues instanceof List<?> values) || values.isEmpty()) {
            return;
        }
        List<String> enumValues = values.stream().map(Object::toString).toList();
        Map<String, Object> allowableValues = stringAllowableValues(enumValues);
        if (operation.vendorExtensions.containsKey("x-inline-request-entity-enum-collection")
                && parameter.items != null) {
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
        if (!parameter.isArray && parameter.isEnumRef && parameter.isString) {
            validateEnumWireValues("operation parameter '" + parameter.baseName + "'",
                                   wireValues(parameter._enum, parameter.allowableValues),
                                   parameter.minLength,
                                   parameter.maxLength,
                                   parameter.pattern);
            parameter.vendorExtensions.put("x-enum-wire-constraints-validated", Boolean.TRUE);
            return;
        }
        if ((!itemEnum && !directEnum) || enumValues(allowableValues).isEmpty()) {
            return;
        }

        String enumName = javaName.apply(operation.operationId)
                + javaName.apply(parameter.paramName)
                + "Enum";
        CodegenProperty constraintSource = itemEnum ? item : null;
        List<String> wireValues = constraintSource == null
                ? wireValues(parameter._enum, allowableValues)
                : wireValues(constraintSource._enum, allowableValues);
        validateEnumWireValues("operation parameter '" + parameter.baseName + "'",
                               wireValues,
                               constraintSource == null ? parameter.minLength : constraintSource.minLength,
                               constraintSource == null ? parameter.maxLength : constraintSource.maxLength,
                               constraintSource == null ? parameter.pattern : constraintSource.pattern);
        parameter.vendorExtensions.put("x-enum-wire-constraints-validated", Boolean.TRUE);
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

        boolean httpMapper = parameter.isPathParam || parameter.isQueryParam || parameter.isHeaderParam;
        reserveApiTypes(apiClassname);
        String converterName = allocateTypeName(allocatedApiTypes,
                                                apiClassname + "Api" + enumName + "JsonConverter");
        Map<String, Object> definition = enumDefinition(enumName,
                                                        apiClassname + "Api." + enumName,
                                                        converterName,
                                                        allowableValues,
                                                        wireValues,
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
        List<String> wireValues = topLevelStringEnumValues.getOrDefault(
                model.schemaName, wireValues(null, model.allowableValues));
        validateEnumWireValues("schema '" + model.schemaName + "'",
                               wireValues,
                               model.getMinLength(),
                               model.getMaxLength(),
                               model.getPattern());
        Map<String, Object> definition = enumDefinition(model.classname,
                                                        model.classname,
                                                        allocateTypeName(allocatedModelTypes,
                                                                         model.classname + "JsonConverter"),
                                                        model.allowableValues,
                                                        wireValues,
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

    Set<String> prepareModels(List<CodegenModel> models) {
        Set<String> topLevelEnums = new LinkedHashSet<>();
        for (CodegenModel model : models) {
            if (prepareTopLevelModel(model)) {
                topLevelEnums.add(model.classname);
            } else if (Boolean.TRUE.equals(model.vendorExtensions.get("x-is-union-interface"))) {
                model.vendorExtensions.put("x-render-vars", List.of());
            } else {
                prepareInlineModelEnums(model);
            }
        }
        return Set.copyOf(topLevelEnums);
    }

    List<CodegenProperty> prepareInlineModelEnums(CodegenModel model) {
        List<CodegenProperty> properties = renderVars(model);
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (CodegenProperty property : properties) {
            if (property.isEnum) {
                String enumName = property.isArray && property.items != null
                        ? property.items.datatypeWithEnum
                        : property.datatypeWithEnum;
                if (enumName != null && !enumName.isBlank()) {
                    property.vendorExtensions.put("x-enum-name", enumName);
                }
            }
            if (property.isEnumRef && property.isString) {
                validateEnumWireValues("model property '" + model.classname + "." + property.baseName + "'",
                                       wireValues(property._enum, property.allowableValues),
                                       property.minLength,
                                       property.maxLength,
                                       property.pattern);
                property.vendorExtensions.put("x-enum-wire-constraints-validated", Boolean.TRUE);
                continue;
            }
            CodegenProperty enumProperty = inlineStringEnumProperty(property);
            if (enumProperty == null || enumProperty.datatypeWithEnum == null
                    || enumProperty.datatypeWithEnum.isBlank()) {
                continue;
            }
            String enumName = enumProperty.datatypeWithEnum;
            List<String> wireValues = wireValues(enumProperty._enum, enumProperty.allowableValues);
            validateEnumWireValues("model property '" + model.classname + "." + property.baseName + "'",
                                   wireValues,
                                   enumProperty.minLength,
                                   enumProperty.maxLength,
                                   enumProperty.pattern);
            property.vendorExtensions.put("x-enum-name", enumName);
            property.vendorExtensions.put("x-enum-wire-constraints-validated", Boolean.TRUE);
            Map<String, Object> definition = enumDefinition(enumName,
                                                            model.classname + "." + enumName,
                                                            allocateTypeName(allocatedModelTypes,
                                                                             model.classname + enumName
                                                                                     + "JsonConverter"),
                                                            enumProperty.allowableValues,
                                                            wireValues,
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
            if ("cookie".equals(resolved.getIn()) && isStringEnumSchema(openAPI, resolved.getSchema())) {
                throw unsupportedCookieEnum(resolved);
            }
            collectEnumSchemaRefs(resolved.getSchema(), result);
            if (resolved.getContent() != null) {
                if ("cookie".equals(resolved.getIn()) && resolved.getContent().values().stream()
                        .anyMatch(mediaType -> isStringEnumSchema(openAPI, mediaType.getSchema()))) {
                    throw unsupportedCookieEnum(resolved);
                }
                resolved.getContent().values().forEach(mediaType -> collectEnumSchemaRefs(mediaType.getSchema(), result));
            }
        }
    }

    private boolean isStringEnumSchema(OpenAPI openAPI, Schema<?> schema) {
        if (schema == null) {
            return false;
        }
        if (schema.get$ref() != null && schema.get$ref().startsWith("#/components/schemas/")
                && openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            return isStringEnumSchema(openAPI, openAPI.getComponents().getSchemas().get(refName(schema.get$ref())));
        }
        return schema.getItems() == null
                ? "string".equals(schema.getType()) && schema.getEnum() != null && !schema.getEnum().isEmpty()
                : isStringEnumSchema(openAPI, schema.getItems());
    }

    private IllegalArgumentException unsupportedCookieEnum(Parameter parameter) {
        return new IllegalArgumentException("Unsupported OpenAPI string enum cookie parameter '"
                + parameter.getName() + "'. Helidon declarative HTTP does not provide a cookie parameter "
                + "annotation. Read the Cookie header in endpoint code or map the value to a supported path, "
                + "query, or header parameter.");
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

    private void captureInlineRequestEntityEnums(OpenAPI openAPI) {
        if (openAPI.getPaths() == null) {
            inlineRequestEntityEnumValues = Map.of();
            inlineRequestEntityEnumCollections = Set.of();
            return;
        }
        Map<String, List<String>> valuesByOperation = new LinkedHashMap<>();
        Set<String> collections = new LinkedHashSet<>();
        openAPI.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) ->
                captureInlineRequestEntityEnum(path, method, operation, valuesByOperation, collections)));
        inlineRequestEntityEnumValues = Map.copyOf(valuesByOperation);
        inlineRequestEntityEnumCollections = Set.copyOf(collections);
    }

    private void captureInlineRequestEntityEnum(String path,
                                                PathItem.HttpMethod method,
                                                Operation operation,
                                                Map<String, List<String>> valuesByOperation,
                                                Set<String> collections) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null
                || operation.getRequestBody().getContent().isEmpty()) {
            return;
        }
        List<String> capturedValues = null;
        Boolean collection = null;
        for (Map.Entry<String, io.swagger.v3.oas.models.media.MediaType> entry
                : operation.getRequestBody().getContent().entrySet()) {
            if (!isJsonMediaType(entry.getKey())) {
                return;
            }
            if (entry.getValue() == null) {
                return;
            }
            Schema<?> schema = entry.getValue().getSchema();
            if (schema == null || schema.get$ref() != null) {
                return;
            }
            boolean currentCollection = schema.getItems() != null;
            Schema<?> enumSchema = currentCollection ? schema.getItems() : schema;
            if (enumSchema.get$ref() != null || !"string".equals(enumSchema.getType())
                    || enumSchema.getEnum() == null || enumSchema.getEnum().isEmpty()) {
                return;
            }
            List<String> currentValues = declaredStringEnumValues(
                    "request entity for operation '" + operation.getOperationId() + "'",
                    enumSchema.getEnum());
            if (capturedValues != null
                    && (!capturedValues.equals(currentValues) || collection != currentCollection)) {
                return;
            }
            capturedValues = currentValues;
            collection = currentCollection;
        }
        if (capturedValues != null) {
            String operationKey = operationKey(path, method.name());
            valuesByOperation.put(operationKey, capturedValues);
            if (Boolean.TRUE.equals(collection)) {
                collections.add(operationKey);
            }
        }
    }

    private boolean isJsonMediaType(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String normalized = mediaType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return "application/json".equals(normalized) || normalized.endsWith("+json");
    }

    private void reserveApiTypes(String apiClassname) {
        allocatedApiTypes.add(apiClassname + "Api");
        allocatedApiTypes.add(apiClassname + "Endpoint");
        allocatedApiTypes.add(apiClassname + "Client");
        allocatedApiTypes.add(apiClassname + "Exception");
        allocatedApiTypes.add(apiClassname + "ErrorHandler");
    }

    private String allocateTypeName(Set<String> allocatedTypes, String preferredName) {
        if (allocatedTypes.add(preferredName)) {
            return preferredName;
        }
        int suffix = 2;
        while (!allocatedTypes.add(preferredName + suffix)) {
            suffix++;
        }
        return preferredName + suffix;
    }

    private void validateEnumWireValues(String location,
                                        List<String> values,
                                        Integer minLength,
                                        Integer maxLength,
                                        String pattern) {
        Pattern compiledPattern = null;
        if (pattern != null && !pattern.isEmpty()) {
            try {
                compiledPattern = Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid OpenAPI pattern on " + location + ": "
                                                           + e.getMessage(), e);
            }
        }
        for (String value : values) {
            if (minLength != null && value.length() < minLength) {
                throw invalidEnumConstraint(location, value, "minLength " + minLength);
            }
            if (maxLength != null && value.length() > maxLength) {
                throw invalidEnumConstraint(location, value, "maxLength " + maxLength);
            }
            if (compiledPattern != null && !compiledPattern.matcher(value).find()) {
                throw invalidEnumConstraint(location, value, "pattern '" + pattern + "'");
            }
        }
    }

    private IllegalArgumentException invalidEnumConstraint(String location, String value, String constraint) {
        return new IllegalArgumentException("OpenAPI string enum wire value '" + value + "' on " + location
                                                    + " does not satisfy " + constraint
                                                    + "; generated enum validation cannot preserve this contradictory schema");
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
        return Map.of("enumVars", enumVars, "values", List.copyOf(values));
    }

    private List<String> wireValues(List<String> declaredValues, Map<String, Object> allowableValues) {
        if (declaredValues != null && !declaredValues.isEmpty()) {
            return List.copyOf(declaredValues);
        }
        if (allowableValues != null && allowableValues.get("values") instanceof List<?> values) {
            return values.stream().map(Object::toString).toList();
        }
        return List.of();
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
                                               List<String> wireValues,
                                               boolean httpMapper) {
        List<Map<String, String>> values = uniqueEnumValues(
                enumName,
                exactEnumValues(enumName, allowableValues, wireValues));
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

    private List<Map<String, String>> exactEnumValues(String enumName,
                                                      Map<String, Object> allowableValues,
                                                      List<String> wireValues) {
        List<Map<String, String>> generatedValues = enumValues(allowableValues);
        if (generatedValues.size() != wireValues.size()) {
            throw new IllegalArgumentException("Cannot preserve the exact OpenAPI wire values for string enum '"
                                                       + enumName + "': the generator produced "
                                                       + generatedValues.size() + " constants for "
                                                       + wireValues.size() + " declared values");
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 0; i < wireValues.size(); i++) {
            String wireValue = wireValues.get(i);
            String name = generatedValues.get(i).get("name");
            result.add(Map.of("name", name, "value", JavaStringLiterals.toJavaStringLiteral(wireValue)));
        }
        return List.copyOf(result);
    }

    private List<String> declaredStringEnumValues(String location, List<?> declaredValues) {
        List<String> result = new ArrayList<>();
        for (Object value : declaredValues) {
            if (value == null) {
                throw new IllegalArgumentException("Unsupported null member in OpenAPI string enum on " + location
                                                           + "; use nullable to allow a null value");
            }
            result.add(value.toString());
        }
        return List.copyOf(result);
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

    private List<Map<String, String>> uniqueEnumValues(String enumName, List<Map<String, String>> values) {
        Set<String> wireValues = new LinkedHashSet<>();
        Set<String> constantNames = new LinkedHashSet<>();
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> value : values) {
            String wireValue = value.get("value");
            if (!wireValues.add(wireValue)) {
                throw new IllegalArgumentException("OpenAPI string enum '" + enumName
                                                           + "' declares duplicate wire value " + wireValue);
            }
            String baseName = value.get("name");
            String constantName = baseName;
            for (int suffix = 2; !constantNames.add(constantName); suffix++) {
                constantName = baseName + "_" + suffix;
            }
            result.add(Map.of("name", constantName, "value", wireValue));
        }
        return List.copyOf(result);
    }

    private String refName(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private String operationKey(String path, String httpMethod) {
        return httpMethod.toUpperCase() + " " + path;
    }
}
