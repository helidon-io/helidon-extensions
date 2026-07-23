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

import io.helidon.extensions.messaging.ConnectorSink;
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
 */
@Service.Singleton
public class KafkaOutgoingConnector implements OutgoingConnector<KafkaConnectorConfig> {
    /**
     * Connector name used in messaging configuration.
     */
    public static final String CONNECTOR = "kafka";

    private static final System.Logger LOGGER = System.getLogger(KafkaOutgoingConnector.class.getName());

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
            producers.remove(resource);
            resource.close();
            throw new IllegalStateException("Kafka outgoing connector is closed");
        }
        return new KafkaSink(config.topic(), config.sendTimeout(), producer);
    }

    @Override
    @Service.PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ProducerResource producer : producers) {
            try {
                producer.close();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.ERROR, "Cannot close Kafka producer", e);
            }
        }
        producers.clear();
    }

    @FunctionalInterface
    interface ProducerFactory {
        Producer<Object, Object> create(Map<String, Object> properties);
    }

    private static final class KafkaSink implements ConnectorSink {
        private final String topic;
        private final Duration sendTimeout;
        private final Producer<Object, Object> producer;

        private KafkaSink(String topic, Duration sendTimeout, Producer<Object, Object> producer) {
            this.topic = topic;
            this.sendTimeout = sendTimeout;
            this.producer = producer;
        }

        @Override
        public <T> void send(Message<T> message) {
            await(enqueue(message));
        }

        @Override
        public <T> void sendBatch(List<Message<T>> messages) {
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

        private Future<RecordMetadata> enqueue(Message<?> message) {
            Objects.requireNonNull(message);
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
        private final AtomicBoolean closed = new AtomicBoolean();

        private ProducerResource(Producer<Object, Object> producer, Duration closeTimeout) {
            this.producer = producer;
            this.closeTimeout = closeTimeout;
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                producer.close(closeTimeout);
            }
        }
    }
}
