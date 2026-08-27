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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.messaging.MessageHeader;
import io.helidon.messaging.MessagingException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulsarMessageTest {
    @Test
    void rejectsNullPayloads() {
        assertThrows(NullPointerException.class, () -> PulsarMessage.builder(null));
    }

    @Test
    void failedMappingEnvelopeRetainsMetadataWithoutReadingRawPayload() {
        AtomicInteger dataReads = new AtomicInteger();
        PulsarMessage<Object> message = PulsarMessageMapper.metadataOnly(
                PulsarTestSupport.nativeMessage("undecodable",
                                                11,
                                                Map.of("trace-id", "pulsar-trace"),
                                                () -> {
                                                    dataReads.incrementAndGet();
                                                    return new byte[11];
                                                }));

        assertThat(((PulsarMessageImpl<?>) message).entityAvailable(), is(false));
        assertThrows(MessagingException.class, message::entity);
        assertThat(dataReads.get(), is(0));
        assertThat(message.header("trace-id").orElseThrow(), is("pulsar-trace"));
        assertThat(message.topic().orElseThrow(), is("persistent://public/default/input"));
    }

    @Test
    void outgoingMessageDefensivelyCopiesBinaryState() {
        byte[] entity = {1, 2};
        byte[] key = {3, 4};
        byte[] orderingKey = {5, 6};
        PulsarMessage<byte[]> message = PulsarMessage.builder(entity)
                .keyBytes(key)
                .orderingKey(orderingKey)
                .header("trace-id", "abc")
                .eventTime(7)
                .build();

        entity[0] = 9;
        key[0] = 9;
        orderingKey[0] = 9;
        assertThat(Arrays.equals(message.entity(), new byte[] {1, 2}), is(true));
        assertThat(Arrays.equals(message.keyBytes().orElseThrow(), new byte[] {3, 4}), is(true));
        assertThat(Arrays.equals(message.orderingKey().orElseThrow(), new byte[] {5, 6}), is(true));
        assertThat(message.base64EncodedKey(), is(true));
        assertThat(message.header("trace-id").orElseThrow(), is("abc"));
        assertThat(message.eventTime().orElseThrow(), is(7L));
        assertThat(message.topic().isEmpty(), is(true));

        message.entity()[0] = 8;
        message.keyBytes().orElseThrow()[0] = 8;
        assertThat(Arrays.equals(message.entity(), new byte[] {1, 2}), is(true));
        assertThat(Arrays.equals(message.keyBytes().orElseThrow(), new byte[] {3, 4}), is(true));
        assertThrows(UnsupportedOperationException.class, () -> message.headers().entries().clear());
    }

    @Test
    void incomingMessageSnapshotsTransportMetadataWithoutPublicPulsarTypes() {
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        properties.put("first", "one");
        properties.put("second", "two");
        PulsarMessage<Object> message = PulsarMessageImpl.incoming(
                "payload",
                PulsarTestSupport.nativeMessage("payload", 7, properties));

        assertThat(message.entity(), is("payload"));
        assertThat(message.headers().entries().stream().map(MessageHeader::name).toList(),
                   is(List.of("first", "second")));
        assertThat(message.header("first").orElseThrow(), is("one"));
        assertThat(message.header("second").orElseThrow(), is("two"));
        assertThat(message.topic().orElseThrow(), is("persistent://public/default/input"));
        assertThat(Arrays.equals(message.messageId().orElseThrow(), new byte[] {1, 2, 3}), is(true));
        assertThat(message.publishTime().orElseThrow(), is(1234L));
        assertThat(message.sequenceId().isEmpty(), is(true));
        assertThat(message.producerName().orElseThrow(), is("source-producer"));
        assertThat(message.redeliveryCount().orElseThrow(), is(2));
        assertThat(Arrays.equals(message.schemaVersion().orElseThrow(), new byte[] {4, 5}), is(true));
    }
}
