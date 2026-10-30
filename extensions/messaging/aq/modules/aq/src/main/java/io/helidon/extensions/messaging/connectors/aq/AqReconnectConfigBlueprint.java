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

import java.time.Duration;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;

/**
 * Connection recovery policy. Recovery replaces provider resources without replaying failed sends.
 */
@Api.Incubating
@Prototype.Blueprint(decorator = AqReconnectSupport.BuilderDecorator.class)
@Prototype.Sealed
@Prototype.Configured(root = false)
interface AqReconnectConfigBlueprint {
    /**
     * Reconnect established channels after recoverable provider connection failures.
     *
     * @return whether automatic recovery is enabled
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean enabled();

    /**
     * Initial recovery delay, doubled after each failed attempt.
     *
     * @return initial delay
     */
    @Option.Configured
    @Option.Default("PT0.1S")
    Duration initialDelay();

    /**
     * Maximum recovery delay, including jitter.
     *
     * @return maximum delay
     */
    @Option.Configured
    @Option.Default("PT30S")
    Duration maxDelay();

    /**
     * Fraction by which each recovery delay may vary, between zero and one.
     *
     * @return random delay variation
     */
    @Option.Configured
    @Option.DefaultDouble(0.2)
    double jitter();

    /**
     * Maximum wait for one initial or recovery connection attempt.
     * A late provider result is closed; another attempt waits for its cleanup.
     *
     * @return connection attempt timeout
     */
    @Option.Configured
    @Option.Default("PT10S")
    Duration connectTimeout();

    /**
     * Maximum wait for an outgoing operation to obtain a recovered connection.
     *
     * @return outgoing recovery wait timeout
     */
    @Option.Configured
    @Option.Default("PT30S")
    Duration waitTimeout();
}
