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

import io.helidon.config.Config;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.IncomingConnectorProvider;
import io.helidon.extensions.messaging.IncomingEndpoint;
import io.helidon.extensions.messaging.OutgoingConnectorProvider;
import io.helidon.extensions.messaging.OutgoingEndpoint;
import io.helidon.service.registry.Service;

/**
 * Stateless Kafka connector provider.
 */
@Service.Singleton
public final class KafkaConnectorProvider
        implements IncomingConnectorProvider<KafkaConnectorConfig>, OutgoingConnectorProvider<KafkaConnectorConfig> {
    /**
     * Kafka connector type used in messaging configuration.
     */
    public static final String CONNECTOR_TYPE = "kafka";

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

    private final KafkaIncomingConnector incomingConnector;
    private final KafkaOutgoingConnector outgoingConnector;

    /**
     * Create the Kafka connector provider.
     */
    @Service.Inject
    public KafkaConnectorProvider() {
        this(new KafkaIncomingConnector(), new KafkaOutgoingConnector());
    }

    KafkaConnectorProvider(KafkaIncomingConnector.ConsumerFactory consumerFactory,
                           KafkaOutgoingConnector.ProducerFactory producerFactory) {
        this(new KafkaIncomingConnector(consumerFactory), new KafkaOutgoingConnector(producerFactory));
    }

    private KafkaConnectorProvider(KafkaIncomingConnector incomingConnector,
                                   KafkaOutgoingConnector outgoingConnector) {
        this.incomingConnector = incomingConnector;
        this.outgoingConnector = outgoingConnector;
    }

    @Override
    public String connectorType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public KafkaConnectorConfig createConfig(Config config) {
        return KafkaConnectorConfig.create(Objects.requireNonNull(config));
    }

    @Override
    public IncomingEndpoint createIncomingEndpoint(KafkaConnectorConfig config, ConnectorSourceContext context) {
        requireDirection(config, ConnectorConfig.Direction.INCOMING);
        return incomingConnector.createIncomingEndpoint(config, context);
    }

    @Override
    public OutgoingEndpoint createOutgoingEndpoint(KafkaConnectorConfig config) {
        requireDirection(config, ConnectorConfig.Direction.OUTGOING);
        return outgoingConnector.createOutgoingEndpoint(config);
    }

    private static void requireDirection(KafkaConnectorConfig config, ConnectorConfig.Direction expected) {
        Objects.requireNonNull(config);
        if (config.direction() != expected) {
            throw new IllegalArgumentException("Kafka connector configuration for channel " + config.channel()
                                                       + " has direction " + config.direction()
                                                       + ", expected " + expected);
        }
    }
}
