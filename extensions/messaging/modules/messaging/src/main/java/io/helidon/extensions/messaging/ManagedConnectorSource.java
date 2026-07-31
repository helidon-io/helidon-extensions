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

import java.time.Duration;

import io.helidon.common.Api;

/**
 * Internal lifecycle contract for a graph-owned incoming connector binding.
 */
@Api.Internal
public interface ManagedConnectorSource extends ConnectorSource, ManagedConnectorBinding {
    /**
     * Put the source under graph-controlled admission before its task is started.
     * <p>
     * A source run directly through the compatibility {@link ConnectorSource} API remains self-starting.
     */
    void prepareForGraph();

    /**
     * Wait until the source has synchronously established the resources required to run.
     *
     * @param timeout maximum wait
     * @throws RuntimeException if startup failed or timed out
     */
    void awaitReady(Duration timeout);

    /**
     * Allow transport acquisition after every graph binding has reported readiness.
     */
    void startAdmission();

    /**
     * Stop acquiring new transport deliveries while allowing an already acquired delivery to settle.
     */
    void stopAdmission();
}
