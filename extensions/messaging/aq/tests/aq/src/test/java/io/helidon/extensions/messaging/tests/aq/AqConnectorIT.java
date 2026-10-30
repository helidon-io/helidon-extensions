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

package io.helidon.extensions.messaging.tests.aq;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilderFactory;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.extensions.messaging.connectors.aq.AqAdt;
import io.helidon.extensions.messaging.connectors.aq.AqBlob;
import io.helidon.extensions.messaging.connectors.aq.AqClob;
import io.helidon.extensions.messaging.connectors.aq.AqConnector;
import io.helidon.extensions.messaging.connectors.aq.AqDestinationType;
import io.helidon.extensions.messaging.connectors.aq.AqIncomingConfig;
import io.helidon.extensions.messaging.connectors.aq.AqMessage;
import io.helidon.extensions.messaging.connectors.aq.AqOutgoingConfig;
import io.helidon.extensions.messaging.connectors.aq.AqPayloadConverter;
import io.helidon.extensions.messaging.connectors.aq.AqPayloadType;
import io.helidon.extensions.messaging.connectors.aq.AqRecipient;
import io.helidon.extensions.messaging.connectors.aq.AqXml;
import io.helidon.messaging.BatchDeliveryException;
import io.helidon.messaging.BatchItemStatus;
import io.helidon.messaging.ConsumerRegistration;
import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.FailureDisposition;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessagingChannel;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.MessagingGraph;
import io.helidon.messaging.MessagingRuntime;
import io.helidon.service.registry.DescriptorHandler;
import io.helidon.service.registry.ServiceDescriptor;
import io.helidon.service.registry.ServiceDiscovery;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryException;
import io.helidon.service.registry.ServiceRegistryManager;

import oracle.jdbc.OracleData;
import oracle.jdbc.OracleDataFactory;
import oracle.jms.AQjmsFactory;
import oracle.jms.AQjmsSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.xml.sax.InputSource;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "aq.test.url", matches = ".+")
@Timeout(60)
class AqConnectorIT {
    private final List<ServiceRegistryManager> managers = new ArrayList<>();

    private AqDatabase database;

    @BeforeAll
    void createSchema() throws Exception {
        database = AqDatabase.create();
    }

    @AfterAll
    void dropSchema() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    @AfterEach
    void stopRuntimes() {
        RuntimeException failure = null;
        for (ServiceRegistryManager manager : managers.reversed()) {
            try {
                manager.shutdown();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        managers.clear();
        if (failure != null) {
            throw failure;
        }
    }

    @Test
    void generatedReceiveFromAndEmitterShareInjectedDataSource() throws Exception {
        ServiceRegistryManager manager = runtime("TEXT_QUEUE", "", "", null, true);
        AqDeclarativeReceiver receiver = manager.registry().get(AqDeclarativeReceiver.class);
        assertThat(receiver.dataSource(), sameInstance(database.dataSource()));

        receiver.emit("generated AQ messaging");

        AqMessage<String> message = receiver.await();
        assertThat(message, notNullValue());
        assertThat(message.entity(), is("generated AQ messaging"));
    }

    @Test
    void textMetadataAndSharedInjectedDataSourceSurviveRuntimeShutdown() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("TEXT_QUEUE", "", "", receiver);
        assertThat(manager.registry().get(DataSource.class), sameInstance(database.dataSource()));
        messaging(manager).emit("outgoing", AqMessage.builder("AQ text")
                .correlationId("order-176")
                .type("created")
                .putProperty("region", "EU")
                .putProperty("attempt", 7)
                .build());

        AqMessage<?> received = receiver.await();
        assertThat(received.entity(), is("AQ text"));
        assertThat(received.correlationId(), is(Optional.of("order-176")));
        assertThat(received.type(), is(Optional.of("created")));
        assertThat(received.properties().get("region"), is("EU"));
        assertThat(received.properties().get("attempt"), is(7));
        assertThat(received.messageId().isPresent(), is(true));
        manager.shutdown();
        managers.remove(manager);

        // The application can continue using its injected DataSource after every channel has closed.
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 1 FROM DUAL")) {
            assertThat(result.next(), is(true));
            assertThat(result.getInt(1), is(1));
        }
        assertThat("committed input must not be delivered again", receiveText("TEXT_QUEUE", 200), nullValue());
    }

    @Test
    void queueReplyToSurvivesReceiveAndForwarding() throws Exception {
        verifyReplyTo("TEXT_QUEUE", AqDestinationType.QUEUE);
    }

    @Test
    void topicReplyToSurvivesReceiveAndForwarding() throws Exception {
        verifyReplyTo("DURABLE_QUEUE", AqDestinationType.TOPIC);
    }

    @Test
    void jndiQueueIsLookedUpAgainAfterReconnectAndRebind() throws Exception {
        Destination original = detachedDestination("TEXT_QUEUE", AqDestinationType.QUEUE);
        Destination rebound = detachedDestination("RECONNECT_QUEUE", AqDestinationType.QUEUE);
        try (var registration = AqJndiTestContextFactory.register()) {
            registration.rebind("jms/Orders", original);
            AqConnector connector = AqConnector.builder().name("jndi-aq").dataSource(database.dataSource())
                    .jndi(registration.config("jms/Orders"))
                    .reconnect(reconnect -> reconnect.initialDelay(Duration.ofMillis(10)).maxDelay(Duration.ofMillis(50))
                            .jitter(0).connectTimeout(Duration.ofSeconds(5)).waitTimeout(Duration.ofSeconds(10)))
                    .build();
            try (var outgoing = connector.outgoing(AqOutgoingConfig.builder()
                    .connector("jndi-aq").channelName("outgoing").build())) {
                outgoing.start();
                outgoing.sendBatch(MessageBatch.create(AqMessage.create("before rebind")));
                TextMessage first = receiveText("TEXT_QUEUE", 5_000);
                assertThat(first, notNullValue());
                assertThat(first.getText(), is("before rebind"));

                registration.rebind("jms/Orders", rebound);
                assertThat(database.killSessions(), greaterThan(0));
                assertThrows(BatchDeliveryException.class,
                             () -> outgoing.sendBatch(MessageBatch.create(AqMessage.create("failed send"))));
                outgoing.sendBatch(MessageBatch.create(AqMessage.create("after rebind")));

                TextMessage second = receiveText("RECONNECT_QUEUE", 5_000);
                assertThat(second, notNullValue());
                assertThat(second.getText(), is("after rebind"));
                assertThat(receiveText("TEXT_QUEUE", 200), nullValue());
                assertThat(registration.opened(), is(2));
                assertThat(registration.closed(), is(2));
            }
        }
    }

