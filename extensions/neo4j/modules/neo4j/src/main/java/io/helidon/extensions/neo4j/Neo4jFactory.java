/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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
package io.helidon.extensions.neo4j;

import java.util.Optional;
import java.util.function.Supplier;

import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.config.Config;
import io.helidon.service.registry.Service;

import org.neo4j.driver.Driver;

/**
 * Factory to create Neo4j instances.
 */
@Service.Singleton
@Weight(Weighted.DEFAULT_WEIGHT - 10)
class Neo4jFactory implements Supplier<Optional<Driver>> {
    private final Supplier<Config> configSupplier;

    Neo4jFactory(Supplier<Config> configSupplier) {
        this.configSupplier = configSupplier;
    }

    @Override
    public Optional<Driver> get() {
        Config config = configSupplier.get();
        if (config.get("neo4j").exists()) {
            return Optional.of(Neo4j.create(config.get("neo4j")).driver());
        }
        return Optional.empty();
    }
}
