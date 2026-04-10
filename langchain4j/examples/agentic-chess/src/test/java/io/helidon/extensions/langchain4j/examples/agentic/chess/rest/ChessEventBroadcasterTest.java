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

package io.helidon.extensions.langchain4j.examples.agentic.chess.rest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.buffers.BufferData;
import io.helidon.common.socket.SocketContext;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.ChessEvent;
import io.helidon.websocket.WsSession;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class ChessEventBroadcasterTest {

    @Test
    void serializesConcurrentSendsPerSession() throws Exception {
        ChessEventBroadcaster broadcaster = new ChessEventBroadcaster();
        BlockingWsSession session = new BlockingWsSession();
        broadcaster.register("game-1", session);

        ChessEvent event = new ChessEvent("state-snapshot", "game-1", 1, null, null, null, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                ready.countDown();
                await(start);
                broadcaster.broadcast("game-1", event);
            });
            Future<?> second = executor.submit(() -> {
                ready.countDown();
                await(start);
                broadcaster.broadcast("game-1", event);
            });

            assertThat(ready.await(1, TimeUnit.SECONDS), is(true));
            start.countDown();

            assertThat(session.firstSendEntered.await(1, TimeUnit.SECONDS), is(true));
            assertThat(session.secondSendEntered.await(150, TimeUnit.MILLISECONDS), is(false));

            session.releaseFirstSend.countDown();

            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        }

        assertThat(session.maxConcurrentSends.get(), is(1));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test synchronization", e);
        }
    }

    private static final class BlockingWsSession implements WsSession {
        private final CountDownLatch firstSendEntered = new CountDownLatch(1);
        private final CountDownLatch secondSendEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstSend = new CountDownLatch(1);
        private final AtomicInteger sendCount = new AtomicInteger();
        private final AtomicInteger concurrentSends = new AtomicInteger();
        private final AtomicInteger maxConcurrentSends = new AtomicInteger();

        @Override
        public WsSession send(String text, boolean last) {
            int currentConcurrency = concurrentSends.incrementAndGet();
            maxConcurrentSends.accumulateAndGet(currentConcurrency, Math::max);
            int currentSend = sendCount.incrementAndGet();
            try {
                if (currentSend == 1) {
                    firstSendEntered.countDown();
                    assertThat(releaseFirstSend.await(1, TimeUnit.SECONDS), is(true));
                } else if (currentSend == 2) {
                    secondSendEntered.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while simulating WebSocket send", e);
            } finally {
                concurrentSends.decrementAndGet();
            }
            return this;
        }

        @Override
        public WsSession send(BufferData bufferData, boolean last) {
            return send(bufferData.toString(), last);
        }

        @Override
        public WsSession ping(BufferData bufferData) {
            return this;
        }

        @Override
        public WsSession pong(BufferData bufferData) {
            return this;
        }

        @Override
        public WsSession close(int code, String reason) {
            return this;
        }

        @Override
        public WsSession terminate() {
            return this;
        }

        @Override
        public SocketContext socketContext() {
            return null;
        }
    }
}
