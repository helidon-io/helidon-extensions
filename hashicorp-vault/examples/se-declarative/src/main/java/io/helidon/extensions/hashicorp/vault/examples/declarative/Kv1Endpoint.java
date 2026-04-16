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

import io.helidon.extensions.hashicorp.vault.Secret;
import io.helidon.extensions.hashicorp.vault.secrets.kv1.Kv1Secrets;
import io.helidon.extensions.hashicorp.vault.sys.Sys;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

@SuppressWarnings({"deprecation", "helidon:api:incubating"})
@Service.Singleton
@RestServer.Endpoint
@Http.Path("kv1")
class Kv1Endpoint {
    private final Sys sys;
    private final Kv1Secrets secrets;

    @Service.Inject
    Kv1Endpoint(Sys sys, Kv1Secrets secrets) {
        this.sys = sys;
        this.secrets = secrets;
    }

    @Http.GET
    @Http.Path("/enable")
    String enableEngine() {
        sys.enableEngine(Kv1Secrets.ENGINE);
        return "KV1 Secret engine enabled";
    }

    @Http.GET
    @Http.Path("/create")
    String createSecrets(ServerRequest req, ServerResponse res) {
        secrets.create("first/secret", Map.of("key", "secretValue"));
        return "Created secret on path /first/secret";
    }

    @Http.GET
    @Http.Path("/secrets/{path:.*}")
    Optional<String> getSecret(@Http.PathParam("path") String path) {
        return secrets.get(path)
                .map(Secret::values)
                .map(Map::toString);
    }

    @Http.DELETE
    @Http.Path("/secrets/{path:.*}")
    String deleteSecret(@Http.PathParam("path") String path) {
        secrets.delete(path);
        return "Deleted secret on path " + path;
    }

    @Http.GET
    @Http.Path("/disable")
    String disableEngine() {
        return "KV1 Secret engine disabled";
    }

}
