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

import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.ConnectorConfig;

import org.apache.kafka.clients.consumer.ConsumerConfig;
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
        KafkaConnectorConfig config = KafkaConnectorConfig.create(Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("direction", "OUTGOING"),
                Map.entry(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, CHANNEL),
                Map.entry(ConnectorConfig.CONNECTOR_ATTRIBUTE, KafkaConnectorConfig.CONNECTOR_NAME),
                Map.entry(KafkaConnectorConfig.BOOTSTRAP_SERVERS_PROPERTY, "broker-a:9092,broker-b:9092"),
                Map.entry(KafkaConnectorConfig.TOPIC_PROPERTY, TOPIC),
                Map.entry("properties.compression.type", "zstd"),
                Map.entry("properties.client.rack", "rack-a")))));

        assertThat(config.bootstrapServers(), is("broker-a:9092,broker-b:9092"));
        assertThat(config.topic(), is(TOPIC));
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
                                   "compression.type", "zstd"))
                .build();

        Map<String, Object> properties = KafkaConnectorConfigSupport.producerProperties(config);

        assertThat(properties.get(KafkaConnectorConfig.BOOTSTRAP_SERVERS_PROPERTY), is("broker:9092"));
        assertThat(properties.get(KafkaConnectorConfig.KEY_SERIALIZER_PROPERTY), is("example.TypedKeySerializer"));
        assertThat(properties.get(KafkaConnectorConfig.VALUE_SERIALIZER_PROPERTY), is("example.TypedValueSerializer"));
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
                                   "fetch.min.bytes", "128"))
                .build();

        Map<String, Object> properties = KafkaConnectorConfigSupport.consumerProperties(config);

        assertThat(properties.get(KafkaConnectorConfig.GROUP_ID_PROPERTY), is(CHANNEL));
        assertThat(properties.get(KafkaConnectorConfig.AUTO_OFFSET_RESET_PROPERTY), is("earliest"));
        assertThat(properties.get(KafkaConnectorConfig.ENABLE_AUTO_COMMIT_PROPERTY), is(false));
        assertThat(properties.get("fetch.min.bytes"), is("128"));
    }

    @Test
    void testConsumerAcquisitionIsCappedByRuntimeDeliveryLimits() {
        KafkaConnectorConfig config = builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .bootstrapServers("broker:9092")
                .topic(TOPIC)
                .properties(Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500",
                                   ConsumerConfig.FETCH_MAX_BYTES_CONFIG, "52428800",
                                   ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, "1048576"))
                .build();

        Map<String, Object> properties = KafkaConnectorConfigSupport.consumerProperties(config, 7, 2_048);

        assertThat(properties.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), is(7));
        assertThat(properties.get(ConsumerConfig.FETCH_MAX_BYTES_CONFIG), is(2_048));
        assertThat(properties.get(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG), is(2_048));
    }

    private static KafkaConnectorConfig.Builder builder() {
        return KafkaConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel(CHANNEL)
                .connector(KafkaConnectorConfig.CONNECTOR_NAME);
    }
}
