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

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jms.MapMessage;
import javax.jms.Session;
import javax.jms.TextMessage;

import io.helidon.messaging.Message;
import io.helidon.messaging.MessagingException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AqPayloadConverterTest {
    @Test
    void incomingTextCanBecomeANestedApplicationMapWithNumericKeys() throws Exception {
        AqRuntimeConfig config = config(converter());
        TextMessage incoming = mock(TextMessage.class);
        when(incoming.getText()).thenReturn("order");
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());

        AqMessage<?> received = AqMessageMapper.fromNative(incoming, config);

        assertThat(received.entity(), is(applicationMap("order")));
        assertThat(received.entity(), is(applicationMap("order")));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) received.entity()).clear());
    }

    @Test
    void genericAndAqApplicationMapsAreConvertedBeforeSelectingTheWireType() throws Exception {
        Map<Object, Object> entity = applicationMap("order");
        for (Message<?> message : List.of(Message.create(entity), AqMessage.mapBuilder(entity).build())) {
            Session session = mock(Session.class);
            TextMessage outgoing = mock(TextMessage.class);
            when(session.createTextMessage("order")).thenReturn(outgoing);

            assertThat(AqMessageMapper.toNative(session, message, config(converter())), sameInstance(outgoing));

            verify(session).createTextMessage("order");
            verify(session, never()).createMapMessage();
            verify(session, never()).createObjectMessage(any(Serializable.class));
        }
    }

    @Test
    void incomingAndOutgoingApplicationMapsSnapshotTheirStructureAndBinaryValues() throws Exception {
        byte[] bytes = {1, 2};
        Map<Object, Object> source = new LinkedHashMap<>(applicationMap("order"));
        source.put(9, bytes);
        AqPayloadConverter converter = mock(AqPayloadConverter.class);
        when(converter.fromPayload("order")).thenReturn(source);
        TextMessage incoming = mock(TextMessage.class);
        when(incoming.getText()).thenReturn("order");
        when(incoming.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        List<AqMessage<?>> messages = List.of(AqMessageMapper.fromNative(incoming, config(converter)),
                                              AqMessage.mapBuilder(source).build());

        source.clear();
        bytes[0] = 99;
        for (AqMessage<?> message : messages) {
            Map<?, ?> first = (Map<?, ?>) message.entity();
            assertThat(first.get(7), is(Map.of("label", "order")));
            assertThat((byte[]) first.get(9), is(new byte[]{1, 2}));
            ((byte[]) first.get(9))[1] = 99;
            assertThat((byte[]) ((Map<?, ?>) message.entity()).get(9), is(new byte[]{1, 2}));
            assertThrows(UnsupportedOperationException.class, first::clear);
        }
    }

    @Test
    void invalidNativeMapsRemainRejectedEvenWhenObjectMessagesAreEnabled() throws Exception {
        AqRuntimeConfig nativeConfig = AqRuntimeConfig.incoming(
                AqConnectorConfig.builder().name("aq").destination("TEST").allowObjectMessages(true).buildPrototype(),
                AqIncomingConfig.builder().connector("aq").channelName("test").build());
        for (Map<?, ?> invalid : List.of(Map.of(7, "value"), Map.of("", "value"),
                                        Map.of("nested", Map.of("label", "order")))) {
            Session session = mock(Session.class);
            MapMessage nativeMessage = mock(MapMessage.class);
            when(session.createMapMessage()).thenReturn(nativeMessage);
            AqMessage<?> message = AqMessage.mapBuilder(invalid).build();

            assertThrows(MessagingException.class, () -> AqMessageMapper.toNative(session, message, nativeConfig));

            AqPayloadConverter converter = mock(AqPayloadConverter.class);
            when(converter.toPayload("order")).thenReturn(invalid);
            assertThrows(MessagingException.class,
                         () -> AqMessageMapper.toNative(session, Message.create("order"), config(converter)));
            verify(session, never()).createObjectMessage(any(Serializable.class));
        }
    }

    @Test
    void converterDoesNotBypassNativeMapValueValidation() throws Exception {
        MapMessage incoming = mock(MapMessage.class);
        when(incoming.getMapNames()).thenReturn(Collections.enumeration(List.of("nested")));
        when(incoming.getObject("nested")).thenReturn(Map.of("label", "order"));
        AqPayloadConverter converter = mock(AqPayloadConverter.class);

        assertThrows(IllegalArgumentException.class, () -> AqMessageMapper.fromNative(incoming, config(converter)));

        verify(converter, never()).fromPayload(any());
    }

    private static Map<Object, Object> applicationMap(Object label) {
        return Map.of(7, Map.of("label", label), "items", List.of(label));
    }

    private static AqPayloadConverter converter() {
        return new AqPayloadConverter() {
            @Override
            public Object fromPayload(Object payload) {
                return applicationMap(payload);
            }

            @Override
            public Object toPayload(Object entity) {
                return ((Map<?, ?>) ((Map<?, ?>) entity).get(7)).get("label");
            }
        };
    }

    private static AqRuntimeConfig config(AqPayloadConverter converter) {
        return AqRuntimeConfig.incoming(
                AqConnectorConfig.builder().name("aq").destination("TEST").payloadConverter(converter).buildPrototype(),
                AqIncomingConfig.builder().connector("aq").channelName("test").build());
    }
}
