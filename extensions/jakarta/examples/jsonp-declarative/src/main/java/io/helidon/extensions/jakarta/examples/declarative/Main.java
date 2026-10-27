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

package io.helidon.extensions.jakarta.examples.declarative;

import java.util.List;
import java.util.ServiceLoader;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.media.spi.MediaSupportProvider;
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
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        if (!Main.class.getModule().isNamed()) {
            throw new IllegalStateException("Run the example on the module path");
        }
        var providers = ServiceLoader.load(MediaSupportProvider.class).stream()
                .filter(provider -> provider.type().getName().contains(".media.jsonp."))
                .map(provider -> provider.type().getName())
                .toList();
        if (!providers.equals(List.of("io.helidon.extensions.jakarta.http.media.jsonp.JsonpMediaSupportProvider"))) {
            throw new IllegalStateException("Expected the extension JSON-P provider: " + providers);
        }
        JsonObject response = exchange();
        if (!response.getString("message").equals("Hello declarative Jakarta")) {
            throw new IllegalStateException("Unexpected declarative response: " + response);
        }
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
