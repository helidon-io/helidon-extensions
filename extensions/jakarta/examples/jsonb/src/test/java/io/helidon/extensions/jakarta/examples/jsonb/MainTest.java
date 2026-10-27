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

import java.util.Map;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.jakarta.http.media.jsonb.JsonbSupport;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServer;

import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.config.PropertyNamingStrategy;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class MainTest {
    @Test
    void clientServerRoundTrip() {
        var response = Main.roundTrip(JsonbSupport.builder().build(), new Greeting("Příliš žluťoučký kůň"));
        assertThat(response.getMessage(), is("Příliš žluťoučký kůň"));
    }

    @Test
    void customJsonbInstance() throws Exception {
        try (var jsonb = JsonbBuilder.create(new JsonbConfig()
                .withPropertyNamingStrategy(PropertyNamingStrategy.UPPER_CAMEL_CASE))) {
            var support = JsonbSupport.builder().jsonb(jsonb).build();
            assertCustomHttpRepresentation(support);
        }
    }

    @Test
    void configurationProperties() {
        var config = Config.create(ConfigSources.create(Map.of(
                "properties.jsonb.property-naming-strategy", PropertyNamingStrategy.UPPER_CAMEL_CASE)));
        var support = JsonbSupport.builder().config(config).build();
        assertCustomHttpRepresentation(support);
    }

    private static void assertCustomHttpRepresentation(JsonbSupport support) {
        WebServer server = WebServer.builder()
                .host("127.0.0.1")
                .port(0)
                .mediaContext(context -> context
                        .mediaSupportsDiscoverServices(false)
                        .addMediaSupport(support))
                .routing(routing -> routing
                        .get("/server-write", (req, res) -> res.send(new Greeting("server-write")))
                        .post("/server-read", (req, res) -> res.send(req.content().as(Greeting.class).getMessage()))
                        .get("/client-read", (req, res) -> {
                            res.headers().contentType(MediaTypes.APPLICATION_JSON);
                            res.send("{\"Message\":\"client-read\"}");
                        })
                        .post("/client-write", (req, res) -> res.send(req.content().as(String.class))))
                .build()
                .start();
        try {
            WebClient client = WebClient.builder()
                    .baseUri("http://127.0.0.1:" + server.port())
                    .mediaContext(context -> context
                            .mediaSupportsDiscoverServices(false)
                            .addMediaSupport(support))
                    .build();

            var serverJson = client.get("/server-write")
                    .accept(MediaTypes.APPLICATION_JSON)
                    .request(String.class)
                    .entity();
            assertThat("Server serialization uses the custom property name",
                       serverJson, is("{\"Message\":\"server-write\"}"));

            var serverMessage = client.post("/server-read")
                    .contentType(MediaTypes.APPLICATION_JSON)
                    .submit("{\"Message\":\"server-read\"}", String.class)
                    .entity();
            assertThat("Server deserialization reads the custom property name", serverMessage, is("server-read"));

            var clientGreeting = client.get("/client-read")
                    .accept(MediaTypes.APPLICATION_JSON)
                    .request(Greeting.class)
                    .entity();
            assertThat("Client deserialization reads the custom property name",
                       clientGreeting.getMessage(), is("client-read"));

            var clientJson = client.post("/client-write")
                    .contentType(MediaTypes.APPLICATION_JSON)
                    .submit(new Greeting("client-write"), String.class)
                    .entity();
            assertThat("Client serialization uses the custom property name",
                       clientJson, is("{\"Message\":\"client-write\"}"));
        } finally {
            server.stop();
        }
    }
}
