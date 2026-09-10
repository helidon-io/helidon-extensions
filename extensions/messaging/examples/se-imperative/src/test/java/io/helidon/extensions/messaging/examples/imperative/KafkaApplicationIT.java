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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.http.Status;
import io.helidon.webclient.http1.Http1Client;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class KafkaApplicationIT {
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Test
    @Timeout(90)
    void publishesHttpMessagesAndReceivesOrders() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String ordersTopic = "orders-" + suffix;
        String messagesTopic = "http-messages-" + suffix;
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(ordersTopic, 1, (short) 1),
                                       new NewTopic(messagesTopic, 1, (short) 1)))
                    .all().get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        Config config = Config.builder(ConfigSources.create(Map.of(
                        "server.port", "0",
                        "server.host", "127.0.0.1",
                        "app.kafka-bootstrap-servers", KAFKA.getBootstrapServers(),
                        "app.orders-topic", ordersTopic,
                        "app.messages-topic", messagesTopic,
                        "app.kafka-group-id", "inventory-" + suffix)), ConfigSources.classpath("application.yaml"))
                .disableEnvironmentVariablesSource()
                .disableSystemPropertiesSource()
                .build();

        try (KafkaApplication application = KafkaApplication.start(config);
             KafkaProducer<String, String> producer = new KafkaProducer<>(
                     Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                            ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000),
                     new StringSerializer(), new StringSerializer());
             KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                     Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                     new StringDeserializer(), new StringDeserializer())) {
            Http1Client client = Http1Client.builder()
                    .baseUri("http://127.0.0.1:" + application.server().port())
                    .keepAlive(false)
                    .readTimeout(Duration.ofSeconds(5))
                    .build();
            var partition = new TopicPartition(messagesTopic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));

            try (var response = client.post("/messages")
                    .contentType(MediaTypes.TEXT_PLAIN)
                    .submit("created from HTTP")) {
                assertThat(response.status(), is(Status.NO_CONTENT_204));
            }
            List<String> published = new ArrayList<>();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (published.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(100)).forEach(record -> published.add(record.value()));
            }
            assertThat("HTTP request must reach the Kafka topic", published, is(List.of("created from HTTP")));

            producer.send(new ProducerRecord<>(ordersTopic, "order-42"))
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            String received = client.get("/orders/latest").requestEntity(String.class);
            deadline = System.nanoTime() + WAIT.toNanos();
            while (!received.equals("order-42") && System.nanoTime() < deadline) {
                Thread.sleep(25);
                received = client.get("/orders/latest").requestEntity(String.class);
            }
            assertThat("Kafka order must reach the application's incoming channel", received, is("order-42"));
        }
    }

    @Test
    @Timeout(90)
    void closesKafkaConsumerWhenHttpConfigurationThrowsError() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String ordersTopic = "orders-" + suffix;
        String messagesTopic = "http-messages-" + suffix;
        String group = "inventory-" + suffix;
        var expectedFailure = new LinkageError("HTTP server configuration failed");
        Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
        try {
            admin.createTopics(List.of(new NewTopic(ordersTopic, 1, (short) 1),
                                       new NewTopic(messagesTopic, 1, (short) 1)))
                    .all().get(WAIT.toSeconds(), TimeUnit.SECONDS);

            Config config = Config.builder(ConfigSources.create(Map.of(
                            "server.port", "0",
                            "server.host", "127.0.0.1",
                            "app.kafka-bootstrap-servers", KAFKA.getBootstrapServers(),
                            "app.orders-topic", ordersTopic,
                            "app.messages-topic", messagesTopic,
                            "app.kafka-group-id", group)))
                    .disableEnvironmentVariablesSource()
                    .disableSystemPropertiesSource()
                    .addMapper(Integer.class, node -> {
                        if (node.key().toString().equals("server.port")) {
                            // Observe an active connection before failing HTTP setup.
                            awaitConsumerGroupMembers(admin, group, 1);
                            throw expectedFailure;
                        }
                        return Integer.parseInt(node.asString().get());
                    })
                    .build();

            LinkageError failure = assertThrows(LinkageError.class, () -> {
                try (KafkaApplication _ = KafkaApplication.start(config)) {
                    // Close the application if startup unexpectedly succeeds.
                }
            });
            assertThat("HTTP startup must propagate the original Error", failure, sameInstance(expectedFailure));
            awaitConsumerGroupMembers(admin, group, 0);
        } finally {
            admin.close(Duration.ofSeconds(5));
        }
    }

    private static void awaitConsumerGroupMembers(Admin admin, String group, int expectedMembers) {
        int activeMembers = -1;
        long deadline = System.nanoTime() + WAIT.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                try {
                    activeMembers = admin.describeConsumerGroups(List.of(group),
                                                                 new DescribeConsumerGroupsOptions().timeoutMs(1000))
                            .describedGroups()
                            .get(group)
                            .get(Math.min(TimeUnit.SECONDS.toNanos(1), deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                            .members()
                            .size();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof GroupIdNotFoundException) {
                        activeMembers = 0;
                    } else if (!(e.getCause() instanceof RetriableException)) {
                        throw new IllegalStateException("Cannot describe consumer group " + group, e);
                    }
                } catch (TimeoutException _) {
                    // Retry transient Admin delays within the overall deadline.
                }
                if (activeMembers == expectedMembers) {
                    return;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting consumer group " + group, e);
        }
        assertThat("active members in consumer group " + group, activeMembers, is(expectedMembers));
    }
}
