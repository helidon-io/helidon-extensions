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

package io.helidon.extensions.jakarta.examples.jsonp;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServer;

import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Runs a generated REST endpoint with the discovered JSON-P extension.
 */
@Service.GenerateBinding
public final class Main {
    private Main() {
    }

    /**
     * Starts the service registry, exchanges an entity, and shuts down.
     */
    static void main() {
        JsonObject response = exchange();
        System.out.println("Jakarta declarative JSON-P example: " + response);
    }

    static JsonObject exchange() {
        var manager = ServiceRegistryManager.start(ApplicationBinding.create());
        try {
            var server = Services.get(WebServer.class);
            return WebClient.create().post("http://127.0.0.1:" + server.port() + "/echo")
                    .contentType(MediaTypes.APPLICATION_JSON)
                    .accept(MediaTypes.APPLICATION_JSON)
                    .submit(Json.createObjectBuilder().add("message", "Hello declarative Jakarta").build(), JsonObject.class)
                    .entity();
        } finally {
            manager.shutdown();
        }
    }
}
