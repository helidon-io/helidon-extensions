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

import jakarta.jms.ConnectionFactory;

/**
 * Shared JMS channel options. Absent channel options inherit the connector's defaults.
 */
interface JmsChannelOptions {
    /**
     * Connection factory supplied programmatically. Registry and JNDI factory lookup are used when absent.
     *
     * @return connection factory
     */
    Optional<ConnectionFactory> connectionFactory();

    /**
     * Name of a Jakarta JMS {@code ConnectionFactory} in the Helidon Service Registry.
     * When absent, the first registered factory is used unless JNDI is configured.
     *
     * @return named connection factory
     */
    @Option.Configured(JmsRuntimeConfigSupport.CONNECTION_FACTORY_PROPERTY)
    Optional<String> connectionFactoryName();

    /**
     * JNDI name of the JMS connection factory.
     *
     * @return JNDI connection factory name
     */
    @Option.Configured(JmsRuntimeConfigSupport.JNDI_CONNECTION_FACTORY_PROPERTY)
    Optional<String> jndiConnectionFactory();

    /**
     * JNDI name of the destination. When absent, {@link #destination()} is created through the JMS session.
     *
     * @return JNDI destination name
     */
    @Option.Configured(JmsRuntimeConfigSupport.JNDI_DESTINATION_PROPERTY)
    Optional<String> jndiDestination();

    /**
     * JNDI initial-context environment.
     *
     * @return JNDI environment
     */
    @Option.Configured(JmsRuntimeConfigSupport.JNDI_ENVIRONMENT_PROPERTY)
    @Option.Confidential
    @Option.Singular("jndiEnvironmentProperty")
    Map<String, String> jndiEnvironment();

    /**
     * JMS destination name used to create a queue or topic through the session.
     *
     * @return destination name
     */
    @Option.Configured(JmsRuntimeConfigSupport.DESTINATION_PROPERTY)
    Optional<String> destination();

    /**
     * JMS destination type.
     *
     * @return destination type
     */
    @Option.Configured(JmsRuntimeConfigSupport.DESTINATION_TYPE_PROPERTY)
    Optional<JmsDestinationType> destinationType();

    /**
     * JMS connection user name.
     *
     * @return connection user name
     */
    @Option.Configured(JmsRuntimeConfigSupport.USERNAME_PROPERTY)
    Optional<String> username();

    /**
     * JMS connection password read from configuration. This value is moved to {@link #passwordSource()} and cleared from
     * the builder before the prototype is created.
     *
     * @return configured password
     */
    @Option.Configured(JmsRuntimeConfigSupport.PASSWORD_PROPERTY)
    @Option.Confidential
    @Option.Access("")
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
    @Option.Configured(JmsRuntimeConfigSupport.CLIENT_ID_PROPERTY)
    Optional<String> clientId();

    /**
     * Whether the connector uses a local JMS transaction for settlement.
     *
     * @return whether the JMS session is transacted
     */
    @Option.Configured(JmsRuntimeConfigSupport.TRANSACTED_PROPERTY)
    Optional<Boolean> transacted();

    /**
     * Whether Java object messages may be serialized and deserialized.
     * This is disabled by default because deserializing untrusted Java objects is unsafe.
     *
     * @return whether object messages are allowed
     */
    @Option.Configured(JmsRuntimeConfigSupport.ALLOW_OBJECT_MESSAGES_PROPERTY)
    Optional<Boolean> allowObjectMessages();

    /**
     * Maximum duration for connector-owned graceful JMS resource cleanup.
     *
     * @return close timeout
     */
    @Option.Configured(JmsRuntimeConfigSupport.CLOSE_TIMEOUT_PROPERTY)
    Optional<Duration> closeTimeout();

    /**
     * Initial delay between reconnection attempts, which must be at least 1 millisecond.
     *
     * @return initial reconnect delay
     */
    @Option.Configured(JmsRuntimeConfigSupport.RECONNECT_INITIAL_DELAY_PROPERTY)
    Optional<Duration> reconnectInitialDelay();

    /**
     * Maximum delay between reconnection attempts, which must be at least 1 millisecond.
     *
     * @return maximum reconnect delay
     */
    @Option.Configured(JmsRuntimeConfigSupport.RECONNECT_MAX_DELAY_PROPERTY)
    Optional<Duration> reconnectMaxDelay();

    /**
     * Fractional random variation applied to a reconnect delay, in the range {@code [0, 1)}.
     *
     * @return reconnect jitter fraction
     */
    @Option.Configured(JmsRuntimeConfigSupport.RECONNECT_JITTER_PROPERTY)
    Optional<Double> reconnectJitter();
}
