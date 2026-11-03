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

package io.helidon.extensions.jakarta.tests.json.schema;

import io.helidon.json.schema.JsonSchema;

import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;

@JsonSchema.Schema
public class JsonbSchemaModel {
    @JsonbProperty("fieldName")
    public String field;

    @JsonbProperty("jsonbField")
    @JsonSchema.PropertyName("schemaField")
    public String preferredField;

    @JsonbTransient
    public String hiddenField;

    @JsonbProperty("ignoredField")
    @JsonSchema.Ignore
    public String ignoredField;

    @JsonSchema.Required
    @JsonbProperty("creatorName")
    private String creator;

    @JsonbCreator
    public static JsonbSchemaModel create(@JsonbProperty("creatorName") String creator,
                                         @JsonbProperty("otherCreator") String otherCreator) {
        return new JsonbSchemaModel();
    }

    @JsonbProperty("setterName")
    public void setValue(String value) {
    }

    @JsonbProperty("jsonbSetter")
    @JsonSchema.PropertyName("schemaSetter")
    public void setPreferredValue(String value) {
    }

    @JsonbTransient
    public void setHiddenValue(String value) {
    }

    @JsonSchema.Ignore
    @JsonbProperty("ignoredSetter")
    public void setIgnoredValue(String value) {
    }
}

