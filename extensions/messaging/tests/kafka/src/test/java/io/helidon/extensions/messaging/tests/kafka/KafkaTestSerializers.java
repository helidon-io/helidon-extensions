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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Test serializers used to establish deterministic downstream Kafka send boundaries.
 */
public final class KafkaTestSerializers {
    private KafkaTestSerializers() {
    }

    /**
     * String serializer which blocks until explicitly released by a test.
     */
    public static final class BlockingStringSerializer implements Serializer<String> {
        private static final Duration SERIALIZE_TIMEOUT = Duration.ofSeconds(30);

        private static volatile CountDownLatch entered = new CountDownLatch(1);
        private static volatile CountDownLatch release = new CountDownLatch(1);

        @Override
        public byte[] serialize(String topic, String data) {
            entered.countDown();
            try {
                if (!release.await(SERIALIZE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new SerializationException("Timed out awaiting test serializer release");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SerializationException("Interrupted awaiting test serializer release", e);
            }
            return data == null ? null : data.getBytes(StandardCharsets.UTF_8);
        }

        static void reset() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        static boolean awaitEntered(Duration timeout) throws InterruptedException {
            return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        static void release() {
            release.countDown();
        }
    }

    /**
     * String serializer which always fails before a record can be enqueued.
     */
    public static final class FailingStringSerializer implements Serializer<String> {
        @Override
        public byte[] serialize(String topic, String data) {
            throw new SerializationException("Expected downstream serialization failure");
        }
    }
}
