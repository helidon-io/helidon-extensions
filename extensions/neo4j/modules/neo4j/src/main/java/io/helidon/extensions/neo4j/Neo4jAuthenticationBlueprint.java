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

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Authentication of a Neo4j connection.
 */
@Prototype.Blueprint
@Prototype.Configured
interface Neo4jAuthenticationBlueprint {
    /**
     * Username for authentication.
     *
     * @return username
     */
    @Option.Configured
    String username();

    /**
     * Password for authentication.
     *
     * @return password
     */
    @Option.Configured
    char[] password();

    /**
     * Whether authentication is enabled.
     * Defaults to {@code true}.
     *
     * @return whether authentication is enabled
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean enabled();
}
