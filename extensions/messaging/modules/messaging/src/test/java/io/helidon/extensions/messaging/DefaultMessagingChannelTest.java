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

package io.helidon.extensions.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultMessagingChannelTest {
    @Test
    void customPayloadUsesRegisteredEstimators() {
        List<CustomPayload> delivered = new ArrayList<>();

        try (MessagingChannel<CustomPayload> channel = MessagingChannel.<CustomPayload>builder()
                .payloadType(CustomPayload.class)
                .addMessageSizeEstimator(message -> OptionalLong.empty())
                .addMessageSizeEstimator(message -> OptionalLong.of(message.entity().toString().length()))
                .addOutput(message -> delivered.add(message.entity()))
                .build()) {
            CustomPayload payload = new CustomPayload("payload");

            channel.emit(payload);

            assertThat(delivered, is(List.of(payload)));
        }
    }

    @Test
    void customPayloadWithoutEstimatorIsRejected() {
        try (MessagingChannel<CustomPayload> channel = MessagingChannel.<CustomPayload>builder()
                .payloadType(CustomPayload.class)
                .build()) {
            MessagingRejectedException thrown = assertThrows(MessagingRejectedException.class,
                                                              () -> channel.emit(new CustomPayload("payload")));

            assertThat(thrown.reason(), is(MessagingRejectedException.Reason.UNKNOWN_SIZE));
        }
    }

    @Test
    void independentlyBuiltInputsCanFeedOneChannel() {
        List<String> delivered = new ArrayList<>();
        try (MessagingChannel<String> first = MessagingChannel.<String>builder().build();
                MessagingChannel<String> second = MessagingChannel.<String>builder().build();
                MessagingChannel<String> merged = MessagingChannel.<String>builder()
                        .addInput(first)
                        .addInput(second)
                        .addOutput(message -> delivered.add(message.entity()))
                        .build()) {
            first.emit("first");
            second.emit("second");

            assertThat(delivered, is(List.of("first", "second")));
        }
    }

    @Test
    void closingBeforeStartClosesStreamInput() {
        AtomicBoolean streamClosed = new AtomicBoolean();
        MessagingChannel<Object> channel = MessagingChannel.builder()
                .addInput(Stream.empty().onClose(() -> streamClosed.set(true)))
                .build();

        channel.close();

        assertThat(streamClosed.get(), is(true));
    }

    @Test
    @Timeout(5)
    void activeUnboundedStreamClosesGracefully() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean streamClosed = new AtomicBoolean();
        MessagingChannel<Integer> channel = MessagingChannel.<Integer>builder()
                .addInput(Stream.generate(() -> 1).onClose(() -> streamClosed.set(true)))
                .addOutput(ignored -> delivered.countDown())
                .build();
        channel.start();
        assertThat(delivered.await(1, TimeUnit.SECONDS), is(true));

        channel.close();

        assertThat(streamClosed.get(), is(true));
    }

    private record CustomPayload(String value) {
    }
}
