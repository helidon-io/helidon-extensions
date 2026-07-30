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
import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaMessageTest {
    @Test
    void testProgrammaticMessageReportsKnownAdmissionWeight() {
        KafkaMessage<String, String> message = KafkaMessage.<String, String>builder("κ", "payload")
                .header("trace", "abc")
                .rawHeader("binary", new byte[] {1, 2})
                .rawHeader("null", null)
                .build();

        assertThat(message.admissionBytes().orElseThrow(), is(34L));
    }

    @Test
    void testIncomingMessageAccountsForRecordMetadataAndPortableHeaderView() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "events",
                3,
                19,
                1234,
                TimestampType.CREATE_TIME,
                2,
                7,
                "κ",
                "payload",
                new RecordHeaders().add("trace", "abc".getBytes(StandardCharsets.UTF_8)),
                Optional.of(11));

        KafkaMessage<String, String> message = KafkaMessageImpl.create(record);

        assertThat(message.admissionBytes().orElseThrow(), is(51L));
    }

    @Test
    void testIncomingMessageCannotUnderstateRetainedStringContent() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "events",
                3,
                19,
                1234,
                TimestampType.CREATE_TIME,
                1,
                1,
                "expanded-key",
                "\uFFFD-expanded-value",
                new RecordHeaders(),
                Optional.empty());

        KafkaMessage<String, String> message = KafkaMessageImpl.create(record);

        assertThat(message.admissionBytes().orElseThrow(), is(57L));
    }

    @Test
    void testIncomingMessageCannotUnderstateRetainedBinaryContent() {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "events",
                3,
                19,
                1234,
                TimestampType.CREATE_TIME,
                1,
                1,
                new byte[8],
                new byte[32],
                new RecordHeaders(),
                Optional.empty());

        KafkaMessage<byte[], byte[]> message = KafkaMessageImpl.create(record);

        assertThat(message.admissionBytes().orElseThrow(), is(67L));
    }

    @Test
    void testIncomingMessageKeepsUnsupportedRetainedContentUnknownDespiteWireSize() {
        ConsumerRecord<UnsupportedPayload, UnsupportedPayload> record = new ConsumerRecord<>(
                "events",
                3,
                19,
                1234,
                TimestampType.CREATE_TIME,
                1,
                1,
                new UnsupportedPayload("key"),
                new UnsupportedPayload("payload"),
                new RecordHeaders(),
                Optional.empty());

        KafkaMessage<UnsupportedPayload, UnsupportedPayload> message = KafkaMessageImpl.create(record);

        assertThat(message.admissionBytes().isEmpty(), is(true));
    }

    @Test
    void testProgrammaticMessageKeepsUnknownAdmissionWeightExplicit() {
        KafkaMessage<String, UnsupportedPayload> message =
                KafkaMessage.create("key", new UnsupportedPayload("payload"));

        assertThat(message.admissionBytes().isEmpty(), is(true));
    }

    @Test
    void testProgrammaticMessageAcceptsExplicitAdmissionWeightForCustomTypes() {
        KafkaMessage<UnsupportedPayload, UnsupportedPayload> message =
                KafkaMessage.builder(new UnsupportedPayload("key"), new UnsupportedPayload("payload"))
                        .rawHeader("binary", new byte[] {1, 2})
                        .admissionBytes(128)
                        .build();

        assertThat(message.admissionBytes().orElseThrow(), is(128L));
    }

    @Test
    void testExplicitAdmissionWeightCannotUnderstateKnownKafkaContent() {
        KafkaMessage<String, String> message = KafkaMessage.<String, String>builder("κ", "payload")
                .header("trace", "abc")
                .admissionBytes(1)
                .build();

        assertThat(message.admissionBytes().orElseThrow(), is(20L));
    }

    @Test
    void testExplicitAdmissionWeightCannotUnderstateKnownPartsOfCustomMessage() {
        KafkaMessage<String, UnsupportedPayload> message =
                KafkaMessage.<String, UnsupportedPayload>builder("key", new UnsupportedPayload("payload"))
                        .rawHeader("binary", new byte[] {1, 2})
                        .admissionBytes(1)
                        .build();

        assertThat(message.admissionBytes().orElseThrow(), is(13L));
    }

    @Test
    void testProgrammaticMessageRejectsNegativeAdmissionWeight() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                                       () -> KafkaMessage.builder("key", "payload")
                                                               .admissionBytes(-1));

        assertThat(thrown.getMessage(), is("Message admission bytes must be zero or greater"));
    }

    private record UnsupportedPayload(String value) {
    }
}
