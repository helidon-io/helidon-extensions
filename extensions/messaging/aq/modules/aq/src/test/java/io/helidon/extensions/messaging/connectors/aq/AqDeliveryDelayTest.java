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

import java.math.BigInteger;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.jms.Session;
import javax.jms.TextMessage;

import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessageMetadata;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class AqDeliveryDelayTest {
    @Test
    void convenienceWritesTheDocumentedMetadataValue() {
        AqMessage<String> message = AqMessage.builder("body")
                .localMetadata("private", "value")
                .deliveryDelay(Duration.ofSeconds(30))
                .build();

        assertThat(message.localMetadata().value(AqMessage.DELIVERY_DELAY_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(30)));
        assertThat(message.localMetadata().text("private").orElseThrow(), is("value"));
        assertThat(message.headers().entries().isEmpty(), is(true));
        assertThat(message.properties().isEmpty(), is(true));
    }

    @Test
    void convenienceAndDirectMetadataHaveOneSourceOfTruth() {
        var builder = AqMessage.builder("body").deliveryDelay(Duration.ofSeconds(30));
        AqMessage<String> first = builder.build();
        AqMessage<String> second = builder
                .localMetadata(AqMessage.DELIVERY_DELAY_METADATA, MessageHeaderValue.IntegerValue.create(7))
                .build();
        AqMessage<String> third = builder.deliveryDelay(Duration.ofSeconds(5)).build();

        assertThat(first.localMetadata().value(AqMessage.DELIVERY_DELAY_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(30)));
        assertThat(second.localMetadata().value(AqMessage.DELIVERY_DELAY_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(7)));
        assertThat(third.localMetadata().value(AqMessage.DELIVERY_DELAY_METADATA).orElseThrow(),
                   is(MessageHeaderValue.IntegerValue.create(5)));
        assertThrows(UnsupportedOperationException.class,
                     () -> first.localMetadata().values().clear());
    }

    @Test
    void acceptsZeroAndMaximumWholeSecondDurations() {
        for (long seconds : new long[] {0, Integer.MAX_VALUE}) {
            AqMessage<String> message = AqMessage.builder("body").deliveryDelay(Duration.ofSeconds(seconds)).build();
            assertThat(message.localMetadata().value(AqMessage.DELIVERY_DELAY_METADATA).orElseThrow(),
                       is(MessageHeaderValue.IntegerValue.create(seconds)));
        }
    }

    @Test
    void rejectsInvalidDurationsAtTheBuilderBoundary() {
        assertThrows(NullPointerException.class, () -> AqMessage.builder("body").deliveryDelay(null));
        for (Duration delay : List.of(Duration.ofSeconds(-1),
                                      Duration.ofNanos(-1),
                                      Duration.ofMillis(1),
                                      Duration.ofMillis(1_001),
                                      Duration.ofSeconds((long) Integer.MAX_VALUE + 1),
                                      Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class, () -> AqMessage.builder("body").deliveryDelay(delay));
        }
    }

    @Test
    void genericAndAqMessagesApplyTheSameNativeDelay() throws Exception {
        for (Message<String> message : List.of(
                Message.builder("body")
                        .localMetadata(AqMessage.DELIVERY_DELAY_METADATA, MessageHeaderValue.IntegerValue.create(30)).build(),
                AqMessage.builder("body").deliveryDelay(Duration.ofSeconds(30)).build())) {
            Session session = mock(Session.class);
            TextMessage nativeMessage = mock(TextMessage.class);
            when(session.createTextMessage("body")).thenReturn(nativeMessage);

            AqMessageMapper.toNative(session, message, config(AqPayloadType.JMS));

            verify(nativeMessage).setIntProperty("JMS_OracleDelay", 30);
            verify(nativeMessage, never()).setObjectProperty(eq(AqMessage.DELIVERY_DELAY_METADATA), any());
        }
    }

    @Test
    void acceptsMaximumIntegerDelayWithoutNarrowingOverflow() throws Exception {
        Session session = mock(Session.class);
        TextMessage nativeMessage = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(nativeMessage);

        AqMessageMapper.toNative(session,
                                AqMessage.builder("body").deliveryDelay(Duration.ofSeconds(Integer.MAX_VALUE)).build(),
                                config(AqPayloadType.JMS));

        verify(nativeMessage).setIntProperty("JMS_OracleDelay", Integer.MAX_VALUE);
    }

    @Test
    void zeroAndUnrelatedMetadataDoNotChangeNativeDelivery() throws Exception {
        for (Message<String> message : List.of(
                Message.create("body"),
                AqMessage.builder("body").deliveryDelay(Duration.ZERO).build(),
                Message.builder("body").localMetadata("private", "secret").build(),
                Message.builder("body").localMetadata(AqMessage.DELIVERY_DELAY_METADATA.toUpperCase(Locale.ROOT), "30").build())) {
            Session session = mock(Session.class);
            TextMessage nativeMessage = mock(TextMessage.class);
            when(session.createTextMessage("body")).thenReturn(nativeMessage);

            AqMessageMapper.toNative(session, message, config(AqPayloadType.JMS));

            verify(nativeMessage, never()).setIntProperty(anyString(), anyInt());
            verify(nativeMessage, never()).setObjectProperty(anyString(), any());
        }
    }

    @Test
    void rejectsInvalidMetadataBeforeCreatingANativeMessage() {
        for (MessageHeaderValue value : List.of(
                MessageHeaderValue.TextValue.create("30"),
                MessageHeaderValue.BooleanValue.create(true),
                MessageHeaderValue.NullValue.create(),
                MessageHeaderValue.IntegerValue.create(-1),
                MessageHeaderValue.IntegerValue.create((long) Integer.MAX_VALUE + 1),
                MessageHeaderValue.IntegerValue.create(BigInteger.ONE.shiftLeft(100)))) {
            Session session = mock(Session.class);
            Message<String> message = Message.builder("body").localMetadata(AqMessage.DELIVERY_DELAY_METADATA, value).build();

            assertThrows(MessagingException.class,
                         () -> AqMessageMapper.toNative(session, message, config(AqPayloadType.JMS)));

            verifyZeroInteractions(session);
        }
    }

    @Test
    void metadataSnapshotCanBeSharedWithAqMessages() throws Exception {
        MessageMetadata metadata = MessageMetadata.builder()
                .set(AqMessage.DELIVERY_DELAY_METADATA, MessageHeaderValue.IntegerValue.create(7))
                .set("private", "secret")
                .build();
        AqMessage<String> message = AqMessage.builder("body").localMetadata(metadata).build();
        Session session = mock(Session.class);
        TextMessage nativeMessage = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(nativeMessage);

        AqMessageMapper.toNative(session, message, config(AqPayloadType.JMS));

        verify(nativeMessage).setIntProperty("JMS_OracleDelay", 7);
        verify(nativeMessage, never()).setObjectProperty(anyString(), any());
        assertThat(message.localMetadata(), is(metadata));
    }

    @Test
    void metadataMapIsSnapshottedAndRejectsNullEntries() {
        Map<String, MessageHeaderValue> values = new HashMap<>();
        values.put("private", MessageHeaderValue.TextValue.create("original"));
        AqMessage<String> message = AqMessage.builder("body").localMetadata(values).build();
        values.put("private", MessageHeaderValue.TextValue.create("changed"));

        assertThat(message.localMetadata().text("private").orElseThrow(), is("original"));

        values.put("invalid", null);
        assertThrows(NullPointerException.class, () -> AqMessage.builder("body").localMetadata(values).build());
        values.remove("invalid");
        values.put(null, MessageHeaderValue.TextValue.create("value"));
        assertThrows(NullPointerException.class, () -> AqMessage.builder("body").localMetadata(values).build());
    }

    @Test
    void sendingTheSameEnvelopeAgainAppliesItsDelayAgain() throws Exception {
        AqMessage<String> message = AqMessage.builder("body").deliveryDelay(Duration.ofSeconds(3)).build();
        Session session = mock(Session.class);
        TextMessage nativeMessage = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(nativeMessage);

        AqMessageMapper.toNative(session, message, config(AqPayloadType.JMS));
        AqMessageMapper.toNative(session, message, config(AqPayloadType.JMS));

        verify(nativeMessage, times(2)).setIntProperty("JMS_OracleDelay", 3);
    }

    @Test
    void deadLettersIgnoreTheOriginalDeliveryDelay() throws Exception {
        for (MessageHeaderValue delay : List.of(MessageHeaderValue.IntegerValue.create(30),
                                                MessageHeaderValue.TextValue.create("invalid"))) {
            Message<String> source = Message.builder("body").localMetadata(AqMessage.DELIVERY_DELAY_METADATA, delay).build();
            DeadLetterMessage<String> deadLetter = DeadLetterMessage.create(source, "orders", 2,
                                                                           new IllegalStateException("private detail"));
            Session session = mock(Session.class);
            TextMessage nativeMessage = mock(TextMessage.class);
            when(session.createTextMessage("body")).thenReturn(nativeMessage);

            AqMessageMapper.toNative(session, deadLetter, config(AqPayloadType.JMS));

            verify(nativeMessage, never()).setIntProperty(eq("JMS_OracleDelay"), anyInt());
            verify(nativeMessage, never()).setObjectProperty(eq(AqMessage.DELIVERY_DELAY_METADATA), any());
            verify(nativeMessage, never()).setObjectProperty(eq(DeadLetterMessage.FAILURE_MESSAGE_METADATA), any());
        }
    }

    @Test
    void receivingAndForwardingDoesNotReapplyTheHistoricalDelay() throws Exception {
        TextMessage incoming = mock(TextMessage.class);
        when(incoming.getText()).thenReturn("body");
        when(incoming.getPropertyNames()).thenReturn(Collections.enumeration(List.of("JMS_OracleDelay")));
        AqMessage<?> received = AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS));
        Session session = mock(Session.class);
        TextMessage outgoing = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(outgoing);

        AqMessageMapper.toNative(session, received, config(AqPayloadType.JMS));

        assertThat(received.localMetadata().contains(AqMessage.DELIVERY_DELAY_METADATA), is(false));
        verify(outgoing, never()).setIntProperty(eq("JMS_OracleDelay"), anyInt());
        verify(outgoing, never()).setObjectProperty(eq("JMS_OracleDelay"), any());
    }

    @Test
    void delayDoesNotRequireJmsPayloadHeaders() throws Exception {
        AQjmsSession session = mock(AQjmsSession.class);
        AQjmsAdtMessage nativeMessage = mock(AQjmsAdtMessage.class);
        when(session.createORAMessage(any(OracleData.class))).thenReturn(nativeMessage);
        AqAdt payload = AqAdt.builder().typeName("APP.EVENT_TYPE").addAttribute("event").build();
        AqMessage<AqAdt> message = AqMessage.builder(payload).deliveryDelay(Duration.ofSeconds(3)).build();

        AqMessageMapper.toNative(session, message, config(AqPayloadType.ADT));

        verify(nativeMessage).setIntProperty("JMS_OracleDelay", 3);
        verify(nativeMessage, never()).setObjectProperty(anyString(), any());
    }

    private static AqRuntimeConfig config(AqPayloadType type) {
        return AqRuntimeConfig.incoming(AqConnectorConfig.builder().name("aq").destination("TEST").payloadType(type)
                                               .buildPrototype(),
                                       AqIncomingConfig.builder().connector("aq").channelName("test").build());
    }
}
