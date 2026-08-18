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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationsMap;

/**
 * Computes and applies generator-only cascading validation metadata.
 */
final class CascadingValidationSupport {

    private CascadingValidationSupport() {
    }

    static Analysis analyze(List<CodegenModel> models,
                            Set<String> modelNames,
                            BiPredicate<CodegenModel, CodegenProperty> directlyConstrained) {
        Map<String, List<CodegenProperty>> propertiesByModel = new HashMap<>();
        Set<String> directlyConstrainedModels = new LinkedHashSet<>();
        for (CodegenModel model : models) {
            List<CodegenProperty> properties = Boolean.TRUE.equals(model.vendorExtensions.get("x-is-union-interface"))
                    ? List.of()
                    : renderVars(model);
            propertiesByModel.put(model.classname, properties);
            if (properties.stream().anyMatch(property -> directlyConstrained.test(model, property))) {
                directlyConstrainedModels.add(model.classname);
            }
        }
        Set<String> participating = participatingModels(models, propertiesByModel,
                                                        modelNames, directlyConstrainedModels);
        Set<String> modelSpecificValidation = modelSpecificValidationModels(propertiesByModel,
                                                                             modelNames,
                                                                             directlyConstrainedModels);
        Inheritance inheritance = inheritedValidationProperties(models,
                                                                 propertiesByModel,
                                                                 modelNames,
                                                                 participating,
                                                                 directlyConstrained);
        Map<String, List<CodegenProperty>> effectiveProperties = effectiveProperties(propertiesByModel, inheritance);
        validateShapes(models, effectiveProperties, modelNames, participating);
        validateDirectBoundaries(models, effectiveProperties, modelNames, participating);
        validateAcyclicGraph(effectiveProperties, modelNames, participating);
        Map<String, Set<String>> unsupportedPolymorphicTypes = unsupportedPolymorphicTypes(
                models, participating, modelSpecificValidation);
        validatePolymorphicProperties(models, effectiveProperties, unsupportedPolymorphicTypes);
        return new Analysis(modelNames,
                            Map.copyOf(propertiesByModel),
                            participating,
                            unsupportedPolymorphicTypes,
                            inheritance.accessorsByModel,
                            inheritance.overriddenByModel);
    }

    static Set<String> participatingModels(List<CodegenModel> models,
                                           Map<String, List<CodegenProperty>> propertiesByModel,
                                           Set<String> modelNames,
                                           Set<String> directlyConstrainedModels) {
        Set<String> participating = new LinkedHashSet<>(directlyConstrainedModels);
        Map<String, Set<String>> dependentsByTarget = new HashMap<>();
        for (CodegenModel model : models) {
            for (CodegenProperty property : propertiesByModel.getOrDefault(model.classname, List.of())) {
                for (String target : ValidationTypeSupport.referencedModels(propertyType(property), modelNames)) {
                    dependentsByTarget.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(model.classname);
                }
            }
            Object parent = model.vendorExtensions.get("x-extends-model");
            if (parent != null && modelNames.contains(parent.toString())) {
                dependentsByTarget.computeIfAbsent(parent.toString(), ignored -> new LinkedHashSet<>())
                        .add(model.classname);
            }
        }

        var queue = new ArrayDeque<>(directlyConstrainedModels.stream().sorted().toList());
        while (!queue.isEmpty()) {
            String target = queue.removeFirst();
            for (String dependent : dependentsByTarget.getOrDefault(target, Set.of()).stream().sorted().toList()) {
                if (participating.add(dependent)) {
                    queue.addLast(dependent);
                }
            }
        }
        return Set.copyOf(participating);
    }

