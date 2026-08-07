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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.extensions.messaging.Emitter;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessageBatch;
import io.helidon.extensions.messaging.Messaging;
import io.helidon.extensions.messaging.connectors.kafka.KafkaMessage;
import io.helidon.service.registry.Service;

final class KafkaMessagingTypes {
    static final String INCOMING_CHANNEL = "kafka-in";
    static final String METADATA_INCOMING_CHANNEL = "kafka-metadata-in";
    static final String OUTGOING_CHANNEL = "kafka-out";
    static final String REDELIVERY_INCOMING_CHANNEL = "kafka-retry-in";
    static final String DEAD_LETTER_INCOMING_CHANNEL = "kafka-dead-letter-in";
    static final String DEAD_LETTER_OUTGOING_CHANNEL = "kafka-dead-letter-out";
    static final String FORWARDING_INCOMING_CHANNEL = "kafka-forward-in";
    static final String FORWARDING_OUTGOING_CHANNEL = "kafka-forward-out";
    static final String FAILING_FORWARDING_INCOMING_CHANNEL = "kafka-failing-forward-in";
    static final String FAILING_FORWARDING_OUTGOING_CHANNEL = "kafka-failing-forward-out";
    static final String RESTART_INCOMING_CHANNEL = "kafka-restart-in";
    static final String DROP_INCOMING_CHANNEL = "kafka-drop-in";
    static final String PARTITION_RETRY_INCOMING_CHANNEL = "kafka-partition-retry-in";
    static final String NUMERIC_INCOMING_CHANNEL = "kafka-numeric-in";
    static final String NUMERIC_OUTGOING_CHANNEL = "kafka-numeric-out";

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
            emitter.emitMessage(message);
        }

        void sendBatch(MessageBatch<String> batch) {
            emitter.emitBatch(batch);
        }
    }

    @Service.Singleton
    static class ForwardingReceiver {
        private final BlockingQueue<Message<String>> deliveries = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(FORWARDING_INCOMING_CHANNEL)
        @Messaging.SendTo(FORWARDING_OUTGOING_CHANNEL)
        Message<String> forward(Message<String> message) {
            deliveries.add(message);
            return Message.builder("forwarded: " + message.entity())
                    .header("processor", "kafka-forwarder")
                    .build();
        }

        Message<String> awaitDelivery(Duration timeout) throws InterruptedException {
            return deliveries.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class FailingForwardingReceiver {
        private final BlockingQueue<Message<String>> deliveries = new LinkedBlockingQueue<>();
        private final AtomicInteger attempts = new AtomicInteger();

        @Messaging.ReceiveFrom(FAILING_FORWARDING_INCOMING_CHANNEL)
        @Messaging.SendTo(FAILING_FORWARDING_OUTGOING_CHANNEL)
        String forward(Message<String> message) {
            attempts.incrementAndGet();
            deliveries.add(message);
            return "will not be serialized: " + message.entity();
        }

        Message<String> awaitDelivery(Duration timeout) throws InterruptedException {
            return deliveries.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        int attemptCount() {
            return attempts.get();
        }
    }

    @Service.Singleton
    static class RestartReceiver {
        private static final AtomicBoolean FAIL_DELIVERIES = new AtomicBoolean();

        private final BlockingQueue<KafkaMessage<String, String>> deliveries = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(RESTART_INCOMING_CHANNEL)
        void receive(KafkaMessage<String, String> message) {
            deliveries.add(message);
            if (FAIL_DELIVERIES.get()) {
                throw new IllegalStateException("Expected delivery to remain uncommitted before restart");
            }
        }

        KafkaMessage<String, String> awaitDelivery(Duration timeout) throws InterruptedException {
            return deliveries.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        static void failDeliveries() {
            FAIL_DELIVERIES.set(true);
        }

        static void succeedDeliveries() {
            FAIL_DELIVERIES.set(false);
        }
    }

    @Service.Singleton
    static class DropReceiver {
        private final List<String> successfulEntities = new CopyOnWriteArrayList<>();
        private final CountDownLatch finalPoisonAttempt = new CountDownLatch(1);
        private final CountDownLatch allowPoisonFailure = new CountDownLatch(1);
        private final CountDownLatch successfulDelivery = new CountDownLatch(1);
        private final CountDownLatch allowSuccessfulDelivery = new CountDownLatch(1);
        private final AtomicInteger poisonAttempts = new AtomicInteger();

        @Messaging.ReceiveFrom(DROP_INCOMING_CHANNEL)
        void receive(String entity) {
            if ("poison".equals(entity)) {
                int attempt = poisonAttempts.incrementAndGet();
                if (attempt == 2) {
                    finalPoisonAttempt.countDown();
                    await(allowPoisonFailure, "Interrupted while awaiting terminal DROP");
                }
                throw new IllegalStateException("Expected poison message failure");
            }

            successfulEntities.add(entity);
            successfulDelivery.countDown();
            await(allowSuccessfulDelivery, "Interrupted while holding successful delivery");
        }

        boolean awaitFinalPoisonAttempt(Duration timeout) throws InterruptedException {
            return finalPoisonAttempt.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void allowPoisonFailure() {
            allowPoisonFailure.countDown();
        }

        boolean awaitSuccessfulDelivery(Duration timeout) throws InterruptedException {
            return successfulDelivery.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void allowSuccessfulDelivery() {
            allowSuccessfulDelivery.countDown();
        }

        int poisonAttempts() {
            return poisonAttempts.get();
        }

        List<String> successfulEntities() {
            return List.copyOf(successfulEntities);
        }

        private void await(CountDownLatch latch, String failureMessage) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failureMessage, e);
            }
        }
    }

    @Service.Singleton
    static class PartitionRetryReceiver {
        private final Map<List<PartitionRecord>, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final List<List<PartitionRecord>> deliveries = new CopyOnWriteArrayList<>();
        private final List<String> batchIds = new CopyOnWriteArrayList<>();
        private final BlockingQueue<PartitionRecord> successfulRecords = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(PARTITION_RETRY_INCOMING_CHANNEL)
        @SuppressWarnings("unchecked")
        void receive(MessageBatch<String> messages) {
            batchIds.add(messages.id());
            List<PartitionRecord> batch = messages.messages().stream()
                    .map(message -> (KafkaMessage<String, String>) message)
                    .map(message -> new PartitionRecord(message.partition().orElseThrow(),
                                                        message.offset().orElseThrow(),
                                                        message.entity()))
                    .toList();
            deliveries.add(batch);
            int attempt = attempts.computeIfAbsent(batch, ignored -> new AtomicInteger()).incrementAndGet();
            if (attempt == 1) {
                throw new IllegalStateException("Expected first delivery of retained Kafka poll to fail");
            }
            successfulRecords.addAll(batch);
        }

        Set<PartitionRecord> awaitSuccessfulRecords(int expectedCount,
                                                    Duration timeout) throws InterruptedException {
            Set<PartitionRecord> result = new LinkedHashSet<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (result.size() < expectedCount) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                PartitionRecord record = successfulRecords.poll(remaining, TimeUnit.NANOSECONDS);
                if (record == null) {
                    break;
                }
                result.add(record);
            }
            return Set.copyOf(result);
        }

        List<List<PartitionRecord>> deliveries() {
            return List.copyOf(deliveries);
        }

        List<String> batchIds() {
            return List.copyOf(batchIds);
        }

        Map<List<PartitionRecord>, Integer> attemptCounts() {
            Map<List<PartitionRecord>, Integer> result = new ConcurrentHashMap<>();
            attempts.forEach((batch, count) -> result.put(batch, count.get()));
            return Map.copyOf(result);
        }
    }

    @Service.Singleton
    static class NumericSender {
        @Service.Named(NUMERIC_OUTGOING_CHANNEL)
        @Service.Inject
        Emitter<Integer> emitter;

        void send(KafkaMessage<Long, Integer> message) {
            emitter.emitMessage(message);
        }
    }

    @Service.Singleton
    static class NumericReceiver {
        private final BlockingQueue<KafkaMessage<Long, Integer>> messages = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(NUMERIC_INCOMING_CHANNEL)
        void receive(KafkaMessage<Long, Integer> message) {
            messages.add(message);
        }

        KafkaMessage<Long, Integer> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class IncomingReceiver {
        private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
        private final BlockingQueue<Message<String>> messages = new LinkedBlockingQueue<>();
        private final BlockingQueue<ReceivedMessage> annotated = new LinkedBlockingQueue<>();
        private final BlockingQueue<MessageBatch<String>> batches = new LinkedBlockingQueue<>();

        void recordPayload(String payload) {
            payloads.add(payload);
        }

        void recordMessage(Message<String> message) {
            messages.add(message);
        }

        void recordAnnotated(String traceId, Message<String> message) {
            annotated.add(new ReceivedMessage(traceId, message.entity(), message));
        }

        void recordBatch(MessageBatch<String> batch) {
            batches.add(batch);
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

        MessageBatch<String> awaitBatch(Duration timeout) throws InterruptedException {
            return batches.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class IncomingPayloadReceiver {
        private final IncomingReceiver receiver;

        @Service.Inject
        IncomingPayloadReceiver(IncomingReceiver receiver) {
            this.receiver = receiver;
        }

        @Messaging.ReceiveFrom(INCOMING_CHANNEL)
        void receivePayload(String payload) {
            receiver.recordPayload(payload);
        }
    }

    @Service.Singleton
    static class IncomingMessageReceiver {
        private final IncomingReceiver receiver;

        @Service.Inject
        IncomingMessageReceiver(IncomingReceiver receiver) {
            this.receiver = receiver;
        }

        @Messaging.ReceiveFrom(INCOMING_CHANNEL)
        void receiveMessage(Message<String> message) {
            receiver.recordMessage(message);
        }
    }

    @Service.Singleton
    static class IncomingAnnotatedReceiver {
        private final IncomingReceiver receiver;

        @Service.Inject
        IncomingAnnotatedReceiver(IncomingReceiver receiver) {
            this.receiver = receiver;
        }

        @Messaging.ReceiveFrom(INCOMING_CHANNEL)
        void receiveAnnotated(@Messaging.HeaderParam("trace-id") String traceId,
                              Message<String> message) {
            receiver.recordAnnotated(traceId, message);
        }
    }

    @Service.Singleton
    static class IncomingBatchReceiver {
        private final IncomingReceiver receiver;

        @Service.Inject
        IncomingBatchReceiver(IncomingReceiver receiver) {
            this.receiver = receiver;
        }

        @Messaging.ReceiveFrom(INCOMING_CHANNEL)
        void receiveBatch(MessageBatch<String> batch) {
            receiver.recordBatch(batch);
        }
    }

    @Service.Singleton
    static class KafkaMetadataReceiver {
        private final BlockingQueue<KafkaMessage<String, String>> messages = new LinkedBlockingQueue<>();
        private final BlockingQueue<MessageBatch<String>> batches = new LinkedBlockingQueue<>();

        void recordMessage(KafkaMessage<String, String> message) {
            messages.add(message);
        }

        void recordBatch(MessageBatch<String> batch) {
            batches.add(batch);
        }

        KafkaMessage<String, String> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @SuppressWarnings("unchecked")
        List<KafkaMessage<String, String>> awaitBatchMessages(int expectedCount,
                                                              Duration timeout) throws InterruptedException {
            List<KafkaMessage<String, String>> result = new ArrayList<>(expectedCount);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (result.size() < expectedCount) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                MessageBatch<String> batch = batches.poll(remaining, TimeUnit.NANOSECONDS);
                if (batch == null) {
                    break;
                }
                batch.messages().stream()
                        .map(message -> (KafkaMessage<String, String>) message)
                        .forEach(result::add);
            }
            return List.copyOf(result);
        }
    }

    @Service.Singleton
    static class KafkaMetadataMessageReceiver {
        private final KafkaMetadataReceiver receiver;

        @Service.Inject
        KafkaMetadataMessageReceiver(KafkaMetadataReceiver receiver) {
            this.receiver = receiver;
        }

        @Messaging.ReceiveFrom(METADATA_INCOMING_CHANNEL)
        void receive(KafkaMessage<String, String> message) {
            receiver.recordMessage(message);
        }
    }

    @Service.Singleton
    static class KafkaMetadataBatchReceiver {
        private final KafkaMetadataReceiver receiver;

        @Service.Inject
        KafkaMetadataBatchReceiver(KafkaMetadataReceiver receiver) {
            this.receiver = receiver;
        }

        @Messaging.ReceiveFrom(METADATA_INCOMING_CHANNEL)
        void receiveBatch(MessageBatch<String> batch) {
            receiver.recordBatch(batch);
        }
    }

    @Service.Singleton
    static class FailOnceIncomingReceiver {
        private final BlockingQueue<Message<String>> deliveries = new LinkedBlockingQueue<>();
        private final CountDownLatch secondAttempt = new CountDownLatch(1);
        private final CountDownLatch allowSecondAttempt = new CountDownLatch(1);
        private final AtomicInteger attempts = new AtomicInteger();

        @Messaging.ReceiveFrom(REDELIVERY_INCOMING_CHANNEL)
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

    @Service.Singleton
    static class AlwaysFailIncomingReceiver {
        private static final int MAX_ATTEMPTS = 3;

        private final BlockingQueue<FailedBatch> finalAttempts = new LinkedBlockingQueue<>();
        private final Map<List<String>, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final List<FailedBatch> failedBatches = new CopyOnWriteArrayList<>();
        private final AtomicBoolean allowAllFailures = new AtomicBoolean();

        @Messaging.ReceiveFrom(DEAD_LETTER_INCOMING_CHANNEL)
        void receive(MessageBatch<String> messages) {
            List<String> entities = messages.payloads();
            int attempt = attempts.computeIfAbsent(entities, ignored -> new AtomicInteger()).incrementAndGet();
            if (attempt == MAX_ATTEMPTS) {
                FailedBatch failedBatch = new FailedBatch(entities, attempt);
                failedBatches.add(failedBatch);
                finalAttempts.add(failedBatch);
                if (allowAllFailures.get()) {
                    failedBatch.allowFailure();
                }
                try {
                    failedBatch.awaitFailureAllowed();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while awaiting permanent failure", e);
                }
            }
            throw new IllegalStateException("Expected permanent handler failure for " + entities.getFirst());
        }

        FailedBatch awaitFinalAttempt(Duration timeout) throws InterruptedException {
            return finalAttempts.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void allowAllFinalAttemptsToFail() {
            allowAllFailures.set(true);
            failedBatches.forEach(FailedBatch::allowFailure);
        }

        Map<List<String>, Integer> attemptCounts() {
            Map<List<String>, Integer> result = new ConcurrentHashMap<>();
            attempts.forEach((entities, count) -> result.put(entities, count.get()));
            return Map.copyOf(result);
        }

        static final class FailedBatch {
            private final List<String> entities;
            private final int attempt;
            private final CountDownLatch allowFailure = new CountDownLatch(1);

            private FailedBatch(List<String> entities, int attempt) {
                this.entities = entities;
                this.attempt = attempt;
            }

            List<String> entities() {
                return entities;
            }

            int attempt() {
                return attempt;
            }

            void allowFailure() {
                allowFailure.countDown();
            }

            private void awaitFailureAllowed() throws InterruptedException {
                allowFailure.await();
            }
        }
    }

    record ReceivedMessage(String traceId, String entity, Message<String> message) {
    }

    record PartitionRecord(int partition, long offset, String entity) {
    }
}
