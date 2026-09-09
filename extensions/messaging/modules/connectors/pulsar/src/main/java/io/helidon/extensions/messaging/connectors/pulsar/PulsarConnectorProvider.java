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

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import io.helidon.common.Api;
import io.helidon.config.Config;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.service.registry.Service;

/**
 * Provider of configured Apache Pulsar connectors.
 */
@Api.Preview
@Service.Singleton
public final class PulsarConnectorProvider implements MessagingConnectorProvider {
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

    private final Supplier<List<PulsarSchemaProvider>> schemaProviders;

    @Service.Inject
    PulsarConnectorProvider(Supplier<List<PulsarSchemaProvider>> schemaProviders) {
        this.schemaProviders = Objects.requireNonNull(schemaProviders);
    }

    @Override
    public String configKey() {
        return CONNECTOR_TYPE;
    }

    @Override
    public MessagingConnector create(Config config, String name) {
        return PulsarConnector.builder()
                .config(Objects.requireNonNull(config))
                .name(Objects.requireNonNull(name))
                .schemaProviders(schemaProviders.get())
                .build();
    }
}
