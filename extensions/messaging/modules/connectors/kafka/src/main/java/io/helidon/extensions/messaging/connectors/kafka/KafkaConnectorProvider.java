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
import io.helidon.messaging.ConnectorConfig;
import io.helidon.messaging.IncomingConnector;
import io.helidon.messaging.IncomingConnectorProvider;
import io.helidon.messaging.OutgoingConnector;
import io.helidon.messaging.OutgoingConnectorProvider;
import io.helidon.service.registry.Service;

/**
 * Stateless Kafka connector provider.
 */
@Service.Singleton
public final class KafkaConnectorProvider
        implements IncomingConnectorProvider, OutgoingConnectorProvider {
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

    private final KafkaIncomingConnector incomingFactory;
    private final KafkaOutgoingConnector outgoingFactory;

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

    private KafkaConnectorProvider(KafkaIncomingConnector incomingFactory,
                                   KafkaOutgoingConnector outgoingFactory) {
        this.incomingFactory = incomingFactory;
        this.outgoingFactory = outgoingFactory;
    }

    @Override
    public String connectorType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public IncomingConnector createIncomingConnector(Config config) {
        return createIncomingConnector(KafkaConnectorConfig.create(Objects.requireNonNull(config)));
    }

    /**
     * Create one unstarted incoming Kafka connector from typed configuration.
     *
     * @param config typed Kafka configuration
     * @return incoming connector
     */
    public IncomingConnector createIncomingConnector(KafkaConnectorConfig config) {
        requireDirection(config, ConnectorConfig.Direction.INCOMING);
        return incomingFactory.createIncomingConnector(config);
    }

    @Override
    public OutgoingConnector createOutgoingConnector(Config config) {
        return createOutgoingConnector(KafkaConnectorConfig.create(Objects.requireNonNull(config)));
    }

    /**
     * Create one unstarted outgoing Kafka connector from typed configuration.
     *
     * @param config typed Kafka configuration
     * @return outgoing connector
     */
    public OutgoingConnector createOutgoingConnector(KafkaConnectorConfig config) {
        requireDirection(config, ConnectorConfig.Direction.OUTGOING);
        return outgoingFactory.createOutgoingConnector(config);
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
