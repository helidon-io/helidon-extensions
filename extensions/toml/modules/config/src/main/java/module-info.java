/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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
 * TOML ConfigParser implementation.
 * <p>
 * Uses the TOML parser and document model that supports the
 * <a href="https://toml.io/en/v1.0.0">TOML v1.0.0</a> and
 * <a href="https://toml.io/en/v1.1.0">TOML v1.1.0</a> specifications.
 */
@Features.Name("TOML Config")
@Features.Description("TOML media type support for config")
@Features.Flavor({HelidonFlavor.SE, HelidonFlavor.MP})
@Features.Path({"Config", "TOML"})
module io.helidon.extensions.toml.config {
    requires io.helidon.common;
    requires io.helidon.extensions.toml;

    requires static io.helidon.common.features.api;

    requires transitive io.helidon.config;

    exports io.helidon.extensions.toml.config;

    provides io.helidon.config.spi.ConfigParser
            with io.helidon.extensions.toml.config.TomlConfigParser;
}
