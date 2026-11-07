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
import io.helidon.common.Size;

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
     * Maximum size of each incoming bytes-message body or detached SQL BLOB attribute, in bytes.
     * Defaults to 1 MiB. Each value is checked before allocating its snapshot, including BLOBs in
     * nested SQL objects and arrays. This is not an aggregate message-size limit. It does not bound
     * JMS text, XML, map or Java object message bodies, other SQL attributes, or the total size of an ADT payload.
     *
     * @return maximum bytes per bounded binary value
     */
    @Option.Configured
    Optional<Size> maxBodyBytes();

    /**
     * Maximum number of characters in each incoming detached SQL CLOB or NCLOB attribute.
     * Defaults to 1,048,576. Each value is checked before allocating its snapshot, including
     * CLOBs and NCLOBs in nested SQL objects and arrays. This is not a byte or aggregate limit.
     *
     * @return maximum characters per detached CLOB or NCLOB
     */
    @Option.Configured
    Optional<Integer> maxLobCharacters();

    /**
     * Maximum duration of a receive call. Defaults to 100 milliseconds.
     *
     * @return receive timeout
     */
    @Option.Configured
    Optional<Duration> receiveTimeout();
}
