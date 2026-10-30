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

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;

import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessagingException;

import oracle.jdbc.OracleData;
import oracle.jms.AQjmsAdtMessage;
import oracle.jms.AQjmsSession;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class AqOracleMetadataTest {
    @Test
    void convenienceMethodsUseMetadataAndPreserveOtherEntries() {
        var builder = AqMessage.builder("body").localMetadata("private", "secret")
                .deliveryDelay(Duration.ofSeconds(5)).applicationId("orders").exceptionQueue("APP.ERRORS");
        AqMessage<String> first = builder.build();
        AqMessage<String> second = builder.applicationId("updated").exceptionQueue("APP.OTHER_ERRORS").build();

        assertThat(first.localMetadata().text(AqMessage.APPLICATION_ID_METADATA).orElseThrow(), is("orders"));
        assertThat(first.localMetadata().text(AqMessage.EXCEPTION_QUEUE_METADATA).orElseThrow(), is("APP.ERRORS"));
        assertThat(first.localMetadata().text("private").orElseThrow(), is("secret"));
        assertThat(first.localMetadata().value(AqMessage.DELIVERY_DELAY_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(5)));
        assertThat(second.localMetadata().text(AqMessage.APPLICATION_ID_METADATA).orElseThrow(), is("updated"));
        assertThat(second.localMetadata().text(AqMessage.EXCEPTION_QUEUE_METADATA).orElseThrow(), is("APP.OTHER_ERRORS"));
        assertThat(first.headers().entries().isEmpty(), is(true));
        assertThat(first.properties().isEmpty(), is(true));
    }

    @Test
    void convenienceMethodsRejectMissingAndBlankValues() {
        assertThrows(NullPointerException.class, () -> AqMessage.builder("body").applicationId(null));
        assertThrows(NullPointerException.class, () -> AqMessage.builder("body").exceptionQueue(null));
        for (String value : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class, () -> AqMessage.builder("body").applicationId(value));
            assertThrows(IllegalArgumentException.class, () -> AqMessage.builder("body").exceptionQueue(value));
        }
    }

    @Test
    void genericAndAqMessagesApplyNativeSendOptionsWithoutPublishingMetadataKeys() throws Exception {
        for (Message<?> message : List.of(
                Message.builder("body").localMetadata(AqMessage.APPLICATION_ID_METADATA, "orders")
                        .localMetadata(AqMessage.EXCEPTION_QUEUE_METADATA, "APP.ERRORS").build(),
                AqMessage.builder("body").applicationId("orders").exceptionQueue("APP.ERRORS").build())) {
            Session session = mock(Session.class);
            TextMessage outgoing = mock(TextMessage.class);
            when(session.createTextMessage("body")).thenReturn(outgoing);

            AqMessageMapper.toNative(session, message, config(AqPayloadType.JMS));

            verify(outgoing).setStringProperty("JMSXAppID", "orders");
            verify(outgoing).setStringProperty("JMS_OracleExcpQ", "APP.ERRORS");
            verify(outgoing, never()).setObjectProperty(anyString(), any());
            verify(outgoing, never()).setStringProperty(eq(AqMessage.APPLICATION_ID_METADATA), anyString());
            verify(outgoing, never()).setStringProperty(eq(AqMessage.EXCEPTION_QUEUE_METADATA), anyString());
        }
    }

    @Test
    void invalidSendMetadataFailsBeforeCreatingANativeMessage() {
        for (String key : List.of(AqMessage.APPLICATION_ID_METADATA, AqMessage.EXCEPTION_QUEUE_METADATA)) {
            for (MessageHeaderValue value : List.of(MessageHeaderValue.NullValue.create(),
                                                    MessageHeaderValue.IntegerValue.create(7),
                                                    MessageHeaderValue.TextValue.create(""),
                                                    MessageHeaderValue.TextValue.create(" \t"))) {
                Session session = mock(Session.class);
                Message<String> message = Message.builder("body").localMetadata(key, value).build();

                assertThrows(MessagingException.class,
                             () -> AqMessageMapper.toNative(session, message, config(AqPayloadType.JMS)));

                verifyZeroInteractions(session);
            }
        }
    }

    @Test
    void applicationIdIsRejectedForAdtAndXmlBeforeCreatingPayloadResources() {
        for (AqPayloadType type : List.of(AqPayloadType.ADT, AqPayloadType.XML)) {
            Session session = mock(Session.class);
            Message<String> message = Message.builder("body").localMetadata(AqMessage.APPLICATION_ID_METADATA, "orders").build();

            assertThrows(MessagingException.class, () -> AqMessageMapper.toNative(session, message, config(type)));

            verifyZeroInteractions(session);
        }
    }

    @Test
    void exceptionQueueDoesNotRequireJmsPayloadHeaders() throws Exception {
        AQjmsSession session = mock(AQjmsSession.class);
        AQjmsAdtMessage outgoing = mock(AQjmsAdtMessage.class);
        when(session.createORAMessage(any(OracleData.class))).thenReturn(outgoing);
        AqAdt payload = AqAdt.builder().typeName("APP.EVENT_TYPE").addAttribute("event").build();

        AqMessageMapper.toNative(session, AqMessage.builder(payload).exceptionQueue("APP.ERRORS").build(),
                                 config(AqPayloadType.ADT));

        verify(outgoing).setStringProperty("JMS_OracleExcpQ", "APP.ERRORS");
        verify(outgoing, never()).setStringProperty(eq("JMSXAppID"), anyString());
    }

    @Test
    void deadLettersIgnoreAllOriginalSendControlsIncludingInvalidValues() throws Exception {
        for (Message<String> source : List.of(
                AqMessage.builder("body").applicationId("orders").exceptionQueue("APP.ERRORS")
                        .deliveryDelay(Duration.ofSeconds(5)).build(),
                Message.builder("body").localMetadata(AqMessage.APPLICATION_ID_METADATA, MessageHeaderValue.NullValue.create())
                        .localMetadata(AqMessage.EXCEPTION_QUEUE_METADATA, MessageHeaderValue.IntegerValue.create(7))
                        .localMetadata(AqMessage.DELIVERY_DELAY_METADATA, "invalid").build())) {
            Session session = mock(Session.class);
            TextMessage outgoing = mock(TextMessage.class);
            when(session.createTextMessage("body")).thenReturn(outgoing);
            DeadLetterMessage<String> deadLetter = DeadLetterMessage.create(source, "orders", 1,
                                                                           new IllegalStateException("private failure"));

            AqMessageMapper.toNative(session, deadLetter, config(AqPayloadType.JMS));

            verify(outgoing, never()).setStringProperty(anyString(), anyString());
            verify(outgoing, never()).setIntProperty(eq("JMS_OracleDelay"), anyInt());
            verify(outgoing, never()).setObjectProperty(eq(DeadLetterMessage.FAILURE_MESSAGE_METADATA), any());
        }
    }

    @Test
    void incomingNativePropertiesBecomeTypedLocalSnapshots() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("JMSXAppID", "orders");
        values.put("JMSXUserID", "APP");
        values.put("JMS_OracleExcpQ", "APP.ERRORS");
        values.put("JMSXState", 0);
        values.put("JMSXRcvTimestamp", 1_777_777_777_123L);
        values.put("JMS_OracleOriginalMessageID", "ID:0123456789ABCDEF");
        values.put("JMS_OracleDelay", 30L);
        TextMessage incoming = nativeText(values);

        AqMessage<?> received = AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS));

        assertThat(received.localMetadata().text(AqMessage.RECEIVED_APPLICATION_ID_METADATA).orElseThrow(), is("orders"));
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_USER_ID_METADATA).orElseThrow(), is("APP"));
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_EXCEPTION_QUEUE_METADATA).orElseThrow(), is("APP.ERRORS"));
        assertThat(received.localMetadata().value(AqMessage.RECEIVED_STATE_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(0)));
        assertThat(received.localMetadata().value(AqMessage.RECEIVED_TIMESTAMP_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(1_777_777_777_123L)));
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_ORIGINAL_MESSAGE_ID_METADATA).orElseThrow(),
                   is("ID:0123456789ABCDEF"));
        assertThat(received.localMetadata().value(AqMessage.RECEIVED_DELIVERY_DELAY_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(30)));
        assertThat(received.localMetadata().size(), is(7));
        assertThat(received.headers().entries().isEmpty(), is(true));
        assertThat(received.properties().isEmpty(), is(true));
        when(incoming.getObjectProperty("JMSXAppID")).thenReturn("changed");
        assertThat(received.localMetadata().text(AqMessage.RECEIVED_APPLICATION_ID_METADATA).orElseThrow(), is("orders"));
        assertThrows(UnsupportedOperationException.class, () -> received.localMetadata().values().clear());
    }

    @Test
    void absentNativeValuesAreOmittedAndProviderFailuresRemainVisible() throws Exception {
        TextMessage incoming = nativeText(Map.of());
        assertThat(AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS)).localMetadata().isEmpty(), is(true));

        when(incoming.getObjectProperty("JMS_OracleOriginalMessageID")).thenThrow(new JMSException("provider failed"));

        assertThrows(MessagingException.class, () -> AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS)));
    }

    @Test
    void forwardingDoesNotTurnReceivedFactsBackIntoSendControls() throws Exception {
        TextMessage incoming = nativeText(Map.of("JMSXAppID", "orders", "JMS_OracleExcpQ", "APP.ERRORS",
                                                  "JMS_OracleDelay", 30L));
        AqMessage<?> received = AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS));
        Session session = mock(Session.class);
        TextMessage outgoing = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(outgoing);

        AqMessageMapper.toNative(session, received, config(AqPayloadType.JMS));

        assertThat(received.localMetadata().contains(AqMessage.APPLICATION_ID_METADATA), is(false));
        assertThat(received.localMetadata().contains(AqMessage.EXCEPTION_QUEUE_METADATA), is(false));
        assertThat(received.localMetadata().contains(AqMessage.DELIVERY_DELAY_METADATA), is(false));
        verify(outgoing, never()).setStringProperty(anyString(), anyString());
        verify(outgoing, never()).setIntProperty(anyString(), anyInt());
        verify(outgoing, never()).setObjectProperty(anyString(), any());
    }

    @Test
    void failureEnvelopeRetainsNativeMetadataWithoutReadingTheBody() throws Exception {
        TextMessage incoming = nativeText(Map.of("JMSXAppID", "orders", "JMSXState", 3));

        AqMessage<?> rejected = AqMessageMapper.metadataOnly(incoming);

        assertThat(rejected.bodyAvailable(), is(false));
        assertThrows(MessagingException.class, rejected::entity);
        assertThat(rejected.localMetadata().text(AqMessage.RECEIVED_APPLICATION_ID_METADATA).orElseThrow(), is("orders"));
        assertThat(rejected.localMetadata().value(AqMessage.RECEIVED_STATE_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(3)));
        verify(incoming, never()).getText();
    }

    @Test
    void adtSnapshotsSkipUnsupportedIdentityPropertiesAndAcceptIntegerDelay() throws Exception {
        AQjmsAdtMessage incoming = mock(AQjmsAdtMessage.class);
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        when(incoming.getObjectProperty("JMS_OracleExcpQ")).thenReturn("APP.ERRORS");
        when(incoming.getObjectProperty("JMS_OracleDelay")).thenReturn(3);

        AqMessage<?> rejected = AqMessageMapper.metadataOnly(incoming);

        assertThat(rejected.localMetadata().text(AqMessage.RECEIVED_EXCEPTION_QUEUE_METADATA).orElseThrow(), is("APP.ERRORS"));
        assertThat(rejected.localMetadata().value(AqMessage.RECEIVED_DELIVERY_DELAY_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(3)));
        verify(incoming, never()).getObjectProperty("JMSXAppID");
        verify(incoming, never()).getObjectProperty("JMSXUserID");
    }

    private static TextMessage nativeText(Map<String, Object> values) throws JMSException {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn("body");
        when(message.getPropertyNames()).thenReturn(Collections.enumeration(values.keySet()));
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            when(message.getObjectProperty(entry.getKey())).thenReturn(entry.getValue());
        }
        return message;
    }

    private static AqRuntimeConfig config(AqPayloadType type) {
        return AqRuntimeConfig.incoming(AqConnectorConfig.builder().name("aq").destination("TEST").payloadType(type)
                                               .buildPrototype(),
                                       AqIncomingConfig.builder().connector("aq").channelName("test").build());
    }
}
