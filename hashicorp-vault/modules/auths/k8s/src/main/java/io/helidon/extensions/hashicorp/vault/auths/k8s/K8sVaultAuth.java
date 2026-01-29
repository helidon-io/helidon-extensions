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

package io.helidon.extensions.hashicorp.vault.auths.k8s;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.config.Config;
import io.helidon.extensions.hashicorp.vault.Vault;
import io.helidon.extensions.hashicorp.vault.VaultApiException;
import io.helidon.extensions.hashicorp.vault.VaultConfig;
import io.helidon.extensions.hashicorp.vault.auths.common.NoVaultAuth;
import io.helidon.extensions.hashicorp.vault.rest.RestApi;
import io.helidon.extensions.hashicorp.vault.spi.VaultAuth;
import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;

/**
 * Vault authentication for Kubernetes (k8s).
 */
@Weight(Weighted.DEFAULT_WEIGHT + 50)
public class K8sVaultAuth implements VaultAuth {
    private static final System.Logger LOGGER = System.getLogger(K8sVaultAuth.class.getName());
    private static final HeaderName VAULT_NAMESPACE_HEADER_NAME = HeaderNames.create("X-Vault-Namespace");
    private final String serviceAccountToken;
    private final String tokenRole;
    private final String tokenLocation;
    private final String methodPath;

    /**
     * Constructor required for Java Service Loader.
     *
     * @deprecated please use {@link #builder()}
     */
    @Deprecated
    public K8sVaultAuth() {
        this.serviceAccountToken = null;
        this.tokenRole = null;
        this.tokenLocation = "/var/run/secrets/kubernetes.io/serviceaccount/token";
        this.methodPath = null;
    }

    private K8sVaultAuth(Builder builder) {
        this.serviceAccountToken = builder.serviceAccountToken;
        this.tokenRole = builder.tokenRole;
        this.tokenLocation = builder.tokenLocation;
        this.methodPath = builder.path;
    }

    /**
     * A new builder for {@link K8sVaultAuth}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<RestApi> authenticate(Config config, VaultConfig vaultConfig) {
        boolean enabled = config.get("auth.k8s.enabled").asBoolean().orElse(true);

        if (!enabled) {
            return Optional.empty();
        }

        String jwtToken;
        if (this.serviceAccountToken == null) {
            Optional<String> maybeToken = config.get("auth.k8s.service-account-token").asString()
                    .or(() -> {
                        Path tokenPath = Paths.get(tokenLocation);
                        if (!Files.exists(tokenPath)) {
                            return Optional.empty();
                        }
                        try {
                            return Optional.of(Files.readString(tokenPath));
                        } catch (IOException e) {
                            throw new VaultApiException("Failed to read token from " + tokenPath.toAbsolutePath(), e);
                        }
                    });

            if (maybeToken.isEmpty()) {
                return Optional.empty();
            }
            jwtToken = maybeToken.get();
        } else {
            jwtToken = serviceAccountToken;
        }

        String roleName = Optional.ofNullable(this.tokenRole)
                .or(() -> config.get("auth.k8s.token-role")
                        .asString()
                        .asOptional())
                .orElseThrow(() -> new VaultApiException(
                        "Token role must be defined when using Kubernetes vault authentication."));

        // this may be changed in the future, when running with a sidecar (there should be a way to get the address from evn)
        String address = vaultConfig.address();

        // explicitly use default
        VaultConfig.Builder loginVaultBuilder = Vault.builder()
                .address(address)
                .vaultAuthsDiscoverServices(false)
                .faultTolerance(vaultConfig.faultTolerance())
                .webClient(vaultConfig.webClient())
                .addVaultAuth(NoVaultAuth.create());

        vaultConfig.baseNamespace().ifPresent(loginVaultBuilder::baseNamespace);

        Vault loginVault = loginVaultBuilder.build();
        String methodPath = Optional.ofNullable(this.methodPath)
                .orElseGet(() -> config.get("auth.k8s.path")
                        .asString()
                        .orElse(K8sAuth.AUTH_METHOD.defaultPath()));

        LOGGER.log(Level.INFO,
                   "Authenticated Vault {0}/{1} using k8s, role \"{2}\"",
                   address, methodPath, roleName);

        return Optional.of(K8sRestApi.k8sBuilder()
                                   .webClientBuilder(webclient -> {
                                       webclient.from(vaultConfig.webClient())
                                               .baseUri(address + "/v1");
                                       vaultConfig.baseNamespace()
                                               .ifPresent(ns -> webclient.addHeader(VAULT_NAMESPACE_HEADER_NAME, ns));
                                   })
                                   .faultTolerance(vaultConfig.faultTolerance())
                                   .auth(loginVault.auth(K8sAuth.AUTH_METHOD, methodPath))
                                   .roleName(roleName)
                                   .jwtToken(jwtToken)
                                   .build());
    }

    /**
     * Fluent API builder for {@link K8sVaultAuth}.
     */
    public static class Builder implements io.helidon.common.Builder<Builder, K8sVaultAuth> {
        private String serviceAccountToken;
        private String tokenRole;
        private String tokenLocation = "/var/run/secrets/kubernetes.io/serviceaccount/token";
        private String path;

        private Builder() {
        }

        @Override
        public K8sVaultAuth build() {
            return new K8sVaultAuth(this);
        }

        /**
         * The k8s service account token.
         *
         * @param serviceAccountToken service account token
         * @return updated builder
         */
        public Builder serviceAccountToken(String serviceAccountToken) {
            this.serviceAccountToken = serviceAccountToken;
            return this;
        }

        /**
         * The token role used for authentication.
         *
         * @param tokenRole token role name
         * @return updated builder
         */
        public Builder tokenRole(String tokenRole) {
            this.tokenRole = tokenRole;
            return this;
        }

        /**
         * File with the k8s service account token.
         *
         * @param tokenLocation path to service account token
         * @return updated builder
         */
        public Builder tokenLocation(String tokenLocation) {
            this.tokenLocation = tokenLocation;
            return this;
        }

        /**
         * Custom method path.
         *
         * @param path path of the k8s method, defaults to
         *             {@link io.helidon.extensions.hashicorp.vault.auths.k8s.K8sAuth#AUTH_METHOD}
         *             default path
         * @return updated builder
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }
    }
}
