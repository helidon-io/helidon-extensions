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

import io.helidon.config.Config;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.service.registry.Service;

/**
 * Provider of configured Apache Pulsar connectors.
 */
@Service.Singleton
final class PulsarConnectorProvider implements MessagingConnectorProvider {
    private final Supplier<List<PulsarSchemaProvider>> schemaProviders;

    @Service.Inject
    PulsarConnectorProvider(Supplier<List<PulsarSchemaProvider>> schemaProviders) {
        this.schemaProviders = Objects.requireNonNull(schemaProviders);
    }

    @Override
    public String configKey() {
        return PulsarConnector.CONNECTOR_TYPE;
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
