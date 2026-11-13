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

package io.helidon.extensions.jackson.jackson2.examples.imperative;

import io.helidon.config.Config;
import io.helidon.extensions.jackson.jackson2.http.media.JacksonSupport;
import io.helidon.logging.common.LogConfig;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        LogConfig.configureRuntime();
        var builder = WebServer.builder().config(Config.create().get("server"));
        configureServer(builder);
        var server = builder.build().start();
        System.out.println("Server started on http://localhost:" + server.port() + "/echo");
    }

    static void configureServer(WebServerConfig.Builder server) {
        server.mediaContext(media -> media.mediaSupportsDiscoverServices(false)
                .addMediaSupport(JacksonSupport.create()));
        server.routing(routing -> routing.post("/echo", (request, response) -> {
            Greeting greeting = request.content().as(Greeting.class);
            response.send(greeting);
        }));
    }
}
