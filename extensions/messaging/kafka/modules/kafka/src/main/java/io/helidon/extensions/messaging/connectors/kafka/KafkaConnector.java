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

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.messaging.spi.IncomingChannel;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingIncomingConfig;
import io.helidon.messaging.spi.MessagingOutgoingConfig;
import io.helidon.messaging.spi.OutgoingChannel;

/**
 * Configured Kafka connector that creates incoming and outgoing channel connections.
 */
@Api.Preview
public final class KafkaConnector implements MessagingConnector, RuntimeType.Api<KafkaConnectorConfig> {
    /**
     * Kafka connector type used in messaging configuration.
     */
    public static final String CONNECTOR_TYPE = "helidon-kafka";

    /**
     * Dead-letter header containing the original Kafka topic.
     */
    public static final String DLQ_ORIGINAL_TOPIC_HEADER = "dlq-orig-topic";

    /**
     * Dead-letter header containing the original Kafka partition.
     */
    public static final String DLQ_ORIGINAL_PARTITION_HEADER = "dlq-orig-partition";

    /**
     * Dead-letter header containing the original Kafka offset.
     */
    public static final String DLQ_ORIGINAL_OFFSET_HEADER = "dlq-orig-offset";

    /**
     * Dead-letter header containing the original Kafka record timestamp in milliseconds.
     * <p>
     * This is source metadata. The dead-letter record itself has its own publication timestamp.
     */
    public static final String DLQ_ORIGINAL_TIMESTAMP_HEADER = "dlq-orig-timestamp";

    /**
     * Dead-letter header containing the name of the original {@link KafkaMessage.TimestampType}.
     */
    public static final String DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER = "dlq-orig-timestamp-type";

    /**
     * Dead-letter header containing the original Kafka leader epoch.
     */
    public static final String DLQ_ORIGINAL_LEADER_EPOCH_HEADER = "dlq-orig-leader-epoch";

    private final KafkaConnectorConfig config;
    private final KafkaIncomingChannel incomingFactory = new KafkaIncomingChannel();
    private final KafkaOutgoingChannel outgoingFactory = new KafkaOutgoingChannel();

    private KafkaConnector(KafkaConnectorConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Create a Kafka connector from its configuration.
     *
     * @param config connector configuration
     * @return configured connector
     */
    public static KafkaConnector create(KafkaConnectorConfig config) {
        return new KafkaConnector(config);
    }

    /**
     * Create a Kafka connector from configuration.
     *
     * @param config connector configuration node
     * @return configured connector
     */
    public static KafkaConnector create(Config config) {
        return builder().config(Objects.requireNonNull(config)).build();
    }

    /**
     * Create a Kafka connector by updating a builder.
     *
     * @param consumer builder consumer
     * @return configured connector
     */
    public static KafkaConnector create(Consumer<KafkaConnectorConfig.Builder> consumer) {
        return builder().update(Objects.requireNonNull(consumer)).build();
    }

    /**
     * Create a Kafka connector builder.
     *
     * @return connector builder
     */
    public static KafkaConnectorConfig.Builder builder() {
        return KafkaConnectorConfig.builder();
    }

    @Override
    public KafkaConnectorConfig prototype() {
        return config;
    }

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public Optional<IncomingChannel> incoming(MessagingIncomingConfig channelConfig) {
        return Optional.of(incoming(incomingConfig(channelConfig)));
    }

    /**
     * Create an unstarted incoming Kafka channel.
     *
     * @param channelConfig channel configuration
     * @return incoming channel
     */
    public IncomingChannel incoming(KafkaIncomingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        return incomingFactory.createIncomingChannel(KafkaConnectorConfigSupport.incoming(config, channelConfig));
    }

    @Override
    public Optional<OutgoingChannel> outgoing(MessagingOutgoingConfig channelConfig) {
        return Optional.of(outgoing(outgoingConfig(channelConfig)));
    }

    /**
     * Create an unstarted outgoing Kafka channel.
     *
     * @param channelConfig channel configuration
     * @return outgoing channel
     */
    public OutgoingChannel outgoing(KafkaOutgoingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        return outgoingFactory.createOutgoingChannel(KafkaConnectorConfigSupport.outgoing(config, channelConfig));
    }

    private static KafkaIncomingConfig incomingConfig(MessagingIncomingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        if (channelConfig instanceof KafkaIncomingConfig kafkaConfig) {
            return kafkaConfig;
        }
        KafkaIncomingConfig.Builder builder = KafkaIncomingConfig.builder();
        channelConfig.config().ifPresent(builder::config);
        return builder.from(channelConfig).build();
    }

    private static KafkaOutgoingConfig outgoingConfig(MessagingOutgoingConfig channelConfig) {
        Objects.requireNonNull(channelConfig);
        if (channelConfig instanceof KafkaOutgoingConfig kafkaConfig) {
            return kafkaConfig;
        }
        KafkaOutgoingConfig.Builder builder = KafkaOutgoingConfig.builder();
        channelConfig.config().ifPresent(builder::config);
        return builder.from(channelConfig).build();
    }
}
