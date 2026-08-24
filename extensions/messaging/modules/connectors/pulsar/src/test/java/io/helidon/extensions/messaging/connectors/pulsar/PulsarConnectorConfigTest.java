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

package io.helidon.extensions.messaging.connectors.pulsar;

import java.time.Duration;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.messaging.ConnectorConfig;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulsarConnectorConfigTest {
    private static final String CHANNEL = "orders";
    private static final String TOPIC = "persistent://public/default/orders";

    @Test
    void requiredOptionsAndDefaults() {
        assertThrows(RuntimeException.class, () -> builder().topic(TOPIC).build());
        assertThrows(RuntimeException.class, () -> builder().serviceUrl("pulsar://localhost:6650").build());

        PulsarConnectorConfig config = completeBuilder().build();
        assertThat(config.schema(), is(PulsarSchemaType.STRING));
        assertThat(config.subscriptionType(), is(PulsarSubscriptionType.EXCLUSIVE));
        assertThat(config.subscriptionInitialPosition(), is(PulsarSubscriptionInitialPosition.LATEST));
        assertThat(config.batchIndexAcknowledgmentEnabled(), is(false));
        assertThat(config.receiverQueueSize(), is(1));
        assertThat(config.maxMessageBytes(), is(10 * 1024 * 1024));
        assertThat(config.receiveTimeout(), is(Duration.ofMillis(100)));
        assertThat(config.settlementTimeout(), is(Duration.ofSeconds(30)));
    }

    @Test
    void readsNestedClasspathClientProperties() {
        PulsarConnectorConfig config = PulsarConnectorConfig.create(Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("direction", "INCOMING"),
                Map.entry(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, CHANNEL),
                Map.entry(ConnectorConfig.CONNECTOR_ATTRIBUTE, PulsarConnectorProvider.CONNECTOR_TYPE),
                Map.entry(PulsarConnectorConfig.SERVICE_URL_PROPERTY, "pulsar://broker:6650"),
                Map.entry(PulsarConnectorConfig.TOPIC_PROPERTY, TOPIC),
                Map.entry(PulsarConnectorConfig.SCHEMA_PROPERTY, "BYTES"),
                Map.entry(PulsarConnectorConfig.SUBSCRIPTION_NAME_PROPERTY, "orders-subscription"),
                Map.entry("client-properties.authPluginClassName", "example.Auth"),
                Map.entry("consumer-properties.consumerName", "orders-consumer"),
                Map.entry("producer-properties.producerName", "orders-producer")))));

        assertThat(config.schema(), is(PulsarSchemaType.BYTES));
        assertThat(config.subscriptionName().orElseThrow(), is("orders-subscription"));
        assertThat(config.clientProperties(), is(Map.of("authPluginClassName", "example.Auth")));
        assertThat(config.consumerProperties(), is(Map.of("consumerName", "orders-consumer")));
        assertThat(config.producerProperties(), is(Map.of("producerName", "orders-producer")));
    }

    @Test
    void passThroughPropertiesAreConfidential() {
        String secret = "do-not-render-this-token";
        PulsarConnectorConfig.Builder builder = completeBuilder()
                .clientProperties(Map.of("authParams", secret))
                .consumerProperties(Map.of("consumerName", secret))
                .producerProperties(Map.of("producerName", secret));

        assertThat(builder.toString(), not(containsString(secret)));
        PulsarConnectorConfig config = builder.build();
        assertThat(config.toString(), containsString("clientProperties=****"));
        assertThat(config.toString(), containsString("consumerProperties=****"));
        assertThat(config.toString(), containsString("producerProperties=****"));
        assertThat(config.toString(), not(containsString(secret)));
    }

    @Test
    void validatesBoundsAndDirection() {
        assertThrows(IllegalArgumentException.class,
                     () -> completeBuilder().receiverQueueSize(0).build());
        assertThrows(IllegalArgumentException.class,
                     () -> completeBuilder().maxMessageBytes(0).build());
        assertThrows(IllegalArgumentException.class,
                     () -> completeBuilder().receiveTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                     () -> completeBuilder().sendTimeout(Duration.ofMillis(Integer.MAX_VALUE).plusMillis(1)).build());
        assertThrows(IllegalArgumentException.class,
                     () -> completeBuilder().settlementTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                     () -> completeBuilder().negativeAckRedeliveryDelay(Duration.ofNanos(-1)).build());
        assertThrows(IllegalArgumentException.class,
                     () -> completeBuilder().closeTimeout(Duration.ofNanos(-1)).build());
        assertDoesNotThrow(() -> completeBuilder().closeTimeout(Duration.ZERO).build());

        PulsarConnectorProvider provider = new PulsarConnectorProvider();
        PulsarConnectorConfig outgoing = completeBuilder().build();
        PulsarConnectorConfig incoming = completeBuilder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .build();
        assertThrows(IllegalArgumentException.class, () -> provider.createIncomingConnector(outgoing));
        assertThrows(IllegalArgumentException.class, () -> provider.createOutgoingConnector(incoming));
        assertThat(completeBuilder().batchIndexAcknowledgmentEnabled(true).build()
                           .batchIndexAcknowledgmentEnabled(), is(true));
    }

    private static PulsarConnectorConfig.Builder completeBuilder() {
        return builder()
                .serviceUrl("pulsar://localhost:6650")
                .topic(TOPIC);
    }

    private static PulsarConnectorConfig.Builder builder() {
        return PulsarConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel(CHANNEL)
                .connector(PulsarConnectorProvider.CONNECTOR_TYPE);
    }
}
