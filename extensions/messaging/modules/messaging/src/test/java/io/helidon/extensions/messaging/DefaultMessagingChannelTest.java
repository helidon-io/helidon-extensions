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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultMessagingChannelTest {
    @Test
    void customPayloadUsesRegisteredEstimators() {
        List<CustomPayload> delivered = new ArrayList<>();
        MessagingGraph.Builder builder = MessagingGraph.builder()
                .addMessageSizeEstimator(message -> OptionalLong.empty())
                .addMessageSizeEstimator(message -> OptionalLong.of(message.entity().toString().length()));
        MessagingChannel<CustomPayload> channel = builder.channel("custom", CustomPayload.class);
        builder.messageSink(channel, message -> delivered.add(message.entity()));

        try (MessagingGraph graph = builder.build()) {
            graph.start();
            CustomPayload payload = new CustomPayload("payload");

            graph.emitter(channel).emit(payload);

            assertThat(delivered, is(List.of(payload)));
        }
    }

    @Test
    void customPayloadWithoutEstimatorIsRejected() {
        MessagingGraph.Builder builder = MessagingGraph.builder();
        MessagingChannel<CustomPayload> channel = builder.channel("custom", CustomPayload.class);
        builder.payloadSink(channel, ignored -> { });
        try (MessagingGraph graph = builder.build()) {
            graph.start();
            MessagingRejectedException thrown = assertThrows(MessagingRejectedException.class,
                                                              () -> graph.emitter(channel)
                                                                      .emit(new CustomPayload("payload")));

            assertThat(thrown.reason(), is(MessagingRejectedException.Reason.UNKNOWN_SIZE));
        }
    }

    @Test
    void independentlyBuiltInputsCanFeedOneChannel() {
        List<String> delivered = new ArrayList<>();
        MessagingGraph.Builder builder = MessagingGraph.builder();
        MessagingChannel<String> first = builder.channel("first", String.class);
        MessagingChannel<String> second = builder.channel("second", String.class);
        MessagingChannel<String> merged = builder.channel("merged", String.class);
        builder.route(first, merged)
                .route(second, merged)
                .messageSink(merged, message -> delivered.add(message.entity()));

        try (MessagingGraph graph = builder.build()) {
            graph.start();
            graph.emitter(first).emit("first");
            graph.emitter(second).emit("second");

            assertThat(delivered, is(List.of("first", "second")));
        }
    }

    @Test
    void closingBeforeStartClosesStreamInput() {
        AtomicBoolean streamClosed = new AtomicBoolean();
        MessagingGraph.Builder builder = MessagingGraph.builder();
        MessagingChannel<Object> channel = builder.channel("stream", Object.class);
        MessagingGraph graph = builder.payloadSource(channel,
                                                      Stream.empty().onClose(() -> streamClosed.set(true)))
                .payloadSink(channel, ignored -> { })
                .build();

        graph.close();

        assertThat(streamClosed.get(), is(true));
    }

    @Test
    @Timeout(5)
    void activeUnboundedStreamClosesGracefully() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean streamClosed = new AtomicBoolean();
        MessagingGraph.Builder builder = MessagingGraph.builder();
        MessagingChannel<Integer> channel = builder.channel("stream", Integer.class);
        MessagingGraph graph = builder.payloadSource(channel,
                                                      Stream.generate(() -> 1)
                                                              .onClose(() -> streamClosed.set(true)))
                .messageSink(channel, ignored -> delivered.countDown())
                .build();
        graph.start();
        assertThat(delivered.await(1, TimeUnit.SECONDS), is(true));

        graph.close();

        assertThat(streamClosed.get(), is(true));
    }

    @Test
    void streamSourceDoesNotHideDownstreamShutdownRejection() {
        MessagingRejectedException rejection = new MessagingRejectedException(
                "downstream",
                MessagingRejectedException.Reason.SHUTDOWN);
        ConnectorSource source = DefaultMessagingChannel.streamSource(Stream.of("first", "second"), ignored -> {
            throw rejection;
        });

        MessagingRejectedException thrown = assertThrows(MessagingRejectedException.class, source::run);

        assertSame(rejection, thrown);
    }

    @Test
    @Timeout(5)
    void forceCloseInterruptsStreamOwnerBeforeNormalCloseInvokesBlockingStreamClose() throws InterruptedException {
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch ownerInterrupted = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicReference<Throwable> sourceFailure = new AtomicReference<>();
        Stream<String> stream = Stream.generate(() -> {
            ownerStarted.countDown();
            try {
                neverReleased.await();
                return "unexpected";
            } catch (InterruptedException e) {
                ownerInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new MessagingRejectedException("stream",
                                                     MessagingRejectedException.Reason.CANCELLED,
                                                     "Stream owner interrupted",
                                                     e);
            }
        }).onClose(() -> {
            closeStarted.countDown();
            awaitUninterruptibly(releaseClose);
        });
        ConnectorSource source = DefaultMessagingChannel.streamSource(stream, ignored -> { });
        Thread sourceThread = Thread.ofVirtual().start(() -> {
            try {
                source.run();
            } catch (Throwable t) {
                sourceFailure.set(t);
            }
        });
        assertThat(ownerStarted.await(1, TimeUnit.SECONDS), is(true));

        ConnectorEndpoint endpoint = (ConnectorEndpoint) source;
        endpoint.forceClose();
        assertThat(closeStarted.getCount(), is(1L));
        assertThat(ownerInterrupted.await(1, TimeUnit.SECONDS), is(true));

        Thread closeThread = Thread.ofVirtual().start(endpoint::close);
        assertThat(closeStarted.await(1, TimeUnit.SECONDS), is(true));
        releaseClose.countDown();
        sourceThread.join(TimeUnit.SECONDS.toMillis(1));
        closeThread.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(sourceThread.isAlive(), is(false));
        assertThat(closeThread.isAlive(), is(false));
        assertThat(sourceFailure.get() instanceof MessagingRejectedException, is(true));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record CustomPayload(String value) {
    }
}
