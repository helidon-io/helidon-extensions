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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelsMap;

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
        validateAcyclicGraph(propertiesByModel, modelNames, participating);
        return new Analysis(modelNames, Map.copyOf(propertiesByModel), participating);
    }

    static Set<String> participatingModels(List<CodegenModel> models,
                                           Map<String, List<CodegenProperty>> propertiesByModel,
                                           Set<String> modelNames,
                                           Set<String> directlyConstrainedModels) {
        Set<String> participating = new LinkedHashSet<>(directlyConstrainedModels);
        boolean changed;
        do {
            changed = false;
            for (CodegenModel model : models) {
                if (participating.contains(model.classname)) {
                    continue;
                }
                for (CodegenProperty property : propertiesByModel.getOrDefault(model.classname, List.of())) {
                    Set<String> referencedModels = ValidationTypeSupport.referencedModels(propertyType(property),
                                                                                           modelNames);
                    if (referencedModels.stream().anyMatch(participating::contains)) {
                        changed |= participating.add(model.classname);
                        break;
                    }
                }
                Object parent = model.vendorExtensions.get("x-extends-model");
                if (parent != null && modelNames.contains(parent.toString())
                        && participating.contains(parent.toString())) {
                    changed |= participating.add(model.classname);
                }
            }
        } while (changed);
        return Set.copyOf(participating);
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

    static boolean apply(CodegenParameter parameter, Set<String> participating) {
        String javaType = parameter.dataType;
        if (ValidationTypeSupport.isDirectParticipatingModel(javaType, participating, participating)) {
            parameter.vendorExtensions.put("x-cascade-validation", Boolean.TRUE);
            return true;
        }
        String annotatedType = ValidationTypeSupport.annotatedType(javaType, participating, participating);
        if (!annotatedType.equals(javaType)) {
            parameter.vendorExtensions.put("x-validation-datatype", annotatedType);
            return true;
        }
        return false;
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
                    Set<String> participatingModels) {
    }

    private record ValidationEdge(String source, String property, String javaType, String target) {
    }

    private enum VisitState {
        NEW,
        VISITING,
        VISITED
    }
}
