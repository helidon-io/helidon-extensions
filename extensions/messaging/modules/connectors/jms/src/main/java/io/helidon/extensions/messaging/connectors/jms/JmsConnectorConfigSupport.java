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

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import io.helidon.builder.api.Prototype;
import io.helidon.messaging.ConnectorConfig;

/**
 * Support methods and constants for {@link JmsConnectorConfig}.
 */
final class JmsConnectorConfigSupport {
    @Prototype.Constant
    static final String CONNECTION_FACTORY_PROPERTY = "connection-factory";
    @Prototype.Constant
    static final String JNDI_CONNECTION_FACTORY_PROPERTY = "jndi.connection-factory";
    @Prototype.Constant
    static final String JNDI_DESTINATION_PROPERTY = "jndi.destination";
    @Prototype.Constant
    static final String JNDI_ENVIRONMENT_PROPERTY = "jndi.environment";
    @Prototype.Constant
    static final String DESTINATION_PROPERTY = "destination";
    @Prototype.Constant
    static final String DESTINATION_TYPE_PROPERTY = "destination-type";
    @Prototype.Constant
    static final String USERNAME_PROPERTY = "username";
    @Prototype.Constant
    static final String PASSWORD_PROPERTY = "password";
    @Prototype.Constant
    static final String CLIENT_ID_PROPERTY = "client-id";
    @Prototype.Constant
    static final String MESSAGE_SELECTOR_PROPERTY = "message-selector";
    @Prototype.Constant
    static final String DURABLE_PROPERTY = "durable";
    @Prototype.Constant
    static final String SUBSCRIPTION_NAME_PROPERTY = "subscription-name";
    @Prototype.Constant
    static final String NO_LOCAL_PROPERTY = "no-local";
    @Prototype.Constant
    static final String TRANSACTED_PROPERTY = "transacted";
    @Prototype.Constant
    static final String ALLOW_OBJECT_MESSAGES_PROPERTY = "allow-object-messages";
    @Prototype.Constant
    static final String MAX_BODY_BYTES_PROPERTY = "max-body-bytes";
    @Prototype.Constant
    static final String RECEIVE_TIMEOUT_PROPERTY = "receive-timeout";
    @Prototype.Constant
    static final String CLOSE_TIMEOUT_PROPERTY = "close-timeout";
    @Prototype.Constant
    static final String RECONNECT_INITIAL_DELAY_PROPERTY = "reconnect.initial-delay";
    @Prototype.Constant
    static final String RECONNECT_MAX_DELAY_PROPERTY = "reconnect.max-delay";
    @Prototype.Constant
    static final String RECONNECT_JITTER_PROPERTY = "reconnect.jitter";

    @Prototype.Constant
    static final String DEFAULT_RECEIVE_TIMEOUT = "PT0.1S";
    @Prototype.Constant
    static final String DEFAULT_CLOSE_TIMEOUT = "PT10S";
    @Prototype.Constant
    static final String DEFAULT_RECONNECT_INITIAL_DELAY = "PT0.1S";
    @Prototype.Constant
    static final String DEFAULT_RECONNECT_MAX_DELAY = "PT30S";
    @Prototype.Constant
    static final int DEFAULT_MAX_BODY_BYTES = 1_048_576;

    private JmsConnectorConfigSupport() {
    }

    /**
     * Configure a JMS connection password.
     *
     * @param target builder to update
     * @param password password characters
     */
    @Prototype.BuilderMethod
    static void password(JmsConnectorConfig.BuilderBase<?, ?> target, char[] password) {
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
    static void password(JmsConnectorConfig.BuilderBase<?, ?> target, String password) {
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
    static void clearPassword(JmsConnectorConfig.BuilderBase<?, ?> target) {
        clearConfiguredPassword(target);
        target.passwordSource(PasswordSupplier.empty());
    }

    private static void clearConfiguredPassword(JmsConnectorConfig.BuilderBase<?, ?> target) {
        target.clearConfiguredPassword();
    }

    private static void requirePositive(String name, java.time.Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    private static void requireNonBlank(String name, java.util.Optional<String> value) {
        value.ifPresent(it -> {
            if (it.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        });
    }

    /**
     * Validates JMS connector configuration.
     */
    static final class BuilderDecorator implements Prototype.BuilderDecorator<JmsConnectorConfig.BuilderBase<?, ?>> {
        @Override
        public void decorate(JmsConnectorConfig.BuilderBase<?, ?> target) {
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
            if (target.direction().orElse(null) == ConnectorConfig.Direction.OUTGOING
                    && (target.messageSelector().isPresent()
                    || target.durable()
                    || target.subscriptionName().isPresent()
                    || target.noLocal())) {
                throw new IllegalArgumentException("Incoming subscription options are not valid for an outgoing connector");
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
            requirePositive(RECONNECT_INITIAL_DELAY_PROPERTY, target.reconnectInitialDelay());
            requirePositive(RECONNECT_MAX_DELAY_PROPERTY, target.reconnectMaxDelay());
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
            implements Prototype.OptionDecorator<JmsConnectorConfig.BuilderBase<?, ?>, Optional<String>> {
        @Override
        public void decorate(JmsConnectorConfig.BuilderBase<?, ?> target, Optional<String> configuredPassword) {
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

        private static PasswordSupplier empty() {
            return new PasswordSupplier(Optional.empty());
        }

        @Override
        public Optional<char[]> get() {
            return Optional.ofNullable(password).map(char[]::clone);
        }
    }
}
