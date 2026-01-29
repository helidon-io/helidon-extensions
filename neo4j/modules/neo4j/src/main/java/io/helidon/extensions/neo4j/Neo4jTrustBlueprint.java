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

import java.nio.file.Path;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Configuration of trust for communication with Neo4j.
 */
@Prototype.Configured
@Prototype.Blueprint
interface Neo4jTrustBlueprint {
    /**
     * Only encrypted connections to Neo4j instances with certificates signed by a trusted certificate will be accepted.
     * The file specified should contain one or more trusted X.509 certificates.
     *
     * @return file with trusted certificate(s)
     */
    @Option.Configured
    Optional<Path> certificate();

    /**
     * Trust strategy to use.
     *
     * @return trust strategy
     */
    @Option.Configured
    @Option.Default("TRUST_SYSTEM_CA_SIGNED_CERTIFICATES")
    Neo4j.TrustStrategy strategy();

    /**
     * Whether hostname verification is enabled. Defaults to {@code true}.
     *
     * @return hostname verification
     */
    @Option.Configured
    @Option.Default("true")
    boolean hostnameVerification();
}
