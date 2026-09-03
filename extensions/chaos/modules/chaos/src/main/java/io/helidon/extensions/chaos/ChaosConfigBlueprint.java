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
package io.helidon.extensions.chaos;

import java.util.Optional;
import java.util.Set;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.webserver.spi.ServerFeatureProvider;

/**
 * Startup configuration for the Helidon Chaos server feature.
 */
@Prototype.Blueprint(decorator = ChaosConfigSupport.BuilderDecorator.class)
@Prototype.Configured(value = "chaos", root = false)
@Prototype.Provides(ServerFeatureProvider.class)
interface ChaosConfigBlueprint extends Prototype.Factory<ChaosServerFeature> {

    /**
     * Whether the feature is enabled at server startup.
     *
     * @return whether the feature is enabled
     */
    @Option.Configured
    @Option.DefaultBoolean(false)
    boolean enabled();

    /**
     * Name of the dedicated control socket.
     *
     * @return control socket name
     */
    @Option.Configured
    Optional<String> controlSocket();

    /**
     * Names of sockets whose application traffic may be disrupted.
     *
     * @return application socket names
     */
    @Option.Configured
    @Option.Singular
    Set<String> applicationSockets();

    /**
     * Control-plane security configuration.
     *
     * @return security configuration
     */
    @Option.Configured
    @Option.DefaultMethod("create")
    ChaosSecurityConfig security();

    /**
     * Server-enforced ceilings for all runs.
     *
     * @return configured ceilings
     */
    @Option.Configured
    @Option.DefaultMethod("create")
    ChaosLimitsConfig limits();

    /**
     * Configured feature instance name.
     *
     * @return feature instance name
     */
    @Option.Default("chaos")
    String name();

    /**
     * Feature installation weight.
     *
     * @return feature weight
     */
    @Option.Configured
    @Option.DefaultDouble(700.0)
    double weight();
}
