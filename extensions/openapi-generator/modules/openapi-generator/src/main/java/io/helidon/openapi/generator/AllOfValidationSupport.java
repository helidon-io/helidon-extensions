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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;

/**
 * Recovers and composes validation metadata that OpenAPI Generator normalization can discard from {@code allOf} models.
 */
final class AllOfValidationSupport {

    private AllOfValidationSupport() {
    }

    static Snapshot snapshot(OpenAPI openAPI) {
        if (openAPI == null) {
            return Snapshot.empty();
        }
        try {
            return new Snapshot(Json.mapper().valueToTree(openAPI));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot snapshot the parsed OpenAPI document for allOf metadata: "
                                                       + e.getMessage(), e);
        }
    }

    static Map<String, String> discriminatorValues(String inputSpec) {
        if (inputSpec == null || inputSpec.isBlank()) {
            return Map.of();
        }
        try {
            String content = InputSpecContentReader.read(inputSpec);
            JsonNode root = inputSpec.toLowerCase().endsWith(".json") || content.stripLeading().startsWith("{")
                    ? Json.mapper().readTree(content)
                    : Yaml.mapper().readTree(content);
            Map<String, String> result = new LinkedHashMap<>();
            collectDiscriminatorValues(root.path("components").path("schemas"), result);
            collectDiscriminatorValues(root.path("definitions"), result);
            return result.isEmpty() ? Map.of() : Map.copyOf(result);
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
    }

    static Map<String, Map<String, List<CodegenProperty>>> validationProperties(
            Snapshot snapshot,
            Function<String, String> modelName,
            PropertyFactory propertyFactory,
            Predicate<CodegenProperty> constrained) {
        if (snapshot.isEmpty()) {
            return Map.of();
        }
        try {
            ObjectMapper mapper = Json.mapper();
            JsonNode root = snapshot.root();
            JsonNode schemas = root.path("components").path("schemas");
            if (!schemas.isObject()) {
                schemas = root.path("definitions");
            }
            if (!schemas.isObject()) {
                return Map.of();
            }
            return collectValidationProperties(schemas, mapper, modelName, propertyFactory, constrained);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot inspect allOf validation constraints in the parsed OpenAPI "
                                                       + "document: " + e.getMessage(), e);
        }
    }

    static CodegenProperty merge(CodegenModel model, List<CodegenProperty> sources) {
        CodegenProperty merged = sources.getLast().clone();
        for (int i = 0; i < sources.size() - 1; i++) {
            CodegenProperty inherited = sources.get(i);
            ensureCompatibleTypes(model, merged, inherited);
            merged.minLength = larger(merged.minLength, inherited.minLength);
            merged.maxLength = smaller(merged.maxLength, inherited.maxLength);
            merged.minItems = larger(merged.minItems, inherited.minItems);
            merged.maxItems = smaller(merged.maxItems, inherited.maxItems);
            merged.pattern = mergeEqual(model, merged, "pattern", merged.pattern, inherited.pattern);
            merged.multipleOf = mergeEqual(model, merged, "multipleOf", merged.multipleOf, inherited.multipleOf);
            mergeMinimum(merged, inherited);
            mergeMaximum(merged, inherited);
        }
        if (merged.minLength != null && merged.maxLength != null && merged.minLength > merged.maxLength) {
            throw incompatible(model, merged, "minLength exceeds maxLength");
        }
        if (merged.minItems != null && merged.maxItems != null && merged.minItems > merged.maxItems) {
            throw incompatible(model, merged, "minItems exceeds maxItems");
        }
        validateNumericBounds(model, merged);
        return merged;
    }

    static List<CodegenProperty> validationSources(
            Map<String, Map<String, List<CodegenProperty>>> propertiesBySchema,
            CodegenModel model,
            CodegenProperty property) {
        if (model == null) {
            return List.of(property);
        }
        List<CodegenProperty> result = new ArrayList<>(propertiesBySchema
                                                               .getOrDefault(model.classname, Map.of())
                                                               .getOrDefault(property.baseName, List.of()));
        result.add(property);
        return result;
    }

    private static Map<String, Map<String, List<CodegenProperty>>> collectValidationProperties(
            JsonNode schemas,
            ObjectMapper mapper,
            Function<String, String> modelName,
            PropertyFactory propertyFactory,
            Predicate<CodegenProperty> constrained) {
        Map<String, Map<String, List<CodegenProperty>>> result = new LinkedHashMap<>();
        schemas.fields().forEachRemaining(entry -> {
            if (!entry.getValue().path("allOf").isArray()) {
                return;
            }
            Map<String, List<CodegenProperty>> properties = new LinkedHashMap<>();
            collectValidationProperties(entry.getValue(), schemas, mapper, propertyFactory, constrained,
                                        properties, new LinkedHashSet<>());
            if (!properties.isEmpty()) {
                Map<String, List<CodegenProperty>> immutableProperties = new LinkedHashMap<>();
                properties.forEach((name, values) -> immutableProperties.put(name, List.copyOf(values)));
                result.put(modelName.apply(entry.getKey()), Map.copyOf(immutableProperties));
            }
        });
        return Map.copyOf(result);
    }

    private static void collectValidationProperties(JsonNode schema,
                                                    JsonNode schemas,
                                                    ObjectMapper mapper,
                                                    PropertyFactory propertyFactory,
                                                    Predicate<CodegenProperty> constrained,
                                                    Map<String, List<CodegenProperty>> result,
                                                    Set<String> visitedRefs) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        JsonNode reference = schema.get("$ref");
        if (reference != null && reference.isTextual()) {
            String referencedName = reference.asText();
            int separator = referencedName.lastIndexOf('/');
            referencedName = separator < 0 ? referencedName : referencedName.substring(separator + 1);
            if (visitedRefs.add(referencedName)) {
                collectValidationProperties(schemas.get(referencedName), schemas, mapper, propertyFactory,
                                            constrained, result, visitedRefs);
                visitedRefs.remove(referencedName);
            }
            return;
        }
        JsonNode allOf = schema.get("allOf");
        if (allOf != null && allOf.isArray()) {
            allOf.forEach(member -> collectValidationProperties(member, schemas, mapper, propertyFactory,
                                                                constrained, result, visitedRefs));
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                Schema<?> propertySchema = mapper.convertValue(entry.getValue(), Schema.class);
                CodegenProperty property = propertyFactory.create(entry.getKey(), propertySchema);
                if (constrained.test(property)) {
                    result.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(property);
                }
            });
        }
    }

    private static void collectDiscriminatorValues(JsonNode schemas, Map<String, String> result) {
        if (schemas == null || !schemas.isObject()) {
            return;
        }
        schemas.fields().forEachRemaining(entry -> {
            String value = discriminatorValue(entry.getValue());
            if (value != null && !value.isBlank()) {
                result.putIfAbsent(entry.getKey(), value);
            }
        });
    }

    private static String discriminatorValue(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return null;
        }
        JsonNode topLevel = schema.get("x-discriminator-value");
        if (topLevel != null && topLevel.isValueNode()) {
            return topLevel.asText();
        }
        JsonNode allOf = schema.get("allOf");
        if (allOf == null || !allOf.isArray()) {
            return null;
        }
        for (JsonNode member : allOf) {
            if (member == null || !member.isObject()) {
                continue;
            }
            JsonNode extension = member.get("x-discriminator-value");
            if (extension != null && extension.isValueNode()) {
                return extension.asText();
            }
            JsonNode shorthand = member.get("discriminator");
            if (shorthand != null && shorthand.isTextual()) {
                return shorthand.asText();
            }
        }
        return null;
    }

    private static void ensureCompatibleTypes(CodegenModel model,
                                              CodegenProperty local,
                                              CodegenProperty inherited) {
        String localType = local.datatypeWithEnum == null ? local.dataType : local.datatypeWithEnum;
        String inheritedType = inherited.datatypeWithEnum == null ? inherited.dataType : inherited.datatypeWithEnum;
        if (!java.util.Objects.equals(localType, inheritedType)) {
            throw incompatible(model, local, "mapped Java types differ ('" + inheritedType + "' and '"
                    + localType + "')");
        }
    }

    private static void mergeMinimum(CodegenProperty merged, CodegenProperty inherited) {
        if (inherited.minimum == null) {
            return;
        }
        if (merged.minimum == null
                || new BigDecimal(inherited.minimum).compareTo(new BigDecimal(merged.minimum)) > 0) {
            merged.minimum = inherited.minimum;
            merged.exclusiveMinimum = inherited.exclusiveMinimum;
        } else if (new BigDecimal(inherited.minimum).compareTo(new BigDecimal(merged.minimum)) == 0) {
            merged.exclusiveMinimum |= inherited.exclusiveMinimum;
        }
    }

    private static void mergeMaximum(CodegenProperty merged, CodegenProperty inherited) {
        if (inherited.maximum == null) {
            return;
        }
        if (merged.maximum == null
                || new BigDecimal(inherited.maximum).compareTo(new BigDecimal(merged.maximum)) < 0) {
            merged.maximum = inherited.maximum;
            merged.exclusiveMaximum = inherited.exclusiveMaximum;
        } else if (new BigDecimal(inherited.maximum).compareTo(new BigDecimal(merged.maximum)) == 0) {
            merged.exclusiveMaximum |= inherited.exclusiveMaximum;
        }
    }

    private static void validateNumericBounds(CodegenModel model, CodegenProperty property) {
        if (property.minimum == null || property.maximum == null) {
            return;
        }
        int comparison = new BigDecimal(property.minimum).compareTo(new BigDecimal(property.maximum));
        if (comparison > 0 || (comparison == 0 && (property.exclusiveMinimum || property.exclusiveMaximum))) {
            throw incompatible(model, property, "numeric bounds have no common value");
        }
    }

    private static <T> T mergeEqual(CodegenModel model,
                                    CodegenProperty property,
                                    String constraint,
                                    T local,
                                    T inherited) {
        if (local == null) {
            return inherited;
        }
        if (inherited == null || normalize(local).equals(normalize(inherited))) {
            return local;
        }
        throw incompatible(model, property, "multiple distinct " + constraint
                + " constraints cannot be represented by one Helidon annotation");
    }

    private static Object normalize(Object value) {
        return value instanceof Number number ? new BigDecimal(number.toString()).stripTrailingZeros() : value;
    }

    private static Integer larger(Integer first, Integer second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : Math.max(first, second);
    }

    private static Integer smaller(Integer first, Integer second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : Math.min(first, second);
    }

    private static IllegalArgumentException incompatible(CodegenModel model,
                                                         CodegenProperty property,
                                                         String reason) {
        return new IllegalArgumentException("Cannot compose allOf validation constraints for schema '"
                + model.classname + "', property '" + property.baseName + "': " + reason + ".");
    }

    @FunctionalInterface
    interface PropertyFactory {
        CodegenProperty create(String name, Schema<?> schema);
    }

    record Snapshot(JsonNode root) {
        private static final Snapshot EMPTY = new Snapshot(null);

        static Snapshot empty() {
            return EMPTY;
        }

        boolean isEmpty() {
            return root == null || root.isMissingNode() || root.isNull();
        }
    }
}
