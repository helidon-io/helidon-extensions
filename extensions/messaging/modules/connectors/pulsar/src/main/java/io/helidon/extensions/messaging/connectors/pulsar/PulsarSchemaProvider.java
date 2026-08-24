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

import io.helidon.service.registry.Service;

import org.apache.pulsar.client.api.Schema;

/**
 * Named provider of a custom Pulsar schema.
 * <p>
 * Applications can register implementations in the Helidon Service Registry and select one with the connector
 * {@code schema-provider} option. Provider names are exact and case-sensitive. The connector invokes {@link #schema()}
 * once for each binding which selects the provider. The returned instance must be safe for that binding; a provider may
 * share an instance only when the schema implementation supports sharing across bindings and topics.
 * <p>
 * The Service Registry owns the provider lifecycle. The connector retains the returned schema for the binding lifecycle
 * but does not close the provider or schema.
 */
@Service.Contract
public interface PulsarSchemaProvider {
    /**
     * Provider name used in connector configuration.
     *
     * @return nonblank provider name
     */
    String name();

    /**
     * Schema for one connector binding. The connector does not close or clone the returned schema.
     *
     * @return Pulsar schema
     */
    Schema<?> schema();
}
