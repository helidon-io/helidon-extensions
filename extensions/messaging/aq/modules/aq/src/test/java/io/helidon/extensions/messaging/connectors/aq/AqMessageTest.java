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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.helidon.messaging.MessageHeaderValue;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AqMessageTest {
    @Test
    void outgoingFactoriesRetainPrototypeAndSnapshotPayload() {
        byte[] bytes = {1, 2};
        AqMessageConfig<byte[]> config = AqMessageConfig.<byte[]>builder().entity(bytes)
                .correlationId("order-1").putProperty("attempt", (short) 2)
                .localMetadata("local", "private").buildPrototype();

        AqOutgoingMessage<byte[]> direct = AqOutgoingMessage.create(config);
        AqOutgoingMessage<byte[]> fromPrototype = config.build();
        assertThat(direct.prototype(), sameInstance(config));
        assertThat(fromPrototype.prototype(), sameInstance(config));
        config.entity()[0] = 9;
        direct.entity()[1] = 8;

        AqMessage<byte[]> common = direct;
        assertThat(common.entity(), is(new byte[]{1, 2}));
        assertThat(fromPrototype.entity(), is(new byte[]{1, 2}));
        assertThat(common.bodyAvailable(), is(true));
        assertThat(common.correlationId().orElseThrow(), is("order-1"));
        assertThat(common.headerValue("attempt").orElseThrow(), is(MessageHeaderValue.IntegerValue.create(2)));
        assertThat(common.localMetadata().text("local").orElseThrow(), is("private"));
        assertThat(common.messageId().isEmpty(), is(true));
        assertThat(common.timestamp().isEmpty(), is(true));
        assertThrows(NullPointerException.class, () -> AqOutgoingMessage.create((AqMessageConfig<String>) null));
        assertThrows(NullPointerException.class,
                     () -> AqOutgoingMessage.create((Consumer<AqMessageConfig.Builder<String>>) null));
    }

    @Test
    void outgoingBuilderAndConsumerFactoriesSnapshotApplicationMaps() {
        byte[] bytes = {1, 2};
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("binary", bytes);
        AqOutgoingMessage<Map<String, Object>> built = AqOutgoingMessage.<Map<String, Object>>builder()
                .entity(payload).build();
        AqOutgoingMessage<Map<String, Object>> supplied = AqOutgoingMessage.<Map<String, Object>>create(builder ->
                builder.entity(payload).correlationId("consumer"));
        bytes[0] = 9;
        payload.clear();
        ((byte[]) built.entity().get("binary"))[1] = 8;

        assertThat((byte[]) built.entity().get("binary"), is(new byte[]{1, 2}));
        assertThat((byte[]) supplied.entity().get("binary"), is(new byte[]{1, 2}));
        assertThat(supplied.correlationId().orElseThrow(), is("consumer"));
        assertThat(supplied.prototype().correlationId(), is(supplied.correlationId()));
        assertThrows(UnsupportedOperationException.class, () -> supplied.entity().put("new", true));
    }

    @Test
    void snapshotsBinaryBodiesAndTypedProperties() {
        byte[] bytes = {1, 2};
        AqMessage<byte[]> message = AqMessage.builder(bytes)
                .correlationId("order-1")
                .putProperty("attempt", (short) 2)
                .putProperty("confirmed", true)
                .addRecipient(AqRecipient.builder().name("DELIVERY").build())
                .build();
        bytes[0] = 9;
        message.entity()[1] = 8;

        assertThat(message.entity(), is(new byte[]{1, 2}));
        assertThat(message.properties().get("attempt"), is((short) 2));
        assertThat(message.headerValue("attempt").orElseThrow(), is(MessageHeaderValue.IntegerValue.create(2)));
        assertThat(message.headerValue("confirmed").orElseThrow(), is(MessageHeaderValue.BooleanValue.create(true)));
        assertThat(message.recipients().getFirst().name().orElseThrow(), is("DELIVERY"));
    }

    @Test
    void snapshotsMapValuesAndBufferPositions() {
        byte[] bytes = {1, 2};
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("binary", bytes);
        map.put("empty", null);
        AqMessage<Map<String, Object>> message = AqMessage.mapBuilder(map).build();
        bytes[0] = 4;
        map.put("later", true);
        ((byte[]) message.entity().get("binary"))[1] = 5;

        assertThat(((byte[]) message.entity().get("binary")), is(new byte[]{1, 2}));
        assertThat(message.entity().containsKey("empty"), is(true));
        assertThat(message.entity().containsKey("later"), is(false));
        assertThrows(UnsupportedOperationException.class, () -> message.entity().put("new", 1));

        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{0, 1, 2});
        buffer.position(1);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        AqMessage<ByteBuffer> binary = AqMessage.builder(buffer).build();
        buffer.put(1, (byte) 9);
        binary.entity().get();
        assertThat(binary.entity().remaining(), is(2));
        assertThat(binary.entity().order(), is(ByteOrder.LITTLE_ENDIAN));
        assertThat(binary.entity().get(), is((byte) 1));
    }

    @Test
    void snapshotsSqlAttributesAndNulls() {
        byte[] bytes = {1, 2};
        Timestamp timestamp = Timestamp.valueOf("2026-10-15 10:15:30.123456789");
        AqAdt adt = AqAdt.create("APP.ORDER_TYPE", List.of(bytes, timestamp, AqAdt.NullValue.INSTANCE));
        bytes[0] = 8;
        timestamp.setNanos(0);
        ((byte[]) adt.attributes().getFirst())[1] = 9;
        ((Timestamp) adt.attributes().get(1)).setNanos(0);

        assertThat((byte[]) adt.attributes().getFirst(), is(new byte[]{1, 2}));
        assertThat(((Timestamp) adt.attributes().get(1)).getNanos(), is(123456789));
        assertThat(adt.attributes().get(2), is(AqAdt.NullValue.INSTANCE));
        assertThrows(IllegalArgumentException.class,
                     () -> AqAdt.create("APP.TYPE", List.of(new Object())));
    }

    @Test
    void sqlValueFactoriesPreserveShapesAndSnapshotLists() {
        List<Object> values = new ArrayList<>(List.of("first", AqAdt.NullValue.INSTANCE));
        AqAdt object = AqAdt.create("APP.OBJECT_TYPE", values);
        AqAdt array = AqAdt.createArray("APP.ARRAY_TYPE", values);
        values.clear();

        assertThat(object.kind(), is(AqAdt.Kind.STRUCT));
        assertThat(array.kind(), is(AqAdt.Kind.ARRAY));
        assertThat(object.typeName(), is("APP.OBJECT_TYPE"));
        assertThat(array.typeName(), is("APP.ARRAY_TYPE"));
        assertThat(object.attributes(), is(List.of("first", AqAdt.NullValue.INSTANCE)));
        assertThat(array.attributes(), is(object.attributes()));
        assertThrows(UnsupportedOperationException.class, () -> array.attributes().clear());
        assertThat(AqAdt.createArray("APP.EMPTY_ARRAY", List.of()).attributes(), is(List.of()));
    }

    @Test
    void sqlValueFactoriesRejectInvalidInputs() {
        assertThrows(NullPointerException.class, () -> AqAdt.create(null, List.of()));
        assertThrows(NullPointerException.class, () -> AqAdt.createArray(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> AqAdt.create(" ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> AqAdt.createArray("", List.of()));
        assertThrows(NullPointerException.class, () -> AqAdt.create("APP.OBJECT_TYPE", null));
        assertThrows(NullPointerException.class, () -> AqAdt.createArray("APP.ARRAY_TYPE", null));
        assertThrows(NullPointerException.class, () -> AqAdt.create("APP.OBJECT_TYPE", Collections.singletonList(null)));
        assertThrows(NullPointerException.class, () -> AqAdt.createArray("APP.ARRAY_TYPE", Collections.singletonList(null)));
    }

    @Test
    void concreteMapFactoriesNormalizeEntityType() {
        HashMap<String, Object> entity = new HashMap<>();
        entity.put("one", 1);
        AqMessage<Map<String, Object>> message = AqMessage.create(entity);
        AqMessage<Map<String, Object>> built = AqMessage.mapBuilder(entity).build();
        entity.clear();
        assertThat(message.entity(), is(Map.of("one", 1)));
        assertThat(built.entity(), is(Map.of("one", 1)));
        assertThrows(IllegalArgumentException.class, () -> AqMessage.<HashMap<String, Object>>create(entity));
        assertThrows(IllegalArgumentException.class, () -> AqMessage.builder(entity));
    }

    @Test
    void validatesRecipientsWithoutRequiringALocalConsumerName() {
        assertThrows(IllegalArgumentException.class, () -> AqRecipient.builder().build());
        assertThrows(IllegalArgumentException.class, () -> AqRecipient.builder().name(" ").build());
        assertThrows(IllegalArgumentException.class, () -> AqRecipient.builder().name("consumer").protocol(-1).build());
        AqRecipient remote = AqRecipient.builder().address("APP.EVENTS@REMOTE").build();
        assertThat(remote.name().isEmpty(), is(true));
        assertThat(remote.address().orElseThrow(), is("APP.EVENTS@REMOTE"));
    }

    @Test
    void rejectsNonportableProperties() {
        assertThrows(IllegalArgumentException.class,
                     () -> AqMessage.builder("body").putProperty("bad-name", "value").build());
        assertThrows(IllegalArgumentException.class,
                     () -> AqMessage.builder("body").putProperty("AND", true).build());
        assertThrows(IllegalArgumentException.class,
                     () -> AqMessage.builder("body").putProperty("JMSXGroupSeq", "2").build());
        assertThrows(IllegalArgumentException.class,
                     () -> AqMessage.builder("body").putProperty("property", new byte[]{1}).build());
    }
}
