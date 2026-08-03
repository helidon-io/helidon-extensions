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
}
