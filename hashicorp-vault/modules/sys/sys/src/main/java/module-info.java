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
 * Sys operations for Vault.
 *
 * @see io.helidon.extensions.hashicorp.vault.sys.Sys
 */
@Features.Name("Sys")
@Features.Description("System operations")
@Features.Flavor({HelidonFlavor.SE, HelidonFlavor.MP})
@Features.Path({"HCP Vault", "Sys"})
module io.helidon.extensions.hashicorp.vault.sys {

    requires io.helidon.extensions.hashicorp.vault.auths.common;

    requires static io.helidon.common.features.api;

    requires transitive io.helidon.extensions.hashicorp.vault;

    exports io.helidon.extensions.hashicorp.vault.sys;

    provides io.helidon.extensions.hashicorp.vault.spi.SysProvider
            with io.helidon.extensions.hashicorp.vault.sys.HcpSysProvider;

}
