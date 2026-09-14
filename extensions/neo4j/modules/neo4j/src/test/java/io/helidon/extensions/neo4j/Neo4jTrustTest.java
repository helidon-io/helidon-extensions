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

package io.helidon.extensions.neo4j;

import java.net.URI;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class Neo4jTrustTest {
    private static final URI NEO4J_URI = URI.create("bolt://localhost:7687");

    @Test
    void hostnameVerificationEnabledByDefault() {
        Neo4jConfig defaults = Neo4j.builder()
                .uri(NEO4J_URI)
                .buildPrototype();
        Neo4jConfig configured = Neo4j.builder()
                .uri(NEO4J_URI)
                .config(Config.empty())
                .buildPrototype();

        assertThat(defaults.trust().hostnameVerification(), is(true));
        assertThat(configured.trust().hostnameVerification(), is(true));
    }

    @Test
    void hostnameVerificationCanBeDisabledProgrammatically() {
        Neo4jConfig config = Neo4j.builder()
                .uri(NEO4J_URI)
                .trust(trust -> trust.hostnameVerification(false))
                .buildPrototype();

        assertThat(config.trust().hostnameVerification(), is(false));
    }

    @Test
    void hostnameVerificationCanBeDisabledByConfig() {
        Config config = Config.just(ConfigSources.create(Map.of(
                "uri", NEO4J_URI.toString(),
                "trust.hostname-verification", "false")));

        Neo4jConfig neo4jConfig = Neo4j.builder()
                .config(config)
                .buildPrototype();

        assertThat(neo4jConfig.trust().hostnameVerification(), is(false));
    }
}
