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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import io.swagger.v3.oas.models.OpenAPI;
import org.openapitools.codegen.model.ModelsMap;

/** Allocates discoverable services independently of supporting-file generation. */
final class ModelRegistryServicesSupport {
    private final Map<String, String> serviceNames = new LinkedHashMap<>();
    private boolean enabled;

    void configure(Map<String, Object> properties, Map<String, String> templates) {
        serviceNames.clear();
        enabled = Boolean.parseBoolean(properties.getOrDefault(
                HelidonDeclarativeCodegen.OPT_MODEL_REGISTRY_SERVICES, false).toString());
        properties.put(HelidonDeclarativeCodegen.OPT_MODEL_REGISTRY_SERVICES, enabled);
        templates.remove("modelRegistryServices.mustache");
        if (enabled) {
            templates.put("modelRegistryServices.mustache", "JsonServices.java");
        }
    }

    String serviceName(String schemaName) {
        String name = serviceNames.get(schemaName);
        if (name == null) {
            throw new IllegalStateException("No model registry service name allocated for '" + schemaName
                                                    + "'; postProcessAllModels must run before emitting services");
        }
        return name;
    }

    void prepare(Map<String, ModelsMap> models, OpenAPI openAPI, UnaryOperator<String> modelNamer) {
        if (!enabled) {
            return;
        }
        Set<String> usedNames = new LinkedHashSet<>();
        if (openAPI != null && openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            openAPI.getComponents().getSchemas().keySet().forEach(name ->
                    usedNames.add(modelNamer.apply(name).toLowerCase(Locale.ROOT)));
        }
        serviceNames.values().forEach(name -> usedNames.add(name.toLowerCase(Locale.ROOT)));
        models.values().forEach(group -> group.getModels().forEach(entry ->
                usedNames.add(entry.getModel().classname.toLowerCase(Locale.ROOT))));
        models.forEach((schemaName, group) -> group.getModels().forEach(entry -> {
            var model = entry.getModel();
            String owner = serviceNames.computeIfAbsent(schemaName, ignored -> {
                String base = model.classname + "JsonServices";
                String candidate = base;
                int suffix = 2;
                while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
                    candidate = base + suffix++;
                }
                return candidate;
            });
            model.vendorExtensions.put("x-model-services-name", owner);
            prepareEnum(model.vendorExtensions.get("x-top-level-string-enum"), owner);
            if (model.vendorExtensions.get("x-inline-string-enums") instanceof List<?> enums) {
                enums.forEach(declaration -> prepareEnum(declaration, owner));
            }
        }));
    }

    @SuppressWarnings("unchecked")
    private void prepareEnum(Object value, String owner) {
        if (value instanceof Map<?, ?> declaration) {
            Map<String, Object> metadata = (Map<String, Object>) declaration;
            String simpleName = (String) metadata.getOrDefault("simpleConverterName", metadata.get("converterName"));
            metadata.put("simpleConverterName", simpleName);
            metadata.put("converterName", owner + "." + simpleName);
            metadata.put("nestedService", true);
            // Model-only generation cannot infer which enums will be used as HTTP parameters by another module.
            metadata.put("httpMapper", true);
        }
    }
}
