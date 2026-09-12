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

package io.helidon.extensions.messaging.jms.examples.imperative;

import io.helidon.config.Config;
import io.helidon.logging.common.LogConfig;
import io.helidon.webserver.WebServer;

/**
 * Starts the imperative JMS messaging example.
 */
public final class JmsMain {
    private JmsMain() {
    }

    /**
     * Start the application.
     */
    static void main() {
        LogConfig.configureRuntime();
        WebServer server = start(Config.create());
        System.out.println("Server started on: http://localhost:" + server.port());
    }

    static WebServer start(Config config) {
        return WebServer.builder()
                .config(config.get("server"))
                .routing(routing -> routing.register(new JmsService(config.get("app"))))
                .build()
                .start();
    }
}
