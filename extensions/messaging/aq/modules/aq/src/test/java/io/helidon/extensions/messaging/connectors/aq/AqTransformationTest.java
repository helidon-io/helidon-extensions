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

package io.helidon.extensions.messaging.connectors.aq;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueSender;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicPublisher;

import io.helidon.messaging.MessagingException;
import io.helidon.service.registry.ServiceRegistry;

import oracle.jms.AQjmsDestination;
import oracle.jms.AQjmsProducer;
import oracle.jms.AQjmsSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@Timeout(10)
class AqTransformationTest {
    @Test
    void configuresNativeQueueAndTopicProducers() {
        for (Class<?> producerType : List.of(QueueSender.class, TopicPublisher.class)) {
            AQjmsProducer producer = mock(AQjmsProducer.class, withSettings().extraInterfaces(producerType));

            AqEnqueueTransformation.configure(producer, "APP.CHANGE_EVENT");

            verify(producer).setTransformation("APP.CHANGE_EVENT");
        }
    }

    @Test
    void destinationValidationRejectsANonOracleSession() {
        Session session = mock(Session.class);
        Queue queue = mock(Queue.class);

        assertThrows(MessagingException.class, () -> AqEnqueueTransformation.requireClassicDestination(session, queue));
    }

    @Test
    void metadataQueryAcceptsClassicQueuesAndTopicsWithoutChangingConnectionState() throws Exception {
        for (AqDestinationType type : AqDestinationType.values()) {
            MetadataQuery query = metadataQuery(type, true, "FALSE");

            AqEnqueueTransformation.requireClassicDestination(query.session(), query.destination());

            verify(query.statement()).setString(1, "Application's schema");
            verify(query.statement()).setString(2, "Mixed Case Destination");
            verify(query.rows()).close();
            verify(query.statement()).close();
            assertConnectionUnchanged(query.connection());
        }
    }

    @Test
    void metadataQueryNormalizesUnquotedLowercaseQueueAndTopicIdentifiers() throws Exception {
        for (AqDestinationType type : AqDestinationType.values()) {
            MetadataQuery query = metadataQuery(type, true, "false");
            if (type == AqDestinationType.QUEUE) {
                when(query.destination().getQueueOwner()).thenReturn("app");
                when(query.destination().getQueueName()).thenReturn("orders");
            } else {
                when(query.destination().getTopicOwner()).thenReturn("app");
                when(query.destination().getTopicName()).thenReturn("orders");
            }

            AqEnqueueTransformation.requireClassicDestination(query.session(), query.destination());

            verify(query.connection()).prepareStatement("SELECT SHARDED FROM SYS.ALL_QUEUES WHERE OWNER = ? AND NAME = ?");
            verify(query.statement()).setString(1, "APP");
            verify(query.statement()).setString(2, "ORDERS");
            verify(query.rows()).close();
            verify(query.statement()).close();
            assertConnectionUnchanged(query.connection());
        }
    }

    @Test
    void malformedQuotedIdentifiersFailBeforeQueryingMetadata() throws Exception {
        for (String name : List.of("\"unterminated", "bad\"quote", "\"embedded\"quote\"", "BAD\0NAME")) {
            MetadataQuery query = metadataQuery(AqDestinationType.QUEUE, true, "FALSE");
            when(query.destination().getQueueName()).thenReturn(name);

            assertThrows(MessagingException.class,
                         () -> AqEnqueueTransformation.requireClassicDestination(query.session(), query.destination()));

            verify(query.connection(), never()).prepareStatement(anyString());
            assertConnectionUnchanged(query.connection());
        }
    }

    @Test
    void metadataQueryRejectsShardedAndUnrecognizedRowsAndClosesResources() throws Exception {
        for (String value : Arrays.asList("TRUE", "true", "unexpected", null)) {
            MetadataQuery query = metadataQuery(AqDestinationType.QUEUE, true, value);

            assertThrows(MessagingException.class,
                         () -> AqEnqueueTransformation.requireClassicDestination(query.session(), query.destination()));

            verify(query.rows()).close();
            verify(query.statement()).close();
            assertConnectionUnchanged(query.connection());
        }
    }

    @Test
    void missingMetadataIsNotTreatedAsAClassicQueue() throws Exception {
        MetadataQuery query = metadataQuery(AqDestinationType.QUEUE, false, "FALSE");

        assertThrows(MessagingException.class,
                     () -> AqEnqueueTransformation.requireClassicDestination(query.session(), query.destination()));

        verify(query.rows()).close();
        verify(query.statement()).close();
        assertConnectionUnchanged(query.connection());
    }

