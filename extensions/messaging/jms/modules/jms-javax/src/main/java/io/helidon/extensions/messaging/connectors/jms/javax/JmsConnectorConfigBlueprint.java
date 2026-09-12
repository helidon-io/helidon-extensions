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

package io.helidon.extensions.messaging.connectors.jms.javax;

import java.time.Duration;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;
import io.helidon.messaging.spi.MessagingConnectorProvider;
import io.helidon.messaging.spi.MessagingConnectorProviderConfig;

/**
 * Configuration of a named JMS connector and defaults shared by its channels.
 * Resource lookup and connection creation are deferred until a channel starts.
 */
@Api.Preview
@Prototype.Blueprint(decorator = JmsConnectorConfigSupport.BuilderDecorator.class)
@Prototype.Sealed
@Prototype.CustomMethods(JmsConnectorConfigSupport.class)
@Prototype.Configured(value = JmsConnector.CONNECTOR_TYPE, root = false)
@Prototype.Provides(MessagingConnectorProvider.class)
interface JmsConnectorConfigBlueprint extends MessagingConnectorProviderConfig, JmsChannelOptions,
                                             Prototype.Factory<JmsConnector> {
    /**
     * JMS connection password read from configuration. This value is moved to {@link #passwordSource()} and cleared from
     * the builder before the prototype is created.
     *
     * @return configured password
     */
    @Override
    @Option.Configured(JmsConnectorConfigSupport.PASSWORD_PROPERTY)
    @Option.Confidential
    @Option.Access("")
    @Option.Decorator(JmsConnectorConfigSupport.ConfiguredPasswordDecorator.class)
    @Option.Redundant
    Optional<String> configuredPassword();

    /**
     * Incoming JMS selector.
     *
     * @return selector
     */
    @Option.Configured(JmsConnectorConfigSupport.MESSAGE_SELECTOR_PROPERTY)
    Optional<String> messageSelector();

    /**
     * Whether an incoming topic subscription is durable.
     *
     * @return whether the subscription is durable
     */
    @Option.Configured(JmsConnectorConfigSupport.DURABLE_PROPERTY)
    @Option.DefaultBoolean(false)
    Optional<Boolean> durable();

    /**
     * Durable topic subscription name.
     *
     * @return subscription name
     */
    @Option.Configured(JmsConnectorConfigSupport.SUBSCRIPTION_NAME_PROPERTY)
    Optional<String> subscriptionName();

    /**
     * Whether a topic consumer should suppress messages produced by its own connection.
     *
     * @return whether the subscription is no-local
     */
    @Option.Configured(JmsConnectorConfigSupport.NO_LOCAL_PROPERTY)
    @Option.DefaultBoolean(false)
    Optional<Boolean> noLocal();

    /**
     * Maximum number of bytes retained for one incoming JMS message body.
     * <p>
     * The limit is checked against a {@link javax.jms.BytesMessage} declared body length before allocating its body
     * snapshot. Other JMS body types do not expose a portable encoded byte length.
     *
     * @return maximum incoming bytes-message body size
     */
    @Option.Configured(JmsConnectorConfigSupport.MAX_BODY_BYTES_PROPERTY)
    @Option.DefaultCode("JmsConnectorConfigSupport.DEFAULT_MAX_BODY_BYTES")
    Optional<Integer> maxBodyBytes();

    /**
     * Maximum duration of one incoming synchronous receive call.
     *
     * @return receive timeout
     */
    @Option.Configured(JmsConnectorConfigSupport.RECEIVE_TIMEOUT_PROPERTY)
    @Option.Default(JmsConnectorConfigSupport.DEFAULT_RECEIVE_TIMEOUT)
    Optional<Duration> receiveTimeout();

    /**
     * JMS destination type.
     *
     * @return destination type
     */
    @Override
    @Option.Configured(JmsConnectorConfigSupport.DESTINATION_TYPE_PROPERTY)
    @Option.DefaultCode("JmsDestinationType.QUEUE")
    Optional<JmsDestinationType> destinationType();

    /**
     * Whether the connector uses a local JMS transaction for settlement.
     *
     * @return whether the JMS session is transacted
     */
    @Override
    @Option.Configured(JmsConnectorConfigSupport.TRANSACTED_PROPERTY)
    @Option.DefaultBoolean(false)
    Optional<Boolean> transacted();

    /**
     * Whether Java object messages may be serialized and deserialized.
     * This is disabled by default because deserializing untrusted Java objects is unsafe.
     *
     * @return whether object messages are allowed
     */
    @Override
    @Option.Configured(JmsConnectorConfigSupport.ALLOW_OBJECT_MESSAGES_PROPERTY)
    @Option.DefaultBoolean(false)
    Optional<Boolean> allowObjectMessages();

    /**
     * Maximum duration for connector-owned graceful JMS resource cleanup.
     *
     * @return close timeout
     */
    @Override
    @Option.Configured(JmsConnectorConfigSupport.CLOSE_TIMEOUT_PROPERTY)
    @Option.Default(JmsConnectorConfigSupport.DEFAULT_CLOSE_TIMEOUT)
    Optional<Duration> closeTimeout();

    /**
     * Initial delay between reconnection attempts, which must be at least 1 millisecond.
     *
     * @return initial reconnect delay
     */
    @Override
    @Option.Configured(JmsConnectorConfigSupport.RECONNECT_INITIAL_DELAY_PROPERTY)
    @Option.Default(JmsConnectorConfigSupport.DEFAULT_RECONNECT_INITIAL_DELAY)
    Optional<Duration> reconnectInitialDelay();

    /**
     * Maximum delay between reconnection attempts, which must be at least 1 millisecond.
     *
     * @return maximum reconnect delay
     */
    @Override
    @Option.Configured(JmsConnectorConfigSupport.RECONNECT_MAX_DELAY_PROPERTY)
    @Option.Default(JmsConnectorConfigSupport.DEFAULT_RECONNECT_MAX_DELAY)
    Optional<Duration> reconnectMaxDelay();

    /**
     * Fractional random variation applied to a reconnect delay, in the range {@code [0, 1)}.
     *
     * @return reconnect jitter fraction
     */
    @Override
    @Option.Configured(JmsConnectorConfigSupport.RECONNECT_JITTER_PROPERTY)
    @Option.DefaultDouble(0.2)
    Optional<Double> reconnectJitter();
}
