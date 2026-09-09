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

package io.helidon.extensions.messaging.connectors.jms;

import java.time.Duration;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;

/**
 * Incoming JMS channel configuration. Absent options inherit the connector's defaults.
 */
@Api.Preview
@Prototype.Blueprint
@Prototype.Configured
interface JmsIncomingConfigBlueprint extends JmsChannelConfigBlueprint {
    /**
     * Logical messaging channel name.
     *
     * @return channel name
     */
    @Option.Configured
    String channelName();

    /**
     * Incoming JMS selector.
     *
     * @return selector
     */
    @Option.Configured(JmsRuntimeConfigSupport.MESSAGE_SELECTOR_PROPERTY)
    Optional<String> messageSelector();

    /**
     * Whether an incoming topic subscription is durable.
     *
     * @return whether the subscription is durable
     */
    @Option.Configured(JmsRuntimeConfigSupport.DURABLE_PROPERTY)
    Optional<Boolean> durable();

    /**
     * Durable topic subscription name.
     *
     * @return subscription name
     */
    @Option.Configured(JmsRuntimeConfigSupport.SUBSCRIPTION_NAME_PROPERTY)
    Optional<String> subscriptionName();

    /**
     * Whether a topic consumer should suppress messages produced by its own connection.
     *
     * @return whether the subscription is no-local
     */
    @Option.Configured(JmsRuntimeConfigSupport.NO_LOCAL_PROPERTY)
    Optional<Boolean> noLocal();

    /**
     * Maximum number of bytes retained for one incoming JMS message body.
     * <p>
     * The limit is checked against a {@link jakarta.jms.BytesMessage} declared body length before allocating its body
     * snapshot. Other JMS body types do not expose a portable encoded byte length.
     *
     * @return maximum incoming bytes-message body size
     */
    @Option.Configured(JmsRuntimeConfigSupport.MAX_BODY_BYTES_PROPERTY)
    Optional<Integer> maxBodyBytes();

    /**
     * Maximum duration of one incoming synchronous receive call.
     *
     * @return receive timeout
     */
    @Option.Configured(JmsRuntimeConfigSupport.RECEIVE_TIMEOUT_PROPERTY)
    Optional<Duration> receiveTimeout();

}
