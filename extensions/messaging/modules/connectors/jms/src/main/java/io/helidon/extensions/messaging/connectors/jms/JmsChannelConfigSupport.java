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
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import io.helidon.builder.api.Prototype;

import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.CLIENT_ID_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.CLOSE_TIMEOUT_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.CONNECTION_FACTORY_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.DESTINATION_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.JNDI_CONNECTION_FACTORY_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.JNDI_DESTINATION_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.JNDI_ENVIRONMENT_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.RECONNECT_INITIAL_DELAY_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.RECONNECT_JITTER_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.RECONNECT_MAX_DELAY_PROPERTY;
import static io.helidon.extensions.messaging.connectors.jms.JmsConnectorConfigSupport.USERNAME_PROPERTY;

/**
 * Support for the incoming and outgoing JMS channel configuration blueprints.
 */
final class JmsChannelConfigSupport {
    private JmsChannelConfigSupport() {
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

    static final class Incoming {
        /**
         * Configure a JMS connection password.
         *
         * @param target builder to update
         * @param password password characters
         */
        @Prototype.BuilderMethod
        static void password(JmsIncomingConfig.BuilderBase<?, ?> target, char[] password) {
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
        static void password(JmsIncomingConfig.BuilderBase<?, ?> target, String password) {
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
        static void clearPassword(JmsIncomingConfig.BuilderBase<?, ?> target) {
            clearConfiguredPassword(target);
            target.passwordSource(PasswordSupplier.empty());
        }

        private static void clearConfiguredPassword(JmsIncomingConfig.BuilderBase<?, ?> target) {
            target.clearConfiguredPassword();
        }

        /**
         * Validates JMS connector configuration.
         */
        static final class BuilderDecorator implements Prototype.BuilderDecorator<JmsIncomingConfig.BuilderBase<?, ?>> {
            @Override
            public void decorate(JmsIncomingConfig.BuilderBase<?, ?> target) {
                clearConfiguredPassword(target);
                target.passwordSource(new PasswordSupplier(target.passwordSource().get()));
                requireNonBlank(CONNECTION_FACTORY_PROPERTY, target.connectionFactoryName());
                requireNonBlank(JNDI_CONNECTION_FACTORY_PROPERTY, target.jndiConnectionFactory());
                requireNonBlank(JNDI_DESTINATION_PROPERTY, target.jndiDestination());
                requireNonBlank(DESTINATION_PROPERTY, target.destination());
                requireNonBlank(USERNAME_PROPERTY, target.username());
                requireNonBlank(CLIENT_ID_PROPERTY, target.clientId());
                target.jndiEnvironment().ifPresent(it -> requireNonNullEntries(JNDI_ENVIRONMENT_PROPERTY, it));

                int factoryRoutes = (target.connectionFactory().isPresent() ? 1 : 0)
                        + (target.connectionFactoryName().isPresent() ? 1 : 0)
                        + (target.jndiConnectionFactory().isPresent() ? 1 : 0);
                if (factoryRoutes > 1) {
                    throw new IllegalArgumentException("JMS connection factory, registry name, and JNDI name are mutually exclusive");
                }
                if (target.destination().isPresent() && target.jndiDestination().isPresent()) {
                    throw new IllegalArgumentException(DESTINATION_PROPERTY + " and " + JNDI_DESTINATION_PROPERTY
                                                               + " are mutually exclusive");
                }
                target.closeTimeout().ifPresent(it -> requirePositive(CLOSE_TIMEOUT_PROPERTY, it));
                target.reconnectInitialDelay().ifPresent(it -> requireRetryDelay(RECONNECT_INITIAL_DELAY_PROPERTY, it));
                target.reconnectMaxDelay().ifPresent(it -> requireRetryDelay(RECONNECT_MAX_DELAY_PROPERTY, it));
                if (target.reconnectInitialDelay().isPresent() && target.reconnectMaxDelay().isPresent()
                        && target.reconnectMaxDelay().orElseThrow().compareTo(target.reconnectInitialDelay().orElseThrow()) < 0) {
                    throw new IllegalArgumentException(RECONNECT_MAX_DELAY_PROPERTY + " must not be less than "
                                                               + RECONNECT_INITIAL_DELAY_PROPERTY);
                }
                target.reconnectJitter().ifPresent(it -> {
                    if (!Double.isFinite(it) || it < 0 || it >= 1) {
                        throw new IllegalArgumentException(RECONNECT_JITTER_PROPERTY + " must be in the range [0, 1)");
                    }
                });
            }
        }

        /**
         * Copies a password read from configuration into defensive storage.
         */
        static final class ConfiguredPasswordDecorator
                implements Prototype.OptionDecorator<JmsIncomingConfig.BuilderBase<?, ?>, Optional<String>> {
            @Override
            public void decorate(JmsIncomingConfig.BuilderBase<?, ?> target, Optional<String> configuredPassword) {
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

    }

    static final class Outgoing {
        /**
         * Configure a JMS connection password.
         *
         * @param target builder to update
         * @param password password characters
         */
        @Prototype.BuilderMethod
        static void password(JmsOutgoingConfig.BuilderBase<?, ?> target, char[] password) {
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
        static void password(JmsOutgoingConfig.BuilderBase<?, ?> target, String password) {
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
        static void clearPassword(JmsOutgoingConfig.BuilderBase<?, ?> target) {
            clearConfiguredPassword(target);
            target.passwordSource(PasswordSupplier.empty());
        }

        private static void clearConfiguredPassword(JmsOutgoingConfig.BuilderBase<?, ?> target) {
            target.clearConfiguredPassword();
        }

        /**
         * Validates JMS connector configuration.
         */
        static final class BuilderDecorator implements Prototype.BuilderDecorator<JmsOutgoingConfig.BuilderBase<?, ?>> {
            @Override
            public void decorate(JmsOutgoingConfig.BuilderBase<?, ?> target) {
                clearConfiguredPassword(target);
                target.passwordSource(new PasswordSupplier(target.passwordSource().get()));
                requireNonBlank(CONNECTION_FACTORY_PROPERTY, target.connectionFactoryName());
                requireNonBlank(JNDI_CONNECTION_FACTORY_PROPERTY, target.jndiConnectionFactory());
                requireNonBlank(JNDI_DESTINATION_PROPERTY, target.jndiDestination());
                requireNonBlank(DESTINATION_PROPERTY, target.destination());
                requireNonBlank(USERNAME_PROPERTY, target.username());
                requireNonBlank(CLIENT_ID_PROPERTY, target.clientId());
                target.jndiEnvironment().ifPresent(it -> requireNonNullEntries(JNDI_ENVIRONMENT_PROPERTY, it));

                int factoryRoutes = (target.connectionFactory().isPresent() ? 1 : 0)
                        + (target.connectionFactoryName().isPresent() ? 1 : 0)
                        + (target.jndiConnectionFactory().isPresent() ? 1 : 0);
                if (factoryRoutes > 1) {
                    throw new IllegalArgumentException("JMS connection factory, registry name, and JNDI name are mutually exclusive");
                }
                if (target.destination().isPresent() && target.jndiDestination().isPresent()) {
                    throw new IllegalArgumentException(DESTINATION_PROPERTY + " and " + JNDI_DESTINATION_PROPERTY
                                                               + " are mutually exclusive");
                }
                target.closeTimeout().ifPresent(it -> requirePositive(CLOSE_TIMEOUT_PROPERTY, it));
                target.reconnectInitialDelay().ifPresent(it -> requireRetryDelay(RECONNECT_INITIAL_DELAY_PROPERTY, it));
                target.reconnectMaxDelay().ifPresent(it -> requireRetryDelay(RECONNECT_MAX_DELAY_PROPERTY, it));
                if (target.reconnectInitialDelay().isPresent() && target.reconnectMaxDelay().isPresent()
                        && target.reconnectMaxDelay().orElseThrow().compareTo(target.reconnectInitialDelay().orElseThrow()) < 0) {
                    throw new IllegalArgumentException(RECONNECT_MAX_DELAY_PROPERTY + " must not be less than "
                                                               + RECONNECT_INITIAL_DELAY_PROPERTY);
                }
                target.reconnectJitter().ifPresent(it -> {
                    if (!Double.isFinite(it) || it < 0 || it >= 1) {
                        throw new IllegalArgumentException(RECONNECT_JITTER_PROPERTY + " must be in the range [0, 1)");
                    }
                });
            }
        }

        /**
         * Copies a password read from configuration into defensive storage.
         */
        static final class ConfiguredPasswordDecorator
                implements Prototype.OptionDecorator<JmsOutgoingConfig.BuilderBase<?, ?>, Optional<String>> {
            @Override
            public void decorate(JmsOutgoingConfig.BuilderBase<?, ?> target, Optional<String> configuredPassword) {
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
