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
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import io.helidon.builder.api.Prototype;

import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.CLIENT_ID_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.CLOSE_TIMEOUT_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.CONNECTION_FACTORY_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.DESTINATION_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.DURABLE_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.JNDI_CONNECTION_FACTORY_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.JNDI_DESTINATION_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.JNDI_ENVIRONMENT_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.MAX_BODY_BYTES_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.MESSAGE_SELECTOR_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.NO_LOCAL_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.PASSWORD_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.RECEIVE_TIMEOUT_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.RECONNECT_INITIAL_DELAY_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.RECONNECT_JITTER_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.RECONNECT_MAX_DELAY_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.SUBSCRIPTION_NAME_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.javax.JmsConnectorConfigSupport.USERNAME_PROPERTY;

/**
 * Support methods for {@link JmsRuntimeConfig}.
 */
final class JmsRuntimeConfigSupport {
    private JmsRuntimeConfigSupport() {
    }

    /**
     * Configure a JMS connection password.
     *
     * @param target builder to update
     * @param password password characters
     */
    @Prototype.BuilderMethod
    static void password(JmsRuntimeConfig.BuilderBase<?, ?> target, char[] password) {
        PasswordSupplier passwordSource = new PasswordSupplier(Objects.requireNonNull(password));
        clearConfiguredPassword(target);
        target.passwordSource(passwordSource);
    }

