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

import java.util.Map;
import java.util.UUID;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.dbclient.DbClient;
import io.helidon.dbclient.DbMapperManager;

import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Inserts a JSON-P object into an embedded database and reads it back as JSON.
 */
public final class Main {
    private Main() {
    }

    /**
     * Runs the example without an external database.
     */
    static void main() {
        JsonObject person = insertAndReadPerson();
        System.out.println("Jakarta DBClient JSON-P example: " + person);
    }

    static JsonObject insertAndReadPerson() {
        Config config = Config.create(ConfigSources.create(Map.of(
                "source", "jdbc",
                "connection.url", "jdbc:h2:mem:" + UUID.randomUUID(),
                "connection.username", "sa",
                "connection.password", "")));

        try (var db = DbClient.create(config)) {
            db.execute().dml("create table person (id integer primary key, name varchar(100), active boolean)");

            JsonObject person = Json.createObjectBuilder()
                    .add("id", 42)
                    .add("name", "Ada")
                    .add("active", true)
                    .build();
            var parameters = DbMapperManager.create().toNamedParameters(person, JsonObject.class);
            db.execute()
                    .createInsert("insert into person (id, name, active) values (:id, :name, :active)")
                    .params(parameters)
                    .execute();

            return db.execute()
                    .get("select id as \"id\", name as \"name\", active as \"active\" from person where id = ?", 42)
                    .orElseThrow()
                    .as(JsonObject.class);
        }
    }
}
