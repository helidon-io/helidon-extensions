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

package io.helidon.extensions.messaging.examples.declarative;

import java.time.Duration;
import java.util.Map;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.http.Status;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DeclarativeMessagingIT {
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true");

    @Test
    @Timeout(90)
    void receivesMessageSentOverHttp() {
        Config config = Config.builder(ConfigSources.create(Map.of(
                        "server.port", "0",
                        "messaging.connector.kafka-1.bootstrap-servers.0", KAFKA.getBootstrapServers())),
                                      ConfigSources.classpath("application.yaml"))
                .disableEnvironmentVariablesSource()
                .disableSystemPropertiesSource()
                .build();

        ServiceRegistryConfig registryConfig = ServiceRegistryConfig.builder()
                .discoverServices(false)
                .discoverServicesFromServiceLoader(false)
                // Resolve contracts at runtime so the supplied Config replaces the default provider.
                .useBinding(false)
                .putContractInstance(Config.class, config)
                .build();
        ServiceRegistryManager manager = ServiceRegistryManager.start(ApplicationBinding.create(), registryConfig);
        try {
            WebServer server = manager.registry().get(WebServer.class);
            Http1Client client = Http1Client.builder()
                    .baseUri("http://127.0.0.1:" + server.port())
                    .build();
            String message = "created from HTTP";
            try (var response = client.post("/messages")
                    .contentType(MediaTypes.TEXT_PLAIN)
                    .submit(message)) {
                assertThat(response.status(), is(Status.NO_CONTENT_204));
            }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(client.get("/messages/latest").requestEntity(String.class), is(message)));
        } finally {
            manager.shutdown();
        }
    }
}
