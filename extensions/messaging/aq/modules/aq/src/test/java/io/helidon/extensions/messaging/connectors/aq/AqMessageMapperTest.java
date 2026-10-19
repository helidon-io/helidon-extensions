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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.Struct;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageHeaderValue;
import io.helidon.messaging.MessagingException;

import oracle.jdbc.OracleData;
import oracle.jdbc.OracleDataFactory;
import oracle.jms.AQjmsAdtMessage;
import oracle.jms.AQjmsAgent;
import oracle.jms.AQjmsSession;
import oracle.jms.AdtMessage;
import oracle.xdb.XMLType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AqMessageMapperTest {
    @Test
    void mapsTextHeadersAndReplyTo() throws Exception {
        Session session = mock(Session.class);
        TextMessage nativeMessage = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);
        when(session.createTextMessage("body")).thenReturn(nativeMessage);
        when(session.createQueue("APP.REPLIES")).thenReturn(replyTo);
        AqMessage<String> message = AqMessage.builder("body")
                .correlationId("order-1")
                .type("order")
                .replyTo("APP.REPLIES")
                .putProperty("count", (short) 2)
                .build();

        assertThat(AqMessageMapper.toNative(session, message, config(AqPayloadType.JMS, false)), sameInstance(nativeMessage));
        verify(nativeMessage).setJMSCorrelationID("order-1");
        verify(nativeMessage).setJMSType("order");
        verify(nativeMessage).setJMSReplyTo(replyTo);
        verify(nativeMessage).setObjectProperty("count", (short) 2);
    }

    @Test
    void rejectsUnsupportedOrDuplicatePortableHeaders() {
        Session session = mock(Session.class);
        Message<String> duplicate = Message.builder("body")
                .addHeader("same", "one").addHeader("same", "two").build();
        Message<String> unsupported = Message.builder("body")
                .addHeader("binary", MessageHeaderValue.BinaryValue.create(new byte[]{1})).build();
        assertThrows(MessagingException.class,
                     () -> AqMessageMapper.toNative(session, duplicate, config(AqPayloadType.JMS, false)));
        assertThrows(MessagingException.class,
                     () -> AqMessageMapper.toNative(session, unsupported, config(AqPayloadType.JMS, false)));
    }

    @Test
    void readsCompleteBinaryPayloadAndRetainsMetadataOnFailure() throws Exception {
        BytesMessage nativeMessage = mock(BytesMessage.class);
        when(nativeMessage.getBodyLength()).thenReturn(3L);
        when(nativeMessage.getJMSMessageID()).thenReturn("ID:one");
        when(nativeMessage.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        AtomicInteger offset = new AtomicInteger();
        doAnswer(invocation -> {
            byte[] chunk = invocation.getArgument(0);
            int index = offset.getAndIncrement();
            chunk[0] = (byte) (index + 1);
            return 1;
        }).when(nativeMessage).readBytes(any(byte[].class), anyInt());
        AqMessage<?> message = AqMessageMapper.fromNative(nativeMessage, config(AqPayloadType.JMS, false));
        assertThat((byte[]) message.entity(), is(new byte[]{1, 2, 3}));

        when(nativeMessage.getBodyLength()).thenReturn(1_048_577L);
        assertThrows(MessagingException.class,
                     () -> AqMessageMapper.fromNative(nativeMessage, config(AqPayloadType.JMS, false)));
        AqMessage<?> rejected = AqMessageMapper.metadataOnly(nativeMessage);
        assertThat(rejected.messageId().orElseThrow(), is("ID:one"));
        assertThat(rejected.bodyAvailable(), is(false));
        assertThrows(MessagingException.class, rejected::entity);
    }

    @Test
    void rejectsTruncatedBinaryPayload() throws Exception {
        BytesMessage nativeMessage = mock(BytesMessage.class);
        when(nativeMessage.getBodyLength()).thenReturn(3L);
        when(nativeMessage.readBytes(any(byte[].class), anyInt())).thenReturn(0);
        assertThrows(MessagingException.class,
                     () -> AqMessageMapper.fromNative(nativeMessage, config(AqPayloadType.JMS, false)));
    }

    @Test
    void mapsOutboundBufferWithoutChangingPosition() throws Exception {
        Session session = mock(Session.class);
        BytesMessage nativeMessage = mock(BytesMessage.class);
        when(session.createBytesMessage()).thenReturn(nativeMessage);
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{0, 1, 2});
        buffer.position(1);

        AqMessageMapper.toNative(session, Message.create(buffer), config(AqPayloadType.JMS, false));
        verify(nativeMessage).writeBytes(new byte[]{1, 2});
        assertThat(buffer.position(), is(1));
    }

    @Test
    void snapshotsMapPayload() throws Exception {
        byte[] bytes = {1, 2};
        MapMessage nativeMessage = mock(MapMessage.class);
        when(nativeMessage.getMapNames()).thenReturn(Collections.enumeration(List.of("binary")));
        when(nativeMessage.getObject("binary")).thenReturn(bytes);
        when(nativeMessage.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        AqMessage<?> message = AqMessageMapper.fromNative(nativeMessage, config(AqPayloadType.JMS, false));
        bytes[0] = 9;
        assertThat((byte[]) ((Map<?, ?>) message.entity()).get("binary"), is(new byte[]{1, 2}));
    }

    @Test
    void disabledObjectMappingDoesNotReadOrSerializePayload() throws Exception {
        ObjectMessage incoming = mock(ObjectMessage.class);
        assertThrows(MessagingException.class,
                     () -> AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS, false)));
        verify(incoming, never()).getObject();

        TrackedObject.SERIALIZATIONS.set(0);
        AqMessage<TrackedObject> message = AqMessage.create(new TrackedObject());
        assertThrows(MessagingException.class,
                     () -> AqMessageMapper.toNative(mock(Session.class), message, config(AqPayloadType.JMS, false)));
        assertThat(TrackedObject.SERIALIZATIONS.get(), is(0));
    }

    @Test
    void disabledForwardDoesNotDeserializeIncomingObjectSnapshot() throws Exception {
        TrackedObject.DESERIALIZATIONS.set(0);
        ObjectMessage incoming = mock(ObjectMessage.class);
        when(incoming.getObject()).thenReturn(new TrackedObject());
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        AqMessage<?> snapshot = AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS, true));

        assertThrows(MessagingException.class,
                     () -> AqMessageMapper.toNative(mock(Session.class), snapshot, config(AqPayloadType.JMS, false)));
        assertThat(TrackedObject.DESERIALIZATIONS.get(), is(0));
    }

    @Test
    void incomingObjectSnapshotIsIndependentOfProviderAndReaders() throws Exception {
        ArrayList<String> payload = new ArrayList<>(List.of("original"));
        ObjectMessage incoming = mock(ObjectMessage.class);
        when(incoming.getObject()).thenReturn(payload);
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        AqMessage<?> snapshot = AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS, true));
        payload.clear();
        ((List<?>) snapshot.entity()).clear();
        assertThat(snapshot.entity(), is(List.of("original")));
    }

    @Test
    void objectMapsRetainObjectMessageRepresentation() throws Exception {
        ObjectMessage incoming = mock(ObjectMessage.class);
        when(incoming.getObject()).thenReturn((Serializable) Map.of("nested", List.of("value")));
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        AqMessage<?> snapshot = AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS, true));
        assertThat(snapshot.entity(), is(Map.of("nested", List.of("value"))));

        Session session = mock(Session.class);
        when(session.createObjectMessage(any(Serializable.class))).thenReturn(mock(ObjectMessage.class));
        AqMessageMapper.toNative(session, snapshot, config(AqPayloadType.JMS, true));
        verify(session).createObjectMessage((Serializable) Map.of("nested", List.of("value")));
        verify(session, never()).createMapMessage();
    }

    @Test
    void unavailableMetadataStillProducesFailureEnvelope() throws Exception {
        TextMessage incoming = mock(TextMessage.class);
        when(incoming.getText()).thenReturn("body");
        when(incoming.getPropertyNames()).thenThrow(new JMSException("property decoding failed"));
        assertThrows(MessagingException.class,
                     () -> AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS, false)));
        AqMessage<?> rejected = AqMessageMapper.metadataOnly(incoming);
        assertThat(rejected.bodyAvailable(), is(false));
        assertThat(rejected.messageId().isEmpty(), is(true));
        assertThat(rejected.headers().entries().isEmpty(), is(true));
        assertThrows(MessagingException.class, rejected::entity);
    }

    @Test
    void snapshotsNativeReplyToAgents() throws Exception {
        AQjmsAgent agent = new AQjmsAgent("consumer", "APP.REPLIES@REMOTE", 0);
        TextMessage incoming = mock(TextMessage.class);
        when(incoming.getText()).thenReturn("body");
        when(incoming.getJMSReplyTo()).thenReturn(agent);
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        AqMessage<?> snapshot = AqMessageMapper.fromNative(incoming, config(AqPayloadType.JMS, false));
        agent.setAddress("MUTATED");
        assertThat(snapshot.replyTo().isEmpty(), is(true));
        assertThat(snapshot.replyToAgent().orElseThrow().address().orElseThrow(), is("APP.REPLIES@REMOTE"));

        Session session = mock(Session.class);
        TextMessage outgoing = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(outgoing);
        AqMessageMapper.toNative(session, snapshot, config(AqPayloadType.JMS, false));
        ArgumentCaptor<AQjmsAgent> replyTo = ArgumentCaptor.forClass(AQjmsAgent.class);
        verify(outgoing).setJMSReplyTo(replyTo.capture());
        assertThat(replyTo.getValue().getName(), is("consumer"));
        assertThat(replyTo.getValue().getAddress(), is("APP.REPLIES@REMOTE"));
    }

    @Test
    void genericAdtFactoryDetachesAndRecreatesSqlValues() throws Exception {
        Struct struct = mock(Struct.class);
        byte[] bytes = {1, 2};
        when(struct.getSQLTypeName()).thenReturn("APP.ORDER_TYPE");
        when(struct.getAttributes()).thenReturn(new Object[]{new BigDecimal("42"), bytes, null});
        OracleDataFactory factory = (OracleDataFactory) AqMessageMapper.payloadFactory(config(AqPayloadType.ADT, false));
        OracleData data = factory.create(struct, Types.STRUCT);
        bytes[0] = 9;
        AdtMessage incoming = mock(AdtMessage.class);
        when(incoming.getAdtPayload()).thenReturn(data);
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());

        AqMessage<?> message = AqMessageMapper.fromNative(incoming, config(AqPayloadType.ADT, false));
        AqAdt entity = (AqAdt) message.entity();
        assertThat(entity.typeName(), is("APP.ORDER_TYPE"));
        assertThat((byte[]) entity.attributes().get(1), is(new byte[]{1, 2}));
        assertThat(entity.attributes().get(2), is(AqAdt.NullValue.INSTANCE));
        verify(incoming, never()).getJMSType();
        verify(incoming, never()).getJMSReplyTo();

        AQjmsSession session = mock(AQjmsSession.class);
        AQjmsAdtMessage outgoing = mock(AQjmsAdtMessage.class);
        when(session.createORAMessage(any(OracleData.class))).thenReturn(outgoing);
        AqMessageMapper.toNative(session, Message.create(entity), config(AqPayloadType.ADT, false));
        ArgumentCaptor<OracleData> payload = ArgumentCaptor.forClass(OracleData.class);
        verify(session).createORAMessage(payload.capture());
        Connection connection = mock(Connection.class);
        payload.getValue().toJDBCObject(connection);
        verify(connection).createStruct(eq("APP.ORDER_TYPE"), any(Object[].class));
        ArgumentCaptor<Object[]> attributes = ArgumentCaptor.forClass(Object[].class);
        verify(connection).createStruct(eq("APP.ORDER_TYPE"), attributes.capture());
        assertThat(attributes.getValue()[0], is(new BigDecimal("42")));
        assertThat((byte[]) attributes.getValue()[1], is(new byte[]{1, 2}));
        assertThat(attributes.getValue()[2] == null, is(true));
    }

    @Test
    void invalidAdtRootFailsDuringSnapshotInsteadOfDequeue() throws Exception {
        OracleDataFactory factory = (OracleDataFactory) AqMessageMapper.payloadFactory(config(AqPayloadType.ADT, false));
        OracleData data = factory.create("not an SQL object", Types.VARCHAR);
        AdtMessage incoming = mock(AdtMessage.class);
        when(incoming.getAdtPayload()).thenReturn(data);

        MessagingException failure = assertThrows(MessagingException.class,
                                                  () -> AqMessageMapper.fromNative(incoming, config(AqPayloadType.ADT, false)));

        assertThat(failure.getCause().getMessage(), is("AQ ADT payload must be an Oracle SQL object"));
    }

    @Test
    void xmlIsDetachedAndClosedBeforeDelivery() throws Exception {
        XMLType xml = mock(XMLType.class);
        when(xml.getStringVal()).thenReturn("<order id=\"42\"/>");
        AdtMessage incoming = mock(AdtMessage.class);
        when(incoming.getAdtPayload()).thenReturn(xml);
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());

        AqMessage<?> message = AqMessageMapper.fromNative(incoming, config(AqPayloadType.XML, false));

        assertThat(((AqXml) message.entity()).text(), is("<order id=\"42\"/>"));
        verify(xml).close();
        verify(incoming, never()).getJMSType();
        verify(incoming, never()).getJMSReplyTo();
    }

    @Test
    void customConversionReceivesOnlyDetachedValues() throws Exception {
        AqPayloadConverter converter = new AqPayloadConverter() {
            @Override
            public Object fromPayload(Object payload) {
                return Integer.parseInt((String) payload);
            }

            @Override
            public Object toPayload(Object entity) {
                return entity.toString();
            }
        };
        AqRuntimeConfig config = AqRuntimeConfig.incoming(
                AqConnectorConfig.builder().name("aq").destination("TEST").payloadConverter(converter).buildPrototype(),
                AqIncomingConfig.builder().connector("aq").channelName("test").build());
        TextMessage incoming = mock(TextMessage.class);
        when(incoming.getText()).thenReturn("42");
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        assertThat(AqMessageMapper.fromNative(incoming, config).entity(), is(42));

        Session session = mock(Session.class);
        when(session.createTextMessage("42")).thenReturn(mock(TextMessage.class));
        AqMessageMapper.toNative(session, Message.create(42), config);
        verify(session).createTextMessage("42");
    }

    @Test
    void deadLettersPreserveMetadataWithoutExportingLocalFailureDetails() throws Exception {
        Session session = mock(Session.class);
        TextMessage outgoing = mock(TextMessage.class);
        when(session.createTextMessage("body")).thenReturn(outgoing);
        AqMessage<String> source = AqMessage.builder("body").correlationId("order-1")
                .putProperty("count", (short) 2).build();
        DeadLetterMessage<String> deadLetter = DeadLetterMessage.create(source, "orders", 2,
                                                                       new IllegalStateException("private detail"));
        AqMessageMapper.toNative(session, deadLetter, config(AqPayloadType.JMS, false));
        verify(outgoing).setJMSCorrelationID("order-1");
        verify(outgoing).setObjectProperty("count", (short) 2);
        verify(outgoing).setObjectProperty(DeadLetterMessage.SOURCE_CHANNEL_HEADER, "orders");
        verify(outgoing, never()).setObjectProperty(eq(DeadLetterMessage.FAILURE_MESSAGE_METADATA), any());
    }

    private static AqRuntimeConfig config(AqPayloadType type, boolean allowObjectMessages) {
        return AqRuntimeConfig.incoming(AqConnectorConfig.builder().name("aq").destination("TEST").payloadType(type)
                                               .allowObjectMessages(allowObjectMessages).buildPrototype(),
                                       AqIncomingConfig.builder().connector("aq").channelName("test").build());
    }

    private static final class TrackedObject implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final AtomicInteger SERIALIZATIONS = new AtomicInteger();
        private static final AtomicInteger DESERIALIZATIONS = new AtomicInteger();

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
    }
}
