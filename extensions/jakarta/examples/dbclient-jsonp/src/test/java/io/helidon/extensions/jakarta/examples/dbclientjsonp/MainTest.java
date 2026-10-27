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

package io.helidon.extensions.jakarta.examples.dbclientjsonp;

import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;

import io.helidon.dbclient.DbMapperManager;
import io.helidon.dbclient.spi.DbMapperProvider;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class MainTest {
    @Test
    void discoveredProviderMapsDatabaseRows() {
        var providers = ServiceLoader.load(DbMapperProvider.class)
                .stream()
                .filter(provider -> provider.type().getName().contains(".jsonp."))
                .map(provider -> provider.type().getName())
                .toList();
        assertThat(providers, is(List.of("io.helidon.extensions.jakarta.dbclient.jsonp.JsonProcessingMapperProvider")));
        var mapped = Main.exampleRow();
        assertThat(mapped.getInt("id"), is(42));
        assertThat(mapped.getString("name"), is("Ada"));
        assertThat(mapped.getBoolean("active"), is(true));
    }

    @Test
    void mapsNamedAndIndexedParametersIncludingJsonNull() {
        var json = Json.createObjectBuilder()
                .add("name", "Ada")
                .add("active", true)
                .addNull("optional")
                .build();
        var manager = DbMapperManager.create();
        var named = manager.toNamedParameters(json, JsonObject.class);
        assertThat(named.get("name"), is("Ada"));
        assertThat(named.get("active"), is(true));
        assertThat(named.get("optional"), is(nullValue()));
        assertThat(manager.toIndexedParameters(json, JsonObject.class), is(Arrays.asList("Ada", true, null)));
    }
}
