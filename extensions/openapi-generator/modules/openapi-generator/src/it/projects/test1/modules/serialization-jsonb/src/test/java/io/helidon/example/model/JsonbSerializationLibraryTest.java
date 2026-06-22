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

package io.helidon.example.model;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class JsonbSerializationLibraryTest {

    private final Jsonb jsonb = JsonbBuilder.create();

    @Test
    void modelRoundTrips() {
        Pet value = new Pet();
        value.id(7L);
        value.name("Mochi");
        value.tag("cat");

        String json = jsonb.toJson(value);
        assertThat(json, containsString("\"id\":7"));
        assertThat(json, containsString("\"name\":\"Mochi\""));
        assertThat(json, containsString("\"tag\":\"cat\""));

        Pet parsed = jsonb.fromJson("""
                {"id":7,"name":"Mochi","tag":"cat"}
                """, Pet.class);

        assertThat(parsed.id(), is(7L));
        assertThat(parsed.name(), is("Mochi"));
        assertThat(parsed.tag(), is("cat"));
    }
}
