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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.DeadLetterMessage;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.OutgoingEndpoint;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.header.internals.RecordHeaders;

import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_LEADER_EPOCH_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_OFFSET_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_PARTITION_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER;
import static io.helidon.extensions.messaging.connectors.kafka.KafkaConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER;

/**
 * Kafka outgoing connector.
 *
 * <p>A sink created by this connector returns from {@code send} or {@code sendBatch}
 * only after the {@link Producer#send(ProducerRecord) producer send futures} complete
 * successfully, or throws if enqueueing or awaiting any send fails. The corresponding
 * Kafka success point is controlled by the producer {@code acks} configuration: no
 * broker acknowledgement for {@code acks=0}, the leader acknowledgement for
 * {@code acks=1}, or all in-sync replica acknowledgements for {@code acks=all}. It
 * does not imply that a consumer has processed the message.
 */
final class KafkaOutgoingConnector {
    private static final Set<String> DLQ_RESERVED_HEADERS = Set.of(DeadLetterMessage.SOURCE_CHANNEL_HEADER,
                                                                   DeadLetterMessage.ATTEMPTS_HEADER,
                                                                   DeadLetterMessage.FAILURE_TYPE_HEADER,
                                                                   DeadLetterMessage.FAILURE_MESSAGE_HEADER,
                                                                   DLQ_ORIGINAL_TOPIC_HEADER,
                                                                   DLQ_ORIGINAL_PARTITION_HEADER,
                                                                   DLQ_ORIGINAL_OFFSET_HEADER,
                                                                   DLQ_ORIGINAL_TIMESTAMP_HEADER,
                                                                   DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER,
                                                                   DLQ_ORIGINAL_LEADER_EPOCH_HEADER);

    private final ProducerFactory producerFactory;

    KafkaOutgoingConnector() {
        this(KafkaProducer::new);
    }

    KafkaOutgoingConnector(ProducerFactory producerFactory) {
        this.producerFactory = Objects.requireNonNull(producerFactory);
    }

    OutgoingEndpoint createOutgoingEndpoint(KafkaConnectorConfig config) {
        Objects.requireNonNull(config);
        if (config.direction() != ConnectorConfig.Direction.OUTGOING) {
            throw new IllegalArgumentException("Kafka connector configuration for channel " + config.channel()
                                                       + " has direction " + config.direction()
                                                       + ", expected " + ConnectorConfig.Direction.OUTGOING);
        }
        return new KafkaSink(config.topic(),
                             config.sendTimeout(),
                             config.closeTimeout(),
                             KafkaConnectorConfigSupport.producerProperties(config),
                             producerFactory);
    }

    @FunctionalInterface
    interface ProducerFactory {
        Producer<Object, Object> create(Map<String, Object> properties);
    }

    private static final class KafkaSink implements OutgoingEndpoint {
        private final String topic;
        private final Duration sendTimeout;
        private final Duration closeTimeout;
        private final Map<String, Object> producerProperties;
        private final ProducerFactory producerFactory;
        private final ReentrantLock lifecycleLock = new ReentrantLock();
        private final Condition lifecycleChanged = lifecycleLock.newCondition();
        private State state = State.NEW;
        private Producer<Object, Object> producer;
        private Throwable startupFailure;
        private Thread startOwner;
        private Thread closeOwner;
        private boolean closeRequested;
        private boolean closing;

        private KafkaSink(String topic,
                          Duration sendTimeout,
                          Duration closeTimeout,
                          Map<String, Object> producerProperties,
                          ProducerFactory producerFactory) {
            this.topic = topic;
            this.sendTimeout = sendTimeout;
            this.closeTimeout = closeTimeout;
            this.producerProperties = producerProperties;
            this.producerFactory = producerFactory;
        }

