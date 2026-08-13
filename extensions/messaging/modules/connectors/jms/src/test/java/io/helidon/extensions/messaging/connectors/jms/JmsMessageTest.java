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

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.helidon.extensions.messaging.DeadLetterMessage;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
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
                     () -> JmsMessage.builder("body").property("and", true).build());
        assertThat(JmsMessage.builder("body").property("_valid$property", true).build().jmsProperties(),
                   is(Map.of("_valid$property", true)));
    }

    @Test
    void testDeadLetterHeadersArePortableJmsApplicationProperties() throws Exception {
        Session session = mock(Session.class);
        TextMessage nativeMessage = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(nativeMessage);
        RuntimeException processingFailure = new RuntimeException("failed");
        DeadLetterMessage<String> deadLetter = DeadLetterMessage.create(
                io.helidon.extensions.messaging.Message.create("body"),
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

        assertThat((byte[]) JmsMessageMapper.fromJmsMessage(nativeMessage, false).entity(),
                   is(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void testBodylessMessageRoundTrip() throws Exception {
        Session session = mock(Session.class);
        Message bodyless = mock(Message.class);
        when(session.createMessage()).thenReturn(bodyless);
        when(bodyless.getBody(Object.class)).thenReturn(null);
        when(bodyless.getPropertyNames()).thenReturn(java.util.Collections.emptyEnumeration());

        assertThat(JmsMessageMapper.toJmsMessage(session,
                                                 io.helidon.extensions.messaging.Message.create(null),
                                                 false),
                   is(bodyless));
        assertThat(JmsMessageMapper.fromJmsMessage(bodyless, false).entity(), is((Object) null));
    }

    @Test
    void testIncomingMetadataAndTypedPropertiesAreSnapshotted() throws Exception {
        TextMessage nativeMessage = mock(TextMessage.class);
        when(nativeMessage.getText()).thenReturn("body");
        when(nativeMessage.getPropertyNames())
                .thenReturn(Collections.enumeration(List.of("attempt", "region", "JMSXDeliveryCount", "JMS_vendor")));
        when(nativeMessage.getObjectProperty("attempt")).thenReturn(2);
        when(nativeMessage.getObjectProperty("region")).thenReturn("EU");
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

        JmsMessage<?> message = JmsMessageMapper.fromJmsMessage(nativeMessage, false);

        assertThat(message.entity(), is("body"));
        assertThat(message.jmsProperties(), is(Map.of("attempt", 2, "region", "EU")));
        assertThat(message.headers(), is(Map.of("attempt", "2", "region", "EU")));
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
        Map<String, Object> mappedBody = (Map<String, Object>) JmsMessageMapper.fromJmsMessage(incomingMap, false).entity();
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
        List<Object> streamBody = (List<Object>) JmsMessageMapper.fromJmsMessage(incomingStream, false).entity();
        streamBytes[0] = 9;
        assertThat(streamBody.get(0), is("first"));
        assertThat(streamBody.get(1), is((Object) null));
        assertThat((byte[]) streamBody.get(2), is(new byte[]{3, 4}));

        Session session = mock(Session.class);
        MapMessage outgoingMap = mock(MapMessage.class);
        StreamMessage outgoingStream = mock(StreamMessage.class);
        when(session.createMapMessage()).thenReturn(outgoingMap);
        when(session.createStreamMessage()).thenReturn(outgoingStream);

        JmsMessageMapper.toJmsMessage(session, io.helidon.extensions.messaging.Message.create(mappedBody), false);
        JmsMessageMapper.toJmsMessage(session, io.helidon.extensions.messaging.Message.create(streamBody), false);

        verify(outgoingMap).setObject("bytes", new byte[]{1, 2});
        verify(outgoingMap).setObject("missing", null);
        verify(outgoingStream).writeObject("first");
        verify(outgoingStream).writeObject(null);
        verify(outgoingStream).writeObject(new byte[]{3, 4});
    }

    @Test
    void testEnabledObjectMessageMapping() throws Exception {
        TestPayload payload = new TestPayload("trusted");
        ObjectMessage incoming = mock(ObjectMessage.class);
        when(incoming.getObject()).thenReturn(payload);
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());

        assertThat(JmsMessageMapper.fromJmsMessage(incoming, true).entity(), is(payload));

        Session session = mock(Session.class);
        ObjectMessage outgoing = mock(ObjectMessage.class);
        when(session.createObjectMessage(any(Serializable.class))).thenReturn(outgoing);

        assertThat(JmsMessageMapper.toJmsMessage(session,
                                                 io.helidon.extensions.messaging.Message.create(payload),
                                                 true),
                   is(outgoing));
        verify(session).createObjectMessage(payload);
    }

    private record TestPayload(String value) implements Serializable {
    }
}
