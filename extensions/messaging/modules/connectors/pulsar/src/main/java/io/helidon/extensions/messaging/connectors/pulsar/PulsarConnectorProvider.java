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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import io.helidon.config.Config;
import io.helidon.messaging.ConnectorDirection;
import io.helidon.messaging.IncomingConnector;
import io.helidon.messaging.IncomingConnectorProvider;
import io.helidon.messaging.OutgoingConnector;
import io.helidon.messaging.OutgoingConnectorProvider;
import io.helidon.service.registry.Service;

/**
 * Stateless Apache Pulsar connector provider.
 */
@Service.Singleton
public final class PulsarConnectorProvider
        implements IncomingConnectorProvider, OutgoingConnectorProvider {
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
    private final PulsarIncomingConnector incomingFactory;
    private final PulsarOutgoingConnector outgoingFactory;

    /**
     * Create an imperative connector provider using only built-in schemas.
     */
    public PulsarConnectorProvider() {
        this(List::of);
    }

    /**
     * Create an imperative connector provider with custom named schemas.
     *
     * @param schemaProviders custom schema providers
     */
    public PulsarConnectorProvider(PulsarSchemaProvider... schemaProviders) {
        this(fixedProviders(schemaProviders));
    }

    @Service.Inject
    PulsarConnectorProvider(Supplier<List<PulsarSchemaProvider>> schemaProviders) {
        this(schemaProviders, new PulsarIncomingConnector(), new PulsarOutgoingConnector());
    }

    PulsarConnectorProvider(PulsarIncomingConnector.ClientFactory incomingClientFactory,
                            PulsarOutgoingConnector.ClientFactory outgoingClientFactory) {
        this(List::of,
             new PulsarIncomingConnector(incomingClientFactory),
             new PulsarOutgoingConnector(outgoingClientFactory));
    }

    private PulsarConnectorProvider(Supplier<List<PulsarSchemaProvider>> schemaProviders,
                                    PulsarIncomingConnector incomingFactory,
                                    PulsarOutgoingConnector outgoingFactory) {
        this.schemaProviders = Objects.requireNonNull(schemaProviders);
        this.incomingFactory = Objects.requireNonNull(incomingFactory);
        this.outgoingFactory = Objects.requireNonNull(outgoingFactory);
    }

    @Override
    public String connectorType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public IncomingConnector createIncomingConnector(Config config) {
        return createIncomingConnector(PulsarConnectorConfig.create(Objects.requireNonNull(config)));
    }

    /**
     * Create an unstarted incoming Pulsar binding.
     *
     * @param config typed configuration
     * @return incoming connector
     */
    public IncomingConnector createIncomingConnector(PulsarConnectorConfig config) {
        requireDirection(config, ConnectorDirection.INCOMING);
        PulsarSchemaResolver.ResolvedSchema schema = PulsarSchemaResolver.resolve(config,
                                                                                  ConnectorDirection.INCOMING,
                                                                                  schemaProviders);
        return incomingFactory.createIncomingConnector(config, schema);
    }

    @Override
    public OutgoingConnector createOutgoingConnector(Config config) {
        return createOutgoingConnector(PulsarConnectorConfig.create(Objects.requireNonNull(config)));
    }

    /**
     * Create an unstarted outgoing Pulsar binding.
     *
     * @param config typed configuration
     * @return outgoing connector
     */
    public OutgoingConnector createOutgoingConnector(PulsarConnectorConfig config) {
        requireDirection(config, ConnectorDirection.OUTGOING);
        PulsarSchemaResolver.ResolvedSchema schema = PulsarSchemaResolver.resolve(config,
                                                                                  ConnectorDirection.OUTGOING,
                                                                                  schemaProviders);
        return outgoingFactory.createOutgoingConnector(config, schema);
    }

    private static void requireDirection(PulsarConnectorConfig config, ConnectorDirection expected) {
        Objects.requireNonNull(config);
        if (config.direction() != expected) {
            throw new IllegalArgumentException("Pulsar connector configuration for channel " + config.channel()
                                                       + " has direction " + config.direction()
                                                       + ", expected " + expected);
        }
    }

    private static Supplier<List<PulsarSchemaProvider>> fixedProviders(PulsarSchemaProvider[] schemaProviders) {
        Objects.requireNonNull(schemaProviders, "schemaProviders");
        List<PulsarSchemaProvider> providers = List.copyOf(Arrays.asList(schemaProviders.clone()));
        return () -> providers;
    }
}
