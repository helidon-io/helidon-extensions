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
package io.helidon.extensions.jakarta.tests.transaction;

import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.data.tests.common.InitialData;
import io.helidon.data.tests.repository.PokemonRepository;
import io.helidon.service.registry.GlobalServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class MySqlExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        var database = new Database();
        // The store closes after fixture @AfterAll methods, including when setup fails.
        context.getStore(ExtensionContext.Namespace.create(MySqlExtension.class))
                .put(Database.class, database);
        database.start();
    }

    private static class Database implements ExtensionContext.Store.CloseableResource {
        private final MySQLContainer<?> container = new MySQLContainer<>(
                DockerImageName.parse("container-registry.oracle.com/mysql/community-server:9.7.3")
                        .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("changeit");
        private final ServiceRegistryManager registryManager = ServiceRegistryManager.create();

        private void start() {
            container.start();
            Config config = Config.just(ConfigSources.create(Map.of("test.database.url", container.getJdbcUrl())),
                                        ConfigSources.classpath("application.yaml"));
            GlobalServiceRegistry.registry(registryManager.registry());
            Services.set(Config.class, config);
            PokemonRepository repository = Services.get(PokemonRepository.class);
            repository.run(InitialData::init);
            repository.run(InitialData::verify);
        }

        @Override
        public void close() {
            try {
                registryManager.shutdown();
            } finally {
                container.stop();
            }
        }
    }
}
