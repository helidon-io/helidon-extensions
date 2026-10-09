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

import io.swagger.v3.oas.models.media.Schema;
import org.apache.commons.text.StringEscapeUtils;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.utils.ModelUtils;

final class ModelDefaultSupport {
    private ModelDefaultSupport() {
    }

    static String scalarStringDefault(CodegenProperty property, Schema<?> schema) {
        if ("String".equals(property.dataType)
                && ModelUtils.isStringSchema(schema)
                && schema.getEnum() == null
                && schema.getDefault() instanceof String value) {
            // Preserve control characters rather than using documentation-oriented escapeText.
            return "\"" + StringEscapeUtils.escapeJava(value) + "\"";
        }
        return null;
    }

    /**
     * Formats a property's default value as a Java literal for use in a field initializer.
     * Returns {@code null} when no useful initializer can be produced (e.g. arrays).
     */
    static String formatDefaultValue(CodegenProperty prop) {
        if (prop.defaultValue == null || prop.defaultValue.isEmpty()) {
            return null;
        }
        String val = prop.defaultValue;
        if (prop.isEnum || prop.isEnumRef) {
            // Upstream AbstractJavaCodegen already formats the default as "TypeName.CONSTANT"
            return val;
        }
        if (prop.isString) {
            // AbstractJavaCodegen already returns a Java expression for string defaults.
            return val;
        }
        if (prop.isLong) {
            return val + "L";
        }
        if (prop.isFloat) {
            return val + "f";
        }
        if (prop.isArray || prop.isMap || ValidationTypeSupport.isJavaArray(prop.datatypeWithEnum)) {
            return null;  // skip — complex initialization
        }
        return val;  // integer, double, boolean — value as-is
    }

}