    private static Set<String> modelSpecificValidationModels(
            Map<String, List<CodegenProperty>> propertiesByModel,
            Set<String> modelNames,
            Set<String> directlyConstrainedModels) {
        Set<String> result = new LinkedHashSet<>(directlyConstrainedModels);
        Map<String, Set<String>> dependentsByTarget = new HashMap<>();
        propertiesByModel.forEach((modelName, properties) -> properties.forEach(property ->
                ValidationTypeSupport.referencedModels(propertyType(property), modelNames)
                        .forEach(target -> dependentsByTarget
                                .computeIfAbsent(target, ignored -> new LinkedHashSet<>())
                                .add(modelName))));

        var queue = new ArrayDeque<>(directlyConstrainedModels.stream().sorted().toList());
        while (!queue.isEmpty()) {
            String target = queue.removeFirst();
            for (String dependent : dependentsByTarget.getOrDefault(target, Set.of()).stream().sorted().toList()) {
                if (result.add(dependent)) {
                    queue.addLast(dependent);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static void validateDirectBoundaries(List<CodegenModel> models,
                                                 Map<String, List<CodegenProperty>> propertiesByModel,
                                                 Set<String> modelNames,
                                                 Set<String> participating) {
        for (CodegenModel model : models) {
            for (CodegenProperty property : propertiesByModel.getOrDefault(model.classname, List.of())) {
                String javaType = propertyType(property);
                if (ValidationTypeSupport.isDirectParticipatingModel(javaType, modelNames, participating)
                        && !property.requiredAndNotNullable()) {
                    throw new IllegalArgumentException("Unsupported nullable direct cascading validation boundary for "
                            + "schema '" + model.classname + "', property '" + property.baseName
                            + "', mapped Java type '" + javaType + "'. Helidon 4.5 invokes direct @Validation.Valid "
                            + "validators eagerly and does not guard null. Make the property required and non-nullable, "
                            + "use Optional or a supported container boundary, or validate it in application logic.");
                }
                validateNestedModelNullability(model, property, javaType, modelNames, participating);
            }
        }
    }

    private static void validateNestedModelNullability(CodegenModel model,
                                                       CodegenProperty property,
                                                       String javaType,
                                                       Set<String> modelNames,
                                                       Set<String> participating) {
        CodegenProperty nested = property.items != null ? property.items : property.additionalProperties;
        if (nested == null) {
            return;
        }
        String nestedType = propertyType(nested);
        if (nested.isNullable
                && ValidationTypeSupport.isDirectParticipatingModel(nestedType, modelNames, participating)) {
            throw new IllegalArgumentException("Unsupported nullable cascading validation boundary for schema '"
                    + model.classname + "', property '" + property.baseName + "', mapped Java type '" + javaType
                    + "': a nested model element or map value is nullable, but Helidon 4.5 invokes "
                    + "@Validation.Valid validators eagerly without a null guard. Make nested model values "
                    + "non-nullable or validate the boundary in application logic.");
        }
        validateNestedModelNullability(model, nested, javaType, modelNames, participating);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> unsupportedPolymorphicTypes(
            List<CodegenModel> models,
            Set<String> participating,
            Set<String> modelSpecificValidation) {
        Map<String, CodegenModel> modelsByName = models.stream()
                .collect(java.util.stream.Collectors.toMap(model -> model.classname, model -> model));
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (CodegenModel model : models) {
            if (Boolean.TRUE.equals(model.vendorExtensions.get("x-is-union-interface"))) {
                Object membersValue = model.vendorExtensions.get("x-union-members");
                if (membersValue instanceof List<?> members) {
                    Set<String> constrainedMembers = members.stream()
                            .filter(Map.class::isInstance)
                            .map(Map.class::cast)
                            .map(member -> member.get("name"))
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .filter(participating::contains)
                            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
                    if (!constrainedMembers.isEmpty()) {
                        result.put(model.classname, immutableSortedSet(constrainedMembers));
                    }
                }
            }

            if (Boolean.TRUE.equals(model.vendorExtensions.get("x-abstract-polymorphic-base"))) {
                Set<String> constrainedSubtypes = models.stream()
                        .filter(candidate -> modelSpecificValidation.contains(candidate.classname))
                        .filter(candidate -> isDescendantOf(candidate, model.classname, modelsByName))
                        .map(candidate -> candidate.classname)
                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
                if (!constrainedSubtypes.isEmpty()) {
                    result.merge(model.classname, immutableSortedSet(constrainedSubtypes), (left, right) -> {
                        Set<String> merged = new java.util.TreeSet<>(left);
                        merged.addAll(right);
                        return immutableSortedSet(merged);
                    });
                }
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static boolean isDescendantOf(CodegenModel candidate,
                                          String ancestor,
                                          Map<String, CodegenModel> modelsByName) {
        Set<String> visited = new LinkedHashSet<>();
        Object parent = candidate.vendorExtensions.get("x-extends-model");
        while (parent != null && visited.add(parent.toString())) {
            if (ancestor.equals(parent.toString())) {
                return true;
            }
            CodegenModel parentModel = modelsByName.get(parent.toString());
            parent = parentModel == null ? null : parentModel.vendorExtensions.get("x-extends-model");
        }
        return false;
    }

    private static void validatePolymorphicProperties(
            List<CodegenModel> models,
            Map<String, List<CodegenProperty>> propertiesByModel,
            Map<String, Set<String>> unsupportedPolymorphicTypes) {
        for (CodegenModel model : models) {
            for (CodegenProperty property : propertiesByModel.getOrDefault(model.classname, List.of())) {
                String javaType = propertyType(property);
                Set<String> polymorphicTypes = ValidationTypeSupport.referencedModels(
                        javaType, unsupportedPolymorphicTypes.keySet());
                if (!polymorphicTypes.isEmpty()) {
                    throw new IllegalArgumentException("Unsupported cascading validation boundary for schema '"
                            + model.classname + "', property '" + property.baseName + "', mapped Java type '"
                            + javaType + "': polymorphic schema type(s) " + polymorphicTypes
                            + " contain constrained runtime subtype(s) "
                            + constrainedRuntimeTypes(polymorphicTypes, unsupportedPolymorphicTypes) + ", "
                            + "but Helidon 4.5 cannot dispatch a static @Validation.Valid boundary to the runtime "
                            + "subtype. Use a concrete property DTO or validate the polymorphic value in "
                            + "application logic.");
                }
            }
        }
    }

    private static Set<String> constrainedRuntimeTypes(
            Set<String> polymorphicTypes,
            Map<String, Set<String>> unsupportedPolymorphicTypes) {
        Set<String> result = new java.util.TreeSet<>();
        polymorphicTypes.forEach(type -> result.addAll(unsupportedPolymorphicTypes.getOrDefault(type, Set.of())));
        return immutableSortedSet(result);
    }

    private static Set<String> immutableSortedSet(Set<String> values) {
        return java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(values));
    }

    private static Inheritance inheritedValidationProperties(
            List<CodegenModel> models,
            Map<String, List<CodegenProperty>> propertiesByModel,
            Set<String> modelNames,
            Set<String> participating,
            BiPredicate<CodegenModel, CodegenProperty> directlyConstrained) {
        Map<String, CodegenModel> modelsByName = models.stream()
                .collect(java.util.stream.Collectors.toMap(model -> model.classname, model -> model));
        Map<String, List<CodegenProperty>> accessorsByModel = new HashMap<>();
        Map<String, Map<String, List<CodegenProperty>>> overriddenByModel = new HashMap<>();
        for (CodegenModel model : models) {
            Set<String> localNames = propertiesByModel.getOrDefault(model.classname, List.of()).stream()
                    .map(property -> property.name)
                    .collect(java.util.stream.Collectors.toSet());
            Map<String, CodegenProperty> inherited = new LinkedHashMap<>();
            Map<String, List<CodegenProperty>> overridden = new LinkedHashMap<>();
            Set<String> visited = new LinkedHashSet<>();
            Object parentValue = model.vendorExtensions.get("x-extends-model");
            while (parentValue != null && visited.add(parentValue.toString())) {
                String parent = parentValue.toString();
                for (CodegenProperty property : propertiesByModel.getOrDefault(parent, List.of())) {
                    boolean cascades = ValidationTypeSupport.referencedModels(propertyType(property), modelNames)
                            .stream()
                            .anyMatch(participating::contains);
                    if (localNames.contains(property.name)) {
                        if (directlyConstrained.test(modelsByName.get(parent), property) || cascades) {
                            overridden.computeIfAbsent(property.name, ignored -> new ArrayList<>()).add(property);
                        }
                    } else if (directlyConstrained.test(modelsByName.get(parent), property) || cascades) {
                        inherited.putIfAbsent(property.name, property);
                    }
                }
                CodegenModel parentModel = modelsByName.get(parent);
                parentValue = parentModel == null ? null : parentModel.vendorExtensions.get("x-extends-model");
            }
            accessorsByModel.put(model.classname, List.copyOf(inherited.values()));
            Map<String, List<CodegenProperty>> immutableOverridden = new LinkedHashMap<>();
            overridden.forEach((name, properties) -> immutableOverridden.put(name, List.copyOf(properties)));
            overriddenByModel.put(model.classname, Map.copyOf(immutableOverridden));
        }
        return new Inheritance(Map.copyOf(accessorsByModel), Map.copyOf(overriddenByModel));
    }

    private static Map<String, List<CodegenProperty>> effectiveProperties(
            Map<String, List<CodegenProperty>> propertiesByModel,
            Inheritance inheritance) {
        Map<String, List<CodegenProperty>> result = new HashMap<>();
        propertiesByModel.forEach((modelName, localProperties) -> {
            List<CodegenProperty> effective = new ArrayList<>(localProperties);
            effective.addAll(inheritance.accessorsByModel.getOrDefault(modelName, List.of()));
            result.put(modelName, List.copyOf(effective));
        });
        return Map.copyOf(result);
    }

    static void validateShapes(List<CodegenModel> models,
                               Map<String, List<CodegenProperty>> propertiesByModel,
                               Set<String> modelNames,
                               Set<String> participating) {
        for (CodegenModel model : models) {
            for (CodegenProperty property : propertiesByModel.getOrDefault(model.classname, List.of())) {
                String javaType = propertyType(property);
                ValidationTypeSupport.unsupportedContainer(javaType, modelNames, participating)
                        .ifPresent(unsupported -> {
                            throw new IllegalArgumentException("Unsupported cascading validation shape for schema '"
                                    + model.classname + "', property '" + property.baseName + "': mapped Java type '"
                                    + javaType + "' contains participating generated model type(s) "
                                    + unsupported.participatingModels() + " inside unsupported container '"
                                    + unsupported.rawType() + "'. Cascading cannot be guaranteed for arbitrary "
                                    + "Iterable or custom container mappings. Map the property to List, Set, "
                                    + "Collection, Optional, Map values, or an array.");
                        });
            }
        }
    }

    static void validateAcyclicGraph(Map<String, List<CodegenProperty>> propertiesByModel,
                                     Set<String> modelNames,
                                     Set<String> participating) {
        Map<String, List<ValidationEdge>> graph = validationGraph(propertiesByModel, modelNames, participating);
        Map<String, VisitState> states = new HashMap<>();
        List<String> nodes = new ArrayList<>(participating);
        nodes.sort(Comparator.naturalOrder());
        for (String node : nodes) {
            if (states.getOrDefault(node, VisitState.NEW) == VisitState.NEW) {
                findValidationCycle(node, graph, states, new ArrayList<>(), new ArrayList<>());
            }
        }
    }

    private static Map<String, List<ValidationEdge>> validationGraph(
            Map<String, List<CodegenProperty>> propertiesByModel,
            Set<String> modelNames,
            Set<String> participating) {
        Map<String, List<ValidationEdge>> graph = new HashMap<>();
        for (String model : participating) {
            List<ValidationEdge> edges = new ArrayList<>();
            for (CodegenProperty property : propertiesByModel.getOrDefault(model, List.of())) {
                String javaType = propertyType(property);
                ValidationTypeSupport.referencedModels(javaType, modelNames).stream()
                        .filter(participating::contains)
                        .sorted()
                        .map(target -> new ValidationEdge(model, property.baseName, javaType, target))
                        .forEach(edges::add);
            }
            graph.put(model, List.copyOf(edges));
        }
        return graph;
    }

    private static void findValidationCycle(String node,
                                            Map<String, List<ValidationEdge>> graph,
                                            Map<String, VisitState> states,
                                            List<String> nodeStack,
                                            List<ValidationEdge> edgeStack) {
        states.put(node, VisitState.VISITING);
        nodeStack.add(node);
        for (ValidationEdge edge : graph.getOrDefault(node, List.of())) {
            VisitState targetState = states.getOrDefault(edge.target, VisitState.NEW);
            if (targetState == VisitState.VISITING) {
                throw recursiveValidationError(edge, nodeStack, edgeStack);
            }
            if (targetState == VisitState.NEW) {
                edgeStack.add(edge);
                findValidationCycle(edge.target, graph, states, nodeStack, edgeStack);
                edgeStack.remove(edgeStack.size() - 1);
            }
        }
        nodeStack.remove(nodeStack.size() - 1);
        states.put(node, VisitState.VISITED);
    }

    private static IllegalArgumentException recursiveValidationError(ValidationEdge closingEdge,
                                                                     List<String> nodeStack,
                                                                     List<ValidationEdge> edgeStack) {
        int cycleStart = nodeStack.indexOf(closingEdge.target);
        List<ValidationEdge> cycle = new ArrayList<>(edgeStack.subList(cycleStart, edgeStack.size()));
        cycle.add(closingEdge);
        StringBuilder path = new StringBuilder();
        for (ValidationEdge edge : cycle) {
            if (!path.isEmpty()) {
                path.append(" -> ");
            }
            path.append(edge.source).append('.').append(edge.property)
                    .append(" (").append(edge.javaType).append(')');
        }
        path.append(" -> ").append(cycle.get(0).source);
        ValidationEdge first = cycle.get(0);
        return new IllegalArgumentException("Unsupported recursive cascading validation graph: schema '"
                + first.source + "', property '" + first.property + "', mapped Java type '" + first.javaType
                + "' references participating generated model '" + first.target + "'. Validation cycle: " + path
                + ". Helidon validation creates eager TypeValidator dependencies for @Validation.Valid boundaries, "
                + "so recursive participating schemas cannot be activated. Remove the validation cycle, map the "
                + "recursive boundary to a non-validating DTO, or validate that boundary in application logic.");
    }

    static void apply(CodegenProperty property,
                      Set<String> modelNames,
                      Set<String> participating,
                      String modelPackage) {
        String javaType = propertyType(property);
        if (ValidationTypeSupport.isDirectParticipatingModel(javaType, modelNames, participating)) {
            property.vendorExtensions.put("x-cascade-validation", Boolean.TRUE);
        } else {
            String annotatedType = ValidationTypeSupport.annotatedType(javaType,
                                                                        modelNames,
                                                                        participating,
                                                                        modelPackage);
            if (!annotatedType.equals(javaType)) {
                property.vendorExtensions.put("x-validation-datatype", annotatedType);
            }
        }
    }

    static boolean apply(CodegenParameter parameter,
                         Analysis analysis,
                         String modelPackage) {
        String javaType = parameter.dataType;
        if (ValidationTypeSupport.referencedModels(javaType, analysis.modelNames).stream()
                .anyMatch(analysis.participatingModels::contains)) {
            validateRequestNullability(parameter, javaType);
        }
        Set<String> unsupportedPolymorphicTypes = ValidationTypeSupport.referencedModels(
                javaType, analysis.unsupportedPolymorphicTypes.keySet());
        if (!unsupportedPolymorphicTypes.isEmpty()) {
            throw new IllegalArgumentException("Unsupported cascading validation request entity '"
                    + parameter.baseName + "', mapped Java type '" + javaType + "': polymorphic schema type(s) "
                    + unsupportedPolymorphicTypes + " contain constrained runtime subtype(s) "
                    + constrainedRuntimeTypes(unsupportedPolymorphicTypes, analysis.unsupportedPolymorphicTypes)
                    + ", but Helidon 4.5 cannot dispatch a static @Validation.Valid boundary to the runtime subtype. "
                    + "Use a concrete request DTO or validate the polymorphic value in application logic.");
        }
        ValidationTypeSupport.unsupportedContainer(javaType, analysis.modelNames, analysis.participatingModels)
                .ifPresent(unsupported -> {
                    throw new IllegalArgumentException("Unsupported cascading validation request entity '"
                            + parameter.baseName + "', mapped Java type '" + javaType
                            + "' contains participating generated model type(s) "
                            + unsupported.participatingModels() + " inside unsupported container '"
                            + unsupported.rawType() + "'. Use List, Set, Collection, Optional, Map values, or an array.");
                });
        if (ValidationTypeSupport.isDirectParticipatingModel(javaType,
                                                             analysis.modelNames,
                                                             analysis.participatingModels)) {
            parameter.vendorExtensions.put("x-cascade-validation", Boolean.TRUE);
            return true;
        }
        String annotatedType = ValidationTypeSupport.annotatedType(javaType,
                                                                   analysis.modelNames,
                                                                   analysis.participatingModels,
                                                                   modelPackage);
        if (!annotatedType.equals(javaType)) {
            parameter.vendorExtensions.put("x-validation-datatype", annotatedType);
            return true;
        }
        return false;
    }

    private static void validateRequestNullability(CodegenParameter parameter, String javaType) {
        if (!parameter.requiredAndNotNullable()) {
            throw new IllegalArgumentException("Unsupported nullable cascading validation request entity '"
                    + parameter.baseName + "', mapped Java type '" + javaType + "'. Helidon 4.5 invokes "
                    + "@Validation.Valid validators eagerly and does not guard null. Make the request body required "
                    + "and non-nullable or validate it in application logic.");
        }
        validateNestedRequestNullability(parameter.items, parameter.baseName, javaType);
        validateNestedRequestNullability(parameter.additionalProperties, parameter.baseName, javaType);
    }

    private static void validateNestedRequestNullability(CodegenProperty property,
                                                          String parameterName,
                                                          String javaType) {
        if (property == null) {
            return;
        }
        if (property.isModel && property.isNullable) {
            throw new IllegalArgumentException("Unsupported nullable cascading validation request entity '"
                    + parameterName + "', mapped Java type '" + javaType + "': a nested model element or map value "
                    + "is nullable, but Helidon 4.5 invokes @Validation.Valid validators eagerly without a null "
                    + "guard. Make nested model values non-nullable or validate the boundary in application logic.");
        }
        validateNestedRequestNullability(property.items, parameterName, javaType);
        validateNestedRequestNullability(property.additionalProperties, parameterName, javaType);
    }

    static void applyInherited(CodegenModel model,
                               Analysis analysis,
                               String modelPackage,
                               Function<CodegenProperty, List<Map<String, Object>>> validationAnnotations) {
        List<CodegenProperty> inherited = analysis.inheritedPropertiesByModel
                .getOrDefault(model.classname, List.of());
        for (CodegenProperty property : inherited) {
            List<Map<String, Object>> annotations = validationAnnotations.apply(property);
            if (!annotations.isEmpty()) {
                property.vendorExtensions.put("x-validation-annotations", annotations);
            }
            apply(property, analysis.modelNames, analysis.participatingModels, modelPackage);
        }
        if (!inherited.isEmpty()) {
            model.vendorExtensions.put("x-inherited-validation-vars", inherited);
        }
    }

    static List<CodegenProperty> validationSources(CodegenModel model,
                                                   CodegenProperty localProperty,
                                                   Analysis analysis) {
        List<CodegenProperty> result = new ArrayList<>(analysis.overriddenPropertiesByModel
                                                               .getOrDefault(model.classname, Map.of())
                                                               .getOrDefault(localProperty.name, List.of()));
        result.add(localProperty);
        return List.copyOf(result);
    }

    static void addRequestEntityImports(OperationsMap operations, List<CodegenOperation> operationList) {
        boolean usesMap = operationList.stream()
                .flatMap(operation -> operation.allParams.stream())
                .map(parameter -> parameter.dataType)
                .anyMatch(type -> type != null && (type.startsWith("Map<") || type.startsWith("java.util.Map<")));
        Map<String, String> mapImport = Map.of("import", "java.util.Map");
        if (usesMap && !operations.getImports().contains(mapImport)) {
            operations.getImports().add(mapImport);
        }
    }

    static void addValidationImport(ModelsMap modelsMap) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> imports = (List<Map<String, String>>) modelsMap.get("imports");
        if (imports == null) {
            return;
        }
        imports.removeIf(importEntry -> {
            String importName = importEntry.get("import");
            return importName != null && importName.indexOf('[') >= 0;
        });
        Map<String, String> validationImport = Map.of("import", "io.helidon.validation.Validation");
        if (!imports.contains(validationImport)) {
            imports.add(validationImport);
        }
    }

    private static String propertyType(CodegenProperty property) {
        return property.datatypeWithEnum != null && !property.datatypeWithEnum.isBlank()
                ? property.datatypeWithEnum
                : property.dataType;
    }

    @SuppressWarnings("unchecked")
    private static List<CodegenProperty> renderVars(CodegenModel model) {
        Object renderVars = model.vendorExtensions.get("x-render-vars");
        return renderVars instanceof List<?> vars ? (List<CodegenProperty>) vars : model.vars;
    }

    record Analysis(Set<String> modelNames,
                    Map<String, List<CodegenProperty>> propertiesByModel,
                    Set<String> participatingModels,
                    Map<String, Set<String>> unsupportedPolymorphicTypes,
                    Map<String, List<CodegenProperty>> inheritedPropertiesByModel,
                    Map<String, Map<String, List<CodegenProperty>>> overriddenPropertiesByModel) {
        static Analysis empty() {
            return new Analysis(Set.of(), Map.of(), Set.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private record Inheritance(Map<String, List<CodegenProperty>> accessorsByModel,
                               Map<String, Map<String, List<CodegenProperty>>> overriddenByModel) {
    }

    private record ValidationEdge(String source, String property, String javaType, String target) {
    }

    private enum VisitState {
        NEW,
        VISITING,
        VISITED
    }
}
