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

package io.helidon.extensions.messaging.tests.jms;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.extensions.messaging.Emitter;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.Messaging;
import io.helidon.extensions.messaging.connectors.jms.JmsMessage;
import io.helidon.faulttolerance.Ft;
import io.helidon.service.registry.Service;

final class JmsMessagingTypes {
    static final String TEXT_INCOMING_CHANNEL = "jms-text-in";
    static final String TEXT_OUTGOING_CHANNEL = "jms-text-out";
    static final String BYTES_INCOMING_CHANNEL = "jms-bytes-in";
    static final String BYTES_OUTGOING_CHANNEL = "jms-bytes-out";
    static final String SELECTOR_INCOMING_CHANNEL = "jms-selector-in";
    static final String FORWARDING_INCOMING_CHANNEL = "jms-forward-in";
    static final String FORWARDING_OUTGOING_CHANNEL = "jms-forward-out";
    static final String DEAD_LETTER_INCOMING_CHANNEL = "jms-dead-letter-in";
    static final String DEAD_LETTER_OUTGOING_CHANNEL = "jms-dead-letter-out";
    static final String RECONNECT_INCOMING_CHANNEL = "jms-reconnect-in";
    static final String RECONNECT_OUTGOING_CHANNEL = "jms-reconnect-out";
    static final String DURABLE_INCOMING_CHANNEL = "jms-durable-in";
    static final String BACK_PRESSURE_INCOMING_CHANNEL = "jms-back-pressure-in";

    private JmsMessagingTypes() {
    }

    @Service.Singleton
    static class TextSender {
        private final Emitter<String> emitter;

        @Service.Inject
        TextSender(@Service.Named(TEXT_OUTGOING_CHANNEL) Emitter<String> emitter) {
            this.emitter = emitter;
        }

        void send(String payload) {
            emitter.emit(payload);
        }

        void send(Message<String> message) {
            emitter.emitMessage(message);
        }
    }

    @Service.Singleton
    static class TextReceiver {
        private final BlockingQueue<JmsMessage<String>> messages = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(TEXT_INCOMING_CHANNEL)
        void receive(JmsMessage<String> message) {
            messages.add(message);
        }

        JmsMessage<String> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class BytesReceiver {
        private final BlockingQueue<JmsMessage<byte[]>> messages = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(BYTES_INCOMING_CHANNEL)
        void receive(JmsMessage<byte[]> message) {
            messages.add(message);
        }

        JmsMessage<byte[]> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class SelectorReceiver {
        private final BlockingQueue<JmsMessage<String>> messages = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(SELECTOR_INCOMING_CHANNEL)
        void receive(JmsMessage<String> message) {
            messages.add(message);
        }

        JmsMessage<String> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class ForwardingReceiver {
        private final BlockingQueue<JmsMessage<String>> deliveries = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(FORWARDING_INCOMING_CHANNEL)
        @Messaging.SendTo(FORWARDING_OUTGOING_CHANNEL)
        JmsMessage<String> forward(JmsMessage<String> message) {
            deliveries.add(message);
            JmsMessage.Builder<String> result = JmsMessage.builder("forwarded: " + message.entity());
            message.correlationId().ifPresent(result::correlationId);
            message.jmsProperties().forEach(result::property);
            return result.type("forwarded-message")
                    .property("route", "forwarded")
                    .property("processor", "jms-forwarder")
                    .build();
        }

        JmsMessage<String> awaitDelivery(Duration timeout) throws InterruptedException {
            return deliveries.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class FtRetryPoisonReceiver {
        static final int CALLS = 3;
        static final String FAILURE_MESSAGE = "Expected fault-tolerance retry exhaustion";

        private final AtomicInteger attempts = new AtomicInteger();

        @Messaging.ReceiveFrom(DEAD_LETTER_INCOMING_CHANNEL)
        @Ft.Retry(calls = CALLS, delay = "PT0S", overallTimeout = "PT5S")
        void receive(JmsMessage<String> message) {
            attempts.incrementAndGet();
            throw new IllegalStateException(FAILURE_MESSAGE);
        }

        int attemptCount() {
            return attempts.get();
        }
    }

    @Service.Singleton
    static class PoisonReceiver {
        private static final int MAX_ATTEMPTS = 3;

        private final BlockingQueue<JmsMessage<String>> successfulMessages = new LinkedBlockingQueue<>();
        private final List<JmsMessage<String>> poisonDeliveries = new CopyOnWriteArrayList<>();
        private final CountDownLatch finalPoisonAttempt = new CountDownLatch(1);
        private final CountDownLatch allowFinalPoisonFailure = new CountDownLatch(1);
        private final AtomicInteger poisonAttempts = new AtomicInteger();

        @Messaging.ReceiveFrom(DEAD_LETTER_INCOMING_CHANNEL)
        void receive(JmsMessage<String> message) {
            if (!"poison".equals(message.entity())) {
                successfulMessages.add(message);
                return;
            }

            poisonDeliveries.add(message);
            if (poisonAttempts.incrementAndGet() == MAX_ATTEMPTS) {
                finalPoisonAttempt.countDown();
                await(allowFinalPoisonFailure, "Interrupted while awaiting poison-message dead-letter publication");
            }
            throw new IllegalStateException("Expected poison JMS message failure");
        }

        boolean awaitFinalPoisonAttempt(Duration timeout) throws InterruptedException {
            return finalPoisonAttempt.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void allowFinalPoisonFailure() {
            allowFinalPoisonFailure.countDown();
        }

        JmsMessage<String> awaitSuccessfulMessage(Duration timeout) throws InterruptedException {
            return successfulMessages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        int poisonAttemptCount() {
            return poisonAttempts.get();
        }

        List<JmsMessage<String>> poisonDeliveries() {
            return List.copyOf(poisonDeliveries);
        }
    }

    @Service.Singleton
    static class ReconnectSender {
        private final Emitter<String> emitter;

        @Service.Inject
        ReconnectSender(@Service.Named(RECONNECT_OUTGOING_CHANNEL) Emitter<String> emitter) {
            this.emitter = emitter;
        }

        void send(String payload) {
            emitter.emit(payload);
        }

        void send(Message<String> message) {
            emitter.emitMessage(message);
        }
    }

    @Service.Singleton
    static class ReconnectReceiver {
        private final BlockingQueue<JmsMessage<String>> messages = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(RECONNECT_INCOMING_CHANNEL)
        void receive(JmsMessage<String> message) {
            messages.add(message);
        }

        JmsMessage<String> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class DurableReceiver {
        private final BlockingQueue<JmsMessage<String>> messages = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(DURABLE_INCOMING_CHANNEL)
        void receive(JmsMessage<String> message) {
            messages.add(message);
        }

        JmsMessage<String> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class BackPressureReceiver {
        private final BlockingQueue<JmsMessage<String>> messages = new LinkedBlockingQueue<>();
        private final CountDownLatch releaseFirstMessage = new CountDownLatch(1);
        private final AtomicInteger deliveries = new AtomicInteger();

        @Messaging.ReceiveFrom(BACK_PRESSURE_INCOMING_CHANNEL)
        void receive(JmsMessage<String> message) {
            int delivery = deliveries.incrementAndGet();
            messages.add(message);
            if (delivery == 1) {
                await(releaseFirstMessage, "Interrupted while holding the first JMS message");
            }
        }

        JmsMessage<String> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        int deliveryCount() {
            return deliveries.get();
        }

        void releaseFirstMessage() {
            releaseFirstMessage.countDown();
        }
    }

    private static void await(CountDownLatch latch, String failureMessage) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        }
    }
}
