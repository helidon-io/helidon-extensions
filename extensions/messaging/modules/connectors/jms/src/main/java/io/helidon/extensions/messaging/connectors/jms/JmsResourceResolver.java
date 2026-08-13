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

import java.util.Hashtable;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import io.helidon.extensions.messaging.MessagingException;
import io.helidon.service.registry.ServiceRegistry;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.Topic;

final class JmsResourceResolver implements JmsConnectionFactoryResolver {
    private final ServiceRegistry registry;

    JmsResourceResolver(ServiceRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry);
    }

    @Override
    public ConnectionFactory resolve(JmsConnectorConfig config) {
        if (config.jndiConnectionFactory().isPresent()) {
            return lookup(config, config.jndiConnectionFactory().orElseThrow(), ConnectionFactory.class);
        }
        if (config.connectionFactory().isPresent()) {
            String name = config.connectionFactory().orElseThrow();
            return registry.firstNamed(ConnectionFactory.class, name)
                    .orElseThrow(() -> new MessagingException("No JMS ConnectionFactory named " + name
                                                                      + " is registered for channel "
                                                                      + config.channel()));
        }
        return registry.first(ConnectionFactory.class)
                .orElseThrow(() -> new MessagingException("No JMS ConnectionFactory is registered for channel "
                                                                  + config.channel()));
    }

    static Destination resolveDestination(Session session, JmsConnectorConfig config) throws JMSException {
        if (config.jndiDestination().isPresent()) {
            return validateDestinationType(lookup(config,
                                                  config.jndiDestination().orElseThrow(),
                                                  Destination.class),
                                           config);
        }
        String name = config.destination().orElseThrow();
        return switch (config.destinationType()) {
        case QUEUE -> session.createQueue(name);
        case TOPIC -> session.createTopic(name);
        };
    }

    static Destination validateDestinationType(Destination destination, JmsConnectorConfig config) {
        boolean expectedType = switch (config.destinationType()) {
        case QUEUE -> destination instanceof Queue;
        case TOPIC -> destination instanceof Topic;
        };
        if (!expectedType) {
            throw new MessagingException("JMS destination for channel " + config.channel()
                                                 + " does not match destination-type " + config.destinationType());
        }
        return destination;
    }

    private static <T> T lookup(JmsConnectorConfig config, String name, Class<T> type) {
        Hashtable<String, String> environment = new Hashtable<>(config.jndiEnvironment());
        InitialContext context = null;
        Throwable lookupFailure = null;
        try {
            context = environment.isEmpty() ? new InitialContext() : new InitialContext(environment);
            Object result = context.lookup(name);
            if (!type.isInstance(result)) {
                throw new MessagingException("JNDI name " + name + " does not resolve to " + type.getName());
            }
            return type.cast(result);
        } catch (NamingException e) {
            MessagingException failure = new MessagingException("Cannot resolve JMS resource " + name + " for channel "
                                                                         + config.channel(), e);
            lookupFailure = failure;
            throw failure;
        } catch (RuntimeException | Error e) {
            lookupFailure = e;
            throw e;
        } finally {
            if (context != null) {
                closeContext(context, config, name, lookupFailure);
            }
        }
    }

    private static void closeContext(InitialContext context,
                                     JmsConnectorConfig config,
                                     String name,
                                     Throwable lookupFailure) {
        try {
            context.close();
        } catch (NamingException | RuntimeException e) {
            JmsResourceCleanupException cleanupFailure = new JmsResourceCleanupException(
                    "Cannot close JNDI InitialContext after resolving JMS resource " + name + " for channel "
                            + config.channel(),
                    e);
            if (lookupFailure instanceof Error) {
                addSuppressed(lookupFailure, cleanupFailure);
            } else {
                if (lookupFailure != null) {
                    addSuppressed(cleanupFailure, lookupFailure);
                }
                throw cleanupFailure;
            }
        } catch (Error e) {
            if (lookupFailure instanceof Error) {
                addSuppressed(lookupFailure, e);
            } else {
                if (lookupFailure != null) {
                    addSuppressed(e, lookupFailure);
                }
                throw e;
            }
        }
    }

    private static void addSuppressed(Throwable primary, Throwable suppressed) {
        if (primary != suppressed) {
            primary.addSuppressed(suppressed);
        }
    }
}
