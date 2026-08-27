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

package io.helidon.extensions.messaging.tests.pulsar;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.extensions.messaging.connectors.pulsar.PulsarMessage;
import io.helidon.extensions.messaging.connectors.pulsar.PulsarSchemaProvider;
import io.helidon.messaging.Emitter;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.Messaging;
import io.helidon.service.registry.Service;

import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.GenericRecord;

final class PulsarMessagingTypes {
    static final String OUTGOING_CHANNEL = "pulsar-out";
    static final String AUTO_OUTGOING_CHANNEL = "pulsar-auto-out";
    static final String JSON_OUTGOING_CHANNEL = "pulsar-json-out";
    static final String INCOMING_CHANNEL = "pulsar-in";
    static final String AUTO_INCOMING_CHANNEL = "pulsar-auto-in";
    static final String REDELIVERY_CHANNEL = "pulsar-redelivery-in";
    static final String FAILED_MAPPING_INCOMING_CHANNEL = "pulsar-failed-mapping-in";
    static final String FAILED_MAPPING_DEAD_LETTER_CHANNEL = "pulsar-failed-mapping-dlq";
    static final String JSON_SCHEMA_PROVIDER = "json-orders";

    private PulsarMessagingTypes() {
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

        void sendBatch(MessageBatch<String> batch) {
            emitter.emit(batch);
        }
    }

    @Service.Singleton
    static class AutoOutgoingSender {
        @Service.Named(AUTO_OUTGOING_CHANNEL)
        @Service.Inject
        Emitter<byte[]> emitter;

        void send(byte[] payload) {
            emitter.emit(payload);
        }
    }

    @Service.Singleton
    static class JsonOutgoingSender {
        @Service.Named(JSON_OUTGOING_CHANNEL)
        @Service.Inject
        Emitter<PulsarTestPayload> emitter;

        void send(PulsarTestPayload payload) {
            emitter.emit(payload);
        }
    }

    @Service.Singleton
    static class IncomingReceiver {
        private final BlockingQueue<PulsarMessage<String>> messages = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(INCOMING_CHANNEL)
        void receive(PulsarMessage<String> message) {
            messages.add(message);
        }

        PulsarMessage<String> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class AutoIncomingReceiver {
        private final BlockingQueue<PulsarMessage<GenericRecord>> messages = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(AUTO_INCOMING_CHANNEL)
        void receive(PulsarMessage<GenericRecord> message) {
            messages.add(message);
        }

        PulsarMessage<GenericRecord> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class FailedMappingReceiver {
        private final BlockingQueue<PulsarMessage<String>> messages = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(FAILED_MAPPING_INCOMING_CHANNEL)
        void receive(PulsarMessage<String> message) {
            messages.add(message);
        }

        PulsarMessage<String> awaitMessage(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Service.Singleton
    static class JsonSchemaProvider implements PulsarSchemaProvider {
        @Override
        public String name() {
            return JSON_SCHEMA_PROVIDER;
        }

        @Override
        public Schema<?> schema() {
            return Schema.JSON(PulsarTestPayload.class);
        }
    }

    @Service.Singleton
    static class FailOnceReceiver {
        private static final AtomicBoolean FAIL_NEXT = new AtomicBoolean();
        private static final AtomicInteger ATTEMPTS = new AtomicInteger();

        private final BlockingQueue<PulsarMessage<String>> deliveries = new LinkedBlockingQueue<>();

        @Messaging.ReceiveFrom(REDELIVERY_CHANNEL)
        void receive(PulsarMessage<String> message) {
            ATTEMPTS.incrementAndGet();
            deliveries.add(message);
            if (FAIL_NEXT.compareAndSet(true, false)) {
                throw new IllegalStateException("Expected first Pulsar delivery to fail");
            }
        }

        PulsarMessage<String> awaitDelivery(Duration timeout) throws InterruptedException {
            return deliveries.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        static void reset() {
            ATTEMPTS.set(0);
            FAIL_NEXT.set(true);
        }

        static int attemptCount() {
            return ATTEMPTS.get();
        }
    }
}
