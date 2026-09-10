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
 * Configured Apache Pulsar connector that creates incoming and outgoing channels.
 */
@Api.Preview
public final class PulsarConnector implements MessagingConnector, RuntimeType.Api<PulsarConnectorConfig> {
    /** Connector type used in messaging configuration. */
    public static final String CONNECTOR_TYPE = "helidon-pulsar";
    /** Dead-letter property containing the original Pulsar topic. */
    public static final String DLQ_ORIGINAL_TOPIC_HEADER = "dlq-orig-topic";
    /** Dead-letter property containing the base64-encoded original message ID. */
    public static final String DLQ_ORIGINAL_MESSAGE_ID_HEADER = "dlq-orig-message-id";
    /** Dead-letter property containing the original publication time. */
    public static final String DLQ_ORIGINAL_PUBLISH_TIME_HEADER = "dlq-orig-publish-time";
    /** Dead-letter property containing the original producer name. */
    public static final String DLQ_ORIGINAL_PRODUCER_NAME_HEADER = "dlq-orig-producer-name";
    /** Dead-letter property containing the original sequence ID. */
    public static final String DLQ_ORIGINAL_SEQUENCE_ID_HEADER = "dlq-orig-sequence-id";
    /** Dead-letter property containing the original redelivery count. */
    public static final String DLQ_ORIGINAL_REDELIVERY_COUNT_HEADER = "dlq-orig-redelivery-count";

    private final PulsarConnectorConfig config;
    private final PulsarIncomingChannel incomingFactory = new PulsarIncomingChannel();
    private final PulsarOutgoingChannel outgoingFactory = new PulsarOutgoingChannel();

    private PulsarConnector(PulsarConnectorConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Create a connector from its configuration.
     *
     * @param config connector configuration
     * @return configured connector
     */
    public static PulsarConnector create(PulsarConnectorConfig config) {
        return new PulsarConnector(config);
    }

    /**
     * Create a connector from a configuration node.
     *
     * @param config connector configuration node
     * @return configured connector
     */
    public static PulsarConnector create(Config config) {
        return builder().config(Objects.requireNonNull(config)).build();
    }

    /**
     * Create a connector by updating a builder.
     *
     * @param consumer builder consumer
     * @return configured connector
     */
    public static PulsarConnector create(Consumer<PulsarConnectorConfig.Builder> consumer) {
        return builder().update(Objects.requireNonNull(consumer)).build();
    }

    /**
     * Create a connector builder.
     *
     * @return connector builder
     */
    public static PulsarConnectorConfig.Builder builder() {
        return PulsarConnectorConfig.builder();
    }

    @Override
    public PulsarConnectorConfig prototype() {
        return config;
    }

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public Optional<IncomingChannel> incoming(MessagingIncomingConfig channelConfig) {
        return Optional.of(incoming(incomingConfig(Objects.requireNonNull(channelConfig))));
    }

    /**
     * Create an unstarted incoming channel.
     *
     * @param channelConfig incoming channel configuration
     * @return incoming channel
     */
    public IncomingChannel incoming(PulsarIncomingConfig channelConfig) {
        return incomingFactory.createIncomingChannel(config, Objects.requireNonNull(channelConfig));
    }

    @Override
    public Optional<OutgoingChannel> outgoing(MessagingOutgoingConfig channelConfig) {
        return Optional.of(outgoing(outgoingConfig(Objects.requireNonNull(channelConfig))));
    }

    /**
     * Create an unstarted outgoing channel.
     *
     * @param channelConfig outgoing channel configuration
     * @return outgoing channel
     */
    public OutgoingChannel outgoing(PulsarOutgoingConfig channelConfig) {
        return outgoingFactory.createOutgoingChannel(config, Objects.requireNonNull(channelConfig));
    }

    private static PulsarIncomingConfig incomingConfig(MessagingIncomingConfig channelConfig) {
        if (channelConfig instanceof PulsarIncomingConfig pulsarConfig) {
            return pulsarConfig;
        }
        var builder = PulsarIncomingConfig.builder();
        channelConfig.config().ifPresent(builder::config);
        return builder.from(channelConfig).build();
    }

    private static PulsarOutgoingConfig outgoingConfig(MessagingOutgoingConfig channelConfig) {
        if (channelConfig instanceof PulsarOutgoingConfig pulsarConfig) {
            return pulsarConfig;
        }
        var builder = PulsarOutgoingConfig.builder();
        channelConfig.config().ifPresent(builder::config);
        return builder.from(channelConfig).build();
    }
}
