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
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.sql.DataSource;

import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.FactoryType;
import io.helidon.service.registry.Lookup;
import io.helidon.service.registry.ServiceInstance;
import io.helidon.service.registry.ServiceRegistry;

import oracle.jms.AQjmsFactory;

/**
 * Resolves shared application resources without taking ownership of them.
 */
final class AqResourceResolver {
    private final Supplier<ServiceRegistry> registry;

    AqResourceResolver(ServiceRegistry registry) {
        Objects.requireNonNull(registry);
        this.registry = () -> registry;
    }

    AqResourceResolver(Supplier<ServiceRegistry> registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    ConnectionFactory resolve(AqRuntimeConfig config) {
        try {
            if (config.connectionFactory().isPresent()) {
                return config.connectionFactory().orElseThrow();
            }
            if (config.dataSource().isPresent()) {
                return AQjmsFactory.getConnectionFactory(config.dataSource().orElseThrow());
            }
            if (config.jdbcUrl().isPresent()) {
                return AQjmsFactory.getConnectionFactory(config.jdbcUrl().orElseThrow(), new Properties());
            }
            ServiceRegistry services = registry.get();
            if (config.connectionFactoryName().isPresent()) {
                String name = config.connectionFactoryName().orElseThrow();
                return services.firstNamed(ConnectionFactory.class, name)
                        .orElseThrow(() -> missing(config, "ConnectionFactory named " + name));
            }
            if (config.dataSourceName().isPresent()) {
                String name = config.dataSourceName().orElseThrow();
                DataSource source = services.firstNamed(DataSource.class, name)
                        .orElseThrow(() -> missing(config, "DataSource named " + name));
                return AQjmsFactory.getConnectionFactory(source);
            }
            var dataSource = unqualified(services, DataSource.class);
            if (dataSource.isPresent()) {
                return AQjmsFactory.getConnectionFactory(dataSource.orElseThrow());
            }
            return unqualified(services, ConnectionFactory.class)
                    .orElseThrow(() -> missing(config, "DataSource or AQ ConnectionFactory"));
        } catch (JMSException e) {
            throw new MessagingException("Cannot resolve AQ connection factory for channel " + config.channelName(), e);
        }
    }

    private static MessagingException missing(AqRuntimeConfig config, String resource) {
        return new MessagingException("No " + resource + " is registered for AQ channel " + config.channelName());
    }

    private static <T> Optional<T> unqualified(ServiceRegistry registry, Class<T> contract) {
        Lookup lookup = Lookup.create(contract);
        return registry.lookupServices(lookup).stream()
                // These factories may supply instances with qualifiers different from the factory's qualifiers.
                .filter(service -> service.qualifiers().isEmpty()
                        || service.factoryType() == FactoryType.SERVICES
                        || service.factoryType() == FactoryType.INJECTION_POINT)
                .flatMap(service -> registry.<T>lookupInstances(Lookup.builder(lookup)
                                .serviceType(service.serviceType())
                                .build())
                        .stream())
                .filter(instance -> instance.qualifiers().isEmpty())
                .map(ServiceInstance::get)
                .findFirst();
    }
}
