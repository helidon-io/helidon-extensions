/*
 * Copyright (c) 2021, 2025 Oracle and/or its affiliates.
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

package io.helidon.extensions.hashicorp.vault.auths.token;

import java.lang.System.Logger.Level;
import java.util.Optional;

import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.config.Config;
import io.helidon.extensions.hashicorp.vault.VaultConfig;
import io.helidon.extensions.hashicorp.vault.auths.common.VaultRestApi;
import io.helidon.extensions.hashicorp.vault.rest.RestApi;
import io.helidon.extensions.hashicorp.vault.spi.VaultAuth;
import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;

/**
 * Java Service Loader implementation for authenticating using a token.
 * You can create a new instance using {@link #builder()}.
 * To use a custom built instance, use {@link io.helidon.extensions.hashicorp.vault.VaultConfig.Builder#addVaultAuth(VaultAuth)}.
 */
@Weight(Weighted.DEFAULT_WEIGHT)
public class TokenVaultAuth implements VaultAuth {
    private static final System.Logger LOGGER = System.getLogger(TokenVaultAuth.class.getName());
    private static final HeaderName VAULT_TOKEN_HEADER_NAME = HeaderNames.create("X-Vault-Token");
    private static final HeaderName VAULT_NAMESPACE_HEADER_NAME = HeaderNames.create("X-Vault-Namespace");
    private final String token;
    private final String baseNamespace;

    /**
     * Required for service loader.
     */
    public TokenVaultAuth() {
        this.token = null;
        this.baseNamespace = null;
    }

    private TokenVaultAuth(Builder builder) {
        this.token = builder.token;
        this.baseNamespace = builder.baseNamespace;
    }

    /**
     * Create a new builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<RestApi> authenticate(Config config, VaultConfig vaultConfig) {
        boolean enabled = config.get("auth.token.enabled").asBoolean().orElse(true);

        if (!enabled) {
            return Optional.empty();
        }

        return Optional.ofNullable(token)
                .or(vaultConfig::token)
                .or(() -> config.get("token").asString().asOptional())
                .map(token -> restApi(vaultConfig, token));
    }

    private RestApi restApi(VaultConfig vaultConfig, String token) {
        String address = vaultConfig.address();

        LOGGER.log(Level.INFO, "Authenticated Vault " + address + " using a token");

        return VaultRestApi.builder()
                .webClientBuilder(builder -> {
                    builder.from(vaultConfig.webClient())
                            .baseUri(address + "/v1")
                            .addHeader(VAULT_TOKEN_HEADER_NAME, token);
                    Optional.ofNullable(baseNamespace)
                            .or(vaultConfig::baseNamespace)
                            .ifPresent(ns -> builder.addHeader(VAULT_NAMESPACE_HEADER_NAME, ns));
                })
                .faultTolerance(vaultConfig.faultTolerance())
                .build();
    }

    /**
     * Fluent API builder for {@link TokenVaultAuth}.
     */
    public static class Builder implements io.helidon.common.Builder<Builder, TokenVaultAuth> {
        private String baseNamespace;
        private String token;

        private Builder() {
        }

        @Override
        public TokenVaultAuth build() {
            return new TokenVaultAuth(this);
        }

        /**
         * Configure a base namespace to use.
         *
         * @param baseNamespace base namespace
         * @return updated builder
         */
        public Builder baseNamespace(String baseNamespace) {
            this.baseNamespace = baseNamespace;
            return this;
        }

        /**
         * Configure the token to use.
         *
         * @param token token value
         * @return updated builder
         */
        public Builder token(String token) {
            this.token = token;
            return this;
        }
    }
}
