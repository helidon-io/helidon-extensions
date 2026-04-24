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

package io.helidon.extensions.gson.examples.imperative;

import io.helidon.config.Config;
import io.helidon.extensions.gson.media.GsonSupport;
import io.helidon.logging.common.LogConfig;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;

public class Main {
    private Main() {
    }

    public static void main(String[] args) {
        LogConfig.configureRuntime();

        Config config = Config.create();
        WebServer server = startServer(config);

        System.out.println("Server started on: http://localhost:" + server.port() + "/hello");
    }

    static WebServer startServer(Config config) {
        WebServerConfig.Builder server = WebServer.builder()
                .config(config.get("server"));

        configureServer(server, config);
        return server.build().start();
    }

    static void configureServer(WebServerConfig.Builder server, Config config) {
        server.mediaContext(context -> {
            context.mediaSupportsDiscoverServices(false);
            context.addMediaSupport(GsonSupport.create(config.get("gson")));
        });
        server.routing(routing -> routing.register("/hello", GreetingService.create(config)));
    }
}
