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

package io.helidon.extensions.messaging.connectors.pulsar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

import io.helidon.messaging.MessagingException;
import io.helidon.messaging.spi.ConnectorDirection;

import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.Field;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulsarSchemaTypeTest {
    @Test
    void mapsBuiltInSchemasAndAcceptsTheirExactPayloadTypes() {
        Map<PulsarSchemaType, Object> payloads = Map.ofEntries(
                Map.entry(PulsarSchemaType.STRING, "value"),
                Map.entry(PulsarSchemaType.BYTES, new byte[] {1}),
                Map.entry(PulsarSchemaType.BYTEBUFFER, ByteBuffer.wrap(new byte[] {1})),
                Map.entry(PulsarSchemaType.BOOLEAN, true),
                Map.entry(PulsarSchemaType.INT8, (byte) 1),
                Map.entry(PulsarSchemaType.INT16, (short) 1),
                Map.entry(PulsarSchemaType.INT32, 1),
                Map.entry(PulsarSchemaType.INT64, 1L),
                Map.entry(PulsarSchemaType.FLOAT, 1F),
                Map.entry(PulsarSchemaType.DOUBLE, 1D),
                Map.entry(PulsarSchemaType.DATE, new Date(1)),
                Map.entry(PulsarSchemaType.TIME, new Time(1)),
                Map.entry(PulsarSchemaType.TIMESTAMP, new Timestamp(1)),
                Map.entry(PulsarSchemaType.INSTANT, Instant.EPOCH),
                Map.entry(PulsarSchemaType.LOCAL_DATE, LocalDate.EPOCH),
                Map.entry(PulsarSchemaType.LOCAL_TIME, LocalTime.NOON),
                Map.entry(PulsarSchemaType.LOCAL_DATE_TIME, LocalDateTime.of(LocalDate.EPOCH, LocalTime.NOON)));

        payloads.forEach((schema, payload) -> {
            assertDoesNotThrow(() -> schema.snapshot(payload, ConnectorDirection.INCOMING));
            assertDoesNotThrow(() -> schema.snapshot(payload, ConnectorDirection.OUTGOING));
        });
        for (PulsarSchemaType schema : PulsarSchemaType.values()) {
            assertThat(schema.snapshot(null, ConnectorDirection.INCOMING), is((Object) null));
            assertThat(schema.snapshot(null, ConnectorDirection.OUTGOING), is((Object) null));
        }

        Map<PulsarSchemaType, Schema<?>> schemas = Map.ofEntries(
                Map.entry(PulsarSchemaType.STRING, Schema.STRING),
                Map.entry(PulsarSchemaType.BYTES, Schema.BYTES),
                Map.entry(PulsarSchemaType.BYTEBUFFER, Schema.BYTEBUFFER),
                Map.entry(PulsarSchemaType.BOOLEAN, Schema.BOOL),
                Map.entry(PulsarSchemaType.INT8, Schema.INT8),
                Map.entry(PulsarSchemaType.INT16, Schema.INT16),
                Map.entry(PulsarSchemaType.INT32, Schema.INT32),
                Map.entry(PulsarSchemaType.INT64, Schema.INT64),
                Map.entry(PulsarSchemaType.FLOAT, Schema.FLOAT),
                Map.entry(PulsarSchemaType.DOUBLE, Schema.DOUBLE),
                Map.entry(PulsarSchemaType.DATE, Schema.DATE),
                Map.entry(PulsarSchemaType.TIME, Schema.TIME),
                Map.entry(PulsarSchemaType.TIMESTAMP, Schema.TIMESTAMP),
                Map.entry(PulsarSchemaType.INSTANT, Schema.INSTANT),
                Map.entry(PulsarSchemaType.LOCAL_DATE, Schema.LOCAL_DATE),
                Map.entry(PulsarSchemaType.LOCAL_TIME, Schema.LOCAL_TIME),
                Map.entry(PulsarSchemaType.LOCAL_DATE_TIME, Schema.LOCAL_DATE_TIME));
        schemas.forEach((type, schema) -> assertThat(type.schema(ConnectorDirection.INCOMING),
                                                     sameInstance((Object) schema)));
    }

    @Test
    void autoSchemaIsDirectionAware() {
        Schema<Object> incoming = PulsarSchemaType.AUTO.schema(ConnectorDirection.INCOMING);
        Schema<Object> outgoing = PulsarSchemaType.AUTO.schema(ConnectorDirection.OUTGOING);
        assertThat(incoming.getClass(), sameInstance((Object) Schema.AUTO_CONSUME().getClass()));
        assertThat(outgoing.getClass(), sameInstance((Object) Schema.AUTO_PRODUCE_BYTES().getClass()));

        GenericRecord record = new GenericRecord() {
            @Override
            public byte[] getSchemaVersion() {
                return new byte[0];
            }

            @Override
            public List<Field> getFields() {
                return List.of();
            }

            @Override
            public Object getField(String fieldName) {
                return null;
            }

            @Override
            public org.apache.pulsar.common.schema.SchemaType getSchemaType() {
                return org.apache.pulsar.common.schema.SchemaType.STRING;
            }

            @Override
            public Object getNativeObject() {
                return "value";
            }
        };
        assertThat(PulsarSchemaType.AUTO.snapshot(record, ConnectorDirection.INCOMING), sameInstance(record));

        byte[] bytes = {1, 2};
        byte[] snapshot = (byte[]) PulsarSchemaType.AUTO.snapshot(bytes, ConnectorDirection.OUTGOING);
        assertThat(snapshot, not(sameInstance(bytes)));
        assertThat(snapshot, is(bytes));

        MessagingException incomingFailure = assertThrows(
                MessagingException.class,
                () -> PulsarSchemaType.AUTO.snapshot(bytes, ConnectorDirection.INCOMING));
        assertThat(incomingFailure.getMessage(), containsString(GenericRecord.class.getName()));
        MessagingException outgoingFailure = assertThrows(
                MessagingException.class,
                () -> PulsarSchemaType.AUTO.snapshot(record, ConnectorDirection.OUTGOING));
        assertThat(outgoingFailure.getMessage(), containsString("byte[]"));
    }

    @Test
    void rejectsWrongPayloadTypesWithoutCoercion() {
        MessagingException failure = assertThrows(
                MessagingException.class,
                () -> PulsarSchemaType.INT32.snapshot(1L, ConnectorDirection.OUTGOING));
        assertThat(failure.getMessage(), containsString(Integer.class.getName()));
        assertThat(failure.getMessage(), containsString(Long.class.getName()));
    }

    @Test
    void snapshotsEntireByteBufferPrefixWithoutMutatingSourceState() {
        ByteBuffer source = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        source.put(new byte[] {1, 2, 3, 4, 5, 6});
        source.position(2);
        source.limit(4);

        ByteBuffer snapshot = (ByteBuffer) PulsarSchemaType.BYTEBUFFER.snapshot(
                source,
                ConnectorDirection.OUTGOING);
        assertThat(source.position(), is(2));
        assertThat(source.limit(), is(4));
        assertThat(snapshot.position(), is(0));
        assertThat(snapshot.limit(), is(4));
        assertThat(snapshot.capacity(), is(4));
        assertThat(snapshot.order(), is(ByteOrder.LITTLE_ENDIAN));
        assertThat(snapshot.get(0), is((byte) 1));
        assertThat(snapshot.get(3), is((byte) 4));

        source.put(0, (byte) 9);
        assertThat(snapshot.get(0), is((byte) 1));
    }

    @Test
    void messageEnvelopeSnapshotsMutableBuiltInPayloads() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3});
        PulsarMessage<ByteBuffer> bufferMessage = PulsarMessage.create(buffer);
        buffer.put(0, (byte) 9);
        assertThat(bufferMessage.entity().get(0), is((byte) 1));
        bufferMessage.entity().put(0, (byte) 8);
        assertThat(bufferMessage.entity().get(0), is((byte) 1));

        Timestamp timestamp = Timestamp.valueOf("2026-08-24 12:34:56.123456789");
        PulsarMessage<Timestamp> timestampMessage = PulsarMessage.create(timestamp);
        timestamp.setNanos(1);
        assertThat(timestampMessage.entity().getNanos(), is(123456789));
        timestampMessage.entity().setNanos(2);
        assertThat(timestampMessage.entity().getNanos(), is(123456789));

        Date date = new Date(1234);
        Date dateSnapshot = (Date) PulsarSchemaType.DATE.snapshot(date, ConnectorDirection.OUTGOING);
        assertThat(dateSnapshot, not(sameInstance(date)));
        date.setTime(5678);
        assertThat(dateSnapshot.getTime(), is(1234L));

        Time time = new Time(1234);
        Time timeSnapshot = (Time) PulsarSchemaType.TIME.snapshot(time, ConnectorDirection.OUTGOING);
        assertThat(timeSnapshot, not(sameInstance(time)));
        time.setTime(5678);
        assertThat(timeSnapshot.getTime(), is(1234L));
    }
}
