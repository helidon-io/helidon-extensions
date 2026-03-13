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
package io.helidon.extensions.hashicorp.vault;

import java.lang.System.Logger.Level;
import java.util.Optional;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.config.Config;
import io.helidon.extensions.hashicorp.vault.rest.RestApi;
import io.helidon.extensions.hashicorp.vault.spi.VaultAuth;
import io.helidon.http.Method;

/**
 * Main entry point to Vault operations.
 * <p>
 * To access secrets in the vault, start with {@link #builder()} to create a new Vault instance.
 * Once you have a Vault instance, you can access secrets through engines.
 * To get access to secrets, use {@link #secrets(Engine)}.
 */
public interface Vault extends RuntimeType.Api<VaultConfig> {
    /**
     * HTTP {@code LIST} method used by several Vault engines.
     */
    Method LIST = Method.create("LIST");

    /**
     * Fluent API builder to construct new instances.
     *
     * @return a new builder
     */
    static VaultConfig.Builder builder() {
        return VaultConfig.builder();
    }

    /**
     * Create a Vault from configuration.
     *
     * @param config configuration
     * @return a new Vault
     * @see io.helidon.extensions.hashicorp.vault.VaultConfig.Builder#config(io.helidon.config.Config)
     */
    static Vault create(Config config) {
        return builder()
                .config(config)
                .build();
    }

    /**
     * Create a Vault from its configuration.
     *
     * @param vaultConfig vault configuration
     * @return a new Vault
     */
    static Vault create(VaultConfig vaultConfig) {
        boolean authenticated = false;

        RestApi restAccess = null;
        Config config = vaultConfig.config().orElseGet(Config::empty);

        var logger = System.getLogger(Vault.class.getName());

        for (VaultAuth vaultAuth : vaultConfig.vaultAuths()) {
            Optional<RestApi> authenticate = vaultAuth.authenticate(config, vaultConfig);
            if (authenticate.isPresent()) {
                if (logger.isLoggable(Level.DEBUG)) {
                    logger.log(Level.DEBUG, "Authenticated Vault " + vaultConfig.address()
                            + " using " + vaultAuth.getClass().getName());
                }
                restAccess = authenticate.get();
                authenticated = true;
                break;
            }
        }

        if (!authenticated) {
            throw new VaultApiException("No Vault authentication discovered");
        }
        return new VaultImpl(vaultConfig, config, restAccess);
    }

    /**
     * Create a Vault updating its configuration.
     *
     * @param consumer vault configuration builder consumer
     * @return a new Vault
     */
    static Vault create(Consumer<VaultConfig.Builder> consumer) {
        return builder()
                .update(consumer)
                .build();
    }

    /**
     * Get access to secrets using the provided engine, using the default mount point of that engine.
     *
     * @param engine engine to use, such as {@code Kv2Secrets#ENGINE}
     * @param <T>    type of the {@link Secrets} the engine supports,
     *               such as {@code io.helidon.extensions.hashicorp.vault.Kv2Secrets}
     * @return instance of {@link Secrets} specific to the used engine
     */
    <T extends Secrets> T secrets(Engine<T> engine);

    /**
     * Get access to secrets using the provided engine, using a custom mount point.
     *
     * @param engine engine to use, such as {@code Kv2Secrets#ENGINE}
     * @param mount  mount point for the engine (such as when the same engine is configured more than once in the Vault)
     * @param <T>    type of the {@link Secrets} the engine supports,
     *               such as {@code Kv2Secrets}
     * @return instance of {@link Secrets} specific to the used engine
     */
    <T extends Secrets> T secrets(Engine<T> engine, String mount);

    /**
     * Get access to authentication method.
     *
     * @param method method to use, such as {@code io.helidon.extensions.hashicorp.vault.AuthMethod.TOKEN}
     * @param <T>    type of the API class used by the method
     * @return instance of the API class specific to the used method
     */
    <T> T auth(AuthMethod<T> method);

    /**
     * Get access to authentication method, using a custom path.
     *
     * @param method method to use, such as {@code io.helidon.extensions.hashicorp.vault.AuthMethod.TOKEN}
     * @param path   path for the method, such as when configuring multiple instances of the same method
     * @param <T>    type of the API class used by the method
     * @return instance of the API class specific to the used method
     */
    <T> T auth(AuthMethod<T> method, String path);

    /**
     * Get access to sys operations on this Vault, such as to configure engines, policies etc. (if such operations are supported).
     *
     * @param api API implementation
     * @param <T> type of the API
     * @return API instance
     */
    <T> T sys(SysApi<T> api);
}
