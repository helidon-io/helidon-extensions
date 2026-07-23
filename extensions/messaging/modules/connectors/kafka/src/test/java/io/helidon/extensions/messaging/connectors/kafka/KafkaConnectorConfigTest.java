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

package io.helidon.extensions.messaging.connectors.kafka;

import java.time.Duration;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.ConnectorConfig;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaConnectorConfigTest {
    private static final String CHANNEL = "audit";
    private static final String TOPIC = "audit-events";

    @Test
    void testBootstrapServersAndTopicAreRequired() {
        assertThrows(RuntimeException.class,
                     () -> builder()
                             .topic(TOPIC)
                             .build());
        assertThrows(RuntimeException.class,
                     () -> builder()
                             .bootstrapServers("localhost:9092")
                             .build());
    }

    @Test
    void testCreateFromConfigReadsNestedKafkaProperties() {
        KafkaConnectorConfig config = KafkaConnectorConfig.create(Config.just(ConfigSources.create(Map.of(
                "direction", "OUTGOING",
                ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, CHANNEL,
                ConnectorConfig.CONNECTOR_ATTRIBUTE, KafkaConnectorConfig.CONNECTOR_NAME,
                KafkaConnectorConfig.BOOTSTRAP_SERVERS_PROPERTY, "broker-a:9092,broker-b:9092",
                KafkaConnectorConfig.TOPIC_PROPERTY, TOPIC,
                KafkaConnectorConfig.REDELIVERY_DELAY_PROPERTY, "PT0.25S",
                "properties.compression.type", "zstd",
                "properties.client.rack", "rack-a"))));

        assertThat(config.bootstrapServers(), is("broker-a:9092,broker-b:9092"));
        assertThat(config.topic(), is(TOPIC));
        assertThat(config.redeliveryDelay(), is(Duration.ofMillis(250)));
        assertThat(config.properties(), is(Map.of("compression.type", "zstd",
                                                  "client.rack", "rack-a")));
    }

    @Test
    void testTypedProducerPropertiesOverrideAdditionalProperties() {
        KafkaConnectorConfig config = builder()
                .bootstrapServers("broker:9092")
                .topic(TOPIC)
                .keySerializer("example.TypedKeySerializer")
                .valueSerializer("example.TypedValueSerializer")
                .properties(Map.of(KafkaConnectorConfig.BOOTSTRAP_SERVERS_PROPERTY, "ignored:9092",
                                   KafkaConnectorConfig.KEY_SERIALIZER_PROPERTY, "example.IgnoredKeySerializer",
                                   KafkaConnectorConfig.VALUE_SERIALIZER_PROPERTY, "example.IgnoredValueSerializer",
                                   KafkaConnectorConfig.REDELIVERY_DELAY_PROPERTY, "PT30S",
                                   "compression.type", "zstd"))
                .build();

        Map<String, Object> properties = KafkaConnectorConfigSupport.producerProperties(config);

        assertThat(properties.get(KafkaConnectorConfig.BOOTSTRAP_SERVERS_PROPERTY), is("broker:9092"));
        assertThat(properties.get(KafkaConnectorConfig.KEY_SERIALIZER_PROPERTY), is("example.TypedKeySerializer"));
        assertThat(properties.get(KafkaConnectorConfig.VALUE_SERIALIZER_PROPERTY), is("example.TypedValueSerializer"));
        assertThat(properties.containsKey(KafkaConnectorConfig.REDELIVERY_DELAY_PROPERTY), is(false));
        assertThat(properties.get("compression.type"), is("zstd"));
    }

    @Test
    void testConsumerUsesChannelAsGroupAndDisablesAutoCommit() {
        KafkaConnectorConfig config = builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .bootstrapServers("broker:9092")
                .topic(TOPIC)
                .autoOffsetReset("earliest")
                .properties(Map.of(KafkaConnectorConfig.GROUP_ID_PROPERTY, "ignored-group",
                                   KafkaConnectorConfig.AUTO_OFFSET_RESET_PROPERTY, "none",
                                   KafkaConnectorConfig.ENABLE_AUTO_COMMIT_PROPERTY, "true",
                                   KafkaConnectorConfig.REDELIVERY_DELAY_PROPERTY, "PT30S",
                                   "fetch.min.bytes", "128"))
                .build();

        Map<String, Object> properties = KafkaConnectorConfigSupport.consumerProperties(config);

        assertThat(properties.get(KafkaConnectorConfig.GROUP_ID_PROPERTY), is(CHANNEL));
        assertThat(properties.get(KafkaConnectorConfig.AUTO_OFFSET_RESET_PROPERTY), is("earliest"));
        assertThat(properties.get(KafkaConnectorConfig.ENABLE_AUTO_COMMIT_PROPERTY), is(false));
        assertThat(properties.containsKey(KafkaConnectorConfig.REDELIVERY_DELAY_PROPERTY), is(false));
        assertThat(properties.get("fetch.min.bytes"), is("128"));
    }

    @Test
    void testDefaultRedeliveryDelay() {
        KafkaConnectorConfig config = builder()
                .bootstrapServers("broker:9092")
                .topic(TOPIC)
                .build();

        assertThat(config.redeliveryDelay(), is(Duration.ofSeconds(1)));
    }

    @Test
    void testRedeliveryDelayMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                     () -> builder()
                             .bootstrapServers("broker:9092")
                             .topic(TOPIC)
                             .redeliveryDelay(Duration.ZERO)
                             .build());
        assertThrows(IllegalArgumentException.class,
                     () -> builder()
                             .bootstrapServers("broker:9092")
                             .topic(TOPIC)
                             .redeliveryDelay(Duration.ofSeconds(-1))
                             .build());
    }

    private static KafkaConnectorConfig.Builder builder() {
        return KafkaConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel(CHANNEL)
                .connector(KafkaConnectorConfig.CONNECTOR_NAME);
    }
}
