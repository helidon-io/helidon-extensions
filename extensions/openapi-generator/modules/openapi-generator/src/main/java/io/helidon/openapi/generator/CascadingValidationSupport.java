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
import java.util.function.Function;
import java.util.function.Predicate;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationsMap;

final class CascadingValidationSupport {

    private CascadingValidationSupport() {
    }

    static Analysis analyze(List<CodegenModel> models,
                            Set<String> modelNames,
                            Predicate<CodegenProperty> directlyConstrained) {
        Map<String, List<CodegenProperty>> propertiesByModel = new HashMap<>();
        Set<String> directlyConstrainedModels = new LinkedHashSet<>();
        for (CodegenModel model : models) {
            List<CodegenProperty> properties = Boolean.TRUE.equals(model.vendorExtensions.get("x-is-union-interface"))
                    ? List.of()
                    : renderVars(model);
            propertiesByModel.put(model.classname, properties);
            if (properties.stream().anyMatch(directlyConstrained)) {
                directlyConstrainedModels.add(model.classname);
            }
        }
        Set<String> participating = participatingModels(models, propertiesByModel,
                                                        modelNames, directlyConstrainedModels);
        validateShapes(models, propertiesByModel, modelNames, participating);
        validateDirectBoundaries(models, propertiesByModel, modelNames, participating);
        validateAcyclicGraph(propertiesByModel, modelNames, participating);
        Set<String> unsupportedUnionTypes = unsupportedUnionTypes(models, participating);
        validateUnionProperties(models, propertiesByModel, unsupportedUnionTypes);
        Map<String, List<CodegenProperty>> inheritedProperties = inheritedValidationProperties(models,
                                                                                                propertiesByModel,
                                                                                                modelNames,
                                                                                                participating,
                                                                                                directlyConstrained);
        return new Analysis(modelNames,
                            Map.copyOf(propertiesByModel),
                            participating,
                            unsupportedUnionTypes,
                            inheritedProperties);
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
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> unsupportedUnionTypes(List<CodegenModel> models, Set<String> participating) {
        Set<String> result = new LinkedHashSet<>();
        for (CodegenModel model : models) {
            if (!Boolean.TRUE.equals(model.vendorExtensions.get("x-is-union-interface"))) {
                continue;
            }
            Object membersValue = model.vendorExtensions.get("x-union-members");
            if (!(membersValue instanceof List<?> members)) {
                continue;
            }
            boolean constrainedMember = members.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(member -> member.get("name"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .anyMatch(participating::contains);
            if (constrainedMember) {
                result.add(model.classname);
            }
        }
        return Set.copyOf(result);
    }

    private static void validateUnionProperties(List<CodegenModel> models,
                                                Map<String, List<CodegenProperty>> propertiesByModel,
                                                Set<String> unsupportedUnionTypes) {
        for (CodegenModel model : models) {
            for (CodegenProperty property : propertiesByModel.getOrDefault(model.classname, List.of())) {
                String javaType = propertyType(property);
                Set<String> unions = ValidationTypeSupport.referencedModels(javaType, unsupportedUnionTypes);
                if (!unions.isEmpty()) {
                    throw new IllegalArgumentException("Unsupported cascading validation boundary for schema '"
                            + model.classname + "', property '" + property.baseName + "', mapped Java type '"
                            + javaType + "': composed schema type(s) " + unions + " contain constrained members, "
                            + "but Helidon 4.5 cannot dispatch a static @Validation.Valid boundary to the runtime "
                            + "subtype. Use a concrete property DTO or validate the union in application logic.");
                }
            }
        }
    }

    private static Map<String, List<CodegenProperty>> inheritedValidationProperties(
            List<CodegenModel> models,
            Map<String, List<CodegenProperty>> propertiesByModel,
            Set<String> modelNames,
            Set<String> participating,
            Predicate<CodegenProperty> directlyConstrained) {
        Map<String, CodegenModel> modelsByName = models.stream()
                .collect(java.util.stream.Collectors.toMap(model -> model.classname, model -> model));
        Map<String, List<CodegenProperty>> result = new HashMap<>();
        for (CodegenModel model : models) {
            Set<String> localNames = propertiesByModel.getOrDefault(model.classname, List.of()).stream()
                    .map(property -> property.name)
                    .collect(java.util.stream.Collectors.toSet());
            Map<String, CodegenProperty> inherited = new LinkedHashMap<>();
            Set<String> visited = new LinkedHashSet<>();
            Object parentValue = model.vendorExtensions.get("x-extends-model");
            while (parentValue != null && visited.add(parentValue.toString())) {
                String parent = parentValue.toString();
                for (CodegenProperty property : propertiesByModel.getOrDefault(parent, List.of())) {
                    boolean cascades = ValidationTypeSupport.referencedModels(propertyType(property), modelNames)
                            .stream()
                            .anyMatch(participating::contains);
                    if (!localNames.contains(property.name) && (directlyConstrained.test(property) || cascades)) {
                        inherited.putIfAbsent(property.name, property);
                    }
                }
                CodegenModel parentModel = modelsByName.get(parent);
                parentValue = parentModel == null ? null : parentModel.vendorExtensions.get("x-extends-model");
            }
            result.put(model.classname, List.copyOf(inherited.values()));
        }
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

    static void apply(CodegenProperty property, Set<String> modelNames, Set<String> participating) {
        String javaType = propertyType(property);
        if (ValidationTypeSupport.isDirectParticipatingModel(javaType, modelNames, participating)) {
            property.vendorExtensions.put("x-cascade-validation", Boolean.TRUE);
        } else {
            String annotatedType = ValidationTypeSupport.annotatedType(javaType, modelNames, participating);
            if (!annotatedType.equals(javaType)) {
                property.vendorExtensions.put("x-validation-datatype", annotatedType);
            }
        }
    }

    static boolean apply(CodegenParameter parameter,
                         Analysis analysis) {
        String javaType = parameter.dataType;
        Set<String> unsupportedUnions = ValidationTypeSupport.referencedModels(javaType,
                                                                                analysis.unsupportedUnionTypes);
        if (!unsupportedUnions.isEmpty()) {
            throw new IllegalArgumentException("Unsupported cascading validation request entity '"
                    + parameter.baseName + "', mapped Java type '" + javaType + "': composed schema type(s) "
                    + unsupportedUnions + " contain constrained members, but Helidon 4.5 cannot dispatch a static "
                    + "@Validation.Valid boundary to the runtime subtype. Use a concrete request DTO or validate "
                    + "the union in application logic.");
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
                                                                   analysis.participatingModels);
        if (!annotatedType.equals(javaType)) {
            parameter.vendorExtensions.put("x-validation-datatype", annotatedType);
            return true;
        }
        return false;
    }

    static void applyInherited(CodegenModel model,
                               Analysis analysis,
                               Function<CodegenProperty, List<Map<String, Object>>> validationAnnotations) {
        List<CodegenProperty> inherited = analysis.inheritedPropertiesByModel
                .getOrDefault(model.classname, List.of());
        for (CodegenProperty property : inherited) {
            List<Map<String, Object>> annotations = validationAnnotations.apply(property);
            if (!annotations.isEmpty()) {
                property.vendorExtensions.put("x-validation-annotations", annotations);
            }
            apply(property, analysis.modelNames, analysis.participatingModels);
        }
        if (!inherited.isEmpty()) {
            model.vendorExtensions.put("x-inherited-validation-vars", inherited);
        }
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
                    Set<String> unsupportedUnionTypes,
                    Map<String, List<CodegenProperty>> inheritedPropertiesByModel) {
        static Analysis empty() {
            return new Analysis(Set.of(), Map.of(), Set.of(), Set.of(), Map.of());
        }
    }

    private record ValidationEdge(String source, String property, String javaType, String target) {
    }

    private enum VisitState {
        NEW,
        VISITING,
        VISITED
    }
}
