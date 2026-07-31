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

package io.helidon.extensions.messaging;

import io.helidon.config.Config;

/**
 * Stateless factory for one connector type.
 * <p>
 * Providers are discovered through the service registry and may be shared by multiple messaging graphs. A provider
 * must not retain endpoint instances or transport resources. Each successful endpoint factory invocation returns a
 * new binding whose lifecycle is owned by its messaging graph.
 *
 * @param <C> connector configuration type
 */
public interface ConnectorProvider<C extends ConnectorConfig> {
    /**
     * Connector type used to select this provider.
     *
     * @return connector type
     */
    String connectorType();

    /**
     * Create typed configuration for one binding.
     * <p>
     * The supplied config is the effective, binding-specific configuration resolved by the messaging runtime. This
     * method may validate and snapshot configuration, but must not acquire transport resources or create threads.
     *
     * @param config effective binding configuration
     * @return typed connector configuration
     */
    C createConfig(Config config);
}
