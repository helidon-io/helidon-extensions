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

package io.helidon.extensions.messaging.tests.jms.javax;

import java.util.Map;
import java.util.UUID;

import javax.jms.ConnectionFactory;
import javax.jms.JMSContext;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JmsJavaxConnectorIT {
    @Container
    private static final GenericContainer<?> JMS = new GenericContainer<>("apache/artemis:2.55.0")
            .withEnv("ARTEMIS_USER", "artemis")
            .withEnv("ARTEMIS_PASSWORD", "artemis")
            .withEnv("JAVA_ARGS_APPEND", "-Dbrokerconfig.minDiskFree=1073741824")
            .withExposedPorts(61616)
            .waitingFor(Wait.forLogMessage(".*AMQ221007:.*\n", 1));

    @Test
    @Timeout(90)
    void forwardsJakartaToJavax() {
        String source = "source-" + UUID.randomUUID();
        String target = "target-" + UUID.randomUUID();
        ConnectionFactory javaxFactory = new org.apache.activemq.ActiveMQConnectionFactory("artemis", "artemis", brokerUrl());
        try (ActiveMQConnectionFactory jakartaFactory = new ActiveMQConnectionFactory(brokerUrl(), "artemis", "artemis");
             jakarta.jms.JMSContext producer = jakartaFactory.createContext();
             JMSContext consumer = javaxFactory.createContext()) {
            var destination = consumer.createConsumer(consumer.createQueue(target));
            ServiceRegistryManager manager = start(jakartaFactory, javaxFactory, source, target, "jakarta", "javax");
            try {
                producer.createProducer().setProperty("route", "jakarta-to-javax")
                        .send(producer.createQueue(source), "from Jakarta JMS");
                var received = destination.receive(20_000);
                assertThat(received, notNullValue());
                assertThat(received.getBody(String.class), is("from Jakarta JMS"));
                assertThat(received.getStringProperty("route"), is("jakarta-to-javax"));
            } catch (javax.jms.JMSException e) {
                throw new IllegalStateException(e);
            } finally {
                manager.shutdown();
            }
        }
    }

    @Test
    @Timeout(90)
    void forwardsJavaxToJakarta() {
        String source = "source-" + UUID.randomUUID();
        String target = "target-" + UUID.randomUUID();
        ConnectionFactory javaxFactory = new org.apache.activemq.ActiveMQConnectionFactory("artemis", "artemis", brokerUrl());
        try (ActiveMQConnectionFactory jakartaFactory = new ActiveMQConnectionFactory(brokerUrl(), "artemis", "artemis");
             JMSContext producer = javaxFactory.createContext();
             jakarta.jms.JMSContext consumer = jakartaFactory.createContext()) {
            var destination = consumer.createConsumer(consumer.createQueue(target));
            ServiceRegistryManager manager = start(jakartaFactory, javaxFactory, source, target, "javax", "jakarta");
            try {
                producer.createProducer().setProperty("route", "javax-to-jakarta")
                        .send(producer.createQueue(source), "from javax JMS");
                var received = destination.receive(20_000);
                assertThat(received, notNullValue());
                assertThat(received.getBody(String.class), is("from javax JMS"));
                assertThat(received.getStringProperty("route"), is("javax-to-jakarta"));
            } catch (jakarta.jms.JMSException e) {
                throw new IllegalStateException(e);
            } finally {
                manager.shutdown();
            }
        }
    }

    private static String brokerUrl() {
        return "tcp://" + JMS.getHost() + ":" + JMS.getMappedPort(61616);
    }

    private static ServiceRegistryManager start(jakarta.jms.ConnectionFactory jakartaFactory,
                                                ConnectionFactory javaxFactory,
                                                String source,
                                                String target,
                                                String incoming,
                                                String outgoing) {
        Config config = Config.builder(ConfigSources.create(Map.of(
                        "messaging.connector.jakarta.type", "helidon-jms",
                        "messaging.connector.jakarta.username", "artemis",
                        "messaging.connector.jakarta.password", "artemis",
                        "messaging.connector.javax.type", "helidon-jms-javax",
                        "messaging.connector.javax.username", "artemis",
                        "messaging.connector.javax.password", "artemis",
                        "messaging.incoming.source.connector", incoming,
                        "messaging.incoming.source.destination", source,
                        "messaging.outgoing.target.connector", outgoing,
                        "messaging.outgoing.target.destination", target)))
                .disableEnvironmentVariablesSource()
                .disableSystemPropertiesSource()
                .build();
        ServiceRegistryConfig registryConfig = ServiceRegistryConfig.builder()
                .discoverServices(false)
                .discoverServicesFromServiceLoader(false)
                .useBinding(false)
                .putContractInstance(Config.class, config)
                .putContractInstance(jakarta.jms.ConnectionFactory.class, jakartaFactory)
                .putContractInstance(ConnectionFactory.class, javaxFactory)
                .build();
        return ServiceRegistryManager.start(ApplicationBinding.create(), registryConfig);
    }
}
