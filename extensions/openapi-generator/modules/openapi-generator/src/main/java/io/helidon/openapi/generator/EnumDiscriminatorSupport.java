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

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.helidon.common.Errors;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;

/** Resolves canonical discriminator expressions and validates enum aliases for one generation run. */
final class EnumDiscriminatorSupport {
    private final Map<String, CodegenModel> modelsByClassname;
    private final BiFunction<String, String, String> enumVarNamer;
    private final Errors.Collector errors = Errors.collector();

    EnumDiscriminatorSupport(Map<String, CodegenModel> modelsByClassname,
                             BiFunction<String, String, String> enumVarNamer) {
        this.modelsByClassname = modelsByClassname;
        this.enumVarNamer = enumVarNamer;
    }

    String valueExpression(CodegenModel owner, String key, String alias, String subtype) {
        String literal = JavaStringLiterals.toJavaStringLiteral(alias);
        CodegenProperty property = DiscriminatorSupport.property(owner, key);
        if (property == null || (!property.isEnum && !property.isEnumRef)) {
            return literal;
        }
        String type = DiscriminatorSupport.qualifiedType(new DiscriminatorSupport.Property(owner, property));
        String location = "Schema '" + owner.schemaName + "', discriminator property '" + key
                + "', subtype '" + subtype + "': ";
        if (type == null || type.isBlank()) {
            errors.fatal(location + "enum discriminator must have a resolved Java type");
        }
        List<String> values = enumValues(property);
        boolean declared = values.contains(alias);
        if (!declared) {
            errors.fatal(location + "discriminator alias must be declared by enum '" + type + "'");
        }
        if (type == null || type.isBlank() || !declared) {
            // Generation stops at checkValid before these staged expressions can be rendered.
            return literal;
        }
        return property.isEnumRef
                ? type + ".fromValue(" + literal + ")"
                : type + "." + enumVarNamer.apply(alias, "String");
    }

    void checkValid() {
        errors.collect().checkValid();
    }

    private List<String> enumValues(CodegenProperty property) {
        List<String> values = DiscriminatorSupport.enumValues(property);
        if (!values.isEmpty() || !property.isEnumRef) {
            return values;
        }
        String type = property.datatypeWithEnum == null ? property.dataType : property.datatypeWithEnum;
        CodegenModel declaration = modelsByClassname.get(type);
        if (declaration == null || declaration.allowableValues == null) {
            return List.of();
        }
        Object declared = declaration.allowableValues.get("values");
        return declared instanceof List<?> enums
                ? enums.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
    }
}
