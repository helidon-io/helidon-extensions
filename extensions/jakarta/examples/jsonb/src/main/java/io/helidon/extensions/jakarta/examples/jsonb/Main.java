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

import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.jakarta.http.media.jsonb.JsonbSupport;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServer;

/**
 * Starts a server, exchanges a JSON entity using the selected integration, and stops.
 */
public final class Main {
    private Main() {
    }

    /**
     * Sends a JSON request to the server and prints its response.
     */
    static void main() {
        Greeting request = new Greeting("Hello Jakarta");
        Greeting response = roundTrip(JsonbSupport.builder().build(), request);
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
}
