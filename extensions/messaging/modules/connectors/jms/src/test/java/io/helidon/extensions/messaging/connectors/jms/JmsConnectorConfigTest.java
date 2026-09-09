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

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.messaging.spi.MessagingConnector;
import io.helidon.messaging.spi.OutgoingChannel;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class JmsConnectorConfigTest {
    @Test
    void testConnectorAndTypedChannelsDoNotResolveResourcesUntilStarted() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        JmsConnector connector = JmsConnector.builder()
                .name("orders-jms")
                .connectionFactory(factory)
                .build();

        assertThat(connector.incoming(JmsIncomingConfig.builder()
                                             .channelName("orders")
                                             .destination("orders")
                                             .build()), notNullValue());
        assertThat(connector.outgoing(JmsOutgoingConfig.builder()
                                             .channelName("audit")
                                             .destination("audit")
                                             .build()), notNullValue());
        verifyZeroInteractions(factory);
    }

    @Test
    void testProviderCreatesNamedConnectorWithoutChannelConfiguration() {
        MessagingConnector connector = new JmsConnectorProvider().create(
                Config.just(ConfigSources.create(Map.of("transacted", "true",
                                                       "reconnect.initial-delay", "PT1S",
                                                       "reconnect.max-delay", "PT10S"))),
                "orders-jms");

        assertThat(connector.name(), is("orders-jms"));
        assertThat(connector.type(), is(JmsConnectorProvider.CONNECTOR_TYPE));
        JmsConnectorConfig prototype = ((JmsConnector) connector).prototype();
        assertThat(prototype.transacted().orElseThrow(), is(true));
        assertThat(prototype.reconnectInitialDelay().orElseThrow(), is(Duration.ofSeconds(1)));
        assertThat(prototype.reconnectMaxDelay().orElseThrow(), is(Duration.ofSeconds(10)));
    }

    @Test
    void testOutgoingChannelInheritsCredentialsAndOverridesTransactionDefault() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        Queue queue = mock(Queue.class);
        MessageProducer producer = mock(MessageProducer.class);
        when(factory.createConnection("orders-user", "secret")).thenReturn(connection);
        when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
        when(session.createQueue("audit")).thenReturn(queue);
        when(session.createProducer(queue)).thenReturn(producer);

        JmsConnector connector = JmsConnector.builder()
                .name("orders-jms")
                .connectionFactory(factory)
                .username("orders-user")
                .password("secret")
                .transacted(true)
                .destination("default-queue")
                .build();
        OutgoingChannel outgoing = connector.outgoing(JmsOutgoingConfig.builder()
                                                             .channelName("audit")
                                                             .destination("audit")
                                                             .transacted(false)
                                                             .build());
        try {
            outgoing.start();
            verify(factory).createConnection("orders-user", "secret");
            verify(connection).createSession(false, Session.AUTO_ACKNOWLEDGE);
            verify(session).createQueue("audit");
        } finally {
            outgoing.close();
        }
    }

    @Test
    void testIncomingSubscriptionDefaultsAreValidatedWhenChannelIsCreated() {
        JmsConnector connector = JmsConnector.builder()
                .name("orders-jms")
                .destinationType(JmsDestinationType.TOPIC)
                .durable(true)
                .subscriptionName("orders-subscription")
                .build();

        assertThat(connector.incoming(JmsIncomingConfig.builder()
                                             .channelName("orders")
                                             .destination("orders")
                                             .build()), notNullValue());
        assertThrows(IllegalArgumentException.class,
                     () -> connector.incoming(JmsIncomingConfig.builder()
                                                      .channelName("orders")
                                                      .destination("orders")
                                                      .destinationType(JmsDestinationType.QUEUE)
                                                      .build()));
    }

    @Test
    void testPasswordIsDefensivelyCopiedAndConfidentialInPublicBlueprints() {
        char[] password = "secret".toCharArray();
        JmsConnector connector = JmsConnector.builder()
                .name("orders-jms")
                .username("orders-user")
                .password(password)
                .build();
        Arrays.fill(password, 'x');

        assertThat(new String(connector.prototype().password().orElseThrow()), is("secret"));
        char[] returned = connector.prototype().password().orElseThrow();
        Arrays.fill(returned, 'x');
        assertThat(new String(connector.prototype().password().orElseThrow()), is("secret"));
        assertThat(connector.prototype().toString().contains("secret"), is(false));

        JmsIncomingConfig channel = JmsIncomingConfig.builder()
                .channelName("orders")
                .username("orders-user")
                .password("channel-secret")
                .build();
        assertThat(new String(channel.password().orElseThrow()), is("channel-secret"));
        assertThat(channel.toString().contains("channel-secret"), is(false));
    }

    @Test
    void testNullFactoryIsRejectedAtBuilderBoundary() {
        assertThrows(NullPointerException.class,
                     () -> JmsConnector.builder().connectionFactory((ConnectionFactory) null));
    }
}
