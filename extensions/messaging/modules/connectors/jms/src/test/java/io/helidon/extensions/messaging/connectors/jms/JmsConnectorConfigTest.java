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
import java.util.Optional;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.messaging.ConnectorConfig;
import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.ServiceRegistry;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Queue;
import jakarta.jms.Topic;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmsConnectorConfigTest {
    @Test
    void testDefaultsAndNestedConfiguration() {
        JmsConnectorConfig config = JmsConnectorConfig.create(Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("direction", "INCOMING"),
                Map.entry(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "orders"),
                Map.entry(ConnectorConfig.CONNECTOR_ATTRIBUTE, JmsConnectorProvider.CONNECTOR_TYPE),
                Map.entry(JmsConnectorConfig.DESTINATION_PROPERTY, "orders"),
                Map.entry(JmsConnectorConfig.DESTINATION_TYPE_PROPERTY, "QUEUE"),
                Map.entry(JmsConnectorConfig.RECONNECT_INITIAL_DELAY_PROPERTY, "PT1S"),
                Map.entry(JmsConnectorConfig.RECONNECT_MAX_DELAY_PROPERTY, "PT10S"),
                Map.entry("jndi.environment.java.naming.factory.initial", "example.Factory")))));

        assertThat(config.destination().orElseThrow(), is("orders"));
        assertThat(config.destinationType(), is(JmsDestinationType.QUEUE));
        assertThat(config.receiveTimeout(), is(Duration.ofMillis(100)));
        assertThat(config.reconnectInitialDelay(), is(Duration.ofSeconds(1)));
        assertThat(config.reconnectMaxDelay(), is(Duration.ofSeconds(10)));
        assertThat(config.allowObjectMessages(), is(false));
        assertThat(config.maxBodyBytes(), is(JmsConnectorConfig.DEFAULT_MAX_BODY_BYTES));
        assertThat(config.jndiEnvironment(),
                   is(Map.of("java.naming.factory.initial", "example.Factory")));
    }

    @Test
    void testCredentialsMustBePaired() {
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder().username("orders-user").build());
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder().password("secret".toCharArray()).build());
    }

    @Test
    void testDurableSubscriptionRequirements() {
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder().durable(true).build());
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder()
                             .destinationType(JmsDestinationType.TOPIC)
                             .durable(true)
                             .clientId("orders-client")
                             .build());

        JmsConnectorConfig config = incomingBuilder()
                .destinationType(JmsDestinationType.TOPIC)
                .durable(true)
                .subscriptionName("orders-subscription")
                .build();

        assertThat(config.durable(), is(true));
        assertThat(config.clientId().isEmpty(), is(true));
    }

    @Test
    void testResourceRoutesAreExclusive() {
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder()
                             .connectionFactory("factory")
                             .jndiConnectionFactory("jms/ConnectionFactory")
                             .build());
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder()
                             .jndiDestination("jms/orders")
                             .build());
    }

    @Test
    void testPasswordIsConfidential() {
        JmsConnectorConfig config = incomingBuilder()
                .username("orders-user")
                .password("secret".toCharArray())
                .build();

        assertThat(new String(config.password().orElseThrow()), is("secret"));
        assertThat(config.toString().contains("secret"), is(false));
    }

    @Test
    void testPasswordIsDefensivelyCopiedAcrossBuilderAndPrototypeBoundaries() {
        char[] supplied = "secret".toCharArray();
        JmsConnectorConfig.Builder builder = incomingBuilder()
                .username("orders-user")
                .password(supplied);
        Arrays.fill(supplied, 'x');

        char[] builderCopy = builder.passwordSource().get().orElseThrow();
        assertThat(new String(builderCopy), is("secret"));
        Arrays.fill(builderCopy, 'x');

        JmsConnectorConfig config = builder.build();
        char[] configCopy = config.password().orElseThrow();
        assertThat(new String(configCopy), is("secret"));
        Arrays.fill(configCopy, 'x');
        char[] sourceCopy = config.passwordSource().get().orElseThrow();
        Arrays.fill(sourceCopy, 'x');
        assertThat(new String(config.password().orElseThrow()), is("secret"));

        JmsConnectorConfig copied = JmsConnectorConfig.builder().from(config).build();
        assertThat(new String(copied.password().orElseThrow()), is("secret"));
    }

    @Test
    void testConfiguredPasswordIsMovedToDefensiveStorage() {
        Config configSource = Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("direction", "INCOMING"),
                Map.entry(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "orders"),
                Map.entry(ConnectorConfig.CONNECTOR_ATTRIBUTE, JmsConnectorProvider.CONNECTOR_TYPE),
                Map.entry(JmsConnectorConfig.DESTINATION_PROPERTY, "orders"),
                Map.entry(JmsConnectorConfig.USERNAME_PROPERTY, "orders-user"),
                Map.entry(JmsConnectorConfig.PASSWORD_PROPERTY, "secret"))));
        JmsConnectorConfig.Builder builder = JmsConnectorConfig.builder().config(configSource);
        assertThat(builder.configuredPassword().orElseThrow(), is("secret"));

        JmsConnectorConfig config = builder.build();

        assertThat(config.configuredPassword().isEmpty(), is(true));
        char[] password = config.password().orElseThrow();
        assertThat(new String(password), is("secret"));
        Arrays.fill(password, 'x');
        assertThat(new String(config.password().orElseThrow()), is("secret"));
    }

    @Test
    void testProgrammaticPasswordChangesClearConfiguredStaging() {
        Config configSource = Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("direction", "INCOMING"),
                Map.entry(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "orders"),
                Map.entry(ConnectorConfig.CONNECTOR_ATTRIBUTE, JmsConnectorProvider.CONNECTOR_TYPE),
                Map.entry(JmsConnectorConfig.DESTINATION_PROPERTY, "orders"),
                Map.entry(JmsConnectorConfig.USERNAME_PROPERTY, "orders-user"),
                Map.entry(JmsConnectorConfig.PASSWORD_PROPERTY, "secret"))));
        JmsConnectorConfig.Builder replacing = JmsConnectorConfig.builder().config(configSource);
        assertThat(replacing.configuredPassword().orElseThrow(), is("secret"));

        JmsConnectorConfig replaced = replacing.password("replacement").build();

        assertThat(replacing.configuredPassword().isEmpty(), is(true));
        assertThat(new String(replaced.password().orElseThrow()), is("replacement"));

        JmsConnectorConfig.Builder clearing = JmsConnectorConfig.builder().config(configSource);
        assertThat(clearing.configuredPassword().orElseThrow(), is("secret"));
        clearing.clearPassword();

        assertThat(clearing.configuredPassword().isEmpty(), is(true));
        assertThat(clearing.passwordSource().get().isEmpty(), is(true));
    }

    @Test
    void testConfiguredPasswordIsNotAliasedWhenCopyingBuilders() {
        Config configSource = Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("direction", "INCOMING"),
                Map.entry(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "orders"),
                Map.entry(ConnectorConfig.CONNECTOR_ATTRIBUTE, JmsConnectorProvider.CONNECTOR_TYPE),
                Map.entry(JmsConnectorConfig.DESTINATION_PROPERTY, "orders"),
                Map.entry(JmsConnectorConfig.USERNAME_PROPERTY, "orders-user"),
                Map.entry(JmsConnectorConfig.PASSWORD_PROPERTY, "secret"))));
        JmsConnectorConfig.Builder source = JmsConnectorConfig.builder().config(configSource);
        JmsConnectorConfig.Builder copy = JmsConnectorConfig.builder().from(source);

        JmsConnectorConfig copiedConfig = copy.build();
        assertThat(source.configuredPassword().orElseThrow(), is("secret"));
        JmsConnectorConfig sourceConfig = source.build();

        assertThat(new String(copiedConfig.password().orElseThrow()), is("secret"));
        assertThat(new String(sourceConfig.password().orElseThrow()), is("secret"));

        JmsConnectorConfig.Builder reverseSource = JmsConnectorConfig.builder().config(configSource);
        JmsConnectorConfig.Builder reverseCopy = JmsConnectorConfig.builder().from(reverseSource);

        JmsConnectorConfig reverseSourceConfig = reverseSource.build();
        assertThat(reverseCopy.configuredPassword().orElseThrow(), is("secret"));
        JmsConnectorConfig reverseCopiedConfig = reverseCopy.build();

        assertThat(new String(reverseSourceConfig.password().orElseThrow()), is("secret"));
        assertThat(new String(reverseCopiedConfig.password().orElseThrow()), is("secret"));
    }

    @Test
    void testPrototypeCopyOverridesStaleConfiguredPassword() {
        Config configSource = Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("direction", "INCOMING"),
                Map.entry(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, "orders"),
                Map.entry(ConnectorConfig.CONNECTOR_ATTRIBUTE, JmsConnectorProvider.CONNECTOR_TYPE),
                Map.entry(JmsConnectorConfig.DESTINATION_PROPERTY, "orders"),
                Map.entry(JmsConnectorConfig.USERNAME_PROPERTY, "orders-user"),
                Map.entry(JmsConnectorConfig.PASSWORD_PROPERTY, "stale-secret"))));
        JmsConnectorConfig prototype = incomingBuilder()
                .username("orders-user")
                .password("prototype-secret")
                .build();
        JmsConnectorConfig.Builder target = JmsConnectorConfig.builder().config(configSource);

        JmsConnectorConfig copied = target.from(prototype).build();

        assertThat(new String(copied.password().orElseThrow()), is("prototype-secret"));
    }

    @Test
    void testJndiEnvironmentIsConfidential() {
        String credential = "jndi-secret";
        JmsConnectorConfig.Builder builder = incomingBuilder()
                .putJndiEnvironmentProperty("java.naming.security.credentials", credential);

        assertThat(builder.toString().contains(credential), is(false));
        assertThat(builder.build().toString().contains(credential), is(false));
    }

    @Test
    void testProviderAcceptsImperativeConnectionFactory() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        JmsConnectorProvider provider = new JmsConnectorProvider(connectionFactory);

        assertThat(provider.connectorType(), is("helidon-jms"));
        assertThat(provider.createIncomingConnector(incomingBuilder().build()) != null, is(true));
        assertThat(provider.createOutgoingConnector(incomingBuilder()
                                                            .direction(ConnectorConfig.Direction.OUTGOING)
                                                            .build()) != null,
                   is(true));
    }

    @Test
    void testImperativeProviderRejectsNullFactoryEagerly() {
        assertThrows(NullPointerException.class,
                     () -> new JmsConnectorProvider((ConnectionFactory) null));
    }

    @Test
    void testNamedConnectionFactoryDoesNotFallBackToDefault() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.firstNamed(ConnectionFactory.class, "missing")).thenReturn(Optional.empty());
        when(registry.first(ConnectionFactory.class)).thenReturn(Optional.of(mock(ConnectionFactory.class)));

        JmsConnectorConfig config = incomingBuilder().connectionFactory("missing").build();

        assertThrows(MessagingException.class, () -> new JmsResourceResolver(registry).resolve(config));
        verify(registry).firstNamed(ConnectionFactory.class, "missing");
        verify(registry, never()).first(ConnectionFactory.class);
    }

    @Test
    void testJndiDestinationTypeIsValidated() {
        Queue queue = mock(Queue.class);
        Topic topic = mock(Topic.class);
        JmsConnectorConfig queueConfig = incomingBuilder().build();
        JmsConnectorConfig topicConfig = incomingBuilder().destinationType(JmsDestinationType.TOPIC).build();

        assertThat(JmsResourceResolver.validateDestinationType(queue, queueConfig), sameInstance(queue));
        assertThat(JmsResourceResolver.validateDestinationType(topic, topicConfig), sameInstance(topic));
        assertThrows(MessagingException.class,
                     () -> JmsResourceResolver.validateDestinationType(topic, queueConfig));
        assertThrows(MessagingException.class,
                     () -> JmsResourceResolver.validateDestinationType(queue, topicConfig));
    }

    @Test
    void testReconnectRangeValidation() {
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder()
                             .reconnectInitialDelay(Duration.ofSeconds(2))
                             .reconnectMaxDelay(Duration.ofSeconds(1))
                             .build());
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder().reconnectJitter(1).build());
        assertThrows(IllegalArgumentException.class,
                     () -> incomingBuilder().reconnectJitter(Double.NaN).build());
    }

    @Test
    void testMaximumBodyBytesValidation() {
        assertThat(incomingBuilder().maxBodyBytes(2048).build().maxBodyBytes(), is(2048));
        assertThrows(IllegalArgumentException.class, () -> incomingBuilder().maxBodyBytes(0).build());
        assertThrows(IllegalArgumentException.class, () -> incomingBuilder().maxBodyBytes(-1).build());
    }

    private static JmsConnectorConfig.Builder incomingBuilder() {
        return JmsConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.INCOMING)
                .channel("orders")
                .connector(JmsConnectorProvider.CONNECTOR_TYPE)
                .destination("orders");
    }
}
