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
import java.util.Optional;

import io.helidon.builder.api.Option;

/**
 * Outgoing AQ options inherited from connector configuration.
 */
interface AqOutgoingOptions {
    /**
     * JMS delivery mode, either 1 (nonpersistent) or 2 (persistent). Defaults to persistent.
     *
     * @return delivery mode
     */
    @Option.Configured
    Optional<Integer> deliveryMode();

    /**
     * Message priority in the range zero through nine. Defaults to four.
     *
     * @return priority
     */
    @Option.Configured
    Optional<Integer> priority();

    /**
     * Message lifetime. Zero means no expiration and is the default.
     *
     * @return message lifetime
     */
    @Option.Configured
    Optional<Duration> timeToLive();
}
