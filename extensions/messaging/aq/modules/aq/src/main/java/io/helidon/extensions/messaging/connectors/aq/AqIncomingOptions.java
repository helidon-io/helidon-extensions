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
 * Incoming AQ options inherited from connector configuration.
 */
interface AqIncomingOptions {
    /**
     * Selector used when dequeuing messages.
     *
     * @return message selector
     */
    @Option.Configured
    Optional<String> messageSelector();

    /**
     * Use a durable topic subscription. Defaults to false.
     * Explicitly disabling durability on a channel also suppresses an inherited subscription name.
     *
     * @return whether the subscription is durable
     */
    @Option.Configured
    Optional<Boolean> durable();

    /**
     * Durable subscription name.
     *
     * @return subscription name
     */
    @Option.Configured
    Optional<String> subscriptionName();

    /**
     * Recipient name for explicit consumption from a multi-consumer queue.
     *
     * @return recipient name
     */
    @Option.Configured
    Optional<String> recipientName();

    /**
     * Suppress messages sent by the same connection. Defaults to false.
     *
     * @return whether local messages are suppressed
     */
    @Option.Configured
    Optional<Boolean> noLocal();

    /**
     * Maximum bytes-message body size, checked before allocating its snapshot.
     * Defaults to 1 MiB. Other payload types do not expose a portable encoded size.
     *
     * @return maximum bytes-message body size
     */
    @Option.Configured
    Optional<Integer> maxBodyBytes();

    /**
     * Maximum duration of a receive call. Defaults to 100 milliseconds.
     *
     * @return receive timeout
     */
    @Option.Configured
    Optional<Duration> receiveTimeout();
}
