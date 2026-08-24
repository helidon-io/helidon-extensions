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
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.messaging.ConnectorConfig;

/**
 * Apache Pulsar connector configuration.
 */
@Prototype.Blueprint(decorator = PulsarConnectorConfigSupport.BuilderDecorator.class)
@Prototype.Configured
@Prototype.CustomMethods(PulsarConnectorConfigSupport.class)
interface PulsarConnectorConfigBlueprint extends ConnectorConfig {
    /**
     * Pulsar broker service URL.
     *
     * @return service URL
     */
    @Option.Required
    @Option.Configured(PulsarConnectorConfigSupport.SERVICE_URL_PROPERTY)
    String serviceUrl();

    /**
     * Pulsar topic.
     *
     * @return topic
     */
    @Option.Required
    @Option.Configured(PulsarConnectorConfigSupport.TOPIC_PROPERTY)
    String topic();

    /**
     * Built-in payload schema.
     *
     * @return payload schema
     */
    @Option.Configured(PulsarConnectorConfigSupport.SCHEMA_PROPERTY)
    @Option.DefaultCode("PulsarSchemaType.STRING")
    PulsarSchemaType schema();

    /**
     * Name of a custom {@link PulsarSchemaProvider} in the Helidon Service Registry. When present, the provider schema
     * overrides {@link #schema()}.
     *
     * @return custom schema provider name
     */
    @Option.Configured(PulsarConnectorConfigSupport.SCHEMA_PROVIDER_PROPERTY)
    Optional<String> schemaProvider();

    /**
     * Incoming durable subscription name. The channel name is used when absent.
     *
     * @return configured subscription name
     */
    @Option.Configured(PulsarConnectorConfigSupport.SUBSCRIPTION_NAME_PROPERTY)
    Optional<String> subscriptionName();

    /**
     * Incoming subscription type.
     *
     * @return subscription type
     */
    @Option.Configured(PulsarConnectorConfigSupport.SUBSCRIPTION_TYPE_PROPERTY)
    @Option.DefaultCode("PulsarSubscriptionType.EXCLUSIVE")
    PulsarSubscriptionType subscriptionType();

    /**
     * Initial position used only when the incoming subscription is created.
     *
     * @return initial subscription position
     */
    @Option.Configured(PulsarConnectorConfigSupport.SUBSCRIPTION_INITIAL_POSITION_PROPERTY)
    @Option.DefaultCode("PulsarSubscriptionInitialPosition.LATEST")
    PulsarSubscriptionInitialPosition subscriptionInitialPosition();

    /**
     * Whether the client acknowledges individual indexes within producer-side Pulsar batches. This requires the broker
     * setting {@code acknowledgmentAtBatchIndexLevelEnabled=true}.
     *
     * @return whether batch-index acknowledgement is enabled
     */
    @Option.Configured(PulsarConnectorConfigSupport.BATCH_INDEX_ACKNOWLEDGMENT_ENABLED_PROPERTY)
    @Option.DefaultBoolean(false)
    boolean batchIndexAcknowledgmentEnabled();

    /**
     * Maximum Pulsar client receive queue size per topic partition. The runtime delivery limit further bounds each
     * partition queue, although aggregate client prefetch can exceed that limit for a partitioned topic. The connector
     * retains and delivers only one message after acquiring a runtime reservation.
     *
     * @return receive queue size
     */
    @Option.Configured(PulsarConnectorConfigSupport.RECEIVER_QUEUE_SIZE_PROPERTY)
    @Option.DefaultInt(1)
    int receiverQueueSize();

    /**
     * Maximum incoming payload size copied into one Helidon message.
     *
     * @return maximum payload bytes
     */
    @Option.Configured(PulsarConnectorConfigSupport.MAX_MESSAGE_BYTES_PROPERTY)
    @Option.DefaultCode("PulsarConnectorConfigSupport.DEFAULT_MAX_MESSAGE_BYTES")
    int maxMessageBytes();

    /**
     * Maximum duration of one incoming receive call.
     *
     * @return receive timeout
     */
    @Option.Configured(PulsarConnectorConfigSupport.RECEIVE_TIMEOUT_PROPERTY)
    @Option.Default(PulsarConnectorConfigSupport.DEFAULT_RECEIVE_TIMEOUT)
    Duration receiveTimeout();

    /**
     * Pulsar redelivery delay after a negatively acknowledged delivery.
     *
     * @return negative acknowledgement redelivery delay
     */
    @Option.Configured(PulsarConnectorConfigSupport.NEGATIVE_ACK_REDELIVERY_DELAY_PROPERTY)
    @Option.Default(PulsarConnectorConfigSupport.DEFAULT_NEGATIVE_ACK_REDELIVERY_DELAY)
    Duration negativeAckRedeliveryDelay();

    /**
     * Maximum duration to await an outgoing broker persistence result.
     *
     * @return send timeout
     */
    @Option.Configured(PulsarConnectorConfigSupport.SEND_TIMEOUT_PROPERTY)
    @Option.Default(PulsarConnectorConfigSupport.DEFAULT_SEND_TIMEOUT)
    Duration sendTimeout();

    /**
     * Maximum duration to await a broker-confirmed incoming acknowledgement.
     *
     * @return settlement timeout
     */
    @Option.Configured(PulsarConnectorConfigSupport.SETTLEMENT_TIMEOUT_PROPERTY)
    @Option.Default(PulsarConnectorConfigSupport.DEFAULT_SETTLEMENT_TIMEOUT)
    Duration settlementTimeout();

    /**
     * Maximum duration for graceful connector shutdown.
     *
     * @return close timeout
     */
    @Option.Configured(PulsarConnectorConfigSupport.CLOSE_TIMEOUT_PROPERTY)
    @Option.Default(PulsarConnectorConfigSupport.DEFAULT_CLOSE_TIMEOUT)
    Duration closeTimeout();

    /**
     * Additional Pulsar client properties. Values are confidential because authentication material may be present.
     *
     * @return client properties
     */
    @Option.Configured(PulsarConnectorConfigSupport.CLIENT_PROPERTIES_PROPERTY)
    @Option.Confidential
    @Option.Singular("clientProperty")
    Map<String, String> clientProperties();

    /**
     * Additional Pulsar consumer properties. Connector lifecycle and settlement invariants override conflicting keys.
     *
     * @return consumer properties
     */
    @Option.Configured(PulsarConnectorConfigSupport.CONSUMER_PROPERTIES_PROPERTY)
    @Option.Confidential
    @Option.Singular("consumerProperty")
    Map<String, String> consumerProperties();

    /**
     * Additional Pulsar producer properties. Typed connector options override conflicting keys.
     *
     * @return producer properties
     */
    @Option.Configured(PulsarConnectorConfigSupport.PRODUCER_PROPERTIES_PROPERTY)
    @Option.Confidential
    @Option.Singular("producerProperty")
    Map<String, String> producerProperties();
}
