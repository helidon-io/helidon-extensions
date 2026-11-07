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

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.NClob;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.Struct;
import java.sql.Types;
import java.util.Collections;
import java.util.List;

import javax.jms.JMSException;

import io.helidon.common.Size;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessagingException;

import oracle.jdbc.OracleArray;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleData;
import oracle.jdbc.OracleDataFactory;
import oracle.jms.AQjmsAdtMessage;
import oracle.jms.AQjmsSession;
import oracle.jms.AdtMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AqLobTest {
    @Test
    void binaryLobIsAnImmutableSnapshot() {
        byte[] bytes = {1, 2};
        AqBlob blob = AqBlob.create(bytes);
        AqAdt object = AqAdt.create("APP.LOB_VALUE", List.of(blob));
        bytes[0] = 9;
        blob.bytes()[1] = 8;

        assertThat(((AqBlob) object.attributes().getFirst()).bytes(), is(new byte[]{1, 2}));
        assertThat(AqClob.create("value").national(), is(false));
        assertThrows(NullPointerException.class, () -> AqBlob.create(null));
        assertThrows(NullPointerException.class, () -> AqClob.create(null));
    }

    @Test
    void textValueFactoriesPreserveNationalFlagAndValueEquality() {
        AqClob clob = AqClob.create("value");
        AqClob national = AqClob.createNational("value");
        AqXml xml = AqXml.create("<value/>");

        assertThat(clob.national(), is(false));
        assertThat(national.national(), is(true));
        assertThat(national.text(), is("value"));
        assertThat(clob, is(AqClob.create("value")));
        assertThat(clob.hashCode(), is(AqClob.create("value").hashCode()));
        assertThat(national, is(AqClob.createNational("value")));
        assertThat(national.hashCode(), is(AqClob.createNational("value").hashCode()));
        assertThat(clob, not(national));
        assertThat(clob, not(AqClob.create("other")));
        assertThat(xml, is(AqXml.create("<value/>")));
        assertThat(xml.hashCode(), is(AqXml.create("<value/>").hashCode()));
        assertThat(xml, not(AqXml.create("<other/>")));
        assertThat(AqClob.create("").text(), is(""));
        assertThat(AqClob.createNational("").text(), is(""));
        assertThat(AqXml.create("").text(), is(""));
        assertThrows(NullPointerException.class, () -> AqClob.createNational(null));
        assertThrows(NullPointerException.class, () -> AqXml.create(null));
    }

    @Test
    void detachesNestedLobsAndPreservesSqlTypes() throws Exception {
        Blob blob = mock(Blob.class);
        when(blob.length()).thenReturn(2L);
        byte[] content = {1, 2};
        when(blob.getBytes(1, 2)).thenReturn(content);
        Clob clob = mock(Clob.class);
        when(clob.length()).thenReturn(4L);
        when(clob.getSubString(1, 4)).thenReturn("text");
        NClob national = mock(NClob.class);
        when(national.length()).thenReturn(2L);
        when(national.getSubString(1, 2)).thenReturn("ž漢");
        Struct nested = struct("APP.LOB_VALUE", blob, clob, national);
        OracleArray array = mock(OracleArray.class);
        when(array.getSQLTypeName()).thenReturn("APP.LOB_VALUES");
        when(array.getArray()).thenReturn(new Object[]{nested});

        OracleData data = detach(struct("APP.ENVELOPE", array), 4);
        content[0] = 9;
        AqAdt envelope = snapshot(data);
        AqAdt values = (AqAdt) envelope.attributes().getFirst();
        AqAdt value = (AqAdt) values.attributes().getFirst();

        assertThat(values.kind(), is(AqAdt.Kind.ARRAY));
        assertThat(((AqBlob) value.attributes().get(0)).bytes(), is(new byte[]{1, 2}));
        assertThat(((AqClob) value.attributes().get(1)).text(), is("text"));
        assertThat(((AqClob) value.attributes().get(1)).national(), is(false));
        assertThat(((AqClob) value.attributes().get(2)).text(), is("ž漢"));
        assertThat(((AqClob) value.attributes().get(2)).national(), is(true));
        verify(blob).free();
        verify(clob).free();
        verify(national).free();
        verify(array).free();
    }

    @Test
    void recreatesNestedLobsOnSendingConnectionAndReleasesThem() throws Exception {
        OracleConnection connection = mock(OracleConnection.class);
        when(connection.unwrap(OracleConnection.class)).thenReturn(connection);
        Blob blob = mock(Blob.class);
        Clob clob = mock(Clob.class);
        NClob national = mock(NClob.class);
        when(connection.createBlob()).thenReturn(blob);
        when(connection.createClob()).thenReturn(clob);
        when(connection.createNClob()).thenReturn(national);
        Struct inner = mock(Struct.class);
        when(connection.createStruct(eq("APP.LOB_VALUE"), any(Object[].class))).thenReturn(inner);
        OracleArray array = mock(OracleArray.class);
        when(connection.createOracleArray(eq("APP.LOB_VALUES"), any(Object[].class))).thenReturn(array);

        AqAdt arrayValue = AqAdt.createArray("APP.LOB_VALUES", List.of(lobs()));
        AQjmsAdtMessage message = mapped(AqAdt.create("APP.ENVELOPE", List.of(arrayValue)));
        ((OracleData) message.getAdtPayload()).toJDBCObject(connection);

        verify(blob).setBytes(1, new byte[]{1, 2});
        verify(clob).setString(1, "text");
        verify(national).setString(1, "ž漢");
        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(connection).createStruct(eq("APP.LOB_VALUE"), values.capture());
        assertThat(values.getValue(), is(new Object[]{blob, clob, national}));
        verify(connection).createOracleArray("APP.LOB_VALUES", new Object[]{inner});
        verify(connection).createStruct("APP.ENVELOPE", new Object[]{array});
        verify(blob, never()).free();

        AqMessageMapper.releaseNative(message);
        AqMessageMapper.releaseNative(message);
        verify(blob).free();
        verify(clob).free();
        verify(national).free();
        verify(array).free();
    }

    @Test
    void freesOversizedIncomingLobsBeforeReadingTheirContents() throws Exception {
        Blob blob = mock(Blob.class);
        when(blob.length()).thenReturn(5L);
        OracleData blobData = detach(struct("APP.VALUE", blob), 4);
        MessagingException blobFailure = assertThrows(MessagingException.class, () -> snapshot(blobData));
        assertThat(blobFailure.getCause().getMessage(), containsString("exceeds max-body-bytes 4"));
        verify(blob, never()).getBytes(1, 5);
        verify(blob).free();

        for (Clob clob : List.of(mock(Clob.class), mock(NClob.class))) {
            when(clob.length()).thenReturn(5L);
            OracleData clobData = detach(struct("APP.VALUE", clob), 4);
            MessagingException clobFailure = assertThrows(MessagingException.class, () -> snapshot(clobData));
            assertThat(clobFailure.getCause().getMessage(), containsString("exceeds max-lob-characters 4"));
            verify(clob, never()).getSubString(1, 5);
            verify(clob).free();
        }
    }

    @Test
    void appliesIndependentBinaryAndCharacterLimitsToNestedLobs() throws Exception {
        Blob oversizedBlob = mock(Blob.class);
        when(oversizedBlob.length()).thenReturn(5L);
        Clob permittedClob = mock(Clob.class);
        when(permittedClob.length()).thenReturn(5L);
        when(permittedClob.getSubString(1, 5)).thenReturn("value");

        OracleData binaryFailure = detach(struct("APP.VALUE", oversizedBlob, permittedClob), 4, 6);
        MessagingException binary = assertThrows(MessagingException.class, () -> snapshot(binaryFailure));
        assertThat(binary.getCause().getMessage(), containsString("exceeds max-body-bytes 4"));
        verify(oversizedBlob, never()).getBytes(1, 5);
        verify(oversizedBlob).free();
        verify(permittedClob).getSubString(1, 5);
        verify(permittedClob).free();

        Blob permittedBlob = mock(Blob.class);
        when(permittedBlob.length()).thenReturn(5L);
        when(permittedBlob.getBytes(1, 5)).thenReturn(new byte[5]);
        NClob oversizedNational = mock(NClob.class);
        when(oversizedNational.length()).thenReturn(5L);

        OracleData characterFailure = detach(struct("APP.VALUE", permittedBlob, oversizedNational), 6, 4);
        MessagingException character = assertThrows(MessagingException.class, () -> snapshot(characterFailure));
        assertThat(character.getCause().getMessage(), containsString("exceeds max-lob-characters 4"));
        verify(permittedBlob).getBytes(1, 5);
        verify(permittedBlob).free();
        verify(oversizedNational, never()).getSubString(1, 5);
        verify(oversizedNational).free();
    }

    @Test
    void freesIncomingLobsAfterReadFailure() throws Exception {
        Blob blob = mock(Blob.class);
        when(blob.length()).thenReturn(1L);
        SQLException readFailure = new SQLException("read failed");
        when(blob.getBytes(1, 1)).thenThrow(readFailure);
        OracleData blobData = detach(struct("APP.VALUE", blob), 4);
        MessagingException blobFailure = assertThrows(MessagingException.class, () -> snapshot(blobData));
        assertThat(blobFailure.getCause(), sameInstance(readFailure));
        verify(blob).free();

        Clob clob = mock(Clob.class);
        when(clob.length()).thenReturn(1L);
        when(clob.getSubString(1, 1)).thenThrow(readFailure);
        OracleData clobData = detach(struct("APP.VALUE", clob), 4);
        MessagingException clobFailure = assertThrows(MessagingException.class, () -> snapshot(clobData));
        assertThat(clobFailure.getCause(), sameInstance(readFailure));
        verify(clob).free();
    }

    @Test
    void preservesMappingFailureAndFreesRemainingNestedResources() throws Exception {
        Blob blob = mock(Blob.class);
        IllegalStateException readFailure = new IllegalStateException("LOB read failed");
        when(blob.length()).thenThrow(readFailure);
        SQLException cleanupFailure = new SQLException("LOB free failed");
        doThrow(cleanupFailure).when(blob).free();
        Clob sibling = mock(Clob.class);
        when(sibling.length()).thenReturn(1L);
        when(sibling.getSubString(1, 1)).thenReturn("a");
        OracleArray array = mock(OracleArray.class);
        Struct first = struct("APP.VALUE", blob);
        Struct second = struct("APP.VALUE", sibling);
        when(array.getSQLTypeName()).thenReturn("APP.VALUES");
        when(array.getArray()).thenReturn(new Object[]{first, second});

        OracleData data = detach(struct("APP.ENVELOPE", array), 4);
        MessagingException failure = assertThrows(MessagingException.class, () -> snapshot(data));

        assertThat(failure.getCause(), sameInstance(readFailure));
        assertThat(readFailure.getSuppressed(), is(new Throwable[]{cleanupFailure}));
        verify(blob).free();
        verify(sibling).free();
        verify(array).free();
        SQLException outgoingFailure = assertThrows(SQLException.class,
                                                    () -> data.toJDBCObject(mock(Connection.class)));
        assertThat(outgoingFailure.getCause(), sameInstance(readFailure));
    }

    @Test
    void failedAdtPayloadRetainsMessageMetadata() throws Exception {
        OracleData data = detach(struct("APP.VALUE", new Object()), 4);
        AdtMessage incoming = mock(AdtMessage.class);
        when(incoming.getAdtPayload()).thenReturn(data);
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        when(incoming.getJMSMessageID()).thenReturn("ID:failed-adt");
        when(incoming.getJMSCorrelationID()).thenReturn("order-1");

        MessagingException failure = assertThrows(MessagingException.class,
                                                  () -> AqMessageMapper.fromNative(incoming, config(4)));
        assertThat(failure.getCause().getMessage(), containsString("Unsupported detached SQL attribute"));
        AqMessage<?> metadata = AqMessageMapper.metadataOnly(incoming);
        assertThat(metadata.messageId().orElseThrow(), is("ID:failed-adt"));
        assertThat(metadata.correlationId().orElseThrow(), is("order-1"));
        assertThat(metadata.bodyAvailable(), is(false));
        assertThrows(MessagingException.class, metadata::entity);
        AqMessageMapper.releaseNative(incoming);
    }

    @Test
    void connectionFailureDuringAdtDetachmentIsMarkedForRecovery() throws Exception {
        Blob blob = mock(Blob.class);
        SQLRecoverableException connectionFailure = new SQLRecoverableException("connection lost", "08006");
        when(blob.length()).thenThrow(connectionFailure);
        OracleData data = detach(struct("APP.VALUE", blob), 4);

        AqMappingException failure = assertThrows(AqMappingException.class, () -> snapshot(data));

        assertThat(failure.getCause(), sameInstance(connectionFailure));
        assertThat(AqReconnectSupport.recoverable(failure), is(true));
        verify(blob).free();
    }

    @Test
    void fatalErrorsEscapeThePayloadFactoryAfterCleanup() throws Exception {
        Blob blob = mock(Blob.class);
        AssertionError fatal = new AssertionError("fatal read failure");
        when(blob.length()).thenThrow(fatal);

        AssertionError failure = assertThrows(AssertionError.class, () -> detach(struct("APP.VALUE", blob), 4));

        assertThat(failure, sameInstance(fatal));
        verify(blob).free();
    }

    @Test
    void freesNewLobWhenWritingContentFails() throws Exception {
        Connection connection = mock(Connection.class);
        Blob blob = mock(Blob.class);
        when(connection.createBlob()).thenReturn(blob);
        SQLException failure = new SQLException("write failed");
        when(blob.setBytes(1, new byte[]{1, 2})).thenThrow(failure);
        AQjmsAdtMessage message = mapped(lobs());

        SQLException thrown = assertThrows(SQLException.class,
                                           () -> ((OracleData) message.getAdtPayload()).toJDBCObject(connection));

        assertThat(thrown, sameInstance(failure));
        verify(blob).free();
        verify(connection, never()).createClob();
        AqMessageMapper.releaseNative(message);
        verify(blob).free();
    }

    @Test
    void freesAllLobsAfterStructFailureAndPreservesCleanupFailure() throws Exception {
        Connection connection = mock(Connection.class);
        Blob blob = mock(Blob.class);
        Clob clob = mock(Clob.class);
        NClob national = mock(NClob.class);
        when(connection.createBlob()).thenReturn(blob);
        when(connection.createClob()).thenReturn(clob);
        when(connection.createNClob()).thenReturn(national);
        SQLException failure = new SQLException("invalid SQL type");
        when(connection.createStruct(eq("APP.LOB_VALUE"), any(Object[].class))).thenThrow(failure);
        doThrow(new SQLException("cannot free NCLOB")).when(national).free();
        AQjmsAdtMessage message = mapped(lobs());

        SQLException thrown = assertThrows(SQLException.class,
                                           () -> ((OracleData) message.getAdtPayload()).toJDBCObject(connection));

        assertThat(thrown, sameInstance(failure));
        assertThat(thrown.getSuppressed().length, is(1));
        assertThat(thrown.getSuppressed()[0].getCause().getMessage(), is("cannot free NCLOB"));
        verify(blob).free();
        verify(clob).free();
        verify(national).free();
        AqMessageMapper.releaseNative(message);
    }

    @Test
    void freesLobIfNativeMessageCreationFailsAfterConversion() throws Exception {
        AQjmsSession session = mock(AQjmsSession.class);
        Connection connection = mock(Connection.class);
        Blob blob = mock(Blob.class);
        when(connection.createBlob()).thenReturn(blob);
        when(session.createORAMessage(any(OracleData.class))).thenAnswer(invocation -> {
            OracleData data = invocation.getArgument(0);
            data.toJDBCObject(connection);
            throw new JMSException("message creation failed");
        });
        AqAdt body = AqAdt.create("APP.BLOB_VALUE", List.of(AqBlob.create(new byte[]{1})));

        MessagingException failure = assertThrows(MessagingException.class,
                                                  () -> AqMessageMapper.toNative(session, Message.create(body), config(4)));

        assertThat(failure.getCause().getMessage(), containsString("message creation failed"));
        verify(blob).free();
    }

    @Test
    void freesLobIfMessageMetadataIsRejectedAfterConversion() throws Exception {
        AQjmsSession session = mock(AQjmsSession.class);
        Connection connection = mock(Connection.class);
        Blob blob = mock(Blob.class);
        when(connection.createBlob()).thenReturn(blob);
        AQjmsAdtMessage nativeMessage = mock(AQjmsAdtMessage.class);
        when(session.createORAMessage(any(OracleData.class))).thenAnswer(invocation -> {
            OracleData data = invocation.getArgument(0);
            data.toJDBCObject(connection);
            when(nativeMessage.getAdtPayload()).thenReturn(data);
            return nativeMessage;
        });
        AqMessage<AqAdt> message = AqMessage.builder(AqAdt.create("APP.BLOB_VALUE", List.of(AqBlob.create(new byte[]{1}))))
                .type("unsupported").build();

        assertThrows(MessagingException.class, () -> AqMessageMapper.toNative(session, message, config(4)));

        verify(blob).free();
    }

    private static AqAdt lobs() {
        return AqAdt.create("APP.LOB_VALUE", List.of(AqBlob.create(new byte[]{1, 2}),
                                                   AqClob.create("text"),
                                                   AqClob.createNational("ž漢")));
    }

    private static Struct struct(String typeName, Object... attributes) throws SQLException {
        Struct struct = mock(Struct.class);
        when(struct.getSQLTypeName()).thenReturn(typeName);
        when(struct.getAttributes()).thenReturn(attributes);
        return struct;
    }

    private static OracleData detach(Struct struct, int limit) throws SQLException {
        return detach(struct, limit, limit);
    }

    private static OracleData detach(Struct struct, int binaryLimit, int characterLimit) throws SQLException {
        OracleDataFactory factory = (OracleDataFactory) AqMessageMapper.payloadFactory(config(binaryLimit, characterLimit));
        return factory.create(struct, Types.STRUCT);
    }

    private static AqAdt snapshot(OracleData data) throws JMSException {
        AdtMessage incoming = mock(AdtMessage.class);
        when(incoming.getAdtPayload()).thenReturn(data);
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        return (AqAdt) AqMessageMapper.fromNative(incoming, config(4)).entity();
    }

    private static AQjmsAdtMessage mapped(AqAdt body) throws JMSException {
        AQjmsSession session = mock(AQjmsSession.class);
        AQjmsAdtMessage message = mock(AQjmsAdtMessage.class);
        when(session.createORAMessage(any(OracleData.class))).thenAnswer(invocation -> {
            when(message.getAdtPayload()).thenReturn(invocation.getArgument(0));
            return message;
        });
        return (AQjmsAdtMessage) AqMessageMapper.toNative(session, Message.create(body), config(4));
    }

    private static AqRuntimeConfig config(int limit) {
        return config(limit, limit);
    }

    private static AqRuntimeConfig config(int binaryLimit, int characterLimit) {
        return AqRuntimeConfig.incoming(AqConnectorConfig.builder().name("aq").destination("TEST")
                                                .payloadType(AqPayloadType.ADT).maxBodyBytes(Size.create(binaryLimit))
                                                .maxLobCharacters(characterLimit).buildPrototype(),
                                        AqIncomingConfig.builder().connector("aq").channelName("test").build());
    }
}
