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
import java.util.HashMap;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulsarConnectorConfigTest {
    private static final String CHANNEL = "orders";
    private static final String TOPIC = "persistent://public/default/orders";

    @Test
    void requiredOptionsAndDefaults() {
        assertThrows(RuntimeException.class, () -> PulsarConnector.builder().name("pulsar").build());
        assertThrows(RuntimeException.class, () -> PulsarOutgoingConfig.builder().topic(TOPIC).build());

        PulsarConnector connector = commonBuilder().build();
        assertThat(connector.prototype().schema(), is(PulsarSchemaType.STRING));
        assertThat(connector.prototype().schemaProvider().isEmpty(), is(true));
        PulsarConnectorConfig config = connector.prototype();
        assertThat(incomingBuilder().build().schema().isEmpty(), is(true));
        assertThat(config.topic().isEmpty(), is(true));
        assertThrows(IllegalArgumentException.class,
                     () -> connector.incoming(PulsarIncomingConfig.builder().channelName(CHANNEL).build()));
        assertThrows(IllegalArgumentException.class,
                     () -> connector.outgoing(PulsarOutgoingConfig.builder().channelName(CHANNEL).build()));
        assertThat(config.subscriptionType(), is(PulsarSubscriptionType.EXCLUSIVE));
        assertThat(config.subscriptionInitialPosition(), is(PulsarSubscriptionInitialPosition.LATEST));
        assertThat(config.batchIndexAcknowledgmentEnabled(), is(false));
        assertThat(config.receiverQueueSize(), is(1));
        assertThat(config.maxMessageBytes(), is(10 * 1024 * 1024));
        assertThat(config.receiveTimeout(), is(Duration.ofMillis(100)));
        assertThat(config.settlementTimeout(), is(Duration.ofSeconds(30)));
    }

    @Test
    void readsCommonAndChannelConfiguration() {
        PulsarConnector connector = PulsarConnector.create(config(Map.of(
                "name", "pulsar",
                "service-url", "pulsar://broker:6650",
                "schema", "BYTES",
                "schema-provider", "orders-json",
                "client-properties.authPluginClassName", "example.Auth")));
        PulsarIncomingConfig incoming = PulsarIncomingConfig.create(config(Map.of(
                "channel-name", CHANNEL,
                "topic", TOPIC,
                "schema", "INT32",
                "subscription-name", "orders-subscription",
                "consumer-properties.consumerName", "orders-consumer")));
        PulsarOutgoingConfig outgoing = PulsarOutgoingConfig.create(config(Map.of(
                "channel-name", CHANNEL,
                "topic", TOPIC,
                "producer-properties.producerName", "orders-producer")));

        assertThat(connector.prototype().schema(), is(PulsarSchemaType.BYTES));
        assertThat(connector.prototype().schemaProvider().orElseThrow(), is("orders-json"));
        assertThat(connector.prototype().clientProperties(), is(Map.of("authPluginClassName", "example.Auth")));
        assertThat(incoming.schema().orElseThrow(), is(PulsarSchemaType.INT32));
        assertThat(incoming.subscriptionName().orElseThrow(), is("orders-subscription"));
        assertThat(incoming.consumerProperties(), is(Map.of("consumerName", "orders-consumer")));
        assertThat(outgoing.producerProperties(), is(Map.of("producerName", "orders-producer")));
    }

    @Test
    void commonDefaultsAndChannelOverrides() {
        PulsarConnectorConfig common = commonBuilder()
                .topic(TOPIC)
                .schema(PulsarSchemaType.BYTES)
                .subscriptionName("shared-subscription")
                .subscriptionType(PulsarSubscriptionType.SHARED)
                .subscriptionInitialPosition(PulsarSubscriptionInitialPosition.EARLIEST)
                .batchIndexAcknowledgmentEnabled(true)
                .receiverQueueSize(8)
                .maxMessageBytes(2048)
                .receiveTimeout(Duration.ofSeconds(2))
                .negativeAckRedeliveryDelay(Duration.ofSeconds(3))
                .settlementTimeout(Duration.ofSeconds(4))
                .sendTimeout(Duration.ofSeconds(5))
                .closeTimeout(Duration.ofSeconds(6))
                .clientProperties(Map.of("shared", "client", "overridden", "common"))
                .consumerProperties(Map.of("shared", "consumer", "overridden", "common"))
                .producerProperties(Map.of("shared", "producer", "overridden", "common"))
                .buildPrototype();
        var inheritedIncoming = PulsarConnectorConfigSupport.incoming(
                common, PulsarIncomingConfig.builder().channelName(CHANNEL).build());
        var inheritedOutgoing = PulsarConnectorConfigSupport.outgoing(
                common, PulsarOutgoingConfig.builder().channelName(CHANNEL).build());

        assertThat(inheritedIncoming.topic(), is(TOPIC));
        assertThat(inheritedIncoming.schema(), is(PulsarSchemaType.BYTES));
        assertThat(inheritedIncoming.subscriptionName().orElseThrow(), is("shared-subscription"));
        assertThat(inheritedIncoming.subscriptionType(), is(PulsarSubscriptionType.SHARED));
        assertThat(inheritedIncoming.subscriptionInitialPosition(), is(PulsarSubscriptionInitialPosition.EARLIEST));
        assertThat(inheritedIncoming.batchIndexAcknowledgmentEnabled(), is(true));
        assertThat(inheritedIncoming.receiverQueueSize(), is(8));
        assertThat(inheritedIncoming.maxMessageBytes(), is(2048));
        assertThat(inheritedIncoming.receiveTimeout(), is(Duration.ofSeconds(2)));
        assertThat(inheritedIncoming.negativeAckRedeliveryDelay(), is(Duration.ofSeconds(3)));
        assertThat(inheritedIncoming.settlementTimeout(), is(Duration.ofSeconds(4)));
        assertThat(inheritedIncoming.closeTimeout(), is(Duration.ofSeconds(6)));
        assertThat(inheritedOutgoing.sendTimeout(), is(Duration.ofSeconds(5)));
        assertThat(inheritedOutgoing.closeTimeout(), is(Duration.ofSeconds(6)));

        var incoming = PulsarConnectorConfigSupport.incoming(common, PulsarIncomingConfig.create(config(Map.ofEntries(
                Map.entry("channel-name", CHANNEL),
                Map.entry("topic", "override-topic"),
                Map.entry("service-url", "pulsar://other:6650"),
                Map.entry("schema", "INT32"),
                Map.entry("subscription-name", "channel-subscription"),
                Map.entry("subscription-type", "EXCLUSIVE"),
                Map.entry("batch-index-acknowledgment-enabled", "false"),
                Map.entry("receiver-queue-size", "1"),
                Map.entry("receive-timeout", "PT0.2S"),
                Map.entry("settlement-timeout", "PT0.3S"),
                Map.entry("client-properties.overridden", "channel"),
                Map.entry("consumer-properties.overridden", "channel")))));
        var outgoing = PulsarConnectorConfigSupport.outgoing(common, PulsarOutgoingConfig.builder()
                .channelName(CHANNEL)
                .topic("outgoing-topic")
                .sendTimeout(Duration.ofSeconds(9))
                .closeTimeout(Duration.ZERO)
                .producerProperties(Map.of("overridden", "channel"))
                .build());

        assertThat(incoming.topic(), is("override-topic"));
        assertThat(incoming.serviceUrl(), is("pulsar://other:6650"));
        assertThat(incoming.schema(), is(PulsarSchemaType.INT32));
        assertThat(incoming.subscriptionName().orElseThrow(), is("channel-subscription"));
        assertThat(incoming.subscriptionType(), is(PulsarSubscriptionType.EXCLUSIVE));
        assertThat(incoming.batchIndexAcknowledgmentEnabled(), is(false));
        assertThat(incoming.receiverQueueSize(), is(1));
        assertThat(incoming.receiveTimeout(), is(Duration.ofMillis(200)));
        assertThat(incoming.settlementTimeout(), is(Duration.ofMillis(300)));
        assertThat(incoming.clientProperties(), is(Map.of("shared", "client", "overridden", "channel")));
        assertThat(incoming.consumerProperties(), is(Map.of("shared", "consumer", "overridden", "channel")));
        assertThat(outgoing.topic(), is("outgoing-topic"));
        assertThat(outgoing.sendTimeout(), is(Duration.ofSeconds(9)));
        assertThat(outgoing.closeTimeout(), is(Duration.ZERO));
        assertThat(outgoing.producerProperties(), is(Map.of("shared", "producer", "overridden", "channel")));
        assertThat(common.topic().orElseThrow(), is(TOPIC));
        assertThat(common.clientProperties().get("overridden"), is("common"));
    }

    @Test
    void passThroughPropertiesAreConfidential() {
        String secret = "do-not-render-this-token";
        PulsarConnectorConfig.Builder common = commonBuilder().clientProperties(Map.of("authParams", secret));
        PulsarIncomingConfig.Builder incoming = incomingBuilder().consumerProperties(Map.of("consumerName", secret));
        PulsarOutgoingConfig.Builder outgoing = outgoingBuilder().producerProperties(Map.of("producerName", secret));

        assertThat(common.toString(), not(containsString(secret)));
        assertThat(incoming.toString(), not(containsString(secret)));
        assertThat(outgoing.toString(), not(containsString(secret)));
        assertThat(common.buildPrototype().toString(), containsString("clientProperties=****"));
        assertThat(incoming.build().toString(), containsString("consumerProperties=****"));
        assertThat(outgoing.build().toString(), containsString("producerProperties=****"));
        assertThat(common.buildPrototype().toString(), not(containsString(secret)));
        assertThat(incoming.build().toString(), not(containsString(secret)));
        assertThat(outgoing.build().toString(), not(containsString(secret)));
    }

    @Test
    void passThroughPropertiesRejectNullEntries() {
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "value");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("key", null);

        assertThrows(NullPointerException.class, () -> commonBuilder().clientProperties(nullKey).build());
        assertThrows(NullPointerException.class, () -> commonBuilder().addClientProperties(nullValue).build());
        assertThrows(NullPointerException.class, () -> incomingBuilder().consumerProperties(nullKey).build());
        assertThrows(NullPointerException.class, () -> incomingBuilder().addConsumerProperties(nullValue).build());
        assertThrows(NullPointerException.class, () -> outgoingBuilder().producerProperties(nullKey).build());
        assertThrows(NullPointerException.class, () -> outgoingBuilder().addProducerProperties(nullValue).build());
    }

    @Test
    void validatesChannelBounds() {
        assertThrows(IllegalArgumentException.class, () -> incomingBuilder().receiverQueueSize(0).build());
        assertThrows(IllegalArgumentException.class, () -> incomingBuilder().maxMessageBytes(0).build());
        assertThrows(IllegalArgumentException.class, () -> incomingBuilder().receiveTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                     () -> outgoingBuilder().sendTimeout(Duration.ofMillis(Integer.MAX_VALUE).plusMillis(1)).build());
        assertThrows(IllegalArgumentException.class, () -> incomingBuilder().settlementTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder().negativeAckRedeliveryDelay(Duration.ofNanos(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> incomingBuilder().closeTimeout(Duration.ofNanos(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> outgoingBuilder().closeTimeout(Duration.ofNanos(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> commonBuilder().schemaProvider(" ").build());
        assertThrows(IllegalArgumentException.class, () -> incomingBuilder().schemaProvider(" ").build());
        assertThrows(IllegalArgumentException.class, () -> outgoingBuilder().schemaProvider(" ").build());
        incomingBuilder().closeTimeout(Duration.ZERO).build();
        outgoingBuilder().closeTimeout(Duration.ZERO).build();
        assertThat(incomingBuilder().batchIndexAcknowledgmentEnabled(true).build()
                           .batchIndexAcknowledgmentEnabled().orElseThrow(), is(true));
    }

    private static Config config(Map<String, String> values) {
        return Config.just(ConfigSources.create(values));
    }

    private static PulsarConnectorConfig.Builder commonBuilder() {
        return PulsarConnector.builder().name("pulsar").serviceUrl("pulsar://localhost:6650");
    }

    private static PulsarIncomingConfig.Builder incomingBuilder() {
        return PulsarIncomingConfig.builder().channelName(CHANNEL).topic(TOPIC);
    }

    private static PulsarOutgoingConfig.Builder outgoingBuilder() {
        return PulsarOutgoingConfig.builder().channelName(CHANNEL).topic(TOPIC);
    }
}
