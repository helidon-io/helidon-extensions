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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;

final class UnionBranchConstraints {

    private UnionBranchConstraints() {
    }

    static String toConstraintListLiteral(CodegenModel model) {
        if (model == null || model.allVars == null || model.allVars.isEmpty()) {
            return "List.of()";
        }

        List<String> constraints = model.allVars.stream()
                .map(UnionBranchConstraints::toConstraintLiteral)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (constraints.isEmpty()) {
            return "List.of()";
        }
        return "List.of(" + String.join(", ", constraints) + ")";
    }

    private static String toConstraintLiteral(CodegenProperty property) {
        String jsonType = jsonType(property);
        List<String> enumValues = enumValues(property);
        Integer minLength = property.minLength;
        Integer maxLength = property.maxLength;
        String pattern = blankToNull(property.pattern);
        String minimum = numericConstraint(property, property.minimum);
        String maximum = numericConstraint(property, property.maximum);
        String multipleOf = property.multipleOf != null ? numericConstraint(property, property.multipleOf.toString()) : null;
        Integer minItems = property.isArray ? property.minItems : null;
        Integer maxItems = property.isArray ? property.maxItems : null;

        if (jsonType == null
                && enumValues.isEmpty()
                && minLength == null
                && maxLength == null
                && pattern == null
                && minimum == null
                && maximum == null
                && multipleOf == null
                && minItems == null
                && maxItems == null) {
            return null;
        }

        return "new UnionConstraint("
                + JavaStringLiterals.toJavaStringLiteral(property.name)
                + ", " + jsonTypeLiteral(jsonType)
                + ", " + property.isNullable
                + ", " + toStringArrayLiteral(enumValues)
                + ", " + integerLiteral(minLength)
                + ", " + integerLiteral(maxLength)
                + ", " + stringLiteral(pattern)
                + ", " + bigDecimalLiteral(minimum)
                + ", " + property.exclusiveMinimum
                + ", " + bigDecimalLiteral(maximum)
                + ", " + property.exclusiveMaximum
                + ", " + bigDecimalLiteral(multipleOf)
                + ", " + integerLiteral(minItems)
                + ", " + integerLiteral(maxItems)
                + ")";
    }

    private static String jsonType(CodegenProperty property) {
        if (property.isString) {
            return "STRING";
        }
        if (property.isBoolean) {
            return "BOOLEAN";
        }
        if (property.isInteger
                || property.isLong
                || property.isNumber
                || property.isFloat
                || property.isDouble
                || property.isDecimal
                || property.isShort) {
            return "NUMBER";
        }
        if (property.isArray) {
            return "ARRAY";
        }
        if (property.isMap || property.isModel || property.isFreeFormObject) {
            return "OBJECT";
        }
        if (property.isEnum) {
            return "STRING";
        }
        return null;
    }

    private static List<String> enumValues(CodegenProperty property) {
        if (property._enum != null && !property._enum.isEmpty()) {
            return new ArrayList<>(property._enum);
        }

        if (property.allowableValues != null) {
            Object values = property.allowableValues.get("values");
            if (values instanceof Collection<?> collection) {
                return collection.stream()
                        .map(String::valueOf)
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        }
        return List.of();
    }

    private static String numericConstraint(CodegenProperty property, String value) {
        if (value == null
                || !(property.isInteger
                || property.isLong
                || property.isNumber
                || property.isFloat
                || property.isDouble
                || property.isDecimal
                || property.isShort)) {
            return null;
        }
        try {
            return new BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String jsonTypeLiteral(String jsonType) {
        if (jsonType == null) {
            return "null";
        }
        return "JsonValueType." + jsonType.toUpperCase(Locale.ROOT);
    }

    private static String toStringArrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "new String[0]";
        }
        return "new String[] {"
                + values.stream()
                        .map(UnionBranchConstraints::enumValueLiteral)
                        .collect(Collectors.joining(", "))
                + "}";
    }

    private static String enumValueLiteral(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return JavaStringLiterals.toJavaStringLiteral(value);
        }
        return value;
    }

    private static String integerLiteral(Integer value) {
        return value == null ? "null" : value.toString();
    }

    private static String stringLiteral(String value) {
        return value == null ? "null" : JavaStringLiterals.toJavaStringLiteral(value);
    }

    private static String bigDecimalLiteral(String value) {
        return value == null ? "null" : "new BigDecimal(" + JavaStringLiterals.toJavaStringLiteral(value) + ")";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
