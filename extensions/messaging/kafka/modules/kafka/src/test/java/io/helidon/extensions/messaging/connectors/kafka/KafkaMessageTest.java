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

package io.helidon.extensions.messaging.connectors.kafka;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.helidon.messaging.MessageHeader;
import io.helidon.messaging.MessageHeaderValue;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaMessageTest {
    @Test
    void rejectsNullConstructionState() {
        assertThrows(NullPointerException.class, () -> KafkaMessage.create((String) null));
        assertThrows(NullPointerException.class, () -> KafkaMessage.create(null, "payload"));
        assertThrows(NullPointerException.class, () -> KafkaMessage.builder((String) null));
        assertThrows(NullPointerException.class, () -> KafkaMessage.builder().entity(null));
        assertThrows(NullPointerException.class, () -> KafkaMessage.builder().key(null));
        assertThrows(NullPointerException.class, () -> KafkaMessage.builder().build());
        ConsumerRecord<String, String> tombstone = new ConsumerRecord<>("topic", 0, 1L, "key", null);
        assertThrows(NullPointerException.class, () -> KafkaMessageImpl.create(tombstone));
    }

    @Test
    void rejectsNullHeaderState() {
        KafkaMessage.Builder<Void, String> builder = KafkaMessage.builder("payload");

        assertThrows(NullPointerException.class, () -> builder.addHeader(null, "value"));
        assertThrows(NullPointerException.class, () -> builder.addHeader("name", null));
        assertThrows(NullPointerException.class, () -> builder.addRawHeader(null, new byte[0]));
        assertThrows(NullPointerException.class, () -> builder.addRawHeader("name", null));
        assertThrows(NullPointerException.class, () -> builder.addNullHeader(null));
    }

    @Test
    void createsKeylessMessage() {
        KafkaMessage<Void, String> message = KafkaMessage.create("payload");

        assertThat(message.key().isEmpty(), is(true));
        assertThat(message.entity(), is("payload"));
    }

    @Test
    void testProgrammaticMessageContainsOnlyOutgoingMetadata() {
        byte[] binary = {1, 2};
        KafkaMessage<String, String> message = KafkaMessage.<String, String>builder("payload")
                .key("key")
                .addHeader("trace", "abc")
                .addRawHeader("binary", binary)
                .update(KafkaMessage.Builder::clearKey)
                .key("key")
                .get();
        binary[0] = 9;

        assertThat(message.key().orElseThrow(), is("key"));
        assertThat(message.entity(), is("payload"));
        assertThat(message.headerValue("trace").orElseThrow(),
                   is(MessageHeaderValue.BinaryValue.create("abc".getBytes(StandardCharsets.UTF_8))));
        assertThat(message.kafkaHeaders().get(1).value().orElseThrow()[0], is((byte) 1));
        assertThat(message.topic().isEmpty(), is(true));
        assertThat(message.partition().isEmpty(), is(true));
        assertThat(message.offset().isEmpty(), is(true));
        assertThat(message.timestamp().isEmpty(), is(true));
        assertThat(message.timestampType().isEmpty(), is(true));
        assertThat(message.leaderEpoch().isEmpty(), is(true));
    }

    @Test
    void testPortableHeadersPreserveNativeOrderDuplicatesBinaryAndNullValues() {
        KafkaMessage<String, String> message = KafkaMessage.<String, String>builder("payload")
                .key("key")
                .addHeader("trace", "first")
                .addNullHeader("trace")
                .addHeader("trace", "last")
                .addNullHeader("trace")
                .addNullHeader("only-null")
                .build();

        assertThat(message.headers().entries(), is(List.of(
                MessageHeader.create("trace", MessageHeaderValue.BinaryValue.create("first".getBytes(StandardCharsets.UTF_8))),
                MessageHeader.create("trace", MessageHeaderValue.NullValue.create()),
                MessageHeader.create("trace", MessageHeaderValue.BinaryValue.create("last".getBytes(StandardCharsets.UTF_8))),
                MessageHeader.create("trace", MessageHeaderValue.NullValue.create()),
                MessageHeader.create("only-null", MessageHeaderValue.NullValue.create()))));
        assertThat(message.headerValue("trace").orElseThrow(), is(MessageHeaderValue.NullValue.create()));
        assertThat(message.headerValue("only-null").orElseThrow(), is(MessageHeaderValue.NullValue.create()));
        assertThat(message.kafkaHeaders().stream().map(KafkaMessage.Header::name).toList(),
                   is(List.of("trace", "trace", "trace", "trace", "only-null")));
        assertThat(message.kafkaHeaders().get(1).value().isEmpty(), is(true));
        assertThat(message.kafkaHeaders().get(3).value().isEmpty(), is(true));
        assertThat(message.kafkaHeaders().get(4).value().isEmpty(), is(true));
    }
}
