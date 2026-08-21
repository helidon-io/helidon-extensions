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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;

import jakarta.jms.BytesMessage;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageEOFException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmsMessageTest {
    @Test
    void testProgrammaticMessageIsImmutable() {
        byte[] body = {1, 2};
        JmsMessage<byte[]> message = JmsMessage.<byte[]>builder(body)
                .correlationId("order-42")
                .type("order")
                .property("attempt", 2)
                .build();
        body[0] = 9;
        byte[] returned = message.entity();
        returned[0] = 8;

        assertThat(message.entity()[0], is((byte) 1));
        assertThat(message.correlationId().orElseThrow(), is("order-42"));
        assertThat(message.type().orElseThrow(), is("order"));
        assertThat(message.jmsProperties(), is(Map.of("attempt", 2)));
        assertThat(message.headers(), is(Map.of("attempt", "2")));
    }

    @Test
    void testPropertyTypesAreValidated() {
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder("body").property("bad", List.of(1)).build());
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder("body").property("bad-name", "value").build());
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder("body").property("JMSXDeliveryCount", 2).build());
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder("body").property("JMSXGroupID", 2).build());
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder("body").property("JMSXGroupSeq", "2").build());
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder("body").property("and", true).build());
        assertThat(JmsMessage.builder("body")
                           .property("_valid$property", true)
                           .property("JMSXGroupID", "orders")
                           .property("JMSXGroupSeq", 2)
                           .build()
                           .jmsProperties(),
                   is(Map.of("_valid$property", true, "JMSXGroupID", "orders", "JMSXGroupSeq", 2)));
    }

    @Test
    void testDeadLetterHeadersArePortableJmsApplicationProperties() throws Exception {
        Session session = mock(Session.class);
        TextMessage nativeMessage = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(nativeMessage);
        RuntimeException processingFailure = new RuntimeException("failed");
        DeadLetterMessage<String> deadLetter = DeadLetterMessage.create(
                io.helidon.messaging.Message.create("body"),
                "orders",
                2,
                processingFailure);

        JmsMessageMapper.toJmsMessage(session, deadLetter, false);

        verify(nativeMessage).setStringProperty(DeadLetterMessage.SOURCE_CHANNEL_HEADER, "orders");
        verify(nativeMessage).setStringProperty(DeadLetterMessage.ATTEMPTS_HEADER, "2");
        verify(nativeMessage).setStringProperty(DeadLetterMessage.FAILURE_TYPE_HEADER,
                                                RuntimeException.class.getName());
        verify(nativeMessage).setStringProperty(DeadLetterMessage.FAILURE_MESSAGE_HEADER, "failed");
        deadLetter.headers().keySet().forEach(name ->
                assertThat(JmsMessageImpl.isApplicationPropertyName(name), is(true)));
    }

    @Test
    void testMapAndStreamValuesAreValidatedAndCopied() {
        byte[] bytes = {1, 2};
        JmsMessage<Map<String, Object>> message = JmsMessage.<Map<String, Object>>builder(Map.of("bytes", bytes)).build();
        bytes[0] = 9;
        byte[] returned = (byte[]) message.entity().get("bytes");
        returned[0] = 8;

        assertThat(((byte[]) message.entity().get("bytes"))[0], is((byte) 1));
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder(Map.of("nested", List.of("unsupported-nesting"))).build());
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder(List.of(new Object())).build());
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder(Map.of(1, "non-string-key")).build());
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder(Map.of("", "empty-name")).build());
        Map<Object, Object> nullName = new LinkedHashMap<>();
        nullName.put(null, "null-name");
        assertThrows(IllegalArgumentException.class, () -> JmsMessage.builder(nullName).build());
        assertThrows(IllegalArgumentException.class,
                     () -> JmsMessage.builder(new Object()).build());
    }

    @Test
    void testTextMessageMapping() throws Exception {
        Session session = mock(Session.class);
        TextMessage nativeMessage = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(nativeMessage);

        JmsMessage<String> message = JmsMessage.<String>builder("body")
                .correlationId("correlation")
                .type("kind")
                .property("attempt", 2)
                .build();

        assertThat(JmsMessageMapper.toJmsMessage(session, message, false), is(nativeMessage));
        verify(nativeMessage).setObjectProperty("attempt", 2);
        verify(nativeMessage).setJMSCorrelationID("correlation");
        verify(nativeMessage).setJMSType("kind");
    }

    @Test
    void testStandardGroupingPropertiesAreMappedFromTypedAndPortableMessages() throws Exception {
        Session session = mock(Session.class);
        TextMessage typedNativeMessage = mock(TextMessage.class);
        TextMessage portableNativeMessage = mock(TextMessage.class);
        when(session.createTextMessage("typed")).thenReturn(typedNativeMessage);
        when(session.createTextMessage("portable")).thenReturn(portableNativeMessage);

        JmsMessageMapper.toJmsMessage(session,
                                      JmsMessage.builder("typed")
                                              .property("JMSXGroupID", "orders")
                                              .property("JMSXGroupSeq", 7)
                                              .build(),
                                      false);
        JmsMessageMapper.toJmsMessage(session,
                                      io.helidon.messaging.Message.builder("portable")
                                              .header("JMSXGroupID", "orders")
                                              .header("JMSXGroupSeq", "8")
                                              .build(),
                                      false);

        verify(typedNativeMessage).setStringProperty("JMSXGroupID", "orders");
        verify(typedNativeMessage).setIntProperty("JMSXGroupSeq", 7);
        verify(portableNativeMessage).setStringProperty("JMSXGroupID", "orders");
        verify(portableNativeMessage).setIntProperty("JMSXGroupSeq", 8);
        assertThrows(MessagingException.class,
                     () -> JmsMessageMapper.copyPortableHeaders(portableNativeMessage,
                                                                io.helidon.messaging.Message.builder("bad")
                                                                        .header("JMSXGroupSeq", "not-an-integer")
                                                                        .build()));
    }

    @Test
    void testBytesMessageMappingUsesSnapshot() throws Exception {
        Session session = mock(Session.class);
        BytesMessage nativeMessage = mock(BytesMessage.class);
        when(session.createBytesMessage()).thenReturn(nativeMessage);
        byte[] body = {1, 2};

        JmsMessageMapper.toJmsMessage(session, JmsMessage.builder(body).build(), false);
        body[0] = 9;

        verify(nativeMessage).writeBytes(new byte[]{1, 2});
    }

    @Test
    void testChunkedBytesMessageIsReadWithoutOverwritingEarlierChunks() throws Exception {
        BytesMessage nativeMessage = mock(BytesMessage.class);
        when(nativeMessage.getBodyLength()).thenReturn(4L);
        when(nativeMessage.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        when(nativeMessage.readBytes(any(byte[].class), anyInt()))
                .thenAnswer(invocation -> {
                    byte[] chunk = invocation.getArgument(0);
                    chunk[0] = 1;
                    chunk[1] = 2;
                    return 2;
                })
                .thenAnswer(invocation -> {
                    byte[] chunk = invocation.getArgument(0);
                    chunk[0] = 3;
                    chunk[1] = 4;
                    return 2;
                });

        assertThat((byte[]) JmsMessageMapper.fromJmsMessage(nativeMessage, false, 4).entity(),
                   is(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void testOversizedBytesMessageIsRejectedBeforeReadingOrAllocatingBody() throws Exception {
        BytesMessage nativeMessage = mock(BytesMessage.class);
        when(nativeMessage.getBodyLength()).thenReturn(1025L);

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> JmsMessageMapper.fromJmsMessage(nativeMessage, false, 1024));

        assertThat(failure.getMessage(), is("JMS bytes message body length 1025 exceeds max-body-bytes 1024"));
        verify(nativeMessage, never()).reset();
        verify(nativeMessage, never()).readBytes(any(byte[].class), anyInt());
    }

    @Test
    void testNegativeBytesMessageLengthIsRejectedBeforeReading() throws Exception {
        BytesMessage nativeMessage = mock(BytesMessage.class);
        when(nativeMessage.getBodyLength()).thenReturn(-1L);

        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> JmsMessageMapper.fromJmsMessage(nativeMessage, false, 1024));

        assertThat(failure.getMessage(), is("JMS bytes message declared a negative body length: -1"));
        verify(nativeMessage, never()).reset();
    }

    @Test
    void testBodylessMessageRoundTrip() throws Exception {
        Session session = mock(Session.class);
        Message bodyless = mock(Message.class);
        when(session.createMessage()).thenReturn(bodyless);
        when(bodyless.getBody(Object.class)).thenReturn(null);
        when(bodyless.getPropertyNames()).thenReturn(java.util.Collections.emptyEnumeration());

        assertThat(JmsMessageMapper.toJmsMessage(session,
                                                 io.helidon.messaging.Message.create(null),
                                                 false),
                   is(bodyless));
        assertThat(JmsMessageMapper.fromJmsMessage(bodyless, false, 1024).entity(), is((Object) null));
    }

    @Test
    void testIncomingMetadataAndTypedPropertiesAreSnapshotted() throws Exception {
        TextMessage nativeMessage = mock(TextMessage.class);
        when(nativeMessage.getText()).thenReturn("body");
        when(nativeMessage.getPropertyNames())
                .thenReturn(Collections.enumeration(List.of("attempt",
                                                             "region",
                                                             "JMSXGroupID",
                                                             "JMSXGroupSeq",
                                                             "JMSXDeliveryCount",
                                                             "JMS_vendor")));
        when(nativeMessage.getObjectProperty("attempt")).thenReturn(2);
        when(nativeMessage.getObjectProperty("region")).thenReturn("EU");
        when(nativeMessage.getObjectProperty("JMSXGroupID")).thenReturn("orders");
        when(nativeMessage.getObjectProperty("JMSXGroupSeq")).thenReturn(11);
        when(nativeMessage.getObjectProperty("JMSXDeliveryCount")).thenReturn(3);
        when(nativeMessage.getObjectProperty("JMS_vendor")).thenReturn("provider-value");
        when(nativeMessage.getJMSMessageID()).thenReturn("ID:42");
        when(nativeMessage.getJMSCorrelationID()).thenReturn("order-42");
        when(nativeMessage.getJMSType()).thenReturn("order");
        when(nativeMessage.getJMSTimestamp()).thenReturn(100L);
        when(nativeMessage.getJMSExpiration()).thenReturn(200L);
        when(nativeMessage.getJMSDeliveryTime()).thenReturn(300L);
        when(nativeMessage.getJMSPriority()).thenReturn(7);
        when(nativeMessage.getJMSRedelivered()).thenReturn(true);

        JmsMessage<?> message = JmsMessageMapper.fromJmsMessage(nativeMessage, false, 1024);

        assertThat(message.entity(), is("body"));
        assertThat(message.jmsProperties(), is(Map.of("attempt", 2,
                                                       "region", "EU",
                                                       "JMSXGroupID", "orders",
                                                       "JMSXGroupSeq", 11)));
        assertThat(message.headers(), is(Map.of("attempt", "2",
                                                "region", "EU",
                                                "JMSXGroupID", "orders",
                                                "JMSXGroupSeq", "11")));
        assertThat(message.messageId(), is(Optional.of("ID:42")));
        assertThat(message.correlationId(), is(Optional.of("order-42")));
        assertThat(message.type(), is(Optional.of("order")));
        assertThat(message.timestamp(), is(OptionalLong.of(100)));
        assertThat(message.expiration(), is(OptionalLong.of(200)));
        assertThat(message.deliveryTime(), is(OptionalLong.of(300)));
        assertThat(message.priority(), is(OptionalInt.of(7)));
        assertThat(message.redelivered(), is(Optional.of(true)));
    }

    @Test
    void testMapAndStreamMessagesAreMappedInBothDirections() throws Exception {
        byte[] mapBytes = {1, 2};
        MapMessage incomingMap = mock(MapMessage.class);
        when(incomingMap.getMapNames()).thenReturn(Collections.enumeration(List.of("bytes", "missing")));
        when(incomingMap.getObject("bytes")).thenReturn(mapBytes);
        when(incomingMap.getObject("missing")).thenReturn(null);
        when(incomingMap.getPropertyNames()).thenReturn(Collections.emptyEnumeration());

        @SuppressWarnings("unchecked")
        Map<String, Object> mappedBody = (Map<String, Object>) JmsMessageMapper.fromJmsMessage(incomingMap,
                                                                                             false,
                                                                                             1024).entity();
        mapBytes[0] = 9;
        assertThat((byte[]) mappedBody.get("bytes"), is(new byte[]{1, 2}));
        assertThat(mappedBody.containsKey("missing"), is(true));
        assertThat(mappedBody.get("missing"), is((Object) null));

        byte[] streamBytes = {3, 4};
        StreamMessage incomingStream = mock(StreamMessage.class);
        when(incomingStream.readObject()).thenReturn("first")
                .thenReturn(null)
                .thenReturn(streamBytes)
                .thenThrow(new MessageEOFException("end"));
        when(incomingStream.getPropertyNames()).thenReturn(Collections.emptyEnumeration());

        @SuppressWarnings("unchecked")
        List<Object> streamBody = (List<Object>) JmsMessageMapper.fromJmsMessage(incomingStream, false, 1024).entity();
        streamBytes[0] = 9;
        assertThat(streamBody.get(0), is("first"));
        assertThat(streamBody.get(1), is((Object) null));
        assertThat((byte[]) streamBody.get(2), is(new byte[]{3, 4}));

        Session session = mock(Session.class);
        MapMessage outgoingMap = mock(MapMessage.class);
        StreamMessage outgoingStream = mock(StreamMessage.class);
        when(session.createMapMessage()).thenReturn(outgoingMap);
        when(session.createStreamMessage()).thenReturn(outgoingStream);

        JmsMessageMapper.toJmsMessage(session, io.helidon.messaging.Message.create(mappedBody), false);
        JmsMessageMapper.toJmsMessage(session, io.helidon.messaging.Message.create(streamBody), false);

        verify(outgoingMap).setObject("bytes", new byte[]{1, 2});
        verify(outgoingMap).setObject("missing", null);
        verify(outgoingStream).writeObject("first");
        verify(outgoingStream).writeObject(null);
        verify(outgoingStream).writeObject(new byte[]{3, 4});
    }

    @Test
    void testEmptyMapMessageNamesAreRejectedBeforeProviderMapping() throws Exception {
        Session session = mock(Session.class);
        Map<Object, Object> nullName = new LinkedHashMap<>();
        nullName.put(null, "null-name");

        assertThrows(MessagingException.class,
                     () -> JmsMessageMapper.toJmsMessage(session,
                                                         io.helidon.messaging.Message.create(
                                                                 Map.of("", "empty-name")),
                                                         false));
        assertThrows(MessagingException.class,
                     () -> JmsMessageMapper.toJmsMessage(session,
                                                         io.helidon.messaging.Message.create(nullName),
                                                         false));
        verify(session, never()).createMapMessage();

        MapMessage incomingMap = mock(MapMessage.class);
        when(incomingMap.getMapNames()).thenReturn(Collections.enumeration(List.of("")));
        assertThrows(MessagingException.class, () -> JmsMessageMapper.fromJmsMessage(incomingMap, false, 1024));
    }

    @Test
    void testDisabledObjectMessageDoesNotInvokeSerializationCallbacks() throws Exception {
        ReadTrackingPayload.reset();
        ReadTrackingPayload payload = new ReadTrackingPayload("untrusted");
        JmsMessage<ReadTrackingPayload> message = JmsMessage.builder(payload).build();
        Session session = mock(Session.class);

        assertThat(message.entity(), is(payload));
        MessageBatch.create(message);
        assertThrows(MessagingException.class, () -> JmsMessageMapper.toJmsMessage(session, message, false));

        assertThat(ReadTrackingPayload.serializationCount(), is(0));
        assertThat(ReadTrackingPayload.deserializationCount(), is(0));
        verify(session, never()).createObjectMessage(any(Serializable.class));
    }

    @Test
    void testEnabledObjectMessageUsesDefensiveSerializationSnapshot() throws Exception {
        ReadTrackingPayload.reset();
        ReadTrackingPayload payload = new ReadTrackingPayload("trusted");
        JmsMessage<ReadTrackingPayload> message = JmsMessage.builder(payload).build();
        Session session = mock(Session.class);
        ObjectMessage outgoing = mock(ObjectMessage.class);
        when(session.createObjectMessage(any(Serializable.class))).thenReturn(outgoing);

        assertThat(JmsMessageMapper.toJmsMessage(session, message, true), is(outgoing));

        org.mockito.ArgumentCaptor<Serializable> snapshot = org.mockito.ArgumentCaptor.forClass(Serializable.class);
        verify(session).createObjectMessage(snapshot.capture());
        assertThat(snapshot.getValue(), is(payload));
        assertNotSame(payload, snapshot.getValue());
        assertThat(ReadTrackingPayload.serializationCount(), is(1));
        assertThat(ReadTrackingPayload.deserializationCount(), is(1));
    }

    @Test
    void testEnabledObjectMessageMapping() throws Exception {
        TestPayload payload = new TestPayload("trusted");
        ObjectMessage incoming = mock(ObjectMessage.class);
        when(incoming.getObject()).thenReturn(payload);
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());

        assertThat(JmsMessageMapper.fromJmsMessage(incoming, true, 1024).entity(), is(payload));

        Session session = mock(Session.class);
        ObjectMessage outgoing = mock(ObjectMessage.class);
        when(session.createObjectMessage(any(Serializable.class))).thenReturn(outgoing);

        assertThat(JmsMessageMapper.toJmsMessage(session,
                                                 io.helidon.messaging.Message.create(payload),
                                                 true),
                   is(outgoing));
        verify(session).createObjectMessage(payload);
    }

    private record TestPayload(String value) implements Serializable {
    }

    private static final class ReadTrackingPayload implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final AtomicInteger SERIALIZATIONS = new AtomicInteger();
        private static final AtomicInteger DESERIALIZATIONS = new AtomicInteger();

        private final String value;

        private ReadTrackingPayload(String value) {
            this.value = value;
        }

        private static void reset() {
            SERIALIZATIONS.set(0);
            DESERIALIZATIONS.set(0);
        }

        private static int serializationCount() {
            return SERIALIZATIONS.get();
        }

        private static int deserializationCount() {
            return DESERIALIZATIONS.get();
        }

        @Serial
        private void writeObject(ObjectOutputStream output) throws IOException {
            SERIALIZATIONS.incrementAndGet();
            output.defaultWriteObject();
        }

        @Serial
        private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
            DESERIALIZATIONS.incrementAndGet();
            input.defaultReadObject();
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof ReadTrackingPayload other && value.equals(other.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }
}