        @Override
        public void start() {
            lifecycleLock.lock();
            try {
                while (state == State.STARTING && !closeRequested) {
                    awaitLifecycleChange("endpoint startup");
                }
                if (state == State.CLOSED || closeRequested) {
                    throw new IllegalStateException("Kafka outgoing endpoint is closed");
                }
                if (state == State.READY) {
                    return;
                }
                if (state == State.FAILED) {
                    throw propagate(startupFailure == null
                                            ? new IllegalStateException("Kafka outgoing endpoint startup failed")
                                            : startupFailure);
                }
                state = State.STARTING;
                startOwner = Thread.currentThread();
            } finally {
                lifecycleLock.unlock();
            }

            Producer<Object, Object> created;
            try {
                created = producerFactory.create(producerProperties);
            } catch (RuntimeException | Error e) {
                Throwable failure = e instanceof RuntimeException
                        ? new MessagingException("Cannot create Kafka producer for topic " + topic, e)
                        : e;
                throw propagate(completeStart(null, failure));
            }

            if (!publishProducer(created)) {
                Throwable failure = new IllegalStateException("Kafka outgoing endpoint was closed during startup");
                try {
                    created.close(Duration.ZERO);
                } catch (RuntimeException | Error closeFailure) {
                    if (closeFailure != failure) {
                        closeFailure.addSuppressed(failure);
                    }
                    failure = closeFailure;
                    retainProducerAfterCloseFailure(created, failure);
                }
                completeStart(created, failure);
                throw propagate(failure);
            }

            Throwable failure = null;
            try {
                List<PartitionInfo> partitions = created.partitionsFor(topic);
                if (partitions == null || partitions.isEmpty()) {
                    throw new MessagingException("Kafka topic " + topic + " has no available partitions");
                }
            } catch (RuntimeException e) {
                failure = e instanceof MessagingException
                        ? e
                        : new MessagingException("Cannot verify Kafka producer readiness for topic " + topic, e);
            } catch (Error e) {
                failure = e;
            }

            Throwable result = completeStart(created, failure);
            if (result != null) {
                throw propagate(result);
            }
        }

