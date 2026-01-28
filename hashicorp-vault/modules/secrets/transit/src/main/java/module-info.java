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

import io.helidon.common.features.api.Features;
import io.helidon.common.features.api.HelidonFlavor;

/**
 * Vault transit secrets.
 *
 * @see io.helidon.extensions.hashicorp.vault.secrets.transit.TransitSecrets
 */
@Features.Name("Transit")
@Features.Description("Transit Secrets Engine")
@Features.Flavor({HelidonFlavor.SE, HelidonFlavor.MP})
@Features.Path({"HCP Vault", "Secrets", "Transit"})
module io.helidon.extensions.hashicorp.vault.secrets.transit {

    requires io.helidon.http;
    requires jakarta.json;

    requires static io.helidon.common.features.api;

    requires transitive io.helidon.extensions.hashicorp.vault;
    requires transitive io.helidon.security;

    exports io.helidon.extensions.hashicorp.vault.secrets.transit;

    provides io.helidon.extensions.hashicorp.vault.spi.SecretsEngineProvider
            with io.helidon.extensions.hashicorp.vault.secrets.transit.TransitEngineProvider;

    provides io.helidon.security.spi.SecurityProviderService
            with io.helidon.extensions.hashicorp.vault.secrets.transit.TransitSecurityService;

}
