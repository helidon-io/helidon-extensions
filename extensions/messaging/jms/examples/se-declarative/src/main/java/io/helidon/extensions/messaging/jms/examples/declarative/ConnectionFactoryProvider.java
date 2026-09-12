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

package io.helidon.extensions.messaging.jms.examples.declarative;

import java.util.function.Supplier;

import io.helidon.config.Config;
import io.helidon.service.registry.Service;

import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

/**
 * Provides the application's Artemis connection factory.
 */
@Service.Singleton
@Service.Named("artemis")
@Service.ExternalContracts(ConnectionFactory.class)
class ConnectionFactoryProvider implements Supplier<ConnectionFactory> {
    private final ActiveMQConnectionFactory connectionFactory;

    @Service.Inject
    ConnectionFactoryProvider(Config config) {
        connectionFactory = new ActiveMQConnectionFactory(config.get("app.jms-broker-url").asString().get());
    }

    @Override
    public ConnectionFactory get() {
        return connectionFactory;
    }

    @Service.PreDestroy
    void shutdown() {
        connectionFactory.close();
    }
}
