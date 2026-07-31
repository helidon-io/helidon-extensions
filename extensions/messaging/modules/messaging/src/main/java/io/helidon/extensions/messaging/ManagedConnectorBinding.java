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

import io.helidon.common.Api;

/**
 * Internal lifecycle contract implemented by connector resources owned by one messaging graph.
 * <p>
 * This bridge keeps connector discovery compatible while graph lifecycle and the final endpoint SPI are developed
 * independently. Implementations must make both operations idempotent.
 */
@Api.Internal
public interface ManagedConnectorBinding extends AutoCloseable {
    /**
     * Signal prompt forced shutdown without waiting for normal delivery settlement.
     */
    void forceClose();

    /**
     * Close the binding after delivery work has drained.
     */
    @Override
    void close();
}
