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
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.messaging.ConnectorConfig;

/**
 * JMS connector configuration.
 */
@Prototype.Blueprint(decorator = JmsConnectorConfigSupport.BuilderDecorator.class)
@Prototype.Configured
@Prototype.CustomMethods(JmsConnectorConfigSupport.class)
interface JmsConnectorConfigBlueprint extends ConnectorConfig {
    /**
     * Name of a Jakarta JMS {@code ConnectionFactory} in the Helidon Service Registry.
     * When absent, the first registered factory is used unless JNDI is configured.
     *
     * @return named connection factory
     */
    @Option.Configured(JmsConnectorConfigSupport.CONNECTION_FACTORY_PROPERTY)
    Optional<String> connectionFactory();

    /**
     * JNDI name of the JMS connection factory.
     *
     * @return JNDI connection factory name
     */
    @Option.Configured(JmsConnectorConfigSupport.JNDI_CONNECTION_FACTORY_PROPERTY)
    Optional<String> jndiConnectionFactory();

    /**
     * JNDI name of the destination. When absent, {@link #destination()} is created through the JMS session.
     *
     * @return JNDI destination name
     */
    @Option.Configured(JmsConnectorConfigSupport.JNDI_DESTINATION_PROPERTY)
    Optional<String> jndiDestination();

    /**
     * JNDI initial-context environment.
     *
     * @return JNDI environment
     */
    @Option.Configured(JmsConnectorConfigSupport.JNDI_ENVIRONMENT_PROPERTY)
    @Option.Confidential
    @Option.Singular("jndiEnvironmentProperty")
    Map<String, String> jndiEnvironment();

    /**
     * JMS destination name used to create a queue or topic through the session.
     *
     * @return destination name
     */
    @Option.Configured(JmsConnectorConfigSupport.DESTINATION_PROPERTY)
    Optional<String> destination();

    /**
     * JMS destination type.
     *
     * @return destination type
     */
    @Option.Configured(JmsConnectorConfigSupport.DESTINATION_TYPE_PROPERTY)
    @Option.DefaultCode("JmsDestinationType.QUEUE")
    JmsDestinationType destinationType();

    /**
     * JMS connection user name.
     *
     * @return connection user name
     */
    @Option.Configured(JmsConnectorConfigSupport.USERNAME_PROPERTY)
    Optional<String> username();

    /**
     * JMS connection password read from configuration. This value is moved to {@link #passwordSource()} and cleared from
     * the builder before the prototype is created.
     *
     * @return configured password
     */
    @Option.Configured(JmsConnectorConfigSupport.PASSWORD_PROPERTY)
    @Option.Confidential
    @Option.Access("")
    @Option.Decorator(JmsConnectorConfigSupport.ConfiguredPasswordDecorator.class)
    @Option.Redundant
    Optional<String> configuredPassword();

    /**
     * Internal source of defensive password copies.
     *
     * @return configured password source
     */
    @Option.Confidential
    @Option.Access("")
    @Option.DefaultCode("java.util.Optional.empty()")
    @Option.Redundant(equality = true, stringValue = false)
    Supplier<Optional<char[]>> passwordSource();

    /**
     * JMS connection password. Each invocation returns a defensive copy. Implementations must avoid retaining a
     * {@link String} representation beyond the {@code ConnectionFactory.createConnection} call.
     *
     * @return configured password characters
     */
    default Optional<char[]> password() {
        return passwordSource().get().map(char[]::clone);
    }

    /**
     * JMS connection client identifier assigned by the application. Omit this option when the connection factory
     * supplies an administratively configured client identifier.
     *
     * @return client identifier
     */
    @Option.Configured(JmsConnectorConfigSupport.CLIENT_ID_PROPERTY)
    Optional<String> clientId();

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
    boolean durable();

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
    boolean noLocal();

    /**
     * Whether the connector uses a local JMS transaction for settlement.
     *
     * @return whether the JMS session is transacted
     */
    @Option.Configured(JmsConnectorConfigSupport.TRANSACTED_PROPERTY)
    @Option.DefaultBoolean(false)
    boolean transacted();

    /**
     * Whether Java object messages may be serialized and deserialized.
     * This is disabled by default because deserializing untrusted Java objects is unsafe.
     *
     * @return whether object messages are allowed
     */
    @Option.Configured(JmsConnectorConfigSupport.ALLOW_OBJECT_MESSAGES_PROPERTY)
    @Option.DefaultBoolean(false)
    boolean allowObjectMessages();

    /**
     * Maximum number of bytes retained for one incoming JMS message body.
     * <p>
     * The limit is checked against a {@link jakarta.jms.BytesMessage} declared body length before allocating its body
     * snapshot. Other JMS body types do not expose a portable encoded byte length.
     *
     * @return maximum incoming bytes-message body size
     */
    @Option.Configured(JmsConnectorConfigSupport.MAX_BODY_BYTES_PROPERTY)
    @Option.DefaultCode("JmsConnectorConfigSupport.DEFAULT_MAX_BODY_BYTES")
    int maxBodyBytes();

    /**
     * Maximum duration of one incoming synchronous receive call.
     *
     * @return receive timeout
     */
    @Option.Configured(JmsConnectorConfigSupport.RECEIVE_TIMEOUT_PROPERTY)
    @Option.Default(JmsConnectorConfigSupport.DEFAULT_RECEIVE_TIMEOUT)
    Duration receiveTimeout();

    /**
     * Maximum duration for connector-owned graceful JMS resource cleanup.
     *
     * @return close timeout
     */
    @Option.Configured(JmsConnectorConfigSupport.CLOSE_TIMEOUT_PROPERTY)
    @Option.Default(JmsConnectorConfigSupport.DEFAULT_CLOSE_TIMEOUT)
    Duration closeTimeout();

    /**
     * Initial delay between reconnection attempts.
     *
     * @return initial reconnect delay
     */
    @Option.Configured(JmsConnectorConfigSupport.RECONNECT_INITIAL_DELAY_PROPERTY)
    @Option.Default(JmsConnectorConfigSupport.DEFAULT_RECONNECT_INITIAL_DELAY)
    Duration reconnectInitialDelay();

    /**
     * Maximum delay between reconnection attempts.
     *
     * @return maximum reconnect delay
     */
    @Option.Configured(JmsConnectorConfigSupport.RECONNECT_MAX_DELAY_PROPERTY)
    @Option.Default(JmsConnectorConfigSupport.DEFAULT_RECONNECT_MAX_DELAY)
    Duration reconnectMaxDelay();

    /**
     * Fractional random variation applied to a reconnect delay, in the range {@code [0, 1)}.
     *
     * @return reconnect jitter fraction
     */
    @Option.Configured(JmsConnectorConfigSupport.RECONNECT_JITTER_PROPERTY)
    @Option.DefaultDouble(0.2)
    double reconnectJitter();
}