    @Test
    void queryFailureClosesTheStatementAndPreservesRecoveryClassification() throws Exception {
        MetadataQuery query = metadataQuery(AqDestinationType.QUEUE, true, "FALSE");
        SQLRecoverableException lost = new SQLRecoverableException("connection lost", "08006");
        when(query.statement().executeQuery()).thenThrow(lost);

        MessagingException failure = assertThrows(MessagingException.class,
                () -> AqEnqueueTransformation.requireClassicDestination(query.session(), query.destination()));

        assertThat(failure.getCause(), sameInstance(lost));
        assertThat(AqReconnectSupport.recoverable(failure), is(true));
        verify(query.statement()).close();
        verify(query.rows(), never()).close();
        assertConnectionUnchanged(query.connection());
    }

    @Test
    void rowReadAndCloseFailuresRetainThePrimaryErrorAndCloseTheStatement() throws Exception {
        MetadataQuery query = metadataQuery(AqDestinationType.QUEUE, true, "FALSE");
        SQLException readFailure = new SQLException("metadata unavailable");
        SQLException closeFailure = new SQLException("cursor close failed");
        when(query.rows().getString(1)).thenThrow(readFailure);
        doThrow(closeFailure).when(query.rows()).close();

        MessagingException failure = assertThrows(MessagingException.class,
                () -> AqEnqueueTransformation.requireClassicDestination(query.session(), query.destination()));

        assertThat(failure.getCause(), sameInstance(readFailure));
        assertThat(Arrays.asList(readFailure.getSuppressed()).contains(closeFailure), is(true));
        verify(query.rows()).close();
        verify(query.statement()).close();
        assertConnectionUnchanged(query.connection());
    }

    @Test
    void enabledTransformationRejectsAnUnsupportedProducerAndClosesTheConnection() throws Exception {
        Client client = genericClient();
        AqConnectorConfig connector = config(factory(client)).enqueueTransformation(transform -> transform.name("APP.CHANGE_EVENT"))
                .buildPrototype();
        try (AqResourceManager manager = manager(connector, outgoing())) {
            assertThrows(MessagingException.class, manager::open);
        }
        verify(client.connection()).close();
    }

    @Test
    void absentAndDisabledPoliciesSupportGenericProducers() throws Exception {
        for (boolean disabled : List.of(false, true)) {
            Client client = genericClient();
            AqConnectorConfig.Builder connector = config(factory(client));
            if (disabled) {
                connector.enqueueTransformation(transform -> transform.enabled(false));
            }
            try (AqResourceManager manager = manager(connector.buildPrototype(), outgoing())) {
                manager.open();
                assertThat(manager.awaitReady().producer(), sameInstance(client.producer()));
            }
            verify(client.connection()).close();
        }
    }

    @Test
    void channelDisableDoesNotApplyTheConnectorTransformation() throws Exception {
        Client client = nativeClient(AqDestinationType.QUEUE);
        AqConnectorConfig connector = config(factory(client))
                .enqueueTransformation(transform -> transform.name("APP.CHANGE_EVENT")).buildPrototype();
        AqOutgoingConfig outgoing = AqOutgoingConfig.builder(outgoing())
                .enqueueTransformation(transform -> transform.enabled(false)).build();
        try (AqResourceManager manager = manager(connector, outgoing)) {
            manager.open();
            assertThat(manager.awaitReady().producer(), sameInstance(client.producer()));
            verify((AQjmsProducer) client.producer(), never()).setTransformation(anyString());
        }
    }

    @Test
    void failedNativeConfigurationDoesNotPublishTheProducerAndClosesResources() throws Exception {
        Client client = nativeClient(AqDestinationType.QUEUE);
        IllegalStateException rejected = new IllegalStateException("transformation rejected");
        doThrow(rejected).when((AQjmsProducer) client.producer()).setTransformation("APP.CHANGE_EVENT");
        AqConnectorConfig connector = config(factory(client))
                .enqueueTransformation(transform -> transform.name("APP.CHANGE_EVENT")).buildPrototype();
        try (AqResourceManager manager = manager(connector, outgoing())) {
            assertThat(assertThrows(IllegalStateException.class, manager::open), sameInstance(rejected));
            assertThrows(MessagingException.class, manager::awaitReady);
        }
        verify(client.connection()).close();
    }

