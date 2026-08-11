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

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class KafkaMessageTest {
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
        assertThat(message.headers().get("trace"), is("abc"));
        assertThat(message.kafkaHeaders().get(1).value().orElseThrow()[0], is((byte) 1));
        assertThat(message.topic().isEmpty(), is(true));
        assertThat(message.partition().isEmpty(), is(true));
        assertThat(message.offset().isEmpty(), is(true));
        assertThat(message.timestamp().isEmpty(), is(true));
        assertThat(message.timestampType().isEmpty(), is(true));
        assertThat(message.leaderEpoch().isEmpty(), is(true));
    }
}
