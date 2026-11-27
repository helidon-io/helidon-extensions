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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;
import io.helidon.common.Size;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.messaging.spi.MessagingConnectorProviderConfig;

/**
 * Shared Apache Pulsar connector configuration.
 */
@Api.Incubating
@Prototype.Blueprint(decorator = PulsarConnectorConfigSupport.BuilderDecorator.class)
@Prototype.Sealed
@Prototype.Configured(value = PulsarConnector.CONNECTOR_TYPE, root = false)
@Prototype.Provides(MessagingConnectorProvider.class)
@Prototype.CustomMethods(PulsarConnectorConfigSupport.class)
interface PulsarConnectorConfigBlueprint extends MessagingConnectorProviderConfig, Prototype.Factory<PulsarConnector> {
    /**
     * Pulsar broker service URL.
     *
     * @return service URL
     */
    @Option.Required
    @Option.Configured(PulsarConnectorConfigSupport.SERVICE_URL_PROPERTY)
    String serviceUrl();

    /**
     * Default Pulsar topic. Each channel must supply a topic when this is absent.
     *
     * @return topic
     */
    @Option.Configured(PulsarConnectorConfigSupport.TOPIC_PROPERTY)
    Optional<String> topic();

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
     * Pulsar receive queue size used to request flow permits per topic partition. The runtime delivery limit caps this
     * setting. Unpacking a producer-batched entry can buffer more logical messages than this size in a single partition.
     * The connector reserves runtime capacity before acquiring each batch.
     *
     * @return receive queue size
     */
    @Option.Configured(PulsarConnectorConfigSupport.RECEIVER_QUEUE_SIZE_PROPERTY)
    @Option.DefaultInt(100)
    int receiverQueueSize();

    /**
     * Maximum number of messages in one native receive batch, bounded by the receiver queue size and runtime delivery
     * limit. When the effective queue size equals the batch count, a partial batch can leave the next receive waiting
     * for its timeout despite a backlog. A queue of seven with a runtime limit of at least seven provides refill
     * headroom for five-message batches. Use one for single-message batches.
     *
     * @return maximum batch message count
     */
    @Option.Configured
    @Option.DefaultInt(100)
    int batchReceiveMaxMessages();

    /**
     * Target maximum encoded bytes in one native receive batch. Pulsar permits the first message to exceed this target;
     * {@link #maxMessageBytes()} independently limits each incoming payload.
     * Supported values range from one byte to {@link Integer#MAX_VALUE} bytes.
     *
     * @return batch byte target
     */
    @Option.Configured
    @Option.Default("10 MiB")
    Size batchReceiveMaxBytes();

    /**
     * Maximum incoming payload size copied into one Helidon message.
     *
     * @return maximum payload bytes
     */
    @Option.Configured(PulsarConnectorConfigSupport.MAX_MESSAGE_BYTES_PROPERTY)
    @Option.DefaultCode("PulsarConnectorConfigSupport.DEFAULT_MAX_MESSAGE_BYTES")
    int maxMessageBytes();

    /**
     * Maximum wait to accumulate a native receive batch before returning the available messages. A batch can return
     * earlier when its count or byte target is reached. A message count of one returns when one message is available.
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
     * Maximum total duration to await broker-confirmed incoming acknowledgements for one receive batch.
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
    /**
     * Custom schema providers available to channels created by this connector.
     * Configured connectors receive these providers from the service registry.
     *
     * @return custom schema providers
     */
    @Option.Singular
    List<PulsarSchemaProvider> schemaProviders();
}
