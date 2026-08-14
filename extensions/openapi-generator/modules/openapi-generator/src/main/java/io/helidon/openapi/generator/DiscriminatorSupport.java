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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;

final class DiscriminatorSupport {
    private DiscriminatorSupport() {
    }

    static Property inheritedProperty(CodegenModel model,
                                      String discriminatorKey,
                                      Map<String, CodegenModel> modelsByClassname,
                                      Function<CodegenModel, List<CodegenProperty>> renderVars,
                                      BiFunction<List<CodegenProperty>, String, CodegenProperty> propertyFinder) {
        Set<String> visited = new LinkedHashSet<>();
        CodegenModel current = model;
        while (current != null && visited.add(current.classname)) {
            CodegenProperty local = propertyFinder.apply(renderVars.apply(current), discriminatorKey);
            if (local != null) {
                return new Property(current, local);
            }
            Object parent = current.vendorExtensions.get("x-extends-model");
            current = parent instanceof String parentName ? modelsByClassname.get(parentName) : null;
        }
        List<CodegenProperty> inheritedProperties = model.allVars == null || model.allVars.isEmpty()
                ? model.vars
                : model.allVars;
        CodegenProperty inherited = propertyFinder.apply(inheritedProperties, discriminatorKey);
        return inherited == null ? null : new Property(model, inherited);
    }

    static Property commonProperty(String discriminatorKey,
                                   List<Property> properties,
                                   Function<String, IllegalStateException> failure) {
        if (properties.isEmpty()) {
            return null;
        }
        Property first = properties.getFirst();
        String expectedType = qualifiedType(first);
        for (Property candidate : properties) {
            if (!expectedType.equals(qualifiedType(candidate))) {
                throw failure.apply("members expose incompatible Java types for discriminator property '"
                                            + discriminatorKey + "': '" + expectedType + "' and '"
                                            + qualifiedType(candidate) + "'");
            }
        }
        return first;
    }

    static String qualifiedType(Property discriminatorProperty) {
        CodegenProperty property = discriminatorProperty.property();
        String type = property.datatypeWithEnum == null ? property.dataType : property.datatypeWithEnum;
        return property.isEnum ? discriminatorProperty.owner().classname + "." + type : type;
    }

    static String sanitizeDiagnosticReference(String value) {
        if (value == null || value.isBlank() || value.startsWith("#/")) {
            return value;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return value.contains("://") ? "<redacted URI>" : value;
        }
        if (uri.getScheme() == null) {
            return value;
        }
        if (uri.isOpaque()) {
            return uri.getScheme() + ":<redacted>";
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, uri.getFragment())
                    .toString();
        } catch (Exception ignored) {
            return uri.getScheme() + ":<redacted>";
        }
    }

    record Property(CodegenModel owner, CodegenProperty property) {
    }

    static final class Hierarchy {
        private final Map<String, List<String>> subtypeNamesByParent;
        private final Map<String, CodegenModel> modelsByClassname;
        private final Function<CodegenModel, String> discriminatorKey;

        Hierarchy(Map<String, List<String>> subtypeNamesByParent,
                  Map<String, CodegenModel> modelsByClassname,
                  Function<CodegenModel, String> discriminatorKey) {
            this.subtypeNamesByParent = subtypeNamesByParent;
            this.modelsByClassname = modelsByClassname;
            this.discriminatorKey = discriminatorKey;
        }

        List<String> concreteSubtypeNames(String parentType) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            collectConcreteSubtypeNames(parentType, new LinkedHashSet<>(), result);
            return new ArrayList<>(result);
        }

        List<String> descendantNames(String parentType) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            collectDescendantNames(parentType, new LinkedHashSet<>(), result);
            return new ArrayList<>(result);
        }

        int depth(String parentType) {
            return depth(parentType, new LinkedHashSet<>());
        }

        Map<String, String> descendantAliases(String parentType,
                                              List<String> concreteSubtypeNames,
                                              Map<String, Map<String, String>> aliasesByParent) {
            String key = discriminatorKey.apply(modelsByClassname.get(parentType));
            Map<String, String> result = new LinkedHashMap<>();
            List<String> descendants = descendantNames(parentType);
            for (String concreteSubtypeName : concreteSubtypeNames) {
                String selectedAlias = null;
                int selectedDepth = -1;
                for (String descendant : descendants) {
                    Map<String, String> aliases = aliasesByParent.get(descendant);
                    if (aliases == null
                            || !key.equals(discriminatorKey.apply(modelsByClassname.get(descendant)))
                            || !aliases.containsKey(concreteSubtypeName)) {
                        continue;
                    }
                    int depth = depth(descendant);
                    if (depth > selectedDepth) {
                        selectedAlias = aliases.get(concreteSubtypeName);
                        selectedDepth = depth;
                    }
                }
                if (selectedAlias != null) {
                    result.put(concreteSubtypeName, selectedAlias);
                }
            }
            return result;
        }

        List<String> mappingTargets(String target, List<String> concreteSubtypeNames) {
            if (target == null || !modelsByClassname.containsKey(target)) {
                return List.of();
            }
            if (concreteSubtypeNames.contains(target)) {
                return List.of(target);
            }
            if (!subtypeNamesByParent.containsKey(target)) {
                return List.of();
            }
            List<String> descendants = concreteSubtypeNames(target);
            return concreteSubtypeNames.containsAll(descendants) ? descendants : List.of();
        }

        private int depth(String parentType, Set<String> visiting) {
            checkCycle(parentType, visiting);
            int depth = 0;
            for (String subtypeName : subtypeNamesByParent.getOrDefault(parentType, List.of())) {
                depth = Math.max(depth, 1 + depth(subtypeName, visiting));
            }
            visiting.remove(parentType);
            return depth;
        }

        private void collectDescendantNames(String parentType, Set<String> visiting, Set<String> result) {
            checkCycle(parentType, visiting);
            for (String subtypeName : subtypeNamesByParent.getOrDefault(parentType, List.of())) {
                result.add(subtypeName);
                collectDescendantNames(subtypeName, visiting, result);
            }
            visiting.remove(parentType);
        }

        private void collectConcreteSubtypeNames(String parentType, Set<String> visiting, Set<String> result) {
            checkCycle(parentType, visiting);
            for (String subtypeName : subtypeNamesByParent.getOrDefault(parentType, List.of())) {
                if (subtypeNamesByParent.containsKey(subtypeName)) {
                    collectConcreteSubtypeNames(subtypeName, visiting, result);
                } else {
                    result.add(subtypeName);
                }
            }
            visiting.remove(parentType);
        }

        private void checkCycle(String modelName, Set<String> visiting) {
            if (!visiting.add(modelName)) {
                throw new IllegalStateException("Cyclic allOf discriminator hierarchy involving model '"
                                                        + modelName + "'");
            }
        }
    }
}
