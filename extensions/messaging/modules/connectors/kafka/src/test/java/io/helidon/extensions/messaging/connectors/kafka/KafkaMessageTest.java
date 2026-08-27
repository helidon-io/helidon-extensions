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

import io.helidon.messaging.HeaderValue;
import io.helidon.messaging.MessageHeader;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaMessageTest {
    @Test
    void rejectsNullPayloads() {
        assertThrows(NullPointerException.class, () -> KafkaMessage.builder("key", null));
        ConsumerRecord<String, String> tombstone = new ConsumerRecord<>("topic", 0, 1L, "key", null);
        assertThrows(NullPointerException.class, () -> KafkaMessageImpl.create(tombstone));
    }

    @Test
    void testProgrammaticMessageContainsOnlyOutgoingMetadata() {
        byte[] binary = {1, 2};
        KafkaMessage<String, String> message = KafkaMessage.<String, String>builder("key", "payload")
                .header("trace", "abc")
                .rawHeader("binary", binary)
                .build();
        binary[0] = 9;

        assertThat(message.key().orElseThrow(), is("key"));
        assertThat(message.entity(), is("payload"));
        assertThat(message.headerValue("trace").orElseThrow(),
                   is(HeaderValue.binary("abc".getBytes(StandardCharsets.UTF_8))));
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
        KafkaMessage<String, String> message = KafkaMessage.<String, String>builder("key", "payload")
                .header("trace", "first")
                .rawHeader("trace", null)
                .header("trace", "last")
                .rawHeader("trace", null)
                .rawHeader("only-null", null)
                .build();

        assertThat(message.headers().entries(), is(List.of(
                MessageHeader.create("trace", HeaderValue.binary("first".getBytes(StandardCharsets.UTF_8))),
                MessageHeader.create("trace", HeaderValue.nullValue()),
                MessageHeader.create("trace", HeaderValue.binary("last".getBytes(StandardCharsets.UTF_8))),
                MessageHeader.create("trace", HeaderValue.nullValue()),
                MessageHeader.create("only-null", HeaderValue.nullValue()))));
        assertThat(message.headerValue("trace").orElseThrow(), is(HeaderValue.nullValue()));
        assertThat(message.headerValue("only-null").orElseThrow(), is(HeaderValue.nullValue()));
        assertThat(message.kafkaHeaders().stream().map(KafkaMessage.Header::name).toList(),
                   is(List.of("trace", "trace", "trace", "trace", "only-null")));
        assertThat(message.kafkaHeaders().get(1).value().isEmpty(), is(true));
        assertThat(message.kafkaHeaders().get(3).value().isEmpty(), is(true));
        assertThat(message.kafkaHeaders().get(4).value().isEmpty(), is(true));
    }
}
