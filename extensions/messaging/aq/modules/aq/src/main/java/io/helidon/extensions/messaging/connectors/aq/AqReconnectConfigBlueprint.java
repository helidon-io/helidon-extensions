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
import io.helidon.faulttolerance.Retry;

/**
 * Connection recovery policy. Recovery replaces provider resources without replaying failed sends.
 */
@Api.Incubating
@Prototype.Blueprint(decorator = AqReconnectSupport.BuilderDecorator.class)
@Prototype.Sealed
@Prototype.Configured
@Prototype.CustomMethods(AqReconnectSupport.class)
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
     * Fault tolerance retry for connection recovery. The first recovery attempt starts immediately after cleanup.
     * By default, recovery allows {@link Integer#MAX_VALUE} attempts over 365 days, with a 100 millisecond delay,
     * a delay factor of two, twenty percent jitter, and a maximum delay of 30 seconds.
     * Exhausting the attempts or overall timeout fails the channel. Failed sends are never replayed.
     * Configuration-file values override these AQ defaults. Supplying a retry or using the retry builder consumer
     * replaces the policy; the consumer starts with standard fault tolerance defaults of three calls and a
     * one-second overall timeout. Set the desired recovery limits explicitly when using that builder, or initialize
     * it from this retry's {@link io.helidon.faulttolerance.Retry#prototype()} before changing individual settings.
     *
     * @return recovery retry
     */
    @Option.Configured(metadata = false)
    @Prototype.Annotated("io.helidon.config.metadata.ConfiguredOption(key = \"retry\", "
            + "type = class::io.helidon.faulttolerance.Retry)")
    @Option.DefaultMethod(type = AqReconnectSupport.class, value = "defaultRetry")
    Retry retry();

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