    @Test
    void jndiTopicFromAClosedLookupSessionCanBePublishedTo() throws Exception {
        Topic topic = (Topic) detachedDestination("DURABLE_QUEUE", AqDestinationType.TOPIC);
        try (var registration = AqJndiTestContextFactory.register();
             Connection connection = AQjmsFactory.getConnectionFactory(database.dataSource()).createConnection()) {
            registration.rebind("jms/Events", topic);
            connection.setClientID("jndi-topic-test");
            AqConnector connector = AqConnector.builder().name("jndi-aq").dataSource(database.dataSource())
                    .destinationType(AqDestinationType.TOPIC).jndi(registration.config("jms/Events")).build();
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                try (MessageConsumer consumer = session.createDurableSubscriber(topic, "JNDI_SUBSCRIBER");
                     var outgoing = connector.outgoing(AqOutgoingConfig.builder()
                             .connector("jndi-aq").channelName("outgoing").build())) {
                    connection.start();
                    outgoing.start();
                    outgoing.sendBatch(MessageBatch.create(AqMessage.create("JNDI topic")));

                    TextMessage received = (TextMessage) consumer.receive(5_000);
                    assertThat(received, notNullValue());
                    assertThat(received.getText(), is("JNDI topic"));
                    assertThat(registration.opened(), is(1));
                    assertThat(registration.closed(), is(1));
                }
                session.unsubscribe("JNDI_SUBSCRIBER");
            }
        }
    }

    @Test
    void incomingJndiQueueFromAClosedLookupSessionCanBeConsumed() throws Exception {
        Destination destination = detachedDestination("TEXT_QUEUE", AqDestinationType.QUEUE);
        try (var registration = AqJndiTestContextFactory.register()) {
            registration.rebind("jms/Orders", destination);
            AqConnector incoming = AqConnector.builder().name("jndi-aq").dataSource(database.dataSource())
                    .jndi(registration.config("jms/Orders")).receiveTimeout(Duration.ofMillis(200)).build();
            AqConnector sender = AqConnector.builder().name("direct-aq").dataSource(database.dataSource())
                    .schema(database.schema()).destination("TEXT_QUEUE").build();
            var channel = MessagingChannel.create("jndi-incoming", String.class);
            BlockingQueue<Message<?>> messages = new LinkedBlockingQueue<>();
            try (var graph = MessagingGraph.builder().channel(channel)
                    .incomingChannel(channel, incoming.incoming(AqIncomingConfig.builder()
                            .connector("jndi-aq").channelName("jndi-incoming").build()))
                    .messageSink(channel, messages::add).build();
                 var outgoing = sender.outgoing(AqOutgoingConfig.builder()
                         .connector("direct-aq").channelName("outgoing").build())) {
                graph.start();
                outgoing.start();
                outgoing.sendBatch(MessageBatch.create(AqMessage.create("JNDI incoming")));

                Message<?> received = messages.poll(20, TimeUnit.SECONDS);
                assertThat(received, instanceOf(AqMessage.class));
                assertThat(received.entity(), is("JNDI incoming"));
            }
            assertThat(registration.opened(), is(1));
            assertThat(registration.closed(), is(1));
        }
        assertThat("the JNDI input must commit its dequeue", receiveText("TEXT_QUEUE", 200), nullValue());
    }

    @Test
    void oracleMetadataIsSnapshottedWithoutReplayingSendOptions() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("TEXT_QUEUE", "", "", receiver);
        String exceptionQueue = database.schema() + ".TEXT_EXCEPTION_QUEUE";
        long beforeSend = System.currentTimeMillis();
        messaging(manager).emit("outgoing", AqMessage.builder("Oracle metadata")
                .applicationId("orders-worker").exceptionQueue(exceptionQueue).build());

        AqMessage<?> received = receiver.await();
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_APPLICATION_ID_METADATA).orElseThrow(),
                   is("orders-worker"));
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_USER_ID_METADATA).orElseThrow(), is(database.schema()));
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_EXCEPTION_QUEUE_METADATA).orElseThrow(), is(exceptionQueue));
        assertReceivedOracleMetadata(received);
        assertThat(metadataInteger(received, AqMessage.RECEIVED_TIMESTAMP_METADATA), greaterThanOrEqualTo(beforeSend));
        assertThat(metadataInteger(received, AqMessage.RECEIVED_TIMESTAMP_METADATA), lessThanOrEqualTo(System.currentTimeMillis()));
        assertThat(received.headers().entries().isEmpty(), is(true));
        assertThat(received.properties().isEmpty(), is(true));

        messaging(manager).emit("outgoing", received);
        AqMessage<?> forwarded = receiver.await();
        assertThat(forwarded.entity(), is("Oracle metadata"));
        assertThat(forwarded.localMetadata().text(AqMessage.RECEIVED_APPLICATION_ID_METADATA).orElse(""),
                   not(is("orders-worker")));
        assertReceivedOracleMetadata(forwarded);
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_APPLICATION_ID_METADATA).orElseThrow(),
                   is("orders-worker"));
    }

    @Test
    void expiredMessageUsesTheExplicitOracleExceptionQueue() throws Exception {
        ServiceRegistryManager sender = runtime("TEXT_QUEUE", "time-to-live: PT1S", "", null);
        String exceptionQueue = database.schema() + ".TEXT_EXCEPTION_QUEUE";
        messaging(sender).emit("outgoing", Message.builder("expired to custom queue")
                .localMetadata(AqMessage.APPLICATION_ID_METADATA, "expired-order")
                .localMetadata(AqMessage.EXCEPTION_QUEUE_METADATA, exceptionQueue).build());
        awaitMessageState("TEXT_TAB", "EXPIRED");
        try (var connection = database.dataSource().getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM AQ$TEXT_TAB"
                                                         + " WHERE QUEUE = 'TEXT_EXCEPTION_QUEUE' AND MSG_STATE = 'EXPIRED'")) {
            assertThat(result.next(), is(true));
            assertThat("database expiration must route to the requested exception queue", result.getInt(1), is(1));
        }
        Receiver receiver = new Receiver();
        runtime("TEXT_EXCEPTION_QUEUE", "", "", receiver);

        AqMessage<?> received = receiver.await();
        assertThat(received.entity(), is("expired to custom queue"));
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_APPLICATION_ID_METADATA).orElseThrow(), is("expired-order"));
        assertReceivedOracleMetadata(received);
        assertThat(metadataInteger(received, AqMessage.RECEIVED_STATE_METADATA), is(3L));
    }

    @Test
    void immediateMessagesOvertakeDelayedGenericAndAqMessages() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("DELAY_TEXT_QUEUE", "", "", receiver);
        Message<String> generic = Message.builder("generic delayed")
                .localMetadata(AqMessage.DELIVERY_DELAY_METADATA, MessageHeaderValue.IntegerValue.create(5))
                .localMetadata("private", "secret")
                .build();
        AqMessage<String> aq = AqMessage.builder("AQ delayed").deliveryDelay(Duration.ofSeconds(5)).build();
        messaging(manager).emitBatch("outgoing", MessageBatch.create(List.of(generic, aq)));

        assertThat("delayed messages must not be ready immediately", receiver.messages.poll(200, TimeUnit.MILLISECONDS),
                   nullValue());
        messaging(manager).emit("outgoing", Message.create("immediate"));
        assertThat(receiver.await().entity(), is("immediate"));
        List<AqMessage<?>> delayed = List.of(receiver.await(), receiver.await());
        assertThat(delayed.stream().map(Message::entity).toList(), containsInAnyOrder("generic delayed", "AQ delayed"));
        for (AqMessage<?> received : delayed) {
            assertReceivedOracleMetadata(received);
            assertThat(received.properties().containsKey(AqMessage.DELIVERY_DELAY_METADATA), is(false));
            assertThat(received.properties().containsKey("private"), is(false));
        }

        messaging(manager).emit("outgoing", delayed.getFirst());
        AqMessage<?> forwarded = receiver.messages.poll(2, TimeUnit.SECONDS);
        assertThat("forwarding must not restart the original five-second delay", forwarded, notNullValue());
        assertThat(forwarded.entity(), is(delayed.getFirst().entity()));
    }

    @Test
    void failedBatchRollsBackDelayedDatabaseEnqueue() throws Exception {
        ServiceRegistryManager manager = runtime("DELAY_ROLLBACK_QUEUE", "", "", null);
        MessageBatch<Object> batch = MessageBatch.create(List.of(
                AqMessage.<Object>builder("must roll back").deliveryDelay(Duration.ofSeconds(5)).build(),
                Message.<Object>create(Map.of("wrong", "queue type"))));

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                      () -> messaging(manager).emitBatch("outgoing", batch));

        assertThat(failure.outcomes().stream().map(item -> item.status()).toList(),
                   is(List.of(BatchItemStatus.FAILED, BatchItemStatus.FAILED)));
        try (var connection = database.dataSource().getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM DELAY_ROLLBACK_TAB")) {
            assertThat(result.next(), is(true));
            assertThat("rollback must remove the waiting message, not merely leave it unavailable", result.getInt(1), is(0));
        }
    }

    @Test
    void timeToLiveStartsAfterDeliveryDelay() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("DELAY_TTL_QUEUE", "time-to-live: PT2S", "", receiver);
        messaging(manager).emit("outgoing", AqMessage.builder("available after delay")
                .deliveryDelay(Duration.ofSeconds(5)).build());

        assertThat(receiver.messages.poll(200, TimeUnit.MILLISECONDS), nullValue());
        assertThat("the two-second TTL starts after the five-second delay",
                   receiver.await().entity(), is("available after delay"));
    }

    @Test
    void unconsumedDelayedMessageStillExpires() throws Exception {
        ServiceRegistryManager manager = runtime("DELAY_EXPIRED_QUEUE", "time-to-live: PT2S", "", null);
        messaging(manager).emit("outgoing", AqMessage.builder("expires after delay")
                .deliveryDelay(Duration.ofSeconds(3)).build());

        awaitMessageState("DELAY_EXPIRED_TAB", "EXPIRED");

        assertThat(receiveText("DELAY_EXPIRED_QUEUE", 200), nullValue());
    }

    @Test
    void adtMessagesSupportDeliveryDelay() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("DELAY_ADT_QUEUE", "payload-type: ADT", "", receiver);
        AqAdt payload = AqAdt.builder().typeName(database.schema() + ".AQ_TEST_EVENT")
                .addAttribute("delayed ADT").addAttribute(new BigDecimal("42")).build();
        String exceptionQueue = database.schema() + ".ADT_EXCEPTION_QUEUE";
        messaging(manager).emit("outgoing", AqMessage.builder(payload).deliveryDelay(Duration.ofSeconds(3))
                .exceptionQueue(exceptionQueue).build());

        assertThat(receiver.messages.poll(200, TimeUnit.MILLISECONDS), nullValue());
        AqMessage<?> received = receiver.await();
        assertThat(received.entity(), instanceOf(AqAdt.class));
        assertThat(((AqAdt) received.entity()).attributes(), is(payload.attributes()));
        assertReceivedOracleMetadata(received);
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_EXCEPTION_QUEUE_METADATA).orElseThrow(), is(exceptionQueue));
        assertThat(received.localMetadata().contains(AqMessage.RECEIVED_APPLICATION_ID_METADATA), is(false));
        assertThat(received.localMetadata().contains(AqMessage.RECEIVED_USER_ID_METADATA), is(false));
    }

    @Test
    @EnabledIfSystemProperty(named = "aq.test.xml", matches = "true")
    void xmlMessagesSupportDeliveryDelay() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("DELAY_XML_QUEUE", "payload-type: XML", "", receiver);
        AqXml payload = AqXml.builder().text("<event>delayed XML</event>").build();
        String exceptionQueue = database.schema() + ".XML_EXCEPTION_QUEUE";
        messaging(manager).emit("outgoing", AqMessage.builder(payload).deliveryDelay(Duration.ofSeconds(3))
                .exceptionQueue(exceptionQueue).build());

        assertThat(receiver.messages.poll(200, TimeUnit.MILLISECONDS), nullValue());
        AqMessage<?> received = receiver.await();
        assertThat(received.entity(), instanceOf(AqXml.class));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(((AqXml) received.entity()).text())));
        assertThat(document.getDocumentElement().getTextContent(), is("delayed XML"));
        assertReceivedOracleMetadata(received);
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_EXCEPTION_QUEUE_METADATA).orElseThrow(), is(exceptionQueue));
        assertThat(received.localMetadata().contains(AqMessage.RECEIVED_APPLICATION_ID_METADATA), is(false));
        assertThat(received.localMetadata().contains(AqMessage.RECEIVED_USER_ID_METADATA), is(false));
    }

    @Test
    void longIdleReceiveDrainsBeforeItsReceiveTimeout() throws Exception {
        AtomicInteger received = new AtomicInteger();
        var connector = AqConnector.builder()
                .name("idle-aq")
                .dataSource(database.dataSource())
                .schema(database.schema())
                .destination("IDLE_QUEUE")
                .receiveTimeout(Duration.ofSeconds(30))
                .closeTimeout(Duration.ofSeconds(2))
                .build();
        var incoming = connector.incoming(AqIncomingConfig.builder()
                                                   .connector("idle-aq")
                                                   .channelName("idle")
                                                   .build());
        var channel = MessagingChannel.create("idle", String.class);
        try (var graph = MessagingGraph.builder()
                .shutdownTimeout(Duration.ofMillis(500))
                .channel(channel)
                .incomingChannel(channel, incoming)
                .payloadSink(channel, _ -> received.incrementAndGet())
                .build()) {
            graph.start();
            database.awaitDequeue();
        }
        assertThat(received.get(), is(0));
    }

    @Test
    void incomingResumesAfterItsDatabaseSessionIsKilled() throws Exception {
        AqConnector connector = reconnectConnector("RECONNECT_QUEUE");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        var channel = MessagingChannel.create("reconnected", String.class);
        try (var graph = MessagingGraph.builder().channel(channel)
                .incomingChannel(channel, connector.incoming(AqIncomingConfig.builder()
                        .connector("reconnect-aq").channelName("reconnected").build()))
                .payloadSink(channel, messages::add).build()) {
            graph.start();
            sendText(connector, AqMessage.create("before failure"));
            assertThat(messages.poll(20, TimeUnit.SECONDS), is("before failure"));
            database.awaitDequeue();
            assertThat(database.killSessions(), greaterThan(0));

            sendText(connector, AqMessage.create("after recovery"));
            assertThat(messages.poll(20, TimeUnit.SECONDS), is("after recovery"));
        }
    }

    @Test
    void uncertainIncomingCommitAllowsDatabaseRedeliveryAfterReconnect() throws Exception {
        AqConnector connector = reconnectConnector("RECONNECT_QUEUE");
        BlockingQueue<AqMessage<?>> messages = new LinkedBlockingQueue<>();
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        var channel = MessagingChannel.create("reconnected", String.class);
        try (var graph = MessagingGraph.builder().channel(channel)
                .incomingChannel(channel, connector.incoming(AqIncomingConfig.builder()
                        .connector("reconnect-aq").channelName("reconnected").build()))
                .messageSink(channel, message -> {
                    messages.add((AqMessage<?>) message);
                    if (first.compareAndSet(true, false)) {
                        try {
                            assertThat(releaseHandler.await(20, TimeUnit.SECONDS), is(true));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted test handler", e);
                        }
                    }
                }).build()) {
            graph.start();
            sendText(connector, AqMessage.create("redeliver after disconnect"));
            AqMessage<?> original = messages.poll(20, TimeUnit.SECONDS);
            assertThat(original, notNullValue());
            assertThat(original.messageId().isPresent(), is(true));
            try {
                assertThat(database.killSessions(), greaterThan(0));
            } finally {
                releaseHandler.countDown();
            }
            AqMessage<?> redelivered = messages.poll(20, TimeUnit.SECONDS);
            assertThat(redelivered, notNullValue());
            assertThat(redelivered.entity(), is("redeliver after disconnect"));
            // Oracle can leave JMSRedelivered false after a killed session; the same native ID proves redelivery.
            assertThat(redelivered.messageId(), is(original.messageId()));

            sendText(connector, AqMessage.create("after redelivery"));
            AqMessage<?> next = messages.poll(20, TimeUnit.SECONDS);
            assertThat(next, notNullValue());
            assertThat(next.entity(), is("after redelivery"));
        } finally {
            releaseHandler.countDown();
        }
    }

    @Test
    void failedOutgoingSendIsNotReplayedAndTheChannelRecovers() throws Exception {
        AqConnector connector = reconnectConnector("RECONNECT_QUEUE");
        try (var outgoing = connector.outgoing(AqOutgoingConfig.builder()
                .connector("reconnect-aq").channelName("outgoing").build())) {
            outgoing.start();
            assertThat(database.killSessions(), greaterThan(0));
            assertThrows(BatchDeliveryException.class,
                         () -> outgoing.sendBatch(MessageBatch.create(AqMessage.create("failed send"))));

            outgoing.sendBatch(MessageBatch.create(AqMessage.create("after recovery")));
            TextMessage delivered = receiveText("RECONNECT_QUEUE", 1000);
            assertThat(delivered, notNullValue());
            assertThat(delivered.getText(), is("after recovery"));
            assertThat("the failed send must not be replayed", receiveText("RECONNECT_QUEUE", 200), nullValue());
        }
    }

    @Test
    void reconnectRestoresDurableSubscriptionAndSelector() throws Exception {
        AqConnector connector = reconnectConnector("RECONNECT_TOPIC");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        var channel = MessagingChannel.create("reconnected", String.class);
        var incoming = connector.incoming(AqIncomingConfig.builder()
                .connector("reconnect-aq").channelName("reconnected").destinationType(AqDestinationType.TOPIC)
                .durable(true).subscriptionName("RECONNECTED_SUBSCRIBER").messageSelector("region = 'EU'").build());
        try (var graph = MessagingGraph.builder().channel(channel).incomingChannel(channel, incoming)
                .payloadSink(channel, messages::add).build()) {
            graph.start();
            database.awaitDequeue();
            assertThat(database.killSessions(), greaterThan(0));
            try (var outgoing = connector.outgoing(AqOutgoingConfig.builder()
                    .connector("reconnect-aq").channelName("outgoing").destinationType(AqDestinationType.TOPIC).build())) {
                outgoing.start();
                outgoing.sendBatch(MessageBatch.create(AqMessage.builder("excluded").putProperty("region", "US").build()));
                outgoing.sendBatch(MessageBatch.create(AqMessage.builder("included").putProperty("region", "EU").build()));
                assertThat(messages.poll(20, TimeUnit.SECONDS), is("included"));
            }
        }
    }

    @Test
    void bytesRoundTripIsDetachedFromSender() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("BYTES_QUEUE", "", "", receiver);
        byte[] bytes = {0, 1, 2, (byte) 255};
        messaging(manager).emit("outgoing", AqMessage.create(bytes));
        bytes[0] = 99;

        assertThat(receiver.await().entity(), is(new byte[] {0, 1, 2, (byte) 255}));
    }

    @Test
    void mapRoundTripPreservesTypedEntries() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("MAP_QUEUE", "", "", receiver);
        Map<String, Object> body = Map.of("text", "value", "count", 42, "enabled", true);
        messaging(manager).emit("outgoing", AqMessage.create(body));

        assertThat(receiver.await().entity(), is(body));
    }

    @Test
    void convertedApplicationMapsCanBeReceivedForwardedAndSent() throws Exception {
        AqPayloadConverter converter = new AqPayloadConverter() {
            @Override
            public Object fromPayload(Object payload) {
                return Map.of(7, Map.of("label", payload), "items", List.of(payload));
            }

            @Override
            public Object toPayload(Object entity) {
                return ((Map<?, ?>) ((Map<?, ?>) entity).get(7)).get("label");
            }
        };
        AqConnector connector = AqConnector.builder().name("converted-aq").dataSource(database.dataSource())
                .schema(database.schema()).destination("TEXT_QUEUE").payloadConverter(converter).build();
        var channel = MessagingChannel.create("converted", Object.class);
        BlockingQueue<Message<?>> received = new LinkedBlockingQueue<>();
        try (var graph = MessagingGraph.builder().channel(channel)
                .incomingChannel(channel, connector.incoming(AqIncomingConfig.builder()
                        .connector("converted-aq").channelName("converted").build()))
                .messageSink(channel, received::add).build();
             var outgoing = connector.outgoing(AqOutgoingConfig.builder()
                     .connector("converted-aq").channelName("outgoing").build())) {
            graph.start();
            outgoing.start();
            Map<Object, Object> first = Map.of(7, Map.of("label", "first"), "items", List.of("first"));
            outgoing.sendBatch(MessageBatch.create(Message.create(first)));
            Message<?> incoming = received.poll(20, TimeUnit.SECONDS);
            assertThat(incoming, instanceOf(AqMessage.class));
            assertThat(incoming.entity(), is(first));
            assertThat(incoming.entity(), is(first));

            outgoing.sendBatch(MessageBatch.create(incoming));
            Message<?> forwarded = received.poll(20, TimeUnit.SECONDS);
            assertThat(forwarded, instanceOf(AqMessage.class));
            assertThat(forwarded.entity(), is(first));

            Map<Object, Object> second = Map.of(7, Map.of("label", "second"), "items", List.of("second"));
            outgoing.sendBatch(MessageBatch.create(AqMessage.mapBuilder(second).build()));
            Message<?> created = received.poll(20, TimeUnit.SECONDS);
            assertThat(created, instanceOf(AqMessage.class));
            assertThat(created.entity(), is(second));
        }
        assertThat("all converted messages must be committed", receiveText("TEXT_QUEUE", 200), nullValue());
    }

    @Test
    void objectRoundTripRequiresExplicitOptIn() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("OBJECT_QUEUE", "allow-object-messages: true", "", receiver);
        ArrayList<String> body = new ArrayList<>(List.of("first", "second"));
        messaging(manager).emit("outgoing", AqMessage.create(body));

        assertThat(receiver.await().entity(), is(body));
    }

    @Test
    void selectorLeavesNonmatchingMessageAvailable() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("SELECTOR_QUEUE", "", "message-selector: \"region = 'EU'\"", receiver);
        messaging(manager).emit("outgoing", AqMessage.builder("not selected").putProperty("region", "US").build());
        messaging(manager).emit("outgoing", AqMessage.builder("selected").putProperty("region", "EU").build());

        assertThat(receiver.await().entity(), is("selected"));
        manager.shutdown();
        managers.remove(manager);
        assertThat(receiveText("SELECTOR_QUEUE", 1000).getText(), is("not selected"));
    }

    @Test
    void explicitRecipientsDeliverToEachNamedConsumer() throws Exception {
        Receiver first = new Receiver();
        Receiver second = new Receiver();
        ServiceRegistryManager firstManager = runtime("RECIPIENT_QUEUE", "destination-type: TOPIC", "recipient-name: FIRST", first);
        runtime("RECIPIENT_QUEUE", "destination-type: TOPIC", "recipient-name: SECOND", second);
        messaging(firstManager).emit("outgoing", AqMessage.builder("selected recipients")
                .addRecipient(AqRecipient.builder().name("FIRST").build())
                .addRecipient(AqRecipient.builder().name("SECOND").build())
                .build());

        assertThat(first.await().entity(), is("selected recipients"));
        assertThat(second.await().entity(), is("selected recipients"));
    }

    @Test
    void durableSubscriptionRetainsMessagesWhileConsumerIsOffline() throws Exception {
        String subscription = "durable: true\nsubscription-name: RETAINED";
        ServiceRegistryManager initial = runtime("DURABLE_QUEUE", "destination-type: TOPIC", subscription, new Receiver());
        awaitDurableSubscription();
        initial.shutdown();
        managers.remove(initial);

        ServiceRegistryManager sender = runtime("DURABLE_QUEUE", "destination-type: TOPIC", "", null);
        messaging(sender).emit("outgoing", AqMessage.create("published while offline"));
        Receiver resumed = new Receiver();
        runtime("DURABLE_QUEUE", "destination-type: TOPIC", subscription, resumed);

        assertThat(resumed.await().entity(), is("published while offline"));
    }

    @Test
    void adtRoundTripRetainsSqlTypeAndOrderedAttributes() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("ADT_QUEUE", "payload-type: ADT", "", receiver);
        AqAdt event = AqAdt.builder().typeName(database.schema() + ".AQ_TEST_EVENT")
                .addAttribute("created")
                .addAttribute(BigDecimal.valueOf(42))
                .build();
        messaging(manager).emit("outgoing", AqMessage.builder(event).correlationId("adt-correlation").build());

        AqMessage<?> received = receiver.await();
        assertThat(received.entity(), instanceOf(AqAdt.class));
        AqAdt body = (AqAdt) received.entity();
        assertThat(body.typeName(), is(database.schema() + ".AQ_TEST_EVENT"));
        assertThat(body.attributes(), is(List.of("created", BigDecimal.valueOf(42))));
        assertThat(received.correlationId(), is(Optional.of("adt-correlation")));
    }

    @Test
    void adtLobValuesSurviveReceiveAndForwarding() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("LOB_QUEUE", "payload-type: ADT", "", receiver);
        messaging(manager).emit("outgoing", AqMessage.create(lobValue()));
        AqMessage<?> first = receiver.await();
        assertLobValue((AqAdt) first.entity());

        messaging(manager).emit("outgoing", first);
        assertLobValue((AqAdt) receiver.await().entity());
    }

    @Test
    void nestedAdtLobValuesSurviveReceiveAndForwarding() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("NESTED_LOB_QUEUE", "payload-type: ADT", "", receiver);
        AqAdt child = lobValue();
        AqAdt array = AqAdt.builder().typeName(database.schema() + ".AQ_TEST_LOB_ARRAY")
                .kind(AqAdt.Kind.ARRAY).addAttribute(child).build();
        AqAdt parent = AqAdt.builder().typeName(database.schema() + ".AQ_TEST_NESTED_LOBS")
                .addAttribute(child).addAttribute(array).build();
        messaging(manager).emit("outgoing", AqMessage.create(parent));
        AqMessage<?> first = receiver.await();
        assertNestedLobValue((AqAdt) first.entity());

        messaging(manager).emit("outgoing", first);
        assertNestedLobValue((AqAdt) receiver.await().entity());
    }

    @Test
    void oversizedAdtIsDroppedBeforeReceivingTheNextMessage() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("LOB_DROP_QUEUE", "payload-type: ADT", """
                max-body-bytes: 2
                failure:
                  on-exhausted: DROP
                """, receiver);
        messaging(manager).emit("outgoing", AqMessage.builder(binaryLobValue(new byte[]{1, 2, 3}))
                .correlationId("oversized").build());
        messaging(manager).emit("outgoing", AqMessage.builder(binaryLobValue(new byte[]{1}))
                .correlationId("valid").build());

        AqMessage<?> received = receiver.await();
        assertThat(received.correlationId(), is(Optional.of("valid")));
        assertThat(((AqBlob) ((AqAdt) received.entity()).attributes().getFirst()).bytes(), is(new byte[]{1}));
        manager.shutdown();
        managers.remove(manager);
        assertThat("both the dropped and processed messages must be settled", adtAvailable("LOB_DROP_QUEUE"), is(false));
    }

    @Test
    void oversizedAdtCanBeDeadLetteredWithItsMetadata() throws Exception {
        AqConnector connector = AqConnector.builder().name("lob-aq").dataSource(database.dataSource())
                .schema(database.schema()).destination("LOB_DLQ_QUEUE").payloadType(AqPayloadType.ADT).build();
        AqIncomingConfig incoming = AqIncomingConfig.builder().connector("lob-aq").channelName("incoming")
                .maxBodyBytes(2)
                .failure(failure -> failure.onExhausted(FailureDisposition.DEAD_LETTER)
                        .deadLetter(deadLetter -> deadLetter.channel("dead-letter")))
                .build();
        var source = MessagingChannel.create("incoming", AqAdt.class);
        var deadLetterChannel = MessagingChannel.create("dead-letter", AqAdt.class);
        BlockingQueue<Message<?>> delivered = new LinkedBlockingQueue<>();
        BlockingQueue<Message<?>> deadLetters = new LinkedBlockingQueue<>();
        try (var graph = MessagingGraph.builder().addConnector(connector)
                .channel(source).channel(deadLetterChannel).incoming(Map.of("incoming", incoming))
                .messageSink(source, delivered::add).messageSink(deadLetterChannel, deadLetters::add).build();
             var outgoing = connector.outgoing(AqOutgoingConfig.builder().connector("lob-aq").channelName("outgoing").build())) {
            graph.start();
            outgoing.start();
            outgoing.sendBatch(MessageBatch.create(AqMessage.builder(binaryLobValue(new byte[]{1, 2, 3}))
                                                           .correlationId("oversized").build()));
            outgoing.sendBatch(MessageBatch.create(AqMessage.builder(binaryLobValue(new byte[]{1}))
                                                           .correlationId("valid").build()));

            Message<?> failed = deadLetters.poll(20, TimeUnit.SECONDS);
            assertThat(failed, instanceOf(DeadLetterMessage.class));
            DeadLetterMessage<?> deadLetter = (DeadLetterMessage<?>) failed;
            assertThat(deadLetter.sourceChannel(), is("incoming"));
            assertThat(deadLetter.attempts(), is(1));
            assertThat(deadLetter.originalMessage(), instanceOf(AqMessage.class));
            AqMessage<?> original = (AqMessage<?>) deadLetter.originalMessage();
            assertThat(original.correlationId(), is(Optional.of("oversized")));
            assertThat(original.messageId().isPresent(), is(true));
            assertThat(original.bodyAvailable(), is(false));
            assertThrows(MessagingException.class, original::entity);

            Message<?> valid = delivered.poll(20, TimeUnit.SECONDS);
            assertThat(valid, instanceOf(AqMessage.class));
            assertThat(((AqMessage<?>) valid).correlationId(), is(Optional.of("valid")));
        }
        assertThat("dead-letter handling and subsequent processing must settle both messages",
                   adtAvailable("LOB_DLQ_QUEUE"), is(false));
    }

    @Test
    @EnabledIfSystemProperty(named = "aq.test.xml", matches = "true")
    void xmlTypeRoundTripRetainsXmlContent() throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("XML_QUEUE", "payload-type: XML", "", receiver);
        String xml = "<event><value>42</value></event>";
        messaging(manager).emit("outgoing", AqMessage.create(AqXml.builder().text(xml).build()));

        AqMessage<?> received = receiver.await();
        assertThat(received.entity(), instanceOf(AqXml.class));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(((AqXml) received.entity()).text())));
        assertThat(document.getDocumentElement().getTagName(), is("event"));
        assertThat(document.getElementsByTagName("value").item(0).getTextContent(), is("42"));
    }

    @Test
    void failedBatchRollsBackEarlierDatabaseEnqueue() throws Exception {
        ServiceRegistryManager manager = runtime("ROLLBACK_QUEUE", "", "", null);
        MessageBatch<Object> batch = MessageBatch.create(List.of(Message.<Object>create("must roll back"),
                                                                 Message.<Object>create(Map.of("wrong", "queue type"))));
        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                      () -> messaging(manager).emitBatch("outgoing", batch));
        assertThat(failure.outcomes().stream().map(item -> item.status()).toList(),
                   is(List.of(BatchItemStatus.FAILED, BatchItemStatus.FAILED)));
        assertThat("first enqueue must be undone by database rollback", receiveText("ROLLBACK_QUEUE", 200), nullValue());
    }

    @Test
    void failedDeliveryIsRedeliveredAfterRuntimeRestart() throws Exception {
        Receiver failed = new Receiver(_ -> {
            throw new IllegalStateException("Expected application failure");
        });
        ServiceRegistryManager first = runtime("ROLLBACK_QUEUE", "", """
                failure:
                  retry:
                    calls: 1
                  on-exhausted: FAIL
                """, failed);
        messaging(first).emit("outgoing", AqMessage.create("retry after failure"));
        assertThat(failed.await().entity(), is("retry after failure"));
        ServiceRegistryException failure = assertThrows(ServiceRegistryException.class, first::shutdown);
        assertThat(Arrays.stream(failure.getSuppressed())
                           .map(Throwable::getCause)
                           .anyMatch(BatchDeliveryException.class::isInstance),
                   is(true));
        managers.remove(first);

        Receiver recovered = new Receiver();
        runtime("ROLLBACK_QUEUE", "", "", recovered);
        AqMessage<?> redelivered = recovered.await();
        assertThat(redelivered.entity(), is("retry after failure"));
        assertThat(redelivered.redelivered(), is(Optional.of(true)));
    }

    private static MessagingRuntime messaging(ServiceRegistryManager manager) {
        return manager.registry().get(MessagingRuntime.class);
    }

    private static void assertReceivedOracleMetadata(AqMessage<?> message) {
        assertThat(message.localMetadata().contains(AqMessage.APPLICATION_ID_METADATA), is(false));
        assertThat(message.localMetadata().contains(AqMessage.EXCEPTION_QUEUE_METADATA), is(false));
        assertThat(message.localMetadata().contains(AqMessage.DELIVERY_DELAY_METADATA), is(false));
        assertThat(metadataInteger(message, AqMessage.RECEIVED_STATE_METADATA), greaterThanOrEqualTo(0L));
        assertThat(metadataInteger(message, AqMessage.RECEIVED_STATE_METADATA), lessThanOrEqualTo(3L));
        assertThat(metadataInteger(message, AqMessage.RECEIVED_TIMESTAMP_METADATA), greaterThan(0L));
        assertThat(message.localMetadata().value(AqMessage.RECEIVED_DELIVERY_DELAY_METADATA).orElseThrow(),
                   instanceOf(MessageHeaderValue.IntegerValue.class));
    }

    private static long metadataInteger(AqMessage<?> message, String key) {
        MessageHeaderValue value = message.localMetadata().value(key).orElseThrow();
        assertThat(value, instanceOf(MessageHeaderValue.IntegerValue.class));
        return ((MessageHeaderValue.IntegerValue) value).value().longValueExact();
    }

    private static void sendText(AqConnector connector, AqMessage<String> message) {
        try (var outgoing = connector.outgoing(AqOutgoingConfig.builder()
                .connector("reconnect-aq").channelName("test-sender").build())) {
            outgoing.start();
            outgoing.sendBatch(MessageBatch.create(message));
        }
    }

    private static void assertLobValue(AqAdt value) {
        assertThat(value.attributes().get(0), instanceOf(AqBlob.class));
        assertThat(((AqBlob) value.attributes().get(0)).bytes(), is(new byte[] {0, 1, 2, (byte) 255}));
        assertThat(value.attributes().get(1), instanceOf(AqClob.class));
        AqClob text = (AqClob) value.attributes().get(1);
        assertThat(text.text(), is("LOB content"));
        assertThat(text.national(), is(false));
        AqClob national = (AqClob) value.attributes().get(2);
        assertThat(national.text(), is("národní 漢字"));
        assertThat(national.national(), is(true));
    }

    private static void assertNestedLobValue(AqAdt value) {
        assertLobValue((AqAdt) value.attributes().get(0));
        AqAdt array = (AqAdt) value.attributes().get(1);
        assertThat(array.kind(), is(AqAdt.Kind.ARRAY));
        assertThat(array.attributes().size(), is(1));
        assertLobValue((AqAdt) array.attributes().getFirst());
    }

    private void verifyReplyTo(String destination, AqDestinationType type) throws Exception {
        Receiver receiver = new Receiver();
        ServiceRegistryManager manager = runtime("TEXT_QUEUE", "", "", receiver);
        String replyTo = database.schema() + "." + destination;
        messaging(manager).emit("outgoing", AqMessage.builder("request")
                .replyTo(replyTo)
                .replyToType(type)
                .build());
        AqMessage<?> first = receiver.await();
        assertThat(first.replyTo(), is(Optional.of(replyTo)));
        assertThat(first.replyToType(), is(type));

        messaging(manager).emit("outgoing", first);
        AqMessage<?> forwarded = receiver.await();
        assertThat(forwarded.entity(), is("request"));
        assertThat(forwarded.replyTo(), is(Optional.of(replyTo)));
        assertThat(forwarded.replyToType(), is(type));
    }

    private AqAdt lobValue() {
        return AqAdt.builder().typeName(database.schema() + ".AQ_TEST_LOBS")
                .addAttribute(AqBlob.builder().bytes(new byte[] {0, 1, 2, (byte) 255}).build())
                .addAttribute(AqClob.builder().text("LOB content").build())
                .addAttribute(AqClob.builder().text("národní 漢字").national(true).build())
                .build();
    }

    private AqConnector reconnectConnector(String destination) {
        return AqConnector.builder().name("reconnect-aq").dataSource(database.dataSource())
                .schema(database.schema()).destination(destination).receiveTimeout(Duration.ofSeconds(30))
                .reconnect(reconnect -> reconnect.initialDelay(Duration.ofMillis(50)).maxDelay(Duration.ofMillis(100))
                        .jitter(0).connectTimeout(Duration.ofSeconds(5)).waitTimeout(Duration.ofSeconds(10)))
                .build();
    }

    private Destination detachedDestination(String destination, AqDestinationType type) throws Exception {
        try (Connection connection = AQjmsFactory.getConnectionFactory(database.dataSource()).createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            AQjmsSession aq = (AQjmsSession) session;
            return type == AqDestinationType.QUEUE
                    ? aq.getQueue(database.schema(), destination) : aq.getTopic(database.schema(), destination);
        }
    }

    private AqAdt binaryLobValue(byte[] bytes) {
        return AqAdt.builder().typeName(database.schema() + ".AQ_TEST_LOBS")
                .addAttribute(AqBlob.builder().bytes(bytes).build())
                .addAttribute(AqAdt.NullValue.INSTANCE).addAttribute(AqAdt.NullValue.INSTANCE).build();
    }

    private boolean adtAvailable(String queue) throws Exception {
        try (Connection connection = AQjmsFactory.getConnectionFactory(database.dataSource()).createConnection();
             Session session = connection.createSession(true, Session.SESSION_TRANSACTED)) {
            connection.start();
            AQjmsSession aq = (AQjmsSession) session;
            OracleDataFactory factory = (value, _) -> (OracleData) _ -> value;
            try (MessageConsumer consumer = aq.createReceiver(aq.getQueue(database.schema(), queue), null, factory)) {
                return consumer.receive(200) != null;
            }
        }
    }

    private ServiceRegistryManager runtime(String queue, String options, String incomingOptions, Receiver receiver) {
        return runtime(queue, options, incomingOptions, receiver, false);
    }

    private ServiceRegistryManager runtime(String queue,
                                           String options,
                                           String incomingOptions,
                                           Receiver receiver,
                                           boolean declarative) {
        String incoming = receiver == null && !declarative ? "" : """
                  incoming:
                    incoming:
                      connector: test-aq
                %s
                """.formatted(incomingOptions.indent(6));
        String yaml = """
                messaging:
                  connector:
                    test-aq:
                      type: helidon-aq
                      destination: %s
                      schema: %s
                      receive-timeout: PT0.2S
                      close-timeout: PT5S
                %s
                %s
                  outgoing:
                    outgoing:
                      connector: test-aq
                """.formatted(queue, database.schema(), options.indent(6), incoming);
        List<ServiceDescriptor<?>> descriptors = ServiceDiscovery.create().allMetadata().stream()
                .<ServiceDescriptor<?>>map(DescriptorHandler::descriptor)
                .filter(descriptor -> declarative
                        || !descriptor.serviceType().fqName().startsWith(AqDeclarativeReceiver.class.getName()))
                .toList();
        ServiceRegistryConfig.Builder config = ServiceRegistryConfig.builder()
                .discoverServices(false)
                .discoverServicesFromServiceLoader(false)
                .addServiceDescriptors(descriptors)
                .putContractInstance(Config.class, Config.just(yaml, MediaTypes.APPLICATION_YAML))
                .putContractInstance(DataSource.class, database.dataSource());
        if (receiver != null) {
            config.putContractInstance(ConsumerRegistration.class, receiver);
        }
        ServiceRegistryManager manager = ServiceRegistryManager.create(config.build());
        managers.add(manager);
        messaging(manager);
        return manager;
    }

    private TextMessage receiveText(String queue, long timeout) throws Exception {
        try (Connection connection = AQjmsFactory.getConnectionFactory(database.dataSource()).createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            connection.start();
            try (MessageConsumer consumer = session.createConsumer(((AQjmsSession) session).getQueue(database.schema(), queue))) {
                return (TextMessage) consumer.receive(timeout);
            }
        }
    }

    private void awaitDurableSubscription() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (var connection = database.dataSource().getConnection(); var statement = connection.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM USER_QUEUE_SUBSCRIBERS"
                                                                 + " WHERE QUEUE_NAME = 'DURABLE_QUEUE'")) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        return;
                    }
                }
                TimeUnit.MILLISECONDS.sleep(50);
            }
        }
        throw new AssertionError("AQ durable subscription was not created");
    }

    private void awaitMessageState(String table, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (var connection = database.dataSource().getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM AQ$" + table + " WHERE MSG_STATE = ?")) {
            statement.setString(1, state);
            while (System.nanoTime() < deadline) {
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        return;
                    }
                }
                TimeUnit.MILLISECONDS.sleep(100);
            }
        }
        throw new AssertionError("AQ message did not reach state " + state);
    }

    private static final class Receiver implements ConsumerRegistration {
        private final BlockingQueue<AqMessage<?>> messages = new LinkedBlockingQueue<>();
        private final Consumer<AqMessage<?>> handler;

        private Receiver() {
            this(_ -> { });
        }

        private Receiver(Consumer<AqMessage<?>> handler) {
            this.handler = handler;
        }

        @Override
        public String channel() {
            return "incoming";
        }

        @Override
        public GenericType<?> payloadGenericType() {
            return GenericType.create(Object.class);
        }

        @Override
        public GenericType<?> envelopeGenericType() {
            return GenericType.create(AqMessage.class);
        }

        @Override
        public void dispatch(MessageBatch<?> batch) {
            for (Message<?> message : batch) {
                AqMessage<?> aqMessage = (AqMessage<?>) message;
                messages.add(aqMessage);
                handler.accept(aqMessage);
            }
        }

        AqMessage<?> await() throws InterruptedException {
            AqMessage<?> message = messages.poll(20, TimeUnit.SECONDS);
            assertThat("AQ message delivery", message, notNullValue());
            return message;
        }
    }
}
