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
package io.helidon.extensions.hashicorp.vault.secrets.cubbyhole;

import java.util.Optional;
import java.util.function.Supplier;

import io.helidon.extensions.hashicorp.vault.Vault;
import io.helidon.service.registry.Service;

/*
Supplier of an unqualified instance (i.e. no mount point defined)
 */
@Service.Singleton
class CubbyholeSecretsFactory implements Supplier<Optional<CubbyholeSecrets>> {
    private final Optional<Vault> vault;

    CubbyholeSecretsFactory(Optional<Vault> vault) {
        this.vault = vault;
    }

    @Override
    public Optional<CubbyholeSecrets> get() {
        return vault.map(it -> it.secrets(CubbyholeSecrets.ENGINE));
    }
}
