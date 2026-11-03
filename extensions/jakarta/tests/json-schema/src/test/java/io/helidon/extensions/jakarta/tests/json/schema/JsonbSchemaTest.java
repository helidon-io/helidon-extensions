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

import java.util.List;
import java.util.Set;

import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.schema.Schema;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class JsonbSchemaTest {

    @Test
    void testSchemaDiscoveredWithJsonbAnnotations() {
        Schema schema = Schema.find(JsonbSchemaModel.class).orElseThrow();
        JsonObject generated = JsonParser.create(schema.generate()).readJsonObject();
        JsonObject properties = generated.objectValue("properties").orElseThrow();

        assertThat(properties.keysAsStrings(),
                   is(Set.of("fieldName", "schemaField", "setterName", "schemaSetter", "creatorName", "otherCreator")));
        for (String name : properties.keysAsStrings()) {
            assertThat(name, properties.objectValue(name).orElseThrow().stringValue("type").orElseThrow(), is("string"));
        }
        assertThat(generated.arrayValue("required").orElseThrow().values().stream()
                           .map(it -> it.asString().value()).toList(),
                   is(List.of("creatorName")));
    }
}

