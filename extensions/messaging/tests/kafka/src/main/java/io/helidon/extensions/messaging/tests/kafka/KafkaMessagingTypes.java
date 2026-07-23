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

package io.helidon.extensions.messaging.tests.kafka;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.extensions.messaging.Emitter;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.Messaging;
import io.helidon.service.registry.Service;

final class KafkaMessagingTypes {
    static final String INCOMING_CHANNEL = "kafka-in";
    static final String OUTGOING_CHANNEL = "kafka-out";
    static final String REDELIVERY_INCOMING_CHANNEL = "kafka-retry-in";

    private KafkaMessagingTypes() {
    }

    @Service.Singleton
    static class OutgoingSender {
        @Service.Named(OUTGOING_CHANNEL)
        @Service.Inject
        Emitter<String> emitter;

        void send(String payload) {
            emitter.emit(payload);
        }

        void send(Message<String> message) {
            emitter.emit(message);
        }

        void sendBatch(List<Message<String>> messages) {
            emitter.emitBatch(messages);
        }
    }

    @Service.Singleton
    static class IncomingReceiver {
        private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
        private final BlockingQueue<Message<String>> messages = new LinkedBlockingQueue<>();
        private final BlockingQueue<ReceivedMessage> annotated = new LinkedBlockingQueue<>();
        private final BlockingQueue<List<Message<String>>> batches = new LinkedBlockingQueue<>();

        @Messaging.OnMessage(INCOMING_CHANNEL)
        void receivePayload(String payload) {
            payloads.add(payload);
        }

        @Messaging.OnMessage(INCOMING_CHANNEL)
        void receiveMessage(Message<String> message) {
            messages.add(message);
        }

        @Messaging.OnMessage(INCOMING_CHANNEL)
        void receiveAnnotated(@Messaging.HeaderParam("trace-id") String traceId,
                              @Messaging.Entity String entity,
                              Message<String> message) {
            annotated.add(new ReceivedMessage(traceId, entity, message));
        }

        @Messaging.OnMessage(INCOMING_CHANNEL)
        void receiveBatch(List<Message<String>> messages) {
            batches.add(List.copyOf(messages));
        }

        String awaitPayload(Duration timeout) throws InterruptedException {
            return payloads.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        Message<String> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        ReceivedMessage awaitAnnotated(Duration timeout) throws InterruptedException {
            return annotated.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        List<Message<String>> awaitBatch(Duration timeout) throws InterruptedException {
            return batches.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class FailOnceIncomingReceiver {
        private final BlockingQueue<Message<String>> deliveries = new LinkedBlockingQueue<>();
        private final CountDownLatch secondAttempt = new CountDownLatch(1);
        private final CountDownLatch allowSecondAttempt = new CountDownLatch(1);
        private final AtomicInteger attempts = new AtomicInteger();

        @Messaging.OnMessage(REDELIVERY_INCOMING_CHANNEL)
        void receive(Message<String> message) {
            int attempt = attempts.incrementAndGet();
            deliveries.add(message);
            if (attempt == 1) {
                throw new IllegalStateException("Expected first delivery to fail");
            }

            secondAttempt.countDown();
            try {
                allowSecondAttempt.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting successful redelivery", e);
            }
        }

        Message<String> awaitDelivery(Duration timeout) throws InterruptedException {
            return deliveries.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean awaitSecondAttempt(Duration timeout) throws InterruptedException {
            return secondAttempt.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void allowSecondAttemptToSucceed() {
            allowSecondAttempt.countDown();
        }

        int attemptCount() {
            return attempts.get();
        }
    }

    record ReceivedMessage(String traceId, String entity, Message<String> message) {
    }
}
