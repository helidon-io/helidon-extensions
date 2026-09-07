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
import java.util.HashMap;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.messaging.ConnectorDirection;
import io.helidon.messaging.spi.IncomingConnector;
import io.helidon.messaging.spi.OutgoingConnector;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
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
                Map.entry("channel-name", CHANNEL),
                Map.entry("connector", KafkaConnectorProvider.CONNECTOR_TYPE),
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
    void testAdditionalPropertiesAreConfidential() {
        String password = "super-secret-password";
        String jaasConfig = "org.apache.kafka.common.security.plain.PlainLoginModule required "
                + "username=\"client\" password=\"" + password + "\";";
        KafkaConnectorConfig.Builder builder = builder()
                .bootstrapServers("broker:9092")
                .topic(TOPIC)
                .properties(Map.of("sasl.jaas.config", jaasConfig,
                                   "ssl.keystore.password", password,
                                   "sasl.mechanism", "PLAIN"));

        String builderDescription = builder.toString();
        assertThat(builderDescription, containsString("properties=****"));
        assertThat(builderDescription, not(containsString("sasl.jaas.config")));
        assertThat(builderDescription, not(containsString(password)));
        KafkaConnectorConfig config = builder.build();
        String configDescription = config.toString();
        assertThat(configDescription, containsString("properties=****"));
        assertThat(configDescription, not(containsString("sasl.mechanism")));
        assertThat(configDescription, not(containsString(password)));
        assertThat(configDescription, not(containsString(jaasConfig)));
        assertThat(config.properties().get("sasl.jaas.config"), is(jaasConfig));
    }

    @Test
    void testAdditionalPropertiesRejectNullEntries() {
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "value");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("key", null);

        assertThrows(NullPointerException.class,
                     () -> configuredBuilder().properties(nullKey).build());
        assertThrows(NullPointerException.class,
                     () -> configuredBuilder().addProperties(nullValue).build());
    }

    @Test
    void testPollTimeoutMustBeAtLeastOneMillisecond() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                                                      () -> configuredBuilder()
                                                              .pollTimeout(Duration.ZERO)
                                                              .build());
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                                                          () -> configuredBuilder()
                                                                  .pollTimeout(Duration.ofNanos(-1))
                                                                  .build());
        IllegalArgumentException subMillisecond = assertThrows(IllegalArgumentException.class,
                                                                () -> configuredBuilder()
                                                                        .pollTimeout(Duration.ofNanos(999_999))
                                                                        .build());

        assertThat(zero.getMessage(), is("poll.timeout must be greater than zero"));
        assertThat(negative.getMessage(), is("poll.timeout must be greater than zero"));
        assertThat(subMillisecond.getMessage(), is("poll.timeout must be at least 1 ms"));
    }

    @Test
    void testSendTimeoutMustBePositive() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                                                      () -> configuredBuilder()
                                                              .sendTimeout(Duration.ZERO)
                                                              .build());
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                                                          () -> configuredBuilder()
                                                                  .sendTimeout(Duration.ofNanos(-1))
                                                                  .build());

        assertThat(zero.getMessage(), is("send.timeout must be greater than zero"));
        assertThat(negative.getMessage(), is("send.timeout must be greater than zero"));
    }

    @Test
    void testTimeoutOverflowIsRejected() {
        Duration millisecondOverflow = Duration.ofMillis(Long.MAX_VALUE).plusMillis(1);
        Duration nanosecondOverflow = Duration.ofNanos(Long.MAX_VALUE).plusNanos(1);

        IllegalArgumentException poll = assertThrows(IllegalArgumentException.class,
                                                      () -> configuredBuilder()
                                                              .pollTimeout(millisecondOverflow)
                                                              .build());
        IllegalArgumentException send = assertThrows(IllegalArgumentException.class,
                                                      () -> configuredBuilder()
                                                              .sendTimeout(nanosecondOverflow)
                                                              .build());
        IllegalArgumentException close = assertThrows(IllegalArgumentException.class,
                                                       () -> configuredBuilder()
                                                               .closeTimeout(nanosecondOverflow)
                                                               .build());

        assertThat(poll.getMessage(), is("poll.timeout must be representable in milliseconds"));
        assertThat(send.getMessage(), is("send.timeout must be representable in nanoseconds"));
        assertThat(close.getMessage(), is("close.timeout must be representable in nanoseconds"));
        assertThat(poll.getCause(), instanceOf(ArithmeticException.class));
        assertThat(send.getCause(), instanceOf(ArithmeticException.class));
        assertThat(close.getCause(), instanceOf(ArithmeticException.class));
    }

    @Test
    void testTimeoutRepresentationBoundariesAreAccepted() {
        Duration minimumPollTimeout = Duration.ofMillis(1);
        Duration maximumMilliseconds = Duration.ofMillis(Long.MAX_VALUE);
        Duration maximumNanoseconds = Duration.ofNanos(Long.MAX_VALUE);
        KafkaConnectorConfig minimumPoll = configuredBuilder()
                .pollTimeout(minimumPollTimeout)
                .build();
        KafkaConnectorConfig config = configuredBuilder()
                .pollTimeout(maximumMilliseconds)
                .sendTimeout(maximumNanoseconds)
                .closeTimeout(maximumNanoseconds)
                .build();

        assertThat(minimumPoll.pollTimeout(), is(minimumPollTimeout));
        assertThat(config.pollTimeout(), is(maximumMilliseconds));
        assertThat(config.sendTimeout(), is(maximumNanoseconds));
        assertThat(config.closeTimeout(), is(maximumNanoseconds));
    }

    @Test
    void testNegativeCloseTimeoutIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                                                         () -> builder()
                                                                 .bootstrapServers("broker:9092")
                                                                 .topic(TOPIC)
                                                                 .closeTimeout(Duration.ofNanos(-1))
                                                                 .build());

        assertThat(failure.getMessage(), is("close.timeout must not be negative"));
    }

    @Test
    void testZeroCloseTimeoutIsAccepted() {
        KafkaConnectorConfig config = builder()
                .bootstrapServers("broker:9092")
                .topic(TOPIC)
                .closeTimeout(Duration.ZERO)
                .build();

        assertThat(config.closeTimeout(), is(Duration.ZERO));
    }

    @Test
    void testConnectorFactoriesRejectMismatchedDirection() {
        KafkaConnectorProvider provider = KafkaConnectorProvider.create();
        KafkaConnectorConfig outgoing = builder()
                .bootstrapServers("broker:9092")
                .topic(TOPIC)
                .build();
        KafkaConnectorConfig incoming = builder()
                .direction(ConnectorDirection.INCOMING)
                .bootstrapServers("broker:9092")
                .topic(TOPIC)
                .build();

        assertThrows(IllegalArgumentException.class, () -> provider.createIncomingConnector(outgoing));
        assertThrows(IllegalArgumentException.class, () -> provider.createOutgoingConnector(incoming));
    }

    @Test
    void testProviderFactoriesParseRawConfiguration() {
        KafkaConnectorProvider provider = KafkaConnectorProvider.create();
        IncomingConnector incoming = provider.createIncomingConnector(rawConfig(ConnectorDirection.INCOMING));
        OutgoingConnector outgoing = provider.createOutgoingConnector(rawConfig(ConnectorDirection.OUTGOING));

        incoming.close();
        outgoing.close();
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
                .direction(ConnectorDirection.INCOMING)
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
    void testConsumerRecordAcquisitionIsCappedByRuntimeMessageLimit() {
        KafkaConnectorConfig config = builder()
                .direction(ConnectorDirection.INCOMING)
                .bootstrapServers("broker:9092")
                .topic(TOPIC)
                .properties(Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500",
                                   ConsumerConfig.FETCH_MAX_BYTES_CONFIG, "52428800",
                                   ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, "1048576"))
                .build();

        Map<String, Object> properties = KafkaConnectorConfigSupport.consumerProperties(config, 7);

        assertThat(properties.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), is(7));
        assertThat(properties.get(ConsumerConfig.FETCH_MAX_BYTES_CONFIG), is("52428800"));
        assertThat(properties.get(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG), is("1048576"));
    }

    private static KafkaConnectorConfig.Builder builder() {
        return KafkaConnectorConfig.builder()
                .direction(ConnectorDirection.OUTGOING)
                .channelName(CHANNEL)
                .connector(KafkaConnectorProvider.CONNECTOR_TYPE);
    }

    private static KafkaConnectorConfig.Builder configuredBuilder() {
        return builder()
                .bootstrapServers("broker:9092")
                .topic(TOPIC);
    }

    private static Config rawConfig(ConnectorDirection direction) {
        return Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("direction", direction.name()),
                Map.entry("channel-name", CHANNEL),
                Map.entry("connector", KafkaConnectorProvider.CONNECTOR_TYPE),
                Map.entry(KafkaConnectorConfig.BOOTSTRAP_SERVERS_PROPERTY, "broker:9092"),
                Map.entry(KafkaConnectorConfig.TOPIC_PROPERTY, TOPIC))));
    }
}