    @Test
    void connectorTransformationDoesNotApplyToIncomingResources() throws Exception {
        Client client = genericClient();
        AqConnectorConfig connector = config(factory(client))
                .enqueueTransformation(transform -> transform.name("APP.CHANGE_EVENT")).buildPrototype();
        AqRuntimeConfig runtime = AqRuntimeConfig.incoming(connector,
                AqIncomingConfig.builder().connector("aq").channelName("incoming").build());
        try (AqResourceManager manager = new AqResourceManager(runtime,
                new AqResourceResolver(mock(ServiceRegistry.class)), true)) {
            manager.open();
            assertThat(manager.awaitReady().session(), sameInstance(client.session()));
        }
        verify(client.connection()).close();
    }

    private static Client nativeClient(AqDestinationType type) throws JMSException {
        AQjmsSession session = mock(AQjmsSession.class);
        AQjmsProducer producer = mock(AQjmsProducer.class, withSettings().extraInterfaces(TopicPublisher.class));
        Connection connection = mock(Connection.class);
        when(connection.createSession(true, Session.SESSION_TRANSACTED)).thenReturn(session);
        if (type == AqDestinationType.QUEUE) {
            Queue queue = mock(Queue.class);
            when(session.createQueue("ORDERS")).thenReturn(queue);
            when(session.createProducer(queue)).thenReturn(producer);
        } else {
            Topic topic = mock(Topic.class);
            when(session.createTopic("ORDERS")).thenReturn(topic);
            when(session.createPublisher(topic)).thenReturn((TopicPublisher) producer);
        }
        return new Client(connection, session, producer);
    }

    private static MetadataQuery metadataQuery(AqDestinationType type, boolean present, String sharded) throws Exception {
        java.sql.Connection connection = mock(java.sql.Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT SHARDED FROM SYS.ALL_QUEUES WHERE OWNER = ? AND NAME = ?")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(present, false);
        when(rows.getString(1)).thenReturn(sharded);
        AQjmsSession session = mock(AQjmsSession.class);
        when(session.getDBConnection()).thenReturn(connection);
        AQjmsDestination destination = mock(AQjmsDestination.class);
        if (type == AqDestinationType.QUEUE) {
            when(destination.getQueueName()).thenReturn("\"Mixed Case Destination\"");
            when(destination.getQueueOwner()).thenReturn("\"Application's schema\"");
        } else {
            when(destination.getTopicName()).thenReturn("\"Mixed Case Destination\"");
            when(destination.getTopicOwner()).thenReturn("\"Application's schema\"");
        }
        return new MetadataQuery(session, destination, connection, statement, rows);
    }

    private static void assertConnectionUnchanged(java.sql.Connection connection) throws SQLException {
        verify(connection, never()).getTypeMap();
        verify(connection, never()).setTypeMap(any());
        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).setReadOnly(anyBoolean());
        verify(connection, never()).setTransactionIsolation(anyInt());
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
        verify(connection, never()).close();
    }

    private static Client genericClient() throws JMSException {
        Connection connection = mock(Connection.class);
        Session session = mock(Session.class);
        Queue queue = mock(Queue.class);
        MessageProducer producer = mock(MessageProducer.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        when(connection.createSession(true, Session.SESSION_TRANSACTED)).thenReturn(session);
        when(session.createQueue("ORDERS")).thenReturn(queue);
        when(session.createProducer(queue)).thenReturn(producer);
        when(session.createConsumer(queue, null, false)).thenReturn(consumer);
        return new Client(connection, session, producer);
    }

    private static ConnectionFactory factory(Client client) throws JMSException {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenReturn(client.connection());
        return factory;
    }

    private static AqConnectorConfig.Builder config(ConnectionFactory factory) {
        return AqConnectorConfig.builder().name("aq").connectionFactory(factory).destination("ORDERS")
                .closeTimeout(Duration.ofSeconds(2))
                .reconnect(reconnect -> reconnect.initialDelay(Duration.ofMillis(1)).maxDelay(Duration.ofMillis(5))
                        .jitter(0).connectTimeout(Duration.ofSeconds(2)).waitTimeout(Duration.ofSeconds(2)));
    }

    private static AqOutgoingConfig outgoing() {
        return AqOutgoingConfig.builder().connector("aq").channelName("outgoing").build();
    }

    private static AqResourceManager manager(AqConnectorConfig connector, AqOutgoingConfig outgoing) {
        return new AqResourceManager(AqRuntimeConfig.outgoing(connector, outgoing),
                                     new AqResourceResolver(mock(ServiceRegistry.class)), false);
    }

    private record Client(Connection connection, Session session, MessageProducer producer) {
    }

    private record MetadataQuery(AQjmsSession session, AQjmsDestination destination, java.sql.Connection connection,
                                 PreparedStatement statement, ResultSet rows) {
    }
}
