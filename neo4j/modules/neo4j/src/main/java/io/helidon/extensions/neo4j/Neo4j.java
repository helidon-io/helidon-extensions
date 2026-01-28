/*
 * Copyright (c) 2023, 2025 Oracle and/or its affiliates.
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

import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.config.Config;

import org.neo4j.driver.Config.TrustStrategy.Strategy;
import org.neo4j.driver.Driver;

/**
 * Main entry point for Neo4j support for Helidon.
 * Performs configuration and the prepared driver.
 */
@RuntimeType.PrototypedBy(Neo4jConfig.class)
public final class Neo4j implements RuntimeType.Api<Neo4jConfig> {
    private final Driver driver;
    private final Neo4jConfig config;

    private Neo4j(Neo4jConfig config) {
        this.config = config;
        this.driver = Neo4jUtil.buildDriver(config);
    }

    /**
     * Create the Neo4j support using builder.
     *
     * @param config from the external configuration
     * @return Neo4j support
     */
    public static Neo4j create(Config config) {
        return builder().config(config).build();
    }

    /**
     * Create Neo4J support from its configuration.
     *
     * @param config Neo4j configuration
     * @return Neo4J support
     */
    public static Neo4j create(Neo4jConfig config) {
        return new Neo4j(config);
    }

    /**
     * Create Neo4J support updating its configuration.
     *
     * @param consumer consumer Neoqj configuration builder
     * @return Neo4J support
     */
    public static Neo4j create(Consumer<Neo4jConfig.Builder> consumer) {
        return builder()
                .update(consumer)
                .build();
    }

    /**
     * Following the builder pattern.
     *
     * @return the builder
     */
    public static Neo4jConfig.Builder builder() {
        return Neo4jConfig.builder();
    }

    /**
     * The main entry point to the Neo4j Support.
     *
     * @return neo4j driver
     */
    public Driver driver() {
        return driver;
    }

    @Override
    public Neo4jConfig prototype() {
        return config;
    }

    /**
     * Security trustStrategy.
     */
    // this is a temporary workaround, as we do not support double-nested types
    public enum TrustStrategy {
        /**
         * Trust all.
         */
        TRUST_ALL_CERTIFICATES(Strategy.TRUST_ALL_CERTIFICATES),
        /**
         * Trust custom certificates.
         */
        TRUST_CUSTOM_CA_SIGNED_CERTIFICATES(Strategy.TRUST_CUSTOM_CA_SIGNED_CERTIFICATES),
        /**
         * Trust system CA.
         */
        TRUST_SYSTEM_CA_SIGNED_CERTIFICATES(Strategy.TRUST_SYSTEM_CA_SIGNED_CERTIFICATES);

        private final Strategy neo4jStrategy;

        TrustStrategy(Strategy neo4jStrategy) {
            this.neo4jStrategy = neo4jStrategy;
        }

        /**
         * Strategy as understood by the Neo4j driver.
         *
         * @return Neo4j trust strategy
         */
        public Strategy neo4jStrategy() {
            return neo4jStrategy;
        }
    }
}
