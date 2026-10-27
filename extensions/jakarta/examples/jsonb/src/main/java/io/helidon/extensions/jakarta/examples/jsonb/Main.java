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

package io.helidon.extensions.jakarta.examples.jsonb;

import java.util.List;
import java.util.ServiceLoader;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.jakarta.http.media.jsonb.JsonbSupport;
import io.helidon.http.media.spi.MediaSupportProvider;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServer;


/**
 * Starts a server, exchanges a JSON entity using the selected integration, and stops.
 */
public final class Main {
    private Main() {
    }

    /**
     * Runs the example on the module path.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        verifyRuntime();
        Greeting request = new Greeting("Hello Jakarta");
        Greeting response = roundTrip(JsonbSupport.builder().build(), request);
        if (!request.getMessage().equals(response.getMessage())) {
            throw new IllegalStateException("JSON round trip changed the entity");
        }
        System.out.println("Jakarta JSON-B example: " + response.getMessage());
    }

    static Greeting roundTrip(JsonbSupport support, Greeting request) {
        WebServer server = WebServer.builder()
                .host("127.0.0.1")
                .port(0)
                .mediaContext(context -> context
                        .mediaSupportsDiscoverServices(false)
                        .addMediaSupport(support))
                .routing(routing -> routing.post("/echo", (req, res) -> res.send(req.content().as(Greeting.class))))
                .build()
                .start();
        try {
            WebClient client = WebClient.builder()
                    .baseUri("http://127.0.0.1:" + server.port() + "/echo")
                    .mediaContext(context -> context
                            .mediaSupportsDiscoverServices(false)
                            .addMediaSupport(support))
                    .build();
            return client.post()
                    .contentType(MediaTypes.APPLICATION_JSON)
                    .accept(MediaTypes.APPLICATION_JSON)
                    .submit(request, Greeting.class)
                    .entity();
        } finally {
            server.stop();
        }
    }

    private static void verifyRuntime() {
        if (!Main.class.getModule().isNamed()) {
            throw new IllegalStateException("Run the example on the module path");
        }
        var providers = ServiceLoader.load(MediaSupportProvider.class)
                .stream()
                .filter(provider -> provider.type().getName().contains(".media.jsonb."))
                .map(provider -> provider.type().getName())
                .toList();
        if (!providers.equals(List.of("io.helidon.extensions.jakarta.http.media.jsonb.JsonbMediaSupportProvider"))) {
            throw new IllegalStateException("Expected only the extension media provider: " + providers);
        }
        var forbidden = ModuleLayer.boot().modules().stream()
                .map(Module::getName)
                .filter(name -> name.equals("io.helidon.http.media.jsonp")
                        || name.equals("io.helidon.http.media.jsonb")
                        || name.equals("io.helidon.dbclient.jsonp")
                        || name.startsWith("io.helidon.") && (name.contains(".persistence") || name.contains(".transaction"))
                        || name.equals("jakarta.persistence")
                        || name.equals("jakarta.transaction"))
                .toList();
        if (!forbidden.isEmpty()) {
            throw new IllegalStateException("Unrelated integration resolved: " + forbidden);
        }
    }
}
