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

package io.helidon.extensions.neo4j.examples.imperative;

import java.util.List;

import io.helidon.config.Config;
import io.helidon.extensions.neo4j.examples.imperative.domain.MovieRepository;
import io.helidon.health.checks.DeadlockHealthCheck;
import io.helidon.health.checks.DiskSpaceHealthCheck;
import io.helidon.health.checks.HeapMemoryHealthCheck;
import io.helidon.extensions.neo4j.Neo4j;
import io.helidon.extensions.neo4j.health.Neo4jHealthCheck;
import io.helidon.logging.common.LogConfig;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.observe.ObserveFeature;
import io.helidon.webserver.observe.health.HealthObserver;
import io.helidon.webserver.spi.ServerFeature;

import org.neo4j.driver.Driver;

import static io.helidon.webserver.http.HttpRouting.Builder;

/**
 * The application main class.
 */
public class Main {
    /**
     * Cannot be instantiated.
     */
    private Main() {
    }

    /**
     * Application main entry point.
     *
     * @param args command line arguments.
     */
    public static void main(String[] args) {
        // load logging configuration
        LogConfig.configureRuntime();

        startServer();
    }

    static void startServer() {
        Config config = Config.create();
        Neo4j neo4j = Neo4j.create(config.get("neo4j"));
        Driver neo4jDriver = neo4j.driver();

        WebServer server = WebServer.builder()
                .config(config.get("server"))
                .featuresDiscoverServices(false)
                .features(features(neo4jDriver))
                .routing(it -> routing(it, neo4jDriver))
                .build()
                .start();

        System.out.println("WEB server is up! http://localhost:" + server.port() + "/api/movies");
    }

    static List<ServerFeature> features(Driver neo4jDriver) {
        Neo4jHealthCheck healthCheck = Neo4jHealthCheck.create(neo4jDriver);
        return List.of(ObserveFeature.just(HealthObserver.builder()
                                                             .useSystemServices(false)
                                                             .addCheck(HeapMemoryHealthCheck.create())
                                                             .addCheck(DiskSpaceHealthCheck.create())
                                                             .addCheck(DeadlockHealthCheck.create())
                                                             .addCheck(healthCheck)
                                                             .build()));
    }

    /**
     * Updates HTTP Routing.
     */
    static void routing(Builder routing, Driver neo4jDriver) {
        MovieService movieService = new MovieService(new MovieRepository(neo4jDriver));


        routing.register(movieService);
    }
}

