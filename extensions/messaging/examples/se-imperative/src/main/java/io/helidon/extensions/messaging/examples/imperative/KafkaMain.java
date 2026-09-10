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

package io.helidon.extensions.messaging.examples.imperative;

import io.helidon.Main;
import io.helidon.config.Config;
import io.helidon.logging.common.LogConfig;

/**
 * Starts the imperative Kafka messaging example.
 */
public final class KafkaMain {
    private KafkaMain() {
    }

    /**
     * Start the application and register its shutdown handler.
     */
    static void main() {
        LogConfig.configureRuntime();
        KafkaApplication application = KafkaApplication.start(Config.create());
        Main.addShutdownHandler(application::close);
        System.out.println("Server started on: http://localhost:" + application.server().port());
    }
}
