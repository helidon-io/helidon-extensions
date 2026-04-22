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

import io.helidon.common.features.api.Features;
import io.helidon.common.features.api.HelidonFlavor;

/**
 * Vault's Cubbyhole Secrets Engine support.
 */
@Features.Name("Cubbyhole")
@Features.Description("Cubbyhole Secrets Engine")
@Features.Flavor({HelidonFlavor.SE, HelidonFlavor.MP})
@Features.Path({"HCP Vault", "Secrets", "Cubbyhole"})
module io.helidon.extensions.hashicorp.vault.secrets.cubbyhole {

    requires static io.helidon.common.features.api;

    requires transitive io.helidon.extensions.hashicorp.vault;
    requires transitive io.helidon.security;

    exports io.helidon.extensions.hashicorp.vault.secrets.cubbyhole;

    // Implementation of service for Vault Secret Engine
    provides io.helidon.extensions.hashicorp.vault.spi.SecretsEngineProvider
            with io.helidon.extensions.hashicorp.vault.secrets.cubbyhole.CubbyholeEngineProvider;

    // Implementation of service to support secrets in Helidon Security
    provides io.helidon.security.spi.SecurityProviderService
            with io.helidon.extensions.hashicorp.vault.secrets.cubbyhole.CubbyholeSecurityService;

}
