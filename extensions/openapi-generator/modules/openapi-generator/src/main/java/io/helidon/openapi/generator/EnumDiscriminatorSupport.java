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

    void validate(CodegenModel mappingOwner,
                  String key,
                  DiscriminatorSupport.Property declaration,
                  String alias,
                  String subtype) {
        if (declaration == null) {
            return;
        }
        CodegenProperty property = declaration.property();
        if (!property.isEnum && !property.isEnumRef) {
            return;
        }
        String type = property.datatypeWithEnum == null ? property.dataType : property.datatypeWithEnum;
        String location = "Schema '" + mappingOwner.schemaName + "', discriminator property '" + key
                + "', subtype '" + subtype + "': ";
        if (type == null || type.isBlank()) {
            errors.fatal(location + "enum discriminator must have a resolved Java type");
        }
        List<String> values = enumValues(property);
        if (values.isEmpty()) {
            errors.fatal(location + "enum discriminator must resolve a declaration with string enum values");
        } else if (!values.contains(alias)) {
            errors.fatal(location + "discriminator alias must be declared by enum '"
                                 + DiscriminatorSupport.qualifiedType(declaration) + "'");
        }
    }

    void checkValid() {
        errors.collect().checkValid();
    }

    // Called only after every discriminator relationship has passed validation.
    String valueExpression(DiscriminatorSupport.Property declaration, String alias) {
        String literal = JavaStringLiterals.toJavaStringLiteral(alias);
        if (declaration == null) {
            return literal;
        }
        CodegenProperty property = declaration.property();
        if (!property.isEnum && !property.isEnumRef) {
            return literal;
        }
        String type = DiscriminatorSupport.qualifiedType(declaration);
        return property.isEnumRef
                ? type + ".fromValue(" + literal + ")"
                : type + "." + enumVarNamer.apply(alias, "String");
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
