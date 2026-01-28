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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.helidon.common.LazyValue;
import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.config.Config;
import io.helidon.config.ConfigException;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;

@Service.Singleton
@Weight(Weighted.DEFAULT_WEIGHT - 10)
class VaultFactory implements Service.ServicesFactory<Vault> {
    private final Supplier<Config> configSupplier;

    @Service.Inject
    VaultFactory(Supplier<Config> configSupplier) {
        this.configSupplier = configSupplier;
    }

    /*
    The options to configure vault:
    vault:
        address: http://localhost:8200

    vaults:
        - name: "default"
          address: http://localhost:8200
        - name: "another"
          address: http://localhost:8201

    vaults:
        "default":
          address: http://localhost:8200
        "another":
          address: http://localhost:8201
     */
    @Override
    public List<Service.QualifiedInstance<Vault>> services() {
        Config rootConfig = configSupplier.get();

        List<Service.QualifiedInstance<Vault>> services = new ArrayList<>();

        Config vaultConfig = rootConfig.get("vault");
        Set<String> names = new HashSet<>();

        if (vaultConfig.get("address").exists()) {
            String name = vaultConfig.get("name").asString().filter(Predicate.not("@default"::equals)).orElse("");
            names.add(name);
            services.add(new SupplierQualifiedInstance(vaultSupplier(vaultConfig), name));
        }

        Config vaults = rootConfig.get("vaults");
        if (vaults.exists()) {
            List<Config> vaultList = vaults.asNodeList()
                    .orElseGet(List::of);
            for (Config config : vaultList) {
                String nodeName = config.key().name();
                String name = config.get("name").asString().orElse(nodeName);
                if (names.add(name)) {
                    services.add(new SupplierQualifiedInstance(vaultSupplier(config), name));
                } else {
                    throw new ConfigException("Duplicate vault name specified in configuration: " + name);
                }
            }
        }

        return List.copyOf(services);
    }

    private Supplier<Vault> vaultSupplier(Config vaultConfig) {
        return LazyValue.create(() -> Vault.create(vaultConfig));
    }

    private static class SupplierQualifiedInstance implements Service.QualifiedInstance<Vault> {
        private final Supplier<Vault> supplier;
        private Set<Qualifier> qualifiers;

        private SupplierQualifiedInstance(Supplier<Vault> supplier, String name) {
            this.supplier = supplier;
            if (name.isEmpty()) {
                this.qualifiers = Set.of();
            } else {
                this.qualifiers = Set.of(Qualifier.createNamed(name));
            }
        }

        @Override
        public Vault get() {
            return supplier.get();
        }

        @Override
        public Set<Qualifier> qualifiers() {
            return qualifiers;
        }
    }
}
