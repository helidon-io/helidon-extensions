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

import java.util.List;
import java.util.ServiceLoader;

import io.helidon.common.mapper.Mappers;
import io.helidon.dbclient.DbColumnBase;
import io.helidon.dbclient.DbMapperManager;
import io.helidon.dbclient.DbRowBase;
import io.helidon.dbclient.spi.DbMapperProvider;

import jakarta.json.JsonObject;

/**
 * Uses the discovered JSON-P mapper with a DBClient row and statement parameters.
 */
public final class Main {
    private Main() {
    }

    /**
     * Runs the example without an external database.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        if (!Main.class.getModule().isNamed()) {
            throw new IllegalStateException("Run the example on the module path");
        }
        var providers = ServiceLoader.load(DbMapperProvider.class)
                .stream()
                .filter(provider -> provider.type().getName().contains(".jsonp."))
                .map(provider -> provider.type().getName())
                .toList();
        if (!providers.equals(List.of("io.helidon.extensions.jakarta.dbclient.jsonp.JsonProcessingMapperProvider"))) {
            throw new IllegalStateException("Expected only the extension DBClient provider: " + providers);
        }
        var forbidden = ModuleLayer.boot().modules().stream()
                .map(Module::getName)
                .filter(name -> name.equals("io.helidon.dbclient.jsonp")
                        || name.contains(".http.media.json")
                        || name.startsWith("io.helidon.") && (name.contains(".persistence") || name.contains(".transaction"))
                        || name.equals("jakarta.persistence")
                        || name.equals("jakarta.transaction"))
                .toList();
        if (!forbidden.isEmpty()) {
            throw new IllegalStateException("Unrelated integration resolved: " + forbidden);
        }
        JsonObject json = exampleRow();
        if (!json.getString("name").equals("Ada") || json.getInt("id") != 42) {
            throw new IllegalStateException("Unexpected row mapping: " + json);
        }
        var parameters = DbMapperManager.create().toNamedParameters(json, JsonObject.class);
        System.out.println("Jakarta DBClient JSON-P example: " + json + ", named parameters: " + parameters);
    }

    static JsonObject exampleRow() {
        var columns = new DbColumnBase[] {
                column("id", 42),
                column("name", "Ada"),
                column("active", true)
        };
        var row = new DbRowBase(columns, DbMapperManager.create()) { };
        return row.as(JsonObject.class);
    }

    private static DbColumnBase column(String name, Object value) {
        return new DbColumnBase(value, Mappers.create()) {
            @Override
            public Class<?> javaType() {
                return value.getClass();
            }

            @Override
            public String dbType() {
                return "example";
            }

            @Override
            public String name() {
                return name;
            }
        };
    }
}
