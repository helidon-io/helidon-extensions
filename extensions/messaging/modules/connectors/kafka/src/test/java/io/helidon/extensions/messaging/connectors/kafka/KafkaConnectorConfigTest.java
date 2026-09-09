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
import io.helidon.messaging.spi.IncomingChannel;
import io.helidon.messaging.spi.OutgoingChannel;

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
    void testConnectorBootstrapServersAndChannelTopicAreRequired() {
        assertThrows(RuntimeException.class, () -> builder().buildPrototype());
        KafkaConnector kafka = configuredBuilder().build();
        assertThrows(IllegalArgumentException.class,
                     () -> kafka.incoming(KafkaIncomingConfig.builder().channelName(CHANNEL).build()));
        assertThrows(IllegalArgumentException.class,
                     () -> kafka.outgoing(KafkaOutgoingConfig.builder().channelName(CHANNEL).build()));
    }

    @Test
    void testCreateFromConfigReadsNestedKafkaProperties() {
        KafkaConnectorConfig config = KafkaConnectorConfig.create(Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("name", "test-kafka"),
                Map.entry(KafkaConnectorConfig.BOOTSTRAP_SERVERS_PROPERTY, "broker-a:9092,broker-b:9092"),
                Map.entry("properties.compression.type", "zstd"),
                Map.entry("properties.client.rack", "rack-a")))));

        assertThat(config.name(), is("test-kafka"));
        assertThat(config.bootstrapServers(), is("broker-a:9092,broker-b:9092"));
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
                .properties(Map.of("sasl.jaas.config", jaasConfig,
                                   "ssl.keystore.password", password,
                                   "sasl.mechanism", "PLAIN"));

        String builderDescription = builder.toString();
        assertThat(builderDescription, containsString("properties=****"));
        assertThat(builderDescription, not(containsString("sasl.jaas.config")));
        assertThat(builderDescription, not(containsString(password)));
        KafkaConnectorConfig config = builder.buildPrototype();
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
                     () -> configuredBuilder().properties(nullKey).buildPrototype());
        assertThrows(NullPointerException.class,
                     () -> configuredBuilder().addProperties(nullValue).buildPrototype());
    }

    @Test
    void testPollTimeoutMustBeAtLeastOneMillisecond() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                                                      () -> configuredBuilder()
                                                              .pollTimeout(Duration.ZERO)
                                                              .buildPrototype());
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                                                          () -> configuredBuilder()
                                                                  .pollTimeout(Duration.ofNanos(-1))
                                                                  .buildPrototype());
        IllegalArgumentException subMillisecond = assertThrows(IllegalArgumentException.class,
                                                                () -> configuredBuilder()
                                                                        .pollTimeout(Duration.ofNanos(999_999))
                                                                        .buildPrototype());

        assertThat(zero.getMessage(), is("poll.timeout must be greater than zero"));
        assertThat(negative.getMessage(), is("poll.timeout must be greater than zero"));
        assertThat(subMillisecond.getMessage(), is("poll.timeout must be at least 1 ms"));
    }

    @Test
    void testSendTimeoutMustBePositive() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                                                      () -> configuredBuilder()
                                                              .sendTimeout(Duration.ZERO)
                                                              .buildPrototype());
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                                                          () -> configuredBuilder()
                                                                  .sendTimeout(Duration.ofNanos(-1))
                                                                  .buildPrototype());

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
                                                              .buildPrototype());
        IllegalArgumentException send = assertThrows(IllegalArgumentException.class,
                                                      () -> configuredBuilder()
                                                              .sendTimeout(nanosecondOverflow)
                                                              .buildPrototype());
        IllegalArgumentException close = assertThrows(IllegalArgumentException.class,
                                                       () -> configuredBuilder()
                                                               .closeTimeout(nanosecondOverflow)
                                                               .buildPrototype());

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
                .buildPrototype();
        KafkaConnectorConfig config = configuredBuilder()
                .pollTimeout(maximumMilliseconds)
                .sendTimeout(maximumNanoseconds)
                .closeTimeout(maximumNanoseconds)
                .buildPrototype();

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
                                                                 .closeTimeout(Duration.ofNanos(-1))
                                                                 .buildPrototype());

        assertThat(failure.getMessage(), is("close.timeout must not be negative"));
    }

    @Test
    void testZeroCloseTimeoutIsAccepted() {
        KafkaConnectorConfig config = builder()
                .bootstrapServers("broker:9092")
                .closeTimeout(Duration.ZERO)
                .buildPrototype();

        assertThat(config.closeTimeout(), is(Duration.ZERO));
    }

    @Test
    void testConfiguredConnectorCreatesBothChannelDirections() {
        KafkaConnectorProvider provider = new KafkaConnectorProvider();
        KafkaConnector connector = (KafkaConnector) provider.create(
                Config.just(ConfigSources.create(Map.of("bootstrap.servers", "broker:9092"))), "test-kafka");
        Config channelConfig = Config.just(ConfigSources.create(Map.of("channel-name", CHANNEL, "topic", TOPIC)));
        try (IncomingChannel incoming = connector.incoming(channelConfig).orElseThrow();
                OutgoingChannel outgoing = connector.outgoing(channelConfig).orElseThrow()) {
            assertThat(connector.name(), is("test-kafka"));
            assertThat(connector.type(), is(KafkaConnectorProvider.CONNECTOR_TYPE));
        }
    }

    @Test
    void testTypedProducerPropertiesOverrideAdditionalProperties() {
        KafkaConnectorConfig config = builder()
                .bootstrapServers("broker:9092")
                .keySerializer("example.TypedKeySerializer")
                .valueSerializer("example.TypedValueSerializer")
                .properties(Map.of(KafkaConnectorConfig.BOOTSTRAP_SERVERS_PROPERTY, "ignored:9092",
                                   KafkaConnectorConfig.KEY_SERIALIZER_PROPERTY, "example.IgnoredKeySerializer",
                                   KafkaConnectorConfig.VALUE_SERIALIZER_PROPERTY, "example.IgnoredValueSerializer",
                                   "compression.type", "zstd"))
                .buildPrototype();

        Map<String, Object> properties = KafkaConnectorConfigSupport.producerProperties(
                KafkaConnectorConfigSupport.outgoing(config, outgoingConfig()));

        assertThat(properties.get(KafkaConnectorConfig.BOOTSTRAP_SERVERS_PROPERTY), is("broker:9092"));
        assertThat(properties.get(KafkaConnectorConfig.KEY_SERIALIZER_PROPERTY), is("example.TypedKeySerializer"));
        assertThat(properties.get(KafkaConnectorConfig.VALUE_SERIALIZER_PROPERTY), is("example.TypedValueSerializer"));
        assertThat(properties.get("compression.type"), is("zstd"));
    }

    @Test
    void testConsumerUsesChannelAsGroupAndDisablesAutoCommit() {
        KafkaConnectorConfig config = builder()
                .bootstrapServers("broker:9092")
                .autoOffsetReset("earliest")
                .properties(Map.of(KafkaConnectorConfig.GROUP_ID_PROPERTY, "ignored-group",
                                   KafkaConnectorConfig.AUTO_OFFSET_RESET_PROPERTY, "none",
                                   KafkaConnectorConfig.ENABLE_AUTO_COMMIT_PROPERTY, "true",
                                   "fetch.min.bytes", "128"))
                .buildPrototype();

        Map<String, Object> properties = KafkaConnectorConfigSupport.consumerProperties(
                KafkaConnectorConfigSupport.incoming(config, incomingConfig()));

        assertThat(properties.get(KafkaConnectorConfig.GROUP_ID_PROPERTY), is(CHANNEL));
        assertThat(properties.get(KafkaConnectorConfig.AUTO_OFFSET_RESET_PROPERTY), is("earliest"));
        assertThat(properties.get(KafkaConnectorConfig.ENABLE_AUTO_COMMIT_PROPERTY), is(false));
        assertThat(properties.get("fetch.min.bytes"), is("128"));
    }

    @Test
    void testConsumerRecordAcquisitionIsCappedByRuntimeMessageLimit() {
        KafkaConnectorConfig config = builder()
                .bootstrapServers("broker:9092")
                .properties(Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500",
                                   ConsumerConfig.FETCH_MAX_BYTES_CONFIG, "52428800",
                                   ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, "1048576"))
                .buildPrototype();

        Map<String, Object> properties = KafkaConnectorConfigSupport.consumerProperties(
                KafkaConnectorConfigSupport.incoming(config, incomingConfig()), 7);

        assertThat(properties.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), is(7));
        assertThat(properties.get(ConsumerConfig.FETCH_MAX_BYTES_CONFIG), is("52428800"));
        assertThat(properties.get(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG), is("1048576"));
    }

    @Test
    void testChannelOverridesAndSharedDefaultsAreCombined() {
        KafkaConnectorConfig common = configuredBuilder()
                .topic("shared-topic")
                .groupId("shared-group")
                .keyDeserializer("shared.KeyDeserializer")
                .valueSerializer("shared.ValueSerializer")
                .pollTimeout(Duration.ofSeconds(2))
                .sendTimeout(Duration.ofSeconds(3))
                .properties(Map.of("client.rack", "shared-rack", "security.protocol", "SSL"))
                .buildPrototype();
        KafkaIncomingConfig incoming = KafkaIncomingConfig.builder()
                .channelName(CHANNEL)
                .topic(TOPIC)
                .groupId("inventory")
                .keyDeserializer("channel.KeyDeserializer")
                .properties(Map.of("client.rack", "channel-rack"))
                .build();
        KafkaOutgoingConfig outgoing = KafkaOutgoingConfig.builder()
                .channelName("audit-copy")
                .topic("audit-copy")
                .valueSerializer("channel.ValueSerializer")
                .sendTimeout(Duration.ofSeconds(1))
                .build();

        var incomingSettings = KafkaConnectorConfigSupport.incoming(common, incoming);
        var outgoingSettings = KafkaConnectorConfigSupport.outgoing(common, outgoing);
        var defaultIncoming = KafkaConnectorConfigSupport.incoming(common,
                KafkaIncomingConfig.builder().channelName(CHANNEL).build());
        var defaultOutgoing = KafkaConnectorConfigSupport.outgoing(common,
                KafkaOutgoingConfig.builder().channelName(CHANNEL).build());
        assertThat(incomingSettings.bootstrapServers(), is("broker:9092"));
        assertThat(incomingSettings.topic(), is(TOPIC));
        assertThat(defaultIncoming.topic(), is("shared-topic"));
        assertThat(defaultOutgoing.topic(), is("shared-topic"));
        assertThat(incomingSettings.keyDeserializer(), is("channel.KeyDeserializer"));
        assertThat(incomingSettings.pollTimeout(), is(Duration.ofSeconds(2)));
        assertThat(incomingSettings.groupId(), is("inventory"));
        assertThat(KafkaConnectorConfigSupport.incoming(common, incomingConfig()).groupId(), is("shared-group"));
        assertThat(incomingSettings.properties(),
                   is(Map.of("client.rack", "channel-rack", "security.protocol", "SSL")));
        assertThat(outgoingSettings.valueSerializer(), is("channel.ValueSerializer"));
        assertThat(outgoingSettings.sendTimeout(), is(Duration.ofSeconds(1)));
        assertThat(outgoingSettings.properties(), is(common.properties()));
        assertThat(common.properties().get("client.rack"), is("shared-rack"));
    }

    @Test
    void testChannelTimeoutOverridesAreValidated() {
        assertThrows(IllegalArgumentException.class,
                     () -> KafkaIncomingConfig.builder().channelName(CHANNEL).topic(TOPIC)
                             .pollTimeout(Duration.ofNanos(1)).build());
        assertThrows(IllegalArgumentException.class,
                     () -> KafkaOutgoingConfig.builder().channelName(CHANNEL).topic(TOPIC)
                             .sendTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                     () -> KafkaIncomingConfig.builder().channelName(CHANNEL).topic(TOPIC)
                             .closeTimeout(Duration.ofNanos(-1)).build());
    }

    private static KafkaConnectorConfig.Builder builder() {
        return KafkaConnectorConfig.builder().name("test-kafka");
    }

    private static KafkaConnectorConfig.Builder configuredBuilder() {
        return builder()
                .bootstrapServers("broker:9092");
    }

    private static KafkaIncomingConfig incomingConfig() {
        return KafkaIncomingConfig.builder().channelName(CHANNEL).topic(TOPIC).build();
    }

    private static KafkaOutgoingConfig outgoingConfig() {
        return KafkaOutgoingConfig.builder().channelName(CHANNEL).topic(TOPIC).build();
    }
}
