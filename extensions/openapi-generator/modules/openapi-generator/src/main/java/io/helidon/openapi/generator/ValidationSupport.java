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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;

final class ValidationSupport {
    private ValidationSupport() {
    }

    static List<Map<String, Object>> buildValidationAnnotations(CodegenProperty prop) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (prop.isString) {
            if (prop.minLength != null || prop.maxLength != null) {
                List<String> attrs = new ArrayList<>();
                if (prop.minLength != null) {
                    attrs.add("min = " + prop.minLength);
                }
                if (prop.maxLength != null) {
                    attrs.add("value = " + prop.maxLength);
                }
                result.add(Map.of("annotation",
                                  "@Validation.String.Length(" + String.join(", ", attrs) + ")"));
            }
            if (prop.pattern != null && !prop.pattern.isEmpty()) {
                String escaped = prop.pattern.replace("\\", "\\\\").replace("\"", "\\\"");
                result.add(Map.of("annotation", "@Validation.String.Pattern(\"" + escaped + "\")"));
            }
        } else if (prop.isInteger) {
            addIntegerBounds(result, prop.minimum, prop.exclusiveMinimum, prop.maximum, prop.exclusiveMaximum);
            Integer multipleOf = toIntMultipleOfValue(prop.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Integer.MultipleOf(" + multipleOf + ")"));
            }
        } else if (prop.isLong) {
            addLongBounds(result, prop.minimum, prop.exclusiveMinimum, prop.maximum, prop.exclusiveMaximum);
            Long multipleOf = toLongMultipleOfValue(prop.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Long.MultipleOf(" + multipleOf + "L)"));
            }
        } else if (prop.isNumber || prop.isFloat || prop.isDouble) {
            if (prop.minimum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Min(\"" + prop.minimum + "\")"));
            }
            if (prop.maximum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Max(\"" + prop.maximum + "\")"));
            }
            String multipleOf = toNumberMultipleOfValue(prop.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Number.MultipleOf(\"" + multipleOf + "\")"));
            }
        }

        if (prop.isArray && (prop.minItems != null || prop.maxItems != null)) {
            List<String> attrs = new ArrayList<>();
            if (prop.minItems != null) {
                attrs.add("min = " + prop.minItems);
            }
            if (prop.maxItems != null) {
                attrs.add("value = " + prop.maxItems);
            }
            result.add(Map.of("annotation",
                              "@Validation.Collection.Size(" + String.join(", ", attrs) + ")"));
        }

        return result;
    }

    static String formatDefaultValue(CodegenProperty prop) {
        if (prop.defaultValue == null || prop.defaultValue.isEmpty()) {
            return null;
        }
        String val = prop.defaultValue;
        if (prop.isEnum) {
            return val;
        }
        if (prop.isString) {
            return "\"" + val.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (prop.isLong) {
            return val + "L";
        }
        if (prop.isFloat) {
            return val + "f";
        }
        if (prop.isArray || prop.isMap) {
            return null;
        }
        return val;
    }

    static List<Map<String, Object>> buildParamValidationAnnotations(CodegenParameter param) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (param.isString) {
            if (param.minLength != null || param.maxLength != null) {
                List<String> attrs = new ArrayList<>();
                if (param.minLength != null) {
                    attrs.add("min = " + param.minLength);
                }
                if (param.maxLength != null) {
                    attrs.add("value = " + param.maxLength);
                }
                result.add(Map.of("annotation",
                                  "@Validation.String.Length(" + String.join(", ", attrs) + ")"));
            }
            if (param.pattern != null && !param.pattern.isEmpty()) {
                String escaped = param.pattern.replace("\\", "\\\\").replace("\"", "\\\"");
                result.add(Map.of("annotation", "@Validation.String.Pattern(\"" + escaped + "\")"));
            }
        } else if (param.isInteger) {
            addIntegerBounds(result, param.minimum, param.exclusiveMinimum, param.maximum, param.exclusiveMaximum);
            Integer multipleOf = toIntMultipleOfValue(param.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Integer.MultipleOf(" + multipleOf + ")"));
            }
        } else if (param.isLong) {
            addLongBounds(result, param.minimum, param.exclusiveMinimum, param.maximum, param.exclusiveMaximum);
            Long multipleOf = toLongMultipleOfValue(param.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Long.MultipleOf(" + multipleOf + "L)"));
            }
        } else if (param.isNumber || param.isFloat || param.isDouble) {
            if (param.minimum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Min(\"" + param.minimum + "\")"));
            }
            if (param.maximum != null) {
                result.add(Map.of("annotation", "@Validation.Number.Max(\"" + param.maximum + "\")"));
            }
            String multipleOf = toNumberMultipleOfValue(param.multipleOf);
            if (multipleOf != null) {
                result.add(Map.of("annotation", "@Validation.Number.MultipleOf(\"" + multipleOf + "\")"));
            }
        }

        if (param.isArray && (param.minItems != null || param.maxItems != null)) {
            List<String> attrs = new ArrayList<>();
            if (param.minItems != null) {
                attrs.add("min = " + param.minItems);
            }
            if (param.maxItems != null) {
                attrs.add("value = " + param.maxItems);
            }
            result.add(Map.of("annotation",
                              "@Validation.Collection.Size(" + String.join(", ", attrs) + ")"));
        }

        return result;
    }

    private static void addIntegerBounds(List<Map<String, Object>> result,
                                         String minimum,
                                         boolean exclusiveMinimum,
                                         String maximum,
                                         boolean exclusiveMaximum) {
        Integer minBound = toIntMinimumBound(minimum, exclusiveMinimum);
        if (minBound != null) {
            result.add(Map.of("annotation", "@Validation.Integer.Min(" + minBound + ")"));
        }

        Integer maxBound = toIntMaximumBound(maximum, exclusiveMaximum);
        if (maxBound != null) {
            result.add(Map.of("annotation", "@Validation.Integer.Max(" + maxBound + ")"));
        }
    }

    private static void addLongBounds(List<Map<String, Object>> result,
                                      String minimum,
                                      boolean exclusiveMinimum,
                                      String maximum,
                                      boolean exclusiveMaximum) {
        Long minBound = toLongMinimumBound(minimum, exclusiveMinimum);
        if (minBound != null) {
            result.add(Map.of("annotation", "@Validation.Long.Min(" + minBound + "L)"));
        }

        Long maxBound = toLongMaximumBound(maximum, exclusiveMaximum);
        if (maxBound != null) {
            result.add(Map.of("annotation", "@Validation.Long.Max(" + maxBound + "L)"));
        }
    }

    private static Integer toIntMinimumBound(String value, boolean exclusive) {
        Long bound = parseIntegralMinimumBound(value, exclusive);
        if (bound == null || bound < Integer.MIN_VALUE || bound > Integer.MAX_VALUE) {
            return null;
        }
        return bound.intValue();
    }

    private static Integer toIntMaximumBound(String value, boolean exclusive) {
        Long bound = parseIntegralMaximumBound(value, exclusive);
        if (bound == null || bound < Integer.MIN_VALUE || bound > Integer.MAX_VALUE) {
            return null;
        }
        return bound.intValue();
    }

    private static Long toLongMinimumBound(String value, boolean exclusive) {
        return parseIntegralMinimumBound(value, exclusive);
    }

    private static Long toLongMaximumBound(String value, boolean exclusive) {
        return parseIntegralMaximumBound(value, exclusive);
    }

    private static Integer toIntMultipleOfValue(Number value) {
        Long multipleOf = parseIntegralMultipleOf(value);
        if (multipleOf == null || multipleOf < Integer.MIN_VALUE || multipleOf > Integer.MAX_VALUE) {
            return null;
        }
        return multipleOf.intValue();
    }

    private static Long toLongMultipleOfValue(Number value) {
        return parseIntegralMultipleOf(value);
    }

    private static String toNumberMultipleOfValue(Number value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long parseIntegralMinimumBound(String value, boolean exclusive) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal numeric = new BigDecimal(value);
            BigDecimal rounded = exclusive
                    ? numeric.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE)
                    : numeric.setScale(0, RoundingMode.CEILING);
            return rounded.longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static Long parseIntegralMaximumBound(String value, boolean exclusive) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal numeric = new BigDecimal(value);
            BigDecimal rounded = exclusive
                    ? numeric.setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE)
                    : numeric.setScale(0, RoundingMode.FLOOR);
            return rounded.longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static Long parseIntegralMultipleOf(Number value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }
}