        private boolean publishProducer(Producer<Object, Object> created) {
            lifecycleLock.lock();
            try {
                if (state != State.STARTING || closeRequested) {
                    return false;
                }
                producer = created;
                lifecycleChanged.signalAll();
                return true;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private Throwable completeStart(Producer<Object, Object> created, Throwable failure) {
            boolean cleanup = false;
            Throwable result;
            lifecycleLock.lock();
            try {
                if (closing) {
                    result = failure == null
                            ? new IllegalStateException("Kafka outgoing endpoint was closed during startup")
                            : failure;
                    startOwner = null;
                    lifecycleChanged.signalAll();
                    return result;
                }
                boolean cancelled = closeRequested || state == State.CLOSED;
                result = failure;
                if (result == null && cancelled) {
                    result = new IllegalStateException("Kafka outgoing endpoint was closed during startup");
                }
                if (result == null) {
                    state = State.READY;
                    startOwner = null;
                    lifecycleChanged.signalAll();
                    return null;
                }
                if (!cancelled && producer == created && created != null) {
                    closing = true;
                    closeOwner = Thread.currentThread();
                    cleanup = true;
                } else {
                    if (state != State.CLOSED) {
                        state = State.FAILED;
                        if (startupFailure == null) {
                            startupFailure = result;
                        }
                    }
                    startOwner = null;
                    lifecycleChanged.signalAll();
                }
            } finally {
                lifecycleLock.unlock();
            }

            if (!cleanup) {
                return result;
            }

            Throwable closeFailure = null;
            try {
                created.close(Duration.ZERO);
            } catch (RuntimeException | Error e) {
                closeFailure = e;
                if (e != result) {
                    if (e instanceof Error) {
                        e.addSuppressed(result);
                        result = e;
                    } else {
                        result.addSuppressed(e);
                    }
                }
            }
            lifecycleLock.lock();
            try {
                closing = false;
                closeOwner = null;
                if (closeFailure == null && producer == created) {
                    producer = null;
                }
                state = closeRequested && closeFailure == null ? State.CLOSED : State.FAILED;
                startupFailure = result;
                startOwner = null;
                lifecycleChanged.signalAll();
            } finally {
                lifecycleLock.unlock();
            }
            return result;
        }

        private void retainProducerAfterCloseFailure(Producer<Object, Object> created, Throwable failure) {
            lifecycleLock.lock();
            try {
                if (producer == null) {
                    producer = created;
                }
                state = State.FAILED;
                startupFailure = failure;
                lifecycleChanged.signalAll();
            } finally {
                lifecycleLock.unlock();
            }
        }

        @Override
        public void flush() {
            Producer<Object, Object> current = readyProducer();
            try {
                current.flush();
            } catch (RuntimeException e) {
                throw new MessagingException("Cannot flush Kafka producer for topic " + topic, e);
            }
        }

        @Override
        public <T> void send(Message<T> message) {
            await(enqueue(message));
        }

        @Override
        public <T> void sendBatch(List<? extends Message<T>> messages) {
            Objects.requireNonNull(messages);
            if (messages.isEmpty()) {
                return;
            }

            List<Future<RecordMetadata>> results = new ArrayList<>(messages.size());
            for (Message<T> message : messages) {
                results.add(enqueue(message));
            }
            for (Future<RecordMetadata> result : results) {
                await(result);
            }
        }

        @Override
        public void forceClose() {
            close(Duration.ZERO, true);
        }

        @Override
        public void close() {
            close(closeTimeout, false);
        }

        private Future<RecordMetadata> enqueue(Message<?> message) {
            Objects.requireNonNull(message);
            if (message instanceof DeadLetterMessage<?> deadLetterMessage) {
                return enqueue(deadLetterMessage);
            }
            if (message instanceof KafkaMessage<?, ?> kafkaMessage) {
                return enqueue(kafkaMessage);
            }
            RecordHeaders headers = new RecordHeaders();
            message.headers().forEach((name, value) -> headers.add(name, value.getBytes(StandardCharsets.UTF_8)));
            ProducerRecord<Object, Object> record = new ProducerRecord<>(topic,
                                                                         null,
                                                                         null,
                                                                         null,
                                                                         message.entity(),
                                                                         headers);
            try {
                return readyProducer().send(record);
            } catch (RuntimeException e) {
                if (e instanceof MessagingException messagingException) {
                    throw messagingException;
                }
                throw new MessagingException("Cannot send Kafka message to topic " + topic, e);
            }
        }

        private Future<RecordMetadata> enqueue(DeadLetterMessage<?> message) {
            Message<?> originalMessage = message.originalMessage();
            if (!(originalMessage instanceof KafkaMessage<?, ?> kafkaMessage)) {
                RecordHeaders headers = new RecordHeaders();
                message.headers().forEach((name, value) -> addUtf8Header(headers, name, value));
                return enqueue(null, message.entity(), headers);
            }

            RecordHeaders headers = kafkaHeaders(kafkaMessage);
            mergePortableWrapperHeaders(headers, message, originalMessage);
            addReservedHeader(headers,
                              DeadLetterMessage.SOURCE_CHANNEL_HEADER,
                              message.sourceChannel());
            addReservedHeader(headers,
                              DeadLetterMessage.ATTEMPTS_HEADER,
                              message.attempts());
            addReservedHeader(headers,
                              DeadLetterMessage.FAILURE_TYPE_HEADER,
                              message.failureType());
            addReservedHeader(headers,
                              DeadLetterMessage.FAILURE_MESSAGE_HEADER,
                              message.failureMessage());
            addKafkaMetadata(headers,
                             DLQ_ORIGINAL_TOPIC_HEADER,
                             kafkaMessage.topic().orElse(null));
            addKafkaMetadata(headers,
                             DLQ_ORIGINAL_PARTITION_HEADER,
                             kafkaMessage.partition().isPresent()
                                     ? kafkaMessage.partition().getAsInt()
                                     : null);
            addKafkaMetadata(headers,
                             DLQ_ORIGINAL_OFFSET_HEADER,
                             kafkaMessage.offset().isPresent()
                                     ? kafkaMessage.offset().getAsLong()
                                     : null);
            addKafkaMetadata(headers,
                             DLQ_ORIGINAL_TIMESTAMP_HEADER,
                             kafkaMessage.timestamp().isPresent()
                                     ? kafkaMessage.timestamp().getAsLong()
                                     : null);
            addKafkaMetadata(headers,
                             DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER,
                             kafkaMessage.timestampType().orElse(null));
            addKafkaMetadata(headers,
                             DLQ_ORIGINAL_LEADER_EPOCH_HEADER,
                             kafkaMessage.leaderEpoch().isPresent()
                                     ? kafkaMessage.leaderEpoch().getAsInt()
                                     : null);
            return enqueue(kafkaMessage.key().orElse(null), message.entity(), headers);
        }

        private Future<RecordMetadata> enqueue(KafkaMessage<?, ?> message) {
            return enqueue(message.key().orElse(null), message.entity(), kafkaHeaders(message));
        }

        private Future<RecordMetadata> enqueue(Object key, Object entity, RecordHeaders headers) {
            ProducerRecord<Object, Object> record = new ProducerRecord<>(topic,
                                                                         null,
                                                                         null,
                                                                         key,
                                                                         entity,
                                                                         headers);
            try {
                return readyProducer().send(record);
            } catch (RuntimeException e) {
                if (e instanceof MessagingException messagingException) {
                    throw messagingException;
                }
                throw new MessagingException("Cannot send Kafka message to topic " + topic, e);
            }
        }

        private Producer<Object, Object> readyProducer() {
            lifecycleLock.lock();
            try {
                if (state != State.READY || closeRequested) {
                    throw new IllegalStateException("Kafka outgoing endpoint is not ready");
                }
                return producer;
            } finally {
                lifecycleLock.unlock();
            }
        }

        private void close(Duration timeout, boolean force) {
            Producer<Object, Object> current;
            Thread owner;
            lifecycleLock.lock();
            try {
                closeRequested = true;
                if (force && closing) {
                    interruptLifecycle(closeOwner == null ? startOwner : closeOwner);
                    return;
                }
                while (closing) {
                    awaitLifecycleChange("endpoint close");
                }
                if (state == State.CLOSED) {
                    return;
                }
                owner = startOwner;
                current = producer;
                if (current == null) {
                    state = State.CLOSED;
                    lifecycleChanged.signalAll();
                    interruptLifecycle(owner);
                    return;
                }
                closing = true;
                closeOwner = Thread.currentThread();
                if (state == State.STARTING && startupFailure == null) {
                    startupFailure = new IllegalStateException("Kafka outgoing endpoint was closed during startup");
                }
            } finally {
                lifecycleLock.unlock();
            }

            interruptLifecycle(owner);

            Throwable failure = null;
            try {
                current.close(timeout);
            } catch (RuntimeException | Error e) {
                failure = e;
            }

            lifecycleLock.lock();
            try {
                closing = false;
                closeOwner = null;
                if (failure == null) {
                    if (producer == current) {
                        producer = null;
                    }
                    state = State.CLOSED;
                } else {
                    state = State.FAILED;
                    if (startupFailure == null) {
                        startupFailure = new MessagingException("Cannot close Kafka producer for topic " + topic,
                                                                failure);
                    }
                }
                lifecycleChanged.signalAll();
            } finally {
                lifecycleLock.unlock();
            }
            if (failure != null) {
                throw propagate(failure);
            }
        }

        private void interruptLifecycle(Thread owner) {
            if (owner != null && owner != Thread.currentThread()) {
                owner.interrupt();
            }
        }

        private RuntimeException propagate(Throwable failure) {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            return new MessagingException("Kafka outgoing endpoint lifecycle failed for topic " + topic, failure);
        }

        private void awaitLifecycleChange(String operation) {
            try {
                lifecycleChanged.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while waiting for Kafka outgoing " + operation
                                                     + " on topic " + topic,
                                             e);
            }
        }

        private RecordHeaders kafkaHeaders(KafkaMessage<?, ?> message) {
            RecordHeaders headers = new RecordHeaders();
            for (KafkaMessage.Header header : message.kafkaHeaders()) {
                headers.add(header.name(), header.value().orElse(null));
            }
            return headers;
        }

        private void mergePortableWrapperHeaders(RecordHeaders headers,
                                                 DeadLetterMessage<?> message,
                                                 Message<?> originalMessage) {
            Map<String, String> originalHeaders = originalMessage.headers();
            message.headers().forEach((name, value) -> {
                if (!DLQ_RESERVED_HEADERS.contains(name)
                        && (!originalHeaders.containsKey(name)
                        || !Objects.equals(originalHeaders.get(name), value))) {
                    addUtf8Header(headers, name, value);
                }
            });
        }

        private void addReservedHeader(RecordHeaders headers, String name, Object value) {
            headers.remove(name);
            addUtf8Header(headers, name, String.valueOf(value));
        }

        private void addKafkaMetadata(RecordHeaders headers, String name, Object value) {
            headers.remove(name);
            if (value != null) {
                addUtf8Header(headers, name, String.valueOf(value));
            }
        }

        private void addUtf8Header(RecordHeaders headers, String name, String value) {
            headers.add(name, value.getBytes(StandardCharsets.UTF_8));
        }

        private void await(Future<RecordMetadata> result) {
            try {
                result.get(sendTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Interrupted while sending Kafka message to topic " + topic, e);
            } catch (ExecutionException e) {
                throw new MessagingException("Cannot send Kafka message to topic " + topic, e.getCause());
            } catch (TimeoutException e) {
                throw new MessagingException("Timed out sending Kafka message to topic " + topic, e);
            }
        }

        private enum State {
            NEW,
            STARTING,
            READY,
            FAILED,
            CLOSED
        }
    }
}
