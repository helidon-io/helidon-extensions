/*
 * Copyright (c) 2025 Oracle and/or its affiliates.
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

import java.net.URI;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Configuration of the {@link io.helidon.extensions.neo4j.Neo4j} extension.
 */
@Prototype.Blueprint
@Prototype.Configured("neo4j")
interface Neo4jConfigBlueprint extends Prototype.Factory<Neo4j> {
    /**
     * URI of the Neo4j database.
     *
     * @return URI to connect to
     */
    @Option.Configured
    URI uri();

    /**
     * Whether to use encrypted communication.
     * Defaults to {@code true}.
     *
     * @return whether to use encrypted communication
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean encrypted();

    /**
     * Authentication configuration.
     *
     * @return authentication configuration if required
     */
    @Option.Configured
    Optional<Neo4jAuthentication> authentication();

    /**
     * Pool configuration.
     *
     * @return pool configuration if required
     */
    @Option.DefaultMethod("create")
    @Option.Configured
    Neo4jPool pool();

    /**
     * Configuration of trust.
     *
     * @return trust configuration
     */
    @Option.DefaultMethod("create")
    @Option.Configured
    Neo4jTrust trust();
}
