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

package io.helidon.extensions.hashicorp.vault.examples.declarative;

import java.util.Map;
import java.util.Optional;

import io.helidon.extensions.hashicorp.vault.secrets.kv2.Kv2Secret;
import io.helidon.extensions.hashicorp.vault.secrets.kv2.Kv2Secrets;
import io.helidon.extensions.hashicorp.vault.sys.Sys;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

@SuppressWarnings("deprecation")
@Service.Singleton
@RestServer.Endpoint
@Http.Path("kv2")
class Kv2Endpoint {
    private final Sys sys;
    private final Kv2Secrets secrets;

    @Service.Inject
    Kv2Endpoint(Sys sys, Kv2Secrets secrets) {
        this.sys = sys;
        this.secrets = secrets;
    }

    @Http.GET
    @Http.Path("/create")
    String createSecrets() {
        secrets.create("first/secret", Map.of("key", "secretValue"));
        return "Created secret on path /first/secret";
    }

    @Http.GET
    @Http.Path("/secrets/{path:.*}")
    Optional<String> getSecret(@Http.PathParam("path") String path) {
        return secrets.get(path)
                .map(Kv2Secret::values)
                .map(Map::toString);
    }

    @Http.DELETE
    @Http.Path("/secrets/{path:.*}")
    String deleteSecret(@Http.PathParam("path") String path) {
        secrets.deleteAll(path);
        return "Deleted secret on path " + path;
    }
}
