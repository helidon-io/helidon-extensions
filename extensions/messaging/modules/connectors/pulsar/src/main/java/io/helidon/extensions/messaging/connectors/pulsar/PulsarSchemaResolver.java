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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import io.helidon.messaging.ConnectorConfig;

import org.apache.pulsar.client.api.Schema;

final class PulsarSchemaResolver {
    private PulsarSchemaResolver() {
    }

    static ResolvedSchema resolve(PulsarConnectorConfig config,
                                  ConnectorConfig.Direction direction,
                                  Supplier<List<PulsarSchemaProvider>> providers) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(direction);
        Objects.requireNonNull(providers);
        if (config.schemaProvider().isEmpty()) {
            PulsarSchemaType type = config.schema();
            return new ResolvedSchema(type.schema(direction), type, type.name(), direction);
        }

        String selectedName = config.schemaProvider().orElseThrow();
        if (selectedName.isBlank()) {
            throw new IllegalArgumentException("Pulsar schema-provider must not be blank for channel "
                                                       + config.channel());
        }
        List<PulsarSchemaProvider> available;
        try {
            available = providers.get();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot load Pulsar schema providers while resolving '" + selectedName
                                                       + "' for channel " + config.channel(), e);
        }
        if (available == null) {
            throw new IllegalArgumentException("Pulsar schema provider lookup returned null while resolving '"
                                                       + selectedName + "' for channel " + config.channel());
        }
        List<PulsarSchemaProvider> matches = new ArrayList<>();
        for (PulsarSchemaProvider provider : available) {
            if (provider == null) {
                throw new IllegalArgumentException("Pulsar schema provider lookup contains null while resolving '"
                                                           + selectedName + "' for channel " + config.channel());
            }
            String providerName;
            try {
                providerName = provider.name();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Cannot read the name of Pulsar schema provider "
                                                           + provider.getClass().getName() + " while resolving '"
                                                           + selectedName + "' for channel " + config.channel(), e);
            }
            if (providerName == null || providerName.isBlank()) {
                throw new IllegalArgumentException("Pulsar schema provider " + provider.getClass().getName()
                                                           + " returned a null or blank name while resolving '"
                                                           + selectedName + "' for channel " + config.channel());
            }
            if (providerName.equals(selectedName)) {
                matches.add(provider);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No Pulsar schema provider named '" + selectedName
                                                       + "' is registered for channel " + config.channel());
        }
        if (matches.size() > 1) {
            String providerTypes = matches.stream()
                    .map(provider -> provider.getClass().getName())
                    .sorted(Comparator.naturalOrder())
                    .distinct()
                    .reduce((first, second) -> first + ", " + second)
                    .orElseThrow();
            throw new IllegalArgumentException("Multiple Pulsar schema providers are named '" + selectedName
                                                       + "' for channel " + config.channel() + ": " + providerTypes);
        }
        PulsarSchemaProvider provider = matches.getFirst();
        Schema<?> schema;
        try {
            schema = provider.schema();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Pulsar schema provider '" + selectedName + "' ("
                                                       + provider.getClass().getName() + ") failed for channel "
                                                       + config.channel(), e);
        }
        if (schema == null) {
            throw new IllegalArgumentException("Pulsar schema provider '" + selectedName + "' ("
                                                       + provider.getClass().getName() + ") returned null for channel "
                                                       + config.channel());
        }
        return new ResolvedSchema(schema(schema), null, selectedName, direction);
    }

    @SuppressWarnings("unchecked")
    private static Schema<Object> schema(Schema<?> schema) {
        return (Schema<Object>) schema;
    }

    record ResolvedSchema(Schema<Object> schema,
                          PulsarSchemaType builtIn,
                          String name,
                          ConnectorConfig.Direction direction) {
        ResolvedSchema {
            Objects.requireNonNull(schema);
            Objects.requireNonNull(name);
            Objects.requireNonNull(direction);
        }

        Object snapshot(Object value) {
            return builtIn == null
                    ? PulsarMessageImpl.snapshotEntity(value)
                    : builtIn.snapshot(value, direction);
        }
    }
}
