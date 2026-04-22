/*
 * Copyright (c) 2021, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.hashicorp.vault.auths.common;

import java.util.Optional;

import io.helidon.common.Weight;
import io.helidon.config.Config;
import io.helidon.extensions.hashicorp.vault.VaultConfig;
import io.helidon.extensions.hashicorp.vault.rest.RestApi;
import io.helidon.extensions.hashicorp.vault.spi.VaultAuth;
import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;

/**
 * Java Service Loader implementation for creating an unauthenticated Vault instance.
 */
@Weight(1)
public class NoVaultAuth implements VaultAuth {
    private static final HeaderName VAULT_NAMESPACE_HEADER_NAME = HeaderNames.create("X-Vault-Namespace");

    /**
     * Required for service loader.
     */
    public NoVaultAuth() {
    }

    /**
     * Create a new instance.
     *
     * @return a new unauthenticated Vault authentication
     */
    public static NoVaultAuth create() {
        return new NoVaultAuth();
    }

    @Override
    public Optional<RestApi> authenticate(Config config, VaultConfig vaultConfig) {
        boolean enabled = config.get("noauth.enabled").asBoolean().orElse(true);

        if (!enabled) {
            return Optional.empty();
        }

        String address = vaultConfig.address();

        return Optional.of(VaultRestApi.builder()
                                   .webClientBuilder(webclient -> {
                                       webclient.from(vaultConfig.webClient())
                                               .baseUri(address + "/v1");
                                       vaultConfig.baseNamespace()
                                               .ifPresent(ns -> webclient.addHeader(VAULT_NAMESPACE_HEADER_NAME, ns));
                                   })
                                   .faultTolerance(vaultConfig.faultTolerance())
                                   .build());
    }
}
