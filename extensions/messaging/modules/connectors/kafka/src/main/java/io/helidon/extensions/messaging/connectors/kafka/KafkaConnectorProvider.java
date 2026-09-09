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

import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.service.registry.Service;

/**
 * Provider of configured Kafka connectors.
 */
@Api.Preview
@Service.Singleton
public final class KafkaConnectorProvider
        implements MessagingConnectorProvider {
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

    /**
     * Create a provider for service discovery.
     */
    public KafkaConnectorProvider() {
    }

    @Override
    public String configKey() {
        return CONNECTOR_TYPE;
    }

    @Override
    public MessagingConnector create(Config config, String name) {
        return KafkaConnector.builder()
                .config(Objects.requireNonNull(config))
                .name(Objects.requireNonNull(name))
                .build();
    }
}
