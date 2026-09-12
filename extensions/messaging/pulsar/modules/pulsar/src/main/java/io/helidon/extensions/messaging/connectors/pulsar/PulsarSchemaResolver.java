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
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.pulsar.client.api.Schema;

final class PulsarSchemaResolver {
    private PulsarSchemaResolver() {
    }

    static ResolvedSchema resolve(String channelName,
                                  PulsarSchemaType type,
                                  Optional<String> schemaProvider,
                                  boolean incoming,
                                  Supplier<List<PulsarSchemaProvider>> providers) {
        Objects.requireNonNull(channelName);
        Objects.requireNonNull(type);
        Objects.requireNonNull(schemaProvider);
        Objects.requireNonNull(providers);
        if (schemaProvider.isEmpty()) {
            return new ResolvedSchema(type.schema(incoming), type, type.name(), incoming);
        }

        String selectedName = schemaProvider.orElseThrow();
        if (selectedName.isBlank()) {
            throw new IllegalArgumentException("Pulsar schema-provider must not be blank for channel "
                                                       + channelName);
        }
        List<PulsarSchemaProvider> available;
        try {
            available = providers.get();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot load Pulsar schema providers while resolving '" + selectedName
                                                       + "' for channel " + channelName, e);
        }
        if (available == null) {
            throw new IllegalArgumentException("Pulsar schema provider lookup returned null while resolving '"
                                                       + selectedName + "' for channel " + channelName);
        }
        List<PulsarSchemaProvider> matches = new ArrayList<>();
        for (PulsarSchemaProvider provider : available) {
            if (provider == null) {
                throw new IllegalArgumentException("Pulsar schema provider lookup contains null while resolving '"
                                                           + selectedName + "' for channel " + channelName);
            }
            String providerName;
            try {
                providerName = provider.name();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Cannot read the name of Pulsar schema provider "
                                                           + provider.getClass().getName() + " while resolving '"
                                                           + selectedName + "' for channel " + channelName, e);
            }
            if (providerName == null || providerName.isBlank()) {
                throw new IllegalArgumentException("Pulsar schema provider " + provider.getClass().getName()
                                                           + " returned a null or blank name while resolving '"
                                                           + selectedName + "' for channel " + channelName);
            }
            if (providerName.equals(selectedName)) {
                matches.add(provider);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No Pulsar schema provider named '" + selectedName
                                                       + "' is registered for channel " + channelName);
        }
        if (matches.size() > 1) {
            String providerTypes = matches.stream()
                    .map(provider -> provider.getClass().getName())
                    .sorted(Comparator.naturalOrder())
                    .distinct()
                    .reduce((first, second) -> first + ", " + second)
                    .orElseThrow();
            throw new IllegalArgumentException("Multiple Pulsar schema providers are named '" + selectedName
                                                       + "' for channel " + channelName + ": " + providerTypes);
        }
        PulsarSchemaProvider provider = matches.getFirst();
        Schema<?> schema;
        try {
            schema = provider.schema();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Pulsar schema provider '" + selectedName + "' ("
                                                       + provider.getClass().getName() + ") failed for channel "
                                                       + channelName, e);
        }
        if (schema == null) {
            throw new IllegalArgumentException("Pulsar schema provider '" + selectedName + "' ("
                                                       + provider.getClass().getName() + ") returned null for channel "
                                                       + channelName);
        }
        return new ResolvedSchema(schema(schema), null, selectedName, incoming);
    }

    @SuppressWarnings("unchecked")
    private static Schema<Object> schema(Schema<?> schema) {
        return (Schema<Object>) schema;
    }

    record ResolvedSchema(Schema<Object> schema,
                          PulsarSchemaType builtIn,
                          String name,
                          boolean incoming) {
        ResolvedSchema {
            Objects.requireNonNull(schema);
            Objects.requireNonNull(name);
        }

        Object snapshot(Object value) {
            return builtIn == null
                    ? PulsarMessageImpl.snapshotEntity(value)
                    : builtIn.snapshot(value, incoming);
        }
    }
}
