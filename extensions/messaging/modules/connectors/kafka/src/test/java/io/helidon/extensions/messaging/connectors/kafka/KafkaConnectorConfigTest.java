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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.config.spi.ConfigNode;
import io.helidon.messaging.MessagingExecutionConfig;
import io.helidon.messaging.spi.IncomingChannel;
import io.helidon.messaging.spi.MessagingIncomingConfig;
import io.helidon.messaging.spi.MessagingOutgoingConfig;
import io.helidon.messaging.spi.OutgoingChannel;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaConnectorConfigTest {
    private static final String CHANNEL = "audit";
    private static final String CONNECTOR = "test-kafka";
    private static final String TOPIC = "audit-events";

    @Test
    void testConnectorBootstrapServersAndChannelTopicAreRequired() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                                                         () -> builder().buildPrototype());
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                                                       () -> builder().bootstrapServers(List.of()).buildPrototype());
        assertThat(missing.getMessage(), is("bootstrap-servers must not be empty"));
        assertThat(empty.getMessage(), is("bootstrap-servers must not be empty"));
        KafkaConnector kafka = configuredBuilder().build();
        KafkaIncomingConfig incoming = KafkaIncomingConfig.builder()
                .connector(CONNECTOR)
                .channelName(CHANNEL)
                .build();
        KafkaOutgoingConfig outgoing = KafkaOutgoingConfig.builder()
                .connector(CONNECTOR)
                .channelName(CHANNEL)
                .build();
        assertThrows(IllegalArgumentException.class,
                     () -> kafka.incoming(incoming));
        assertThrows(IllegalArgumentException.class,
                     () -> kafka.outgoing(outgoing));
    }

    @Test
    void testCreateFromConfigReadsNestedKafkaProperties() {
        ConfigNode.ObjectNode node = ConfigNode.ObjectNode.builder()
                .addValue("name", CONNECTOR)
                .addList("bootstrap-servers", ConfigNode.ListNode.builder()
                        .addValue("broker-a:9092")
                        .addValue("broker-b:9092")
                        .build())
                .addValue("group-id", "configured-group")
                .addValue("key-serializer", "configured.KeySerializer")
                .addValue("value-serializer", "configured.ValueSerializer")
                .addValue("key-deserializer", "configured.KeyDeserializer")
                .addValue("value-deserializer", "configured.ValueDeserializer")
                .addValue("auto-offset-reset", "earliest")
                .addValue("poll-timeout", "PT0.2S")
                .addValue("send-timeout", "PT4S")
                .addValue("close-timeout", "PT2S")
                .addObject("properties", ConfigNode.ObjectNode.builder()
                        .addValue("compression.type", "zstd")
                        .addValue("client.rack", "rack-a")
                        .build())
                .build();
        Config source = Config.just(ConfigSources.create(node));
        KafkaConnectorConfig config = KafkaConnectorConfig.create(source);
        KafkaIncomingConfig incoming = KafkaIncomingConfig.builder()
                .config(source)
                .connector(CONNECTOR)
                .channelName(CHANNEL)
                .build();
        KafkaOutgoingConfig outgoing = KafkaOutgoingConfig.builder()
                .config(source)
                .connector(CONNECTOR)
                .channelName(CHANNEL)
                .build();

        assertThat(config.name(), is("test-kafka"));
        assertThat(config.bootstrapServers(), is(List.of("broker-a:9092", "broker-b:9092")));
        assertThat(config.groupId(), is(Optional.of("configured-group")));
        assertThat(config.keySerializer(), is("configured.KeySerializer"));
        assertThat(config.valueSerializer(), is("configured.ValueSerializer"));
        assertThat(config.keyDeserializer(), is("configured.KeyDeserializer"));
        assertThat(config.valueDeserializer(), is("configured.ValueDeserializer"));
        assertThat(config.autoOffsetReset(), is("earliest"));
        assertThat(config.pollTimeout(), is(Duration.ofMillis(200)));
        assertThat(config.sendTimeout(), is(Duration.ofSeconds(4)));
        assertThat(config.closeTimeout(), is(Duration.ofSeconds(2)));
        assertThat(config.properties(), is(Map.of("compression.type", "zstd",
                                                  "client.rack", "rack-a")));
        assertThat(incoming.bootstrapServers(), is(Optional.of(config.bootstrapServers())));
        assertThat(incoming.groupId(), is(config.groupId()));
        assertThat(incoming.keyDeserializer(), is(Optional.of(config.keyDeserializer())));
        assertThat(incoming.valueDeserializer(), is(Optional.of(config.valueDeserializer())));
        assertThat(incoming.autoOffsetReset(), is(Optional.of(config.autoOffsetReset())));
        assertThat(incoming.pollTimeout(), is(Optional.of(config.pollTimeout())));
        assertThat(incoming.closeTimeout(), is(Optional.of(config.closeTimeout())));
        assertThat(outgoing.bootstrapServers(), is(Optional.of(config.bootstrapServers())));
        assertThat(outgoing.keySerializer(), is(Optional.of(config.keySerializer())));
        assertThat(outgoing.valueSerializer(), is(Optional.of(config.valueSerializer())));
        assertThat(outgoing.sendTimeout(), is(Optional.of(config.sendTimeout())));
        assertThat(outgoing.closeTimeout(), is(Optional.of(config.closeTimeout())));
    }

    @Test
    void testBootstrapServerListsAndSingularBuildersPreserveChannelOverrides() {
        KafkaConnectorConfig common = builder()
                .bootstrapServers(List.of("broker-a:9092", "broker-b:9092"))
                .addBootstrapServer("broker-c:9092")
                .buildPrototype();
        KafkaIncomingConfig inheritedIncoming = incomingConfig();
        KafkaOutgoingConfig inheritedOutgoing = outgoingConfig();
        KafkaIncomingConfig incoming = KafkaIncomingConfig.builder(inheritedIncoming)
                .addBootstrapServer("discarded:9092")
                .bootstrapServers(List.of("incoming-a:9092"))
                .addBootstrapServer("incoming-b:9092")
                .build();
        KafkaOutgoingConfig outgoing = KafkaOutgoingConfig.builder(inheritedOutgoing)
                .bootstrapServers(List.of("outgoing-a:9092", "outgoing-b:9092"))
                .addBootstrapServer("outgoing-c:9092")
                .build();
        var incomingSettings = KafkaConnectorConfigSupport.incoming(common, incoming);
        var outgoingSettings = KafkaConnectorConfigSupport.outgoing(common, outgoing);

        assertThat(common.bootstrapServers(), is(List.of("broker-a:9092", "broker-b:9092", "broker-c:9092")));
        assertThat(inheritedIncoming.bootstrapServers(), is(Optional.empty()));
        assertThat(inheritedOutgoing.bootstrapServers(), is(Optional.empty()));
        assertThat(KafkaConnectorConfigSupport.incoming(common, inheritedIncoming).bootstrapServers(),
                   is(common.bootstrapServers()));
        assertThat(KafkaConnectorConfigSupport.outgoing(common, inheritedOutgoing).bootstrapServers(),
                   is(common.bootstrapServers()));
        assertThat(incomingSettings.bootstrapServers(), is(List.of("incoming-a:9092", "incoming-b:9092")));
        assertThat(outgoingSettings.bootstrapServers(),
                   is(List.of("outgoing-a:9092", "outgoing-b:9092", "outgoing-c:9092")));
        assertThat(KafkaConnectorConfigSupport.consumerProperties(incomingSettings)
                           .get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG),
                   is("incoming-a:9092,incoming-b:9092"));
        assertThat(KafkaConnectorConfigSupport.producerProperties(outgoingSettings)
                           .get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG),
                   is("outgoing-a:9092,outgoing-b:9092,outgoing-c:9092"));
    }

    @Test
    void testAdditionalPropertiesAreConfidential() {
        String password = "super-secret-password";
        String jaasConfig = "org.apache.kafka.common.security.plain.PlainLoginModule required "
                + "username=\"client\" password=\"" + password + "\";";
        KafkaConnectorConfig.Builder builder = builder()
                .addBootstrapServer("broker:9092")
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

        assertThat(zero.getMessage(), is("poll-timeout must be greater than zero"));
        assertThat(negative.getMessage(), is("poll-timeout must be greater than zero"));
        assertThat(subMillisecond.getMessage(), is("poll-timeout must be at least 1 ms"));
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

        assertThat(zero.getMessage(), is("send-timeout must be greater than zero"));
        assertThat(negative.getMessage(), is("send-timeout must be greater than zero"));
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

        assertThat(poll.getMessage(), is("poll-timeout must be representable in milliseconds"));
        assertThat(send.getMessage(), is("send-timeout must be representable in nanoseconds"));
        assertThat(close.getMessage(), is("close-timeout must be representable in nanoseconds"));
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
                                                                 .addBootstrapServer("broker:9092")
                                                                 .closeTimeout(Duration.ofNanos(-1))
                                                                 .buildPrototype());

        assertThat(failure.getMessage(), is("close-timeout must not be negative"));
    }

    @Test
    void testZeroCloseTimeoutIsAccepted() {
        KafkaConnectorConfig config = builder()
                .addBootstrapServer("broker:9092")
                .closeTimeout(Duration.ZERO)
                .buildPrototype();

        assertThat(config.closeTimeout(), is(Duration.ZERO));
    }

    @Test
    void testConfiguredConnectorCreatesBothChannelDirections() {
        KafkaConnectorProvider provider = new KafkaConnectorProvider();
        KafkaConnector connector = (KafkaConnector) provider.create(
                Config.just(ConfigSources.create(ConfigNode.ObjectNode.builder()
                        .addList("bootstrap-servers", ConfigNode.ListNode.builder().addValue("broker:9092").build())
                        .build())), "test-kafka");
        Config channelConfig = Config.just(ConfigSources.create(Map.of("topic", TOPIC)));
        MessagingIncomingConfig incomingConfig = MessagingIncomingConfig.builder()
                .config(channelConfig)
                .connector(CONNECTOR)
                .channelName(CHANNEL)
                .build();
        MessagingOutgoingConfig outgoingConfig = MessagingOutgoingConfig.builder()
                .config(channelConfig)
                .connector(CONNECTOR)
                .channelName(CHANNEL)
                .build();
        try (IncomingChannel incoming = connector.incoming(incomingConfig).orElseThrow();
                OutgoingChannel outgoing = connector.outgoing(outgoingConfig).orElseThrow()) {
            assertThat(connector.name(), is("test-kafka"));
            assertThat(connector.type(), is(KafkaConnector.CONNECTOR_TYPE));
        }
    }

    @Test
    void testTypedChannelOptionsOverrideRetainedConfigThroughCommonSpi() {
        Config rawConfig = Config.just(ConfigSources.create(Map.of("topic", "configured-topic",
                                                                  "poll-timeout", "PT0S",
                                                                  "send-timeout", "PT0S")));
        MessagingExecutionConfig execution = MessagingExecutionConfig.builder().queueCapacity(2).build();
        MessagingIncomingConfig incomingConfig = KafkaIncomingConfig.builder()
                .config(rawConfig)
                .connector(CONNECTOR)
                .channelName(CHANNEL)
                .execution(execution)
                .topic(TOPIC)
                .pollTimeout(Duration.ofMillis(10))
                .build();
        MessagingOutgoingConfig outgoingConfig = KafkaOutgoingConfig.builder()
                .config(rawConfig)
                .connector(CONNECTOR)
                .channelName(CHANNEL)
                .execution(execution)
                .topic(TOPIC)
                .sendTimeout(Duration.ofSeconds(1))
                .build();
        KafkaConnector connector = configuredBuilder().build();

        try (IncomingChannel incoming = connector.incoming(incomingConfig).orElseThrow();
                OutgoingChannel outgoing = connector.outgoing(outgoingConfig).orElseThrow()) {
            assertThat(incoming, notNullValue());
            assertThat(outgoing, notNullValue());
        }
    }

    @Test
    void testTypedProducerPropertiesOverrideAdditionalProperties() {
        KafkaConnectorConfig config = builder()
                .addBootstrapServer("broker:9092")
                .keySerializer("example.TypedKeySerializer")
                .valueSerializer("example.TypedValueSerializer")
                .properties(Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "ignored:9092",
                                   ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "example.IgnoredKeySerializer",
                                   ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "example.IgnoredValueSerializer",
                                   "compression.type", "zstd"))
                .buildPrototype();

        Map<String, Object> properties = KafkaConnectorConfigSupport.producerProperties(
                KafkaConnectorConfigSupport.outgoing(config, outgoingConfig()));

        assertThat(properties.get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG), is("broker:9092"));
        assertThat(properties.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG), is("example.TypedKeySerializer"));
        assertThat(properties.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG), is("example.TypedValueSerializer"));
        assertThat(properties.get("compression.type"), is("zstd"));
    }

    @Test
    void testConsumerUsesChannelAsGroupAndDisablesAutoCommit() {
        KafkaConnectorConfig config = builder()
                .addBootstrapServer("broker:9092")
                .autoOffsetReset("earliest")
                .properties(Map.of(ConsumerConfig.GROUP_ID_CONFIG, "ignored-group",
                                   ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none",
                                   ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true",
                                   "fetch.min.bytes", "128"))
                .buildPrototype();

        Map<String, Object> properties = KafkaConnectorConfigSupport.consumerProperties(
                KafkaConnectorConfigSupport.incoming(config, incomingConfig()));

        assertThat(properties.get(ConsumerConfig.GROUP_ID_CONFIG), is(CHANNEL));
        assertThat(properties.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), is("earliest"));
        assertThat(properties.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG), is(false));
        assertThat(properties.get("fetch.min.bytes"), is("128"));
    }

    @Test
    void testConsumerRecordAcquisitionIsCappedByRuntimeMessageLimit() {
        KafkaConnectorConfig config = builder()
                .addBootstrapServer("broker:9092")
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
    void testTypedHelidonOptionsDoNotLeakIntoNativeProperties() {
        KafkaConnectorConfig common = configuredBuilder()
                .topic(TOPIC)
                .groupId("orders-group")
                .pollTimeout(Duration.ofMillis(10))
                .sendTimeout(Duration.ofSeconds(1))
                .closeTimeout(Duration.ofSeconds(2))
                .buildPrototype();
        MessagingExecutionConfig execution = MessagingExecutionConfig.builder().queueCapacity(3).build();
        KafkaIncomingConfig incoming = KafkaIncomingConfig.builder(incomingConfig()).execution(execution).build();
        KafkaOutgoingConfig outgoing = KafkaOutgoingConfig.builder(outgoingConfig()).execution(execution).build();

        Map<String, Object> consumer = KafkaConnectorConfigSupport.consumerProperties(
                KafkaConnectorConfigSupport.incoming(common, incoming), 7);
        Map<String, Object> producer = KafkaConnectorConfigSupport.producerProperties(
                KafkaConnectorConfigSupport.outgoing(common, outgoing));

        assertThat(consumer, is(Map.of(
                CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "broker:9092",
                ConsumerConfig.GROUP_ID_CONFIG, "orders-group",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, KafkaConnectorConfig.DEFAULT_KEY_DESERIALIZER,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaConnectorConfig.DEFAULT_VALUE_DESERIALIZER,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 7)));
        assertThat(producer, is(Map.of(
                CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "broker:9092",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, KafkaConnectorConfig.DEFAULT_KEY_SERIALIZER,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaConnectorConfig.DEFAULT_VALUE_SERIALIZER)));
    }

    @Test
    void testExplicitNativePropertiesArePreservedWithoutMutatingSourceMaps() {
        Map<String, String> nativeProperties = new HashMap<>(Map.ofEntries(
                Map.entry("name", "native-name"),
                Map.entry("connector", "native-connector"),
                Map.entry("channel-name", "native-channel"),
                Map.entry("topic", "native-topic"),
                Map.entry("poll.timeout", "native-poll"),
                Map.entry("send.timeout", "native-send"),
                Map.entry("close.timeout", "native-close"),
                Map.entry("poll-timeout", "native-hyphenated-poll"),
                Map.entry("send-timeout", "native-hyphenated-send"),
                Map.entry("close-timeout", "native-hyphenated-close"),
                Map.entry("execution", "native-execution"),
                Map.entry("failure", "native-failure")));
        Map<String, String> originalProperties = Map.copyOf(nativeProperties);
        Map<String, String> channelProperties = new HashMap<>(Map.of("topic", "channel-native-topic"));
        Map<String, String> originalChannelProperties = Map.copyOf(channelProperties);
        KafkaConnectorConfig common = configuredBuilder().properties(nativeProperties).buildPrototype();
        KafkaIncomingConfig incoming = KafkaIncomingConfig.builder(incomingConfig())
                .properties(channelProperties)
                .build();
        KafkaOutgoingConfig outgoing = KafkaOutgoingConfig.builder(outgoingConfig())
                .properties(channelProperties)
                .build();
        Map<String, String> expectedProperties = new HashMap<>(originalProperties);
        expectedProperties.putAll(originalChannelProperties);

        Map<String, Object> consumer = KafkaConnectorConfigSupport.consumerProperties(
                KafkaConnectorConfigSupport.incoming(common, incoming));
        Map<String, Object> producer = KafkaConnectorConfigSupport.producerProperties(
                KafkaConnectorConfigSupport.outgoing(common, outgoing));

        expectedProperties.forEach((key, value) -> {
            assertThat("consumer native property " + key, consumer.get(key), is(value));
            assertThat("producer native property " + key, producer.get(key), is(value));
        });
        assertThat(nativeProperties, is(originalProperties));
        assertThat(channelProperties, is(originalChannelProperties));
        assertThat(common.properties(), is(originalProperties));
        assertThat(incoming.properties(), is(Optional.of(originalChannelProperties)));
        assertThat(outgoing.properties(), is(Optional.of(originalChannelProperties)));
        assertThrows(UnsupportedOperationException.class, () -> consumer.put("changed", "value"));
        assertThrows(UnsupportedOperationException.class, () -> producer.put("changed", "value"));
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
                .connector(CONNECTOR)
                .channelName(CHANNEL)
                .topic(TOPIC)
                .groupId("inventory")
                .keyDeserializer("channel.KeyDeserializer")
                .properties(Map.of("client.rack", "channel-rack"))
                .build();
        KafkaOutgoingConfig outgoing = KafkaOutgoingConfig.builder()
                .connector(CONNECTOR)
                .channelName("audit-copy")
                .topic("audit-copy")
                .valueSerializer("channel.ValueSerializer")
                .sendTimeout(Duration.ofSeconds(1))
                .build();

        var incomingSettings = KafkaConnectorConfigSupport.incoming(common, incoming);
        var outgoingSettings = KafkaConnectorConfigSupport.outgoing(common, outgoing);
        var defaultIncoming = KafkaConnectorConfigSupport.incoming(common,
                KafkaIncomingConfig.builder().connector(CONNECTOR).channelName(CHANNEL).build());
        var defaultOutgoing = KafkaConnectorConfigSupport.outgoing(common,
                KafkaOutgoingConfig.builder().connector(CONNECTOR).channelName(CHANNEL).build());
        assertThat(incomingSettings.bootstrapServers(), is(List.of("broker:9092")));
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
                     () -> KafkaIncomingConfig.builder().connector(CONNECTOR).channelName(CHANNEL).topic(TOPIC)
                             .pollTimeout(Duration.ofNanos(1)).build());
        assertThrows(IllegalArgumentException.class,
                     () -> KafkaOutgoingConfig.builder().connector(CONNECTOR).channelName(CHANNEL).topic(TOPIC)
                             .sendTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                     () -> KafkaIncomingConfig.builder().connector(CONNECTOR).channelName(CHANNEL).topic(TOPIC)
                             .closeTimeout(Duration.ofNanos(-1)).build());
    }

    @Test
    void testChannelPropertiesPreserveAbsenceAndMergeExplicitEmptyOverrides() {
        KafkaConnectorConfig common = configuredBuilder()
                .properties(Map.of("client.rack", "shared-rack"))
                .buildPrototype();
        KafkaIncomingConfig incoming = incomingConfig();
        KafkaOutgoingConfig outgoing = outgoingConfig();
        KafkaIncomingConfig emptyIncoming = KafkaIncomingConfig.builder(incoming).properties(Map.of()).build();
        KafkaOutgoingConfig emptyOutgoing = KafkaOutgoingConfig.builder(outgoing).properties(Map.of()).build();

        assertThat(incoming.properties(), is(Optional.empty()));
        assertThat(outgoing.properties(), is(Optional.empty()));
        assertThat(emptyIncoming.properties(), is(Optional.of(Map.of())));
        assertThat(emptyOutgoing.properties(), is(Optional.of(Map.of())));
        assertThat(KafkaConnectorConfigSupport.incoming(common, incoming).properties(), is(common.properties()));
        assertThat(KafkaConnectorConfigSupport.outgoing(common, outgoing).properties(), is(common.properties()));
        assertThat(KafkaConnectorConfigSupport.incoming(common, emptyIncoming).properties(), is(common.properties()));
        assertThat(KafkaConnectorConfigSupport.outgoing(common, emptyOutgoing).properties(), is(common.properties()));
    }

    private static KafkaConnectorConfig.Builder builder() {
        return KafkaConnectorConfig.builder().name(CONNECTOR);
    }

    private static KafkaConnectorConfig.Builder configuredBuilder() {
        return builder()
                .addBootstrapServer("broker:9092");
    }

    private static KafkaIncomingConfig incomingConfig() {
        return KafkaIncomingConfig.builder().connector(CONNECTOR).channelName(CHANNEL).topic(TOPIC).build();
    }

    private static KafkaOutgoingConfig outgoingConfig() {
        return KafkaOutgoingConfig.builder().connector(CONNECTOR).channelName(CHANNEL).topic(TOPIC).build();
    }
}
