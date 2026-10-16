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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.helidon.messaging.MessageHeaderValue;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AqMessageTest {
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
        AqAdt adt = AqAdt.builder()
                .typeName("APP.ORDER_TYPE")
                .addAttribute(bytes)
                .addAttribute(timestamp)
                .addAttribute(AqAdt.NullValue.INSTANCE)
                .build();
        bytes[0] = 8;
        timestamp.setNanos(0);
        ((byte[]) adt.attributes().getFirst())[1] = 9;
        ((Timestamp) adt.attributes().get(1)).setNanos(0);

        assertThat((byte[]) adt.attributes().getFirst(), is(new byte[]{1, 2}));
        assertThat(((Timestamp) adt.attributes().get(1)).getNanos(), is(123456789));
        assertThat(adt.attributes().get(2), is(AqAdt.NullValue.INSTANCE));
        assertThrows(IllegalArgumentException.class,
                     () -> AqAdt.builder().typeName("APP.TYPE").addAttribute(new Object()).build());
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
