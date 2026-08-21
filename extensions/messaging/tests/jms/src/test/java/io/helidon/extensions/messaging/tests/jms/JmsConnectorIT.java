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

package io.helidon.extensions.messaging.tests.jms;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import io.helidon.extensions.messaging.connectors.jms.JmsMessage;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.BytesReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.ForwardingReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.FtRetryPoisonReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.PoisonReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.SelectorReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.TextReceiver;
import io.helidon.extensions.messaging.tests.jms.JmsMessagingTypes.TextSender;
import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.MessagingRuntime;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryException;
import io.helidon.service.registry.ServiceRegistryManager;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.ObjectMessage;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JmsConnectorIT {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(300);

    @TempDir
    private Path brokerDataDirectory;

    private final List<ServiceRegistryManager> managers = new ArrayList<>();
    private ArtemisBroker broker;

    @BeforeEach
    void startBroker() throws Exception {
        broker = ArtemisBroker.create(brokerDataDirectory);
        broker.start();
    }

    @AfterEach
    void closeResources() throws Exception {
        Throwable failure = null;
        for (int i = managers.size() - 1; i >= 0; i--) {
            try {
                managers.get(i).shutdown();
            } catch (Throwable e) {
                failure = merge(failure, e);
            }
        }
        managers.clear();
        try {
            broker.close();
        } catch (Throwable e) {
            failure = merge(failure, e);
        }
        rethrow(failure);
    }

    @Test
    @Timeout(60)
    void queueRoundTripPreservesTextMetadataAndProperties() throws Exception {
        String queue = uniqueName("text");
        ServiceRegistryManager manager = registryManager(textQueueConfig(queue), TextSender.class, TextReceiver.class);
        ServiceRegistry registry = manager.registry();
        TextSender sender = registry.get(TextSender.class);
        TextReceiver receiver = registry.get(TextReceiver.class);
        registry.get(MessagingRuntime.class);

        sender.send(JmsMessage.<String>builder("queue message")
                            .correlationId("correlation-42")
                            .type("order-created")
                            .property("region", "EU")
                            .property("attempt", 7)
                            .property("JMSXGroupID", "order-group")
                            .property("JMSXGroupSeq", 3)
                            .build());

        JmsMessage<String> received = receiver.awaitMessage(WAIT_TIMEOUT);
        assertThat("queue delivery", received, notNullValue());
        assertThat(received.entity(), is("queue message"));
        assertThat(received.messageId().isPresent(), is(true));
        assertThat(received.correlationId(), is(Optional.of("correlation-42")));
        assertThat(received.type(), is(Optional.of("order-created")));
        assertThat(received.timestamp().orElseThrow() > 0, is(true));
        assertThat(received.expiration().orElseThrow(), is(0L));
        assertThat(received.deliveryTime().isPresent(), is(true));
        assertThat(received.priority().orElseThrow(), is(4));
        assertThat(received.redelivered(), is(Optional.of(false)));
        assertThat(received.jmsProperties(), is(Map.of("region", "EU",
                                                       "attempt", 7,
                                                       "JMSXGroupID", "order-group",
                                                       "JMSXGroupSeq", 3)));
        assertThat(received.headers(), is(Map.of("region", "EU",
                                                "attempt", "7",
                                                "JMSXGroupID", "order-group",
                                                "JMSXGroupSeq", "3")));
    }

    @Test
    @Timeout(60)
    void queueRoundTripPreservesBytesAndEverySupportedPropertyType() throws Exception {
        String queue = uniqueName("bytes");
        ServiceRegistryManager manager = registryManager(bytesQueueConfig(queue), BytesReceiver.class);
        ServiceRegistry registry = manager.registry();
        BytesReceiver receiver = registry.get(BytesReceiver.class);
        MessagingRuntime runtime = registry.get(MessagingRuntime.class);
        byte[] body = new byte[] {0, 1, 2, (byte) 0xFF};

        runtime.emit(JmsMessagingTypes.BYTES_OUTGOING_CHANNEL,
                     JmsMessage.<byte[]>builder(body)
                             .property("boolean_value", true)
                             .property("byte_value", (byte) 1)
                             .property("short_value", (short) 2)
                             .property("integer_value", 3)
                             .property("long_value", 4L)
                             .property("float_value", 5.5F)
                             .property("double_value", 6.5D)
                             .property("string_value", "seven")
                             .build());
        body[0] = 99;

        JmsMessage<byte[]> received = receiver.awaitMessage(WAIT_TIMEOUT);
        assertThat("bytes delivery", received, notNullValue());
        assertArrayEquals(new byte[] {0, 1, 2, (byte) 0xFF}, received.entity());
        assertThat(received.jmsProperties(), is(Map.of(
                "boolean_value", true,
                "byte_value", (byte) 1,
                "short_value", (short) 2,
                "integer_value", 3,
                "long_value", 4L,
                "float_value", 5.5F,
                "double_value", 6.5D,
                "string_value", "seven")));
    }

    @Test
    @Timeout(60)
    void topicSelectorDeliversOnlyMatchingMessages() throws Exception {
        String topic = uniqueName("selector");
        ServiceRegistryManager manager = registryManager(selectorTopicConfig(topic), SelectorReceiver.class);
        ServiceRegistry registry = manager.registry();
        SelectorReceiver receiver = registry.get(SelectorReceiver.class);
        registry.get(MessagingRuntime.class);

        JmsTestClient.sendText(connectionFactory(), topic, true, "not selected",
                               message -> setStringProperty(message, "region", "US"));
        JmsTestClient.sendText(connectionFactory(), topic, true, "selected",
                               message -> setStringProperty(message, "region", "EU"));

        JmsMessage<String> received = receiver.awaitMessage(WAIT_TIMEOUT);
        assertThat("selected topic delivery", received, notNullValue());
        assertThat(received.entity(), is("selected"));
        assertThat(received.jmsProperties().get("region"), is("EU"));
        assertThat("non-matching topic delivery", receiver.awaitMessage(POLL_TIMEOUT), nullValue());
    }

    @Test
    @Timeout(60)
    void processorForwardsPayloadMetadataAndPropertiesToPhysicalOutput() throws Exception {
        String incomingQueue = uniqueName("forward-in");
        String outgoingQueue = uniqueName("forward-out");
        ServiceRegistryManager manager = registryManager(forwardingConfig(incomingQueue, outgoingQueue),
                                                         ForwardingReceiver.class);
        ServiceRegistry registry = manager.registry();
        ForwardingReceiver receiver = registry.get(ForwardingReceiver.class);
        registry.get(MessagingRuntime.class);

        JmsTestClient.sendText(connectionFactory(), incomingQueue, false, "source message", message -> {
            setCorrelationId(message, "source-correlation");
            setType(message, "source-type");
            setStringProperty(message, "route", "original");
            setStringProperty(message, "source_prop", "source-value");
        });

        JmsMessage<String> incoming = receiver.awaitDelivery(WAIT_TIMEOUT);
        assertThat("processor input", incoming, notNullValue());
        assertThat(incoming.entity(), is("source message"));
        assertThat(incoming.correlationId(), is(Optional.of("source-correlation")));
        assertThat(incoming.type(), is(Optional.of("source-type")));

        TextMessage forwarded = JmsTestClient.receiveText(connectionFactory(), outgoingQueue, WAIT_TIMEOUT);
        assertThat("physical forwarded message", forwarded, notNullValue());
        assertThat(forwarded.getText(), is("forwarded: source message"));
        assertThat(forwarded.getJMSCorrelationID(), is("source-correlation"));
        assertThat(forwarded.getJMSType(), is("forwarded-message"));
        assertThat(forwarded.getStringProperty("route"), is("forwarded"));
        assertThat(forwarded.getStringProperty("processor"), is("jms-forwarder"));
        assertThat(forwarded.getStringProperty("source_prop"), is("source-value"));
    }

    @Test
    @Timeout(60)
    void faultToleranceRetryExhaustionIsOneMessagingAttemptBeforeDeadLetter() throws Exception {
        String incomingQueue = uniqueName("ft-dead-letter-source");
        String deadLetterQueue = uniqueName("ft-dead-letter");
        ServiceRegistryManager manager = registryManager(deadLetterConfig(incomingQueue, deadLetterQueue, 1),
                                                         FtRetryPoisonReceiver.class);
        ServiceRegistry registry = manager.registry();
        FtRetryPoisonReceiver receiver = registry.get(FtRetryPoisonReceiver.class);
        registry.get(MessagingRuntime.class);

        JmsTestClient.sendText(connectionFactory(), incomingQueue, false, "poison", message -> {
        });

        TextMessage deadLetter = JmsTestClient.receiveText(connectionFactory(), deadLetterQueue, WAIT_TIMEOUT);
        assertThat("physical fault-tolerance dead-letter message", deadLetter, notNullValue());
        assertThat(deadLetter.getText(), is("poison"));
        assertThat(deadLetter.getStringProperty(DeadLetterMessage.SOURCE_CHANNEL_HEADER),
                   is(JmsMessagingTypes.DEAD_LETTER_INCOMING_CHANNEL));
        assertThat("one outer messaging attempt",
                   deadLetter.getStringProperty(DeadLetterMessage.ATTEMPTS_HEADER),
                   is("1"));
        assertThat(deadLetter.getStringProperty(DeadLetterMessage.FAILURE_TYPE_HEADER),
                   is(IllegalStateException.class.getName()));
        assertThat(deadLetter.getStringProperty(DeadLetterMessage.FAILURE_MESSAGE_HEADER),
                   is(FtRetryPoisonReceiver.FAILURE_MESSAGE));
        await(() -> broker.queueDeliveringCount(incomingQueue) == 0
                        && broker.queuePendingMessageCount(incomingQueue) == 0,
              WAIT_TIMEOUT,
              "source JMS delivery was not settled after fault-tolerance dead-letter publication");
        assertThat("fault-tolerance method calls", receiver.attemptCount(), is(FtRetryPoisonReceiver.CALLS));
        await(() -> broker.queueDeliveringCount(deadLetterQueue) == 0,
              WAIT_TIMEOUT,
              "fault-tolerance dead-letter delivery was not acknowledged");
        assertThat("duplicate fault-tolerance dead-letter message",
                   broker.queuePendingMessageCount(deadLetterQueue),
                   is(0L));
    }

    @Test
    @Timeout(60)
    void terminalDeadLetterPublishesPhysicalMessageThenContinues() throws Exception {
        String incomingQueue = uniqueName("dead-letter-source");
        String deadLetterQueue = uniqueName("dead-letter");
        ServiceRegistryManager manager = registryManager(deadLetterConfig(incomingQueue, deadLetterQueue, 3),
                                                         PoisonReceiver.class);
        ServiceRegistry registry = manager.registry();
        PoisonReceiver receiver = registry.get(PoisonReceiver.class);
        registry.get(MessagingRuntime.class);
        JmsTestClient.sendText(connectionFactory(), incomingQueue, false, "poison",
                               message -> setStringProperty(message, "source_prop", "source-value"));
        JmsTestClient.sendText(connectionFactory(), incomingQueue, false, "after poison", message -> {
        });

        try {
            assertThat("terminal poison attempt", receiver.awaitFinalPoisonAttempt(WAIT_TIMEOUT), is(true));
            assertThat(receiver.poisonAttemptCount(), is(3));
            assertThat("dead letter before terminal failure",
                       JmsTestClient.receiveText(connectionFactory(), deadLetterQueue, POLL_TIMEOUT),
                       nullValue());

            receiver.allowFinalPoisonFailure();

            TextMessage deadLetter = JmsTestClient.receiveText(connectionFactory(), deadLetterQueue, WAIT_TIMEOUT);
            assertThat("physical dead-letter message", deadLetter, notNullValue());
            assertThat(deadLetter.getText(), is("poison"));
            assertThat(deadLetter.getStringProperty("source_prop"), is("source-value"));
            assertThat(deadLetter.getStringProperty(DeadLetterMessage.SOURCE_CHANNEL_HEADER),
                       is(JmsMessagingTypes.DEAD_LETTER_INCOMING_CHANNEL));
            assertThat(deadLetter.getStringProperty(DeadLetterMessage.ATTEMPTS_HEADER), is("3"));
            assertThat(deadLetter.getStringProperty(DeadLetterMessage.FAILURE_TYPE_HEADER),
                       is(IllegalStateException.class.getName()));
            assertThat(deadLetter.getStringProperty(DeadLetterMessage.FAILURE_MESSAGE_HEADER),
                       is("Expected poison JMS message failure"));

            JmsMessage<String> continued = receiver.awaitSuccessfulMessage(WAIT_TIMEOUT);
            assertThat("delivery after dead letter", continued, notNullValue());
            assertThat(continued.entity(), is("after poison"));
        } finally {
            receiver.allowFinalPoisonFailure();
        }
    }

    @Test
    @Timeout(60)
    void terminalDropSettlesPoisonAndContinuesWithNextPhysicalMessage() throws Exception {
        String queue = uniqueName("drop");
        ServiceRegistryManager manager = registryManager(dropConfig(queue), PoisonReceiver.class);
        ServiceRegistry registry = manager.registry();
        PoisonReceiver receiver = registry.get(PoisonReceiver.class);
        registry.get(MessagingRuntime.class);
        JmsTestClient.sendText(connectionFactory(), queue, false, "poison", message -> {
        });
        JmsTestClient.sendText(connectionFactory(), queue, false, "after poison", message -> {
        });

        try {
            assertThat("terminal poison attempt", receiver.awaitFinalPoisonAttempt(WAIT_TIMEOUT), is(true));
            assertThat(receiver.poisonAttemptCount(), is(3));
            assertThat("next delivery before terminal drop",
                       receiver.awaitSuccessfulMessage(POLL_TIMEOUT),
                       nullValue());

            receiver.allowFinalPoisonFailure();

            JmsMessage<String> continued = receiver.awaitSuccessfulMessage(WAIT_TIMEOUT);
            assertThat("delivery after terminal drop", continued, notNullValue());
            assertThat(continued.entity(), is("after poison"));
            assertThat(receiver.poisonAttemptCount(), is(3));
        } finally {
            receiver.allowFinalPoisonFailure();
        }
    }

    @Test
    @Timeout(60)
    void disabledObjectMessageDropSettlesAndContinues() throws Exception {
        String queue = uniqueName("disabled-object-drop");
        ServiceRegistryManager manager = registryManager(dropConfig(queue), PoisonReceiver.class);
        ServiceRegistry registry = manager.registry();
        PoisonReceiver receiver = registry.get(PoisonReceiver.class);
        registry.get(MessagingRuntime.class);

        JmsTestClient.sendObject(connectionFactory(), queue, "untrusted-object", message -> {
        });
        JmsTestClient.sendText(connectionFactory(), queue, false, "after disabled object", message -> {
        });

        JmsMessage<String> continued = receiver.awaitSuccessfulMessage(WAIT_TIMEOUT);
        assertThat("delivery after dropped ObjectMessage", continued, notNullValue());
        assertThat(continued.entity(), is("after disabled object"));
        assertThat("disabled ObjectMessage did not reach the handler", receiver.poisonAttemptCount(), is(0));
        await(() -> broker.queueDeliveringCount(queue) == 0
                        && broker.queuePendingMessageCount(queue) == 0,
              WAIT_TIMEOUT,
              "disabled ObjectMessage was not settled after drop");
    }

    @Test
    @Timeout(60)
    void disabledObjectMessageDeadLettersMetadataAndContinues() throws Exception {
        String incomingQueue = uniqueName("disabled-object-dead-letter-source");
        String deadLetterQueue = uniqueName("disabled-object-dead-letter");
        ServiceRegistryManager manager = registryManager(deadLetterConfig(incomingQueue, deadLetterQueue, 3),
                                                         PoisonReceiver.class);
        ServiceRegistry registry = manager.registry();
        PoisonReceiver receiver = registry.get(PoisonReceiver.class);
        registry.get(MessagingRuntime.class);

        JmsTestClient.sendObject(connectionFactory(), incomingQueue, "untrusted-object",
                                 message -> message.setStringProperty("source_prop", "source-value"));
        JmsTestClient.sendText(connectionFactory(), incomingQueue, false, "after disabled object", message -> {
        });

        Message deadLetter = JmsTestClient.receive(connectionFactory(), deadLetterQueue, false, WAIT_TIMEOUT);
        assertThat("physical ObjectMessage dead letter", deadLetter, notNullValue());
        assertThat("unsafe dead-letter body was not propagated", deadLetter instanceof ObjectMessage, is(false));
        assertThat("metadata-only dead letter has no text body", deadLetter instanceof TextMessage, is(false));
        assertThat(deadLetter.getStringProperty("source_prop"), is("source-value"));
        assertThat(deadLetter.getStringProperty(DeadLetterMessage.SOURCE_CHANNEL_HEADER),
                   is(JmsMessagingTypes.DEAD_LETTER_INCOMING_CHANNEL));
        assertThat(deadLetter.getStringProperty(DeadLetterMessage.ATTEMPTS_HEADER), is("3"));
        assertThat(deadLetter.getStringProperty(DeadLetterMessage.FAILURE_TYPE_HEADER),
                   is(MessagingException.class.getName()));
        assertThat(deadLetter.getStringProperty(DeadLetterMessage.FAILURE_MESSAGE_HEADER),
                   containsString("ObjectMessage is disabled"));

        JmsMessage<String> continued = receiver.awaitSuccessfulMessage(WAIT_TIMEOUT);
        assertThat("delivery after dead-lettered ObjectMessage", continued, notNullValue());
        assertThat(continued.entity(), is("after disabled object"));
        assertThat("disabled ObjectMessage did not reach the handler", receiver.poisonAttemptCount(), is(0));
        await(() -> broker.queueDeliveringCount(incomingQueue) == 0
                        && broker.queuePendingMessageCount(incomingQueue) == 0,
              WAIT_TIMEOUT,
              "disabled ObjectMessage was not settled after dead-letter publication");
    }

    @Test
    @Timeout(60)
    void unacknowledgedMessageIsProviderRedeliveredAfterRegistryRestart() throws Exception {
        String queue = uniqueName("redelivery");
        ServiceRegistryManager firstManager = registryManager(failingRedeliveryConfig(queue), PoisonReceiver.class);
        ServiceRegistry firstRegistry = firstManager.registry();
        PoisonReceiver failingReceiver = firstRegistry.get(PoisonReceiver.class);
        firstRegistry.get(MessagingRuntime.class);
        JmsTestClient.sendText(connectionFactory(), queue, false, "poison",
                               message -> setStringProperty(message, "source_prop", "restart-value"));

        await(() -> failingReceiver.poisonAttemptCount() == 1,
              WAIT_TIMEOUT,
              "first failed physical JMS delivery");
        assertThat(failingReceiver.poisonDeliveries().size(), is(1));
        assertThat(failingReceiver.poisonDeliveries().getFirst().redelivered(), is(Optional.of(false)));
        ServiceRegistryException shutdownFailure = assertThrows(ServiceRegistryException.class,
                                                                () -> shutdown(firstManager));
        assertThat("expected terminal handler failure on first registry",
                   hasCause(shutdownFailure, IllegalStateException.class, "Expected poison JMS message failure"),
                   is(true));

        ServiceRegistryManager secondManager = registryManager(textIncomingConfig(queue), TextReceiver.class);
        ServiceRegistry secondRegistry = secondManager.registry();
        TextReceiver succeedingReceiver = secondRegistry.get(TextReceiver.class);
        secondRegistry.get(MessagingRuntime.class);

        JmsMessage<String> redelivered = succeedingReceiver.awaitMessage(WAIT_TIMEOUT);
        assertThat("provider redelivery after registry restart", redelivered, notNullValue());
        assertThat(redelivered.entity(), is("poison"));
        assertThat(redelivered.jmsProperties().get("source_prop"), is("restart-value"));
        assertThat(redelivered.redelivered(), is(Optional.of(true)));
    }

    @Test
    @Timeout(60)
    void transactedMessageIsRolledBackThenCommittedAfterRegistryRestart() throws Exception {
        String queue = uniqueName("transacted-redelivery");
        ServiceRegistryManager firstManager = registryManager(failingTransactedRedeliveryConfig(queue),
                                                              PoisonReceiver.class);
        ServiceRegistry firstRegistry = firstManager.registry();
        PoisonReceiver failingReceiver = firstRegistry.get(PoisonReceiver.class);
        firstRegistry.get(MessagingRuntime.class);
        JmsTestClient.sendText(connectionFactory(), queue, false, "poison",
                               message -> setStringProperty(message, "source_prop", "transaction-value"));

        await(() -> failingReceiver.poisonAttemptCount() == 1,
              WAIT_TIMEOUT,
              "failed transacted JMS delivery");
        ServiceRegistryException shutdownFailure = assertThrows(ServiceRegistryException.class,
                                                                () -> shutdown(firstManager));
        assertThat("expected terminal handler failure on first transacted registry",
                   hasCause(shutdownFailure, IllegalStateException.class, "Expected poison JMS message failure"),
                   is(true));

        ServiceRegistryManager secondManager = registryManager(transactedTextIncomingConfig(queue), TextReceiver.class);
        ServiceRegistry secondRegistry = secondManager.registry();
        TextReceiver succeedingReceiver = secondRegistry.get(TextReceiver.class);
        secondRegistry.get(MessagingRuntime.class);

        JmsMessage<String> redelivered = succeedingReceiver.awaitMessage(WAIT_TIMEOUT);
        assertThat("provider redelivery after transaction rollback", redelivered, notNullValue());
        assertThat(redelivered.entity(), is("poison"));
        assertThat(redelivered.jmsProperties().get("source_prop"), is("transaction-value"));
        assertThat(redelivered.redelivered(), is(Optional.of(true)));
        await(() -> broker.queueDeliveringCount(queue) == 0
                        && broker.queuePendingMessageCount(queue) == 0,
              WAIT_TIMEOUT,
              "successful transacted JMS delivery was not committed");

        shutdown(secondManager);
        assertThat("successful transacted delivery must be committed",
                   JmsTestClient.receiveText(connectionFactory(), queue, POLL_TIMEOUT),
                   nullValue());
    }

    private ServiceRegistryManager registryManager(String yaml, Class<?>... fixtureTypes) {
        ServiceRegistryManager manager = JmsScenarioRegistry.create(yaml, connectionFactory(), fixtureTypes);
        managers.add(manager);
        return manager;
    }

    private void shutdown(ServiceRegistryManager manager) {
        managers.remove(manager);
        manager.shutdown();
    }

    private ConnectionFactory connectionFactory() {
        return broker.connectionFactory();
    }

    private static String textQueueConfig(String queue) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                    outgoing:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        close-timeout: PT2S
                """.formatted(JmsMessagingTypes.TEXT_INCOMING_CHANNEL,
                               queue,
                               JmsMessagingTypes.TEXT_OUTGOING_CHANNEL,
                               queue);
    }

    private static String bytesQueueConfig(String queue) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                    outgoing:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        close-timeout: PT2S
                """.formatted(JmsMessagingTypes.BYTES_INCOMING_CHANNEL,
                               queue,
                               JmsMessagingTypes.BYTES_OUTGOING_CHANNEL,
                               queue);
    }

    private static String selectorTopicConfig(String topic) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: TOPIC
                        message-selector: "region IN ('EU', 'CZ')"
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                """.formatted(JmsMessagingTypes.SELECTOR_INCOMING_CHANNEL, topic);
    }

    private static String forwardingConfig(String incomingQueue, String outgoingQueue) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                    outgoing:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        close-timeout: PT2S
                """.formatted(JmsMessagingTypes.FORWARDING_INCOMING_CHANNEL,
                               incomingQueue,
                               JmsMessagingTypes.FORWARDING_OUTGOING_CHANNEL,
                               outgoingQueue);
    }

    private static String deadLetterConfig(String incomingQueue, String deadLetterQueue, int maxAttempts) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                        failure:
                          retry:
                            delay: PT0.02S
                            max-attempts: %d
                          on-exhausted: DEAD_LETTER
                          dead-letter:
                            channel: %s
                    outgoing:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        close-timeout: PT2S
                """.formatted(JmsMessagingTypes.DEAD_LETTER_INCOMING_CHANNEL,
                               incomingQueue,
                               maxAttempts,
                               JmsMessagingTypes.DEAD_LETTER_OUTGOING_CHANNEL,
                               JmsMessagingTypes.DEAD_LETTER_OUTGOING_CHANNEL,
                               deadLetterQueue);
    }

    private static String dropConfig(String queue) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                        failure:
                          retry:
                            delay: PT0.02S
                            max-attempts: 3
                          on-exhausted: DROP
                """.formatted(JmsMessagingTypes.DEAD_LETTER_INCOMING_CHANNEL, queue);
    }

    private static String failingRedeliveryConfig(String queue) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                        failure:
                          retry:
                            delay: PT0.02S
                            max-attempts: 1
                          on-exhausted: FAIL
                """.formatted(JmsMessagingTypes.DEAD_LETTER_INCOMING_CHANNEL, queue);
    }

    private static String failingTransactedRedeliveryConfig(String queue) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        transacted: true
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                        failure:
                          retry:
                            delay: PT0.02S
                            max-attempts: 1
                          on-exhausted: FAIL
                """.formatted(JmsMessagingTypes.DEAD_LETTER_INCOMING_CHANNEL, queue);
    }

    private static String textIncomingConfig(String queue) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                """.formatted(JmsMessagingTypes.TEXT_INCOMING_CHANNEL, queue);
    }

    private static String transactedTextIncomingConfig(String queue) {
        return """
                helidon:
                  messaging:
                    incoming:
                      %s:
                        connector: helidon-jms
                        destination: "%s"
                        destination-type: QUEUE
                        transacted: true
                        receive-timeout: PT0.05S
                        close-timeout: PT2S
                """.formatted(JmsMessagingTypes.TEXT_INCOMING_CHANNEL, queue);
    }

    private static String uniqueName(String prefix) {
        return prefix + '-' + Long.toUnsignedString(System.nanoTime());
    }

    private static void setCorrelationId(TextMessage message, String value) {
        try {
            message.setJMSCorrelationID(value);
        } catch (JMSException e) {
            throw new IllegalStateException("Cannot set test JMS correlation ID", e);
        }
    }

    private static void setType(TextMessage message, String value) {
        try {
            message.setJMSType(value);
        } catch (JMSException e) {
            throw new IllegalStateException("Cannot set test JMS type", e);
        }
    }

    private static void setStringProperty(TextMessage message, String name, String value) {
        try {
            message.setStringProperty(name, value);
        } catch (JMSException e) {
            throw new IllegalStateException("Cannot set test JMS property " + name, e);
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout, String description) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(description, condition.getAsBoolean(), is(true));
    }

    private static Throwable merge(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type, String message) {
        return hasCause(failure, type, message, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean hasCause(Throwable failure,
                                    Class<? extends Throwable> type,
                                    String message,
                                    Set<Throwable> visited) {
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (type.isInstance(current) && message.equals(current.getMessage())) {
                return true;
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (hasCause(suppressed, type, message, visited)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new AssertionError("Cannot close JMS integration-test resources", failure);
        }
    }
}
