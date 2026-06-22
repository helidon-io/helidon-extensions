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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openapitools.codegen.CodegenModel;

final class SerializationLibrarySupport {
    private SerializationLibrarySupport() {
    }

    static String normalize(Object value) {
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (!Set.of("helidon", "jsonb", "jackson").contains(text)) {
            throw new IllegalArgumentException("serializationLibrary must be one of helidon, jsonb, or jackson, but was '"
                                                       + value + "'");
        }
        return text;
    }

    static void rejectUnsupportedUnions(String serializationLibrary, List<CodegenModel> models) {
        if ("helidon".equals(serializationLibrary)) {
            return;
        }
        for (CodegenModel model : models) {
            if ("jsonb".equals(serializationLibrary)
                    && (Boolean.TRUE.equals(model.vendorExtensions.get("x-is-union-interface"))
                    || Boolean.TRUE.equals(model.vendorExtensions.get("x-has-polymorphic-subtypes")))) {
                throw new IllegalArgumentException("serializationLibrary=jsonb does not support composed schema "
                                                           + model.classname
                                                           + "; use serializationLibrary=helidon for structural "
                                                           + "oneOf/anyOf or serializationLibrary=jackson for "
                                                           + "discriminator-based oneOf/anyOf");
            }
            if ("jackson".equals(serializationLibrary)
                    && Boolean.TRUE.equals(model.vendorExtensions.get("x-is-union-interface"))
                    && !Boolean.TRUE.equals(model.vendorExtensions.get("x-has-union-discriminator"))) {
                throw new IllegalArgumentException("serializationLibrary=" + serializationLibrary
                                                           + " requires a discriminator for composed schema "
                                                           + model.classname
                                                           + "; structural oneOf/anyOf unions are supported only with "
                                                           + "serializationLibrary=helidon");
            }
        }
    }

    static Set<String> discriminatorPropertyNames(CodegenModel model,
                                                  Map<String, CodegenModel> modelsByClassname,
                                                  Map<String, LinkedHashSet<String>> unionInterfacesByMember) {
        Set<String> result = new LinkedHashSet<>();
        addDiscriminatorPropertyName(result, model.vendorExtensions.get("x-polymorphic-key"));
        addDiscriminatorPropertyName(result, model.vendorExtensions.get("x-allof-discriminator-key"));
        if (model.discriminator != null) {
            addDiscriminatorPropertyName(result, model.discriminator.getPropertyBaseName());
            addDiscriminatorPropertyName(result, model.discriminator.getPropertyName());
        }

        Set<String> unionInterfaces = unionInterfacesByMember.get(model.classname);
        if (unionInterfaces != null) {
            for (String unionInterface : unionInterfaces) {
                CodegenModel unionModel = modelsByClassname.get(unionInterface);
                if (unionModel != null) {
                    addDiscriminatorPropertyName(result, unionModel.vendorExtensions.get("x-union-discriminator-key"));
                }
            }
        }
        return result;
    }

    private static void addDiscriminatorPropertyName(Set<String> names, Object name) {
        if (name != null && !name.toString().isBlank()) {
            names.add(name.toString());
        }
    }
}
