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

package io.helidon.extensions.messaging.connectors.aq;

import java.util.Objects;

import io.helidon.config.Config;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistry;

/**
 * Provider of configured AQ connectors.
 */
@Service.Singleton
final class AqConnectorProvider implements MessagingConnectorProvider {
    private final AqResourceResolver resolver;

    @Service.Inject
    AqConnectorProvider(ServiceRegistry registry) {
        resolver = new AqResourceResolver(registry);
    }

    @Override
    public String configKey() {
        return AqConnector.CONNECTOR_TYPE;
    }

    @Override
    public MessagingConnector create(Config config, String name) {
        return AqConnector.create(AqConnectorConfig.builder()
                                          .config(Objects.requireNonNull(config))
                                          .name(Objects.requireNonNull(name))
                                          .buildPrototype(),
                                  resolver);
    }
}
