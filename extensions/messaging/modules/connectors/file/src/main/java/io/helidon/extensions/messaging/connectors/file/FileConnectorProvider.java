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

package io.helidon.extensions.messaging.connectors.file;

import java.util.Objects;

import io.helidon.config.Config;
import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.IncomingConnectorProvider;
import io.helidon.extensions.messaging.OutgoingConnector;
import io.helidon.extensions.messaging.OutgoingConnectorProvider;
import io.helidon.service.registry.Service;

/**
 * Stateless file connector provider.
 * <p>
 * Each factory invocation returns an independent connector. The provider does not retain connectors, threads, files,
 * or lifecycle state.
 */
@Service.Singleton
public final class FileConnectorProvider
        implements IncomingConnectorProvider, OutgoingConnectorProvider {
    /**
     * Connector type used in messaging configuration.
     */
    public static final String CONNECTOR_TYPE = "helidon-file";

    @Override
    public String connectorType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public IncomingConnector createIncomingConnector(Config config) {
        return createIncomingConnector(FileConnectorConfig.create(Objects.requireNonNull(config)));
    }

    /**
     * Create an incoming connector from typed file configuration.
     *
     * @param config file connector configuration
     * @return incoming connector
     */
    public IncomingConnector createIncomingConnector(FileConnectorConfig config) {
        requireDirection(config, ConnectorConfig.Direction.INCOMING);
        return FileIncomingConnector.createConnector(config);
    }

    @Override
    public OutgoingConnector createOutgoingConnector(Config config) {
        return createOutgoingConnector(FileConnectorConfig.create(Objects.requireNonNull(config)));
    }

    /**
     * Create an outgoing connector from typed file configuration.
     *
     * @param config file connector configuration
     * @return outgoing connector
     */
    public OutgoingConnector createOutgoingConnector(FileConnectorConfig config) {
        requireDirection(config, ConnectorConfig.Direction.OUTGOING);
        return FileOutgoingConnector.createConnector(config);
    }

    private static void requireDirection(FileConnectorConfig config, ConnectorConfig.Direction expected) {
        Objects.requireNonNull(config);
        if (config.direction() != expected) {
            throw new IllegalArgumentException("File connector configuration for channel " + config.channel()
                                                       + " has direction " + config.direction()
                                                       + ", expected " + expected);
        }
    }
}
