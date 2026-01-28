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

package io.helidon.extensions.hashicorp.vault;

import java.util.List;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.config.Config;
import io.helidon.extensions.hashicorp.vault.spi.VaultAuth;
import io.helidon.faulttolerance.FtHandler;
import io.helidon.webclient.api.WebClientConfig;

/**
 * Configuration of {@link io.helidon.extensions.hashicorp.vault.Vault}.
 */
@Prototype.Blueprint
@Prototype.Configured
interface VaultConfigBlueprint extends Prototype.Factory<Vault> {
    /**
     * An {@link FtHandler} can be configured to be used by all calls to the
     * Vault, to add support for retries, circuit breaker, bulkhead, etc.
     *
     * @return fault tolerance handler to use
     */
    @Option.DefaultCode("@io.helidon.faulttolerance.FaultTolerance@.builder().build()")
    FtHandler faultTolerance();

    /**
     * Configure the address of the Vault, including the scheme, host, and port.
     *
     * @return address of the Vault
     */
    @Option.Configured
    String address();

    /**
     * Configure the token to use to connect to the Vault.
     *
     * @return the token to use
     */
    @Option.Configured
    Optional<String> token();

    /**
     * Base namespace to use when invoking Vault operations.
     *
     * @return base namespace
     */
    @Option.Configured
    Optional<String> baseNamespace();

    /**
     * Web client configuration.
     *
     * @return configuration of the web client used to connect to the Vault
     */
    @Option.Configured
    @Option.DefaultMethod("create")
    WebClientConfig webClient();

    /**
     * Vault authentication methods to use with this Vault.
     * By default, these are discovered from service registry (which will use {@link java.util.ServiceLoader} as
     * one of the sources).
     *
     * @return vault authentication methods
     */
    @Option.RegistryService
    @Option.Singular
    List<VaultAuth> vaultAuths();

    /**
     * Config used to configure the vault, if any.
     *
     * @return config used to configure the vault, if any
     */
    Optional<Config> config();
}
