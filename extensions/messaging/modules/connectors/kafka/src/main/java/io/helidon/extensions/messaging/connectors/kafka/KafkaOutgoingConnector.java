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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.extensions.messaging.ConnectorSink;
import io.helidon.extensions.messaging.DeadLetterMessage;
import io.helidon.extensions.messaging.ManagedConnectorBinding;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.extensions.messaging.OutgoingConnector;
import io.helidon.service.registry.Service;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeaders;

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
@Service.Singleton
public class KafkaOutgoingConnector implements OutgoingConnector<KafkaConnectorConfig> {
    /**
     * Connector name used in messaging configuration.
     */
    public static final String CONNECTOR = "kafka";

    /**
     * Dead-letter header containing the original Kafka topic.
     */
    public static final String DLQ_ORIGINAL_TOPIC_HEADER = "dlq-orig-topic";

    /**
     * Dead-letter header containing the original Kafka partition.
     */
    public static final String DLQ_ORIGINAL_PARTITION_HEADER = "dlq-orig-partition";

    /**
     * Dead-letter header containing the original Kafka offset.
     */
    public static final String DLQ_ORIGINAL_OFFSET_HEADER = "dlq-orig-offset";

    /**
     * Dead-letter header containing the original Kafka record timestamp in milliseconds.
     * <p>
     * This is source metadata. The dead-letter record itself has its own publication timestamp.
     */
    public static final String DLQ_ORIGINAL_TIMESTAMP_HEADER = "dlq-orig-timestamp";

    /**
     * Dead-letter header containing the name of the original {@link KafkaMessage.TimestampType}.
     */
    public static final String DLQ_ORIGINAL_TIMESTAMP_TYPE_HEADER = "dlq-orig-timestamp-type";

    /**
     * Dead-letter header containing the original Kafka leader epoch.
     */
    public static final String DLQ_ORIGINAL_LEADER_EPOCH_HEADER = "dlq-orig-leader-epoch";

    private static final System.Logger LOGGER = System.getLogger(KafkaOutgoingConnector.class.getName());
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
    private final Set<ProducerResource> producers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Create a Kafka outgoing connector.
     */
    @Service.Inject
    public KafkaOutgoingConnector() {
        this(KafkaProducer::new);
    }

    KafkaOutgoingConnector(ProducerFactory producerFactory) {
        this.producerFactory = Objects.requireNonNull(producerFactory);
    }

    @Override
    public String connectorName() {
        return CONNECTOR;
    }

    @Override
    public ConnectorSink createSink(KafkaConnectorConfig config) {
        Objects.requireNonNull(config);
        if (closed.get()) {
            throw new IllegalStateException("Kafka outgoing connector is closed");
        }

        Producer<Object, Object> producer;
        try {
            producer = producerFactory.create(KafkaConnectorConfigSupport.producerProperties(config));
        } catch (RuntimeException e) {
            throw new MessagingException("Cannot create Kafka producer for topic " + config.topic(), e);
        }

        ProducerResource resource = new ProducerResource(producer, config.closeTimeout());
        producers.add(resource);
        if (closed.get()) {
            resource.close();
            producers.remove(resource);
            throw new IllegalStateException("Kafka outgoing connector is closed");
        }
        return new KafkaSink(config.topic(),
                             config.sendTimeout(),
                             producer,
                             () -> {
                                 resource.forceClose();
                                 producers.remove(resource);
                             },
                             () -> {
                                 resource.close();
                                 producers.remove(resource);
                             });
    }

    @Override
    @Service.PreDestroy
    public void close() {
        closed.set(true);
        RuntimeException closeFailure = null;
        for (ProducerResource producer : producers) {
            try {
                producer.close();
                producers.remove(producer);
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.ERROR, "Cannot close Kafka producer", e);
                if (closeFailure == null) {
                    closeFailure = e;
                } else if (closeFailure != e) {
                    closeFailure.addSuppressed(e);
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    @FunctionalInterface
    interface ProducerFactory {
        Producer<Object, Object> create(Map<String, Object> properties);
    }

    private static final class KafkaSink implements ConnectorSink, ManagedConnectorBinding {
        private final String topic;
        private final Duration sendTimeout;
        private final Producer<Object, Object> producer;
        private final Runnable forceClose;
        private final Runnable close;

        private KafkaSink(String topic,
                          Duration sendTimeout,
                          Producer<Object, Object> producer,
                          Runnable forceClose,
                          Runnable close) {
            this.topic = topic;
            this.sendTimeout = sendTimeout;
            this.producer = producer;
            this.forceClose = forceClose;
            this.close = close;
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
            forceClose.run();
        }

        @Override
        public void close() {
            close.run();
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
                return producer.send(record);
            } catch (RuntimeException e) {
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
                return producer.send(record);
            } catch (RuntimeException e) {
                throw new MessagingException("Cannot send Kafka message to topic " + topic, e);
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
    }

    private static final class ProducerResource {
        private final Producer<Object, Object> producer;
        private final Duration closeTimeout;
        private final ReentrantLock closeLock = new ReentrantLock();
        private boolean closed;

        private ProducerResource(Producer<Object, Object> producer, Duration closeTimeout) {
            this.producer = producer;
            this.closeTimeout = closeTimeout;
        }

        private void close() {
            closeLock.lock();
            try {
                if (closed) {
                    return;
                }
                producer.close(closeTimeout);
                closed = true;
            } finally {
                closeLock.unlock();
            }
        }

        private void forceClose() {
            closeLock.lock();
            try {
                if (closed) {
                    return;
                }
                producer.close(Duration.ZERO);
                closed = true;
            } finally {
                closeLock.unlock();
            }
        }
    }
}
