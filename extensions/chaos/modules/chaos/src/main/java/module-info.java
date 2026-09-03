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
 * Bounded local chaos engineering for Helidon WebServer.
 */
@Features.Name("Chaos")
@Features.Description("Bounded local chaos engineering for Helidon WebServer")
@Features.Flavor({HelidonFlavor.SE, HelidonFlavor.MP})
@Features.Path({"WebServer", "Chaos"})
@Features.Preview
module io.helidon.extensions.chaos {
    requires transitive io.helidon.builder.api;
    requires transitive io.helidon.common;
    requires transitive io.helidon.config;
    requires transitive io.helidon.webserver;

    requires io.helidon.common.media.type;
    requires io.helidon.http;
    requires io.helidon.http.media.json;
    requires io.helidon.json;
    requires io.helidon.security;
    requires io.helidon.service.registry;
    requires io.helidon.webserver.security;

    requires static io.helidon.common.features.api;
    requires static io.helidon.config.metadata;

    exports io.helidon.extensions.chaos;

    provides io.helidon.webserver.spi.ServerFeatureProvider
            with io.helidon.extensions.chaos.ChaosServerFeatureProvider;
}