    /**
     * Configure a JMS connection password.
     *
     * @param target builder to update
     * @param password password
     */
    @Prototype.BuilderMethod
    static void password(JmsRuntimeConfig.BuilderBase<?, ?> target, String password) {
        char[] passwordChars = Objects.requireNonNull(password).toCharArray();
        try {
            password(target, passwordChars);
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    /**
     * Clear the configured JMS connection password.
     *
     * @param target builder to update
     */
    @Prototype.BuilderMethod
    static void clearPassword(JmsRuntimeConfig.BuilderBase<?, ?> target) {
        clearConfiguredPassword(target);
        target.passwordSource(PasswordSupplier.empty());
    }

    private static void clearConfiguredPassword(JmsRuntimeConfig.BuilderBase<?, ?> target) {
        target.clearConfiguredPassword();
    }

    private static void requirePositive(String name, Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    private static void requireRetryDelay(String name, Duration duration) {
        if (duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(name + " must be at least 1 millisecond");
        }
    }

    private static void requireNonBlank(String name, Optional<String> value) {
        value.ifPresent(it -> {
            if (it.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        });
    }

    private static void requireNonNullEntries(String name, Map<?, ?> values) {
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, name + " key");
            Objects.requireNonNull(value, name + " value");
        });
    }

    /**
     * Validates JMS connector configuration.
     */
    static final class BuilderDecorator implements Prototype.BuilderDecorator<JmsRuntimeConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(JmsRuntimeConfig.BuilderBase<?, ?> target) {
            clearConfiguredPassword(target);
            target.passwordSource(new PasswordSupplier(target.passwordSource().get()));
            requireNonBlank(CONNECTION_FACTORY_PROPERTY, target.connectionFactory());
            requireNonBlank(JNDI_CONNECTION_FACTORY_PROPERTY, target.jndiConnectionFactory());
            requireNonBlank(JNDI_DESTINATION_PROPERTY, target.jndiDestination());
            requireNonBlank(DESTINATION_PROPERTY, target.destination());
            requireNonBlank(USERNAME_PROPERTY, target.username());
            requireNonBlank(CLIENT_ID_PROPERTY, target.clientId());
            requireNonBlank(MESSAGE_SELECTOR_PROPERTY, target.messageSelector());
            requireNonBlank(SUBSCRIPTION_NAME_PROPERTY, target.subscriptionName());
            requireNonNullEntries(JNDI_ENVIRONMENT_PROPERTY, target.jndiEnvironment());

            if (target.connectionFactory().isPresent() && target.jndiConnectionFactory().isPresent()) {
                throw new IllegalArgumentException(CONNECTION_FACTORY_PROPERTY + " and "
                                                           + JNDI_CONNECTION_FACTORY_PROPERTY
                                                           + " are mutually exclusive");
            }
            if (target.destination().isEmpty() && target.jndiDestination().isEmpty()) {
                throw new IllegalArgumentException("Either " + DESTINATION_PROPERTY + " or "
                                                           + JNDI_DESTINATION_PROPERTY + " must be configured");
            }
            if (target.destination().isPresent() && target.jndiDestination().isPresent()) {
                throw new IllegalArgumentException(DESTINATION_PROPERTY + " and " + JNDI_DESTINATION_PROPERTY
                                                           + " are mutually exclusive");
            }
            if (target.username().isPresent() != target.passwordSource().get().isPresent()) {
                throw new IllegalArgumentException(USERNAME_PROPERTY + " and " + PASSWORD_PROPERTY
                                                           + " must be configured together");
            }
            if (target.durable()) {
                if (target.destinationType() != JmsDestinationType.TOPIC) {
                    throw new IllegalArgumentException(DURABLE_PROPERTY + " requires destination-type TOPIC");
                }
                if (target.subscriptionName().isEmpty()) {
                    throw new IllegalArgumentException(DURABLE_PROPERTY + " requires " + SUBSCRIPTION_NAME_PROPERTY);
                }
            } else if (target.subscriptionName().isPresent()) {
                throw new IllegalArgumentException(SUBSCRIPTION_NAME_PROPERTY + " requires " + DURABLE_PROPERTY);
            }
            if (target.noLocal() && target.destinationType() != JmsDestinationType.TOPIC) {
                throw new IllegalArgumentException(NO_LOCAL_PROPERTY + " requires destination-type TOPIC");
            }
            if (target.maxBodyBytes() < 1) {
                throw new IllegalArgumentException(MAX_BODY_BYTES_PROPERTY + " must be greater than zero");
            }

            requirePositive(RECEIVE_TIMEOUT_PROPERTY, target.receiveTimeout());
            requirePositive(CLOSE_TIMEOUT_PROPERTY, target.closeTimeout());
            requireRetryDelay(RECONNECT_INITIAL_DELAY_PROPERTY, target.reconnectInitialDelay());
            requireRetryDelay(RECONNECT_MAX_DELAY_PROPERTY, target.reconnectMaxDelay());
            if (target.reconnectMaxDelay().compareTo(target.reconnectInitialDelay()) < 0) {
                throw new IllegalArgumentException(RECONNECT_MAX_DELAY_PROPERTY + " must not be less than "
                                                           + RECONNECT_INITIAL_DELAY_PROPERTY);
            }
            if (!Double.isFinite(target.reconnectJitter())
                    || target.reconnectJitter() < 0
                    || target.reconnectJitter() >= 1) {
                throw new IllegalArgumentException(RECONNECT_JITTER_PROPERTY + " must be in the range [0, 1)");
            }
        }
    }

    /**
     * Copies a password read from configuration into defensive storage.
     */
    static final class ConfiguredPasswordDecorator
            implements Prototype.OptionDecorator<JmsRuntimeConfig.BuilderBase<?, ?>, Optional<String>> {
        @Override
        public void decorate(JmsRuntimeConfig.BuilderBase<?, ?> target, Optional<String> configuredPassword) {
            configuredPassword.ifPresent(it -> {
                char[] password = it.toCharArray();
                try {
                    target.passwordSource(new PasswordSupplier(password));
                } finally {
                    Arrays.fill(password, '\0');
                }
            });
        }
    }

    private static final class PasswordSupplier implements Supplier<Optional<char[]>> {
        private final char[] password;

        private PasswordSupplier(char[] password) {
            this.password = password.clone();
        }

        private PasswordSupplier(Optional<char[]> password) {
            this.password = password.map(char[]::clone).orElse(null);
        }

        @Override
        public Optional<char[]> get() {
            return Optional.ofNullable(password).map(char[]::clone);
        }

        private static PasswordSupplier empty() {
            return new PasswordSupplier(Optional.empty());
        }
    }
}
