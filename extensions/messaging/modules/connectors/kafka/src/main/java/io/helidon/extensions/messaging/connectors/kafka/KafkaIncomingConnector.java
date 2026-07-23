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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.extensions.messaging.ConnectorSource;
import io.helidon.extensions.messaging.ConnectorSourceContext;
import io.helidon.extensions.messaging.IncomingConnector;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;
import io.helidon.service.registry.Service;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;

/**
 * Kafka incoming connector.
 */
@Service.Singleton
public class KafkaIncomingConnector implements IncomingConnector<KafkaConnectorConfig> {
    private static final System.Logger LOGGER = System.getLogger(KafkaIncomingConnector.class.getName());

    private final ConsumerFactory consumerFactory;
    private final Set<KafkaSource> sources = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Create a Kafka incoming connector.
     */
    @Service.Inject
    public KafkaIncomingConnector() {
        this(KafkaConsumer::new);
    }

    KafkaIncomingConnector(ConsumerFactory consumerFactory) {
        this.consumerFactory = Objects.requireNonNull(consumerFactory);
    }

    @Override
    public String connectorName() {
        return KafkaOutgoingConnector.CONNECTOR;
    }

    @Override
    public ConnectorSource createSource(KafkaConnectorConfig config, ConnectorSourceContext context) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(context);
        if (closed.get()) {
            throw new IllegalStateException("Kafka incoming connector is closed");
        }

        KafkaSource source = new KafkaSource(config, context);
        sources.add(source);
        if (closed.get() && sources.remove(source)) {
            source.close();
            throw new IllegalStateException("Kafka incoming connector is closed");
        }
        return source;
    }

    @Override
    @Service.PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) {
            sources.forEach(KafkaSource::close);
        }
    }

    @FunctionalInterface
    interface ConsumerFactory {
        Consumer<Object, Object> create(Map<String, Object> properties);
    }

    private final class KafkaSource implements ConnectorSource {
        private final KafkaConnectorConfig config;
        private final ConnectorSourceContext context;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Consumer<Object, Object>> activeConsumer = new AtomicReference<>();
        private final CountDownLatch closeSignal = new CountDownLatch(1);

        private KafkaSource(KafkaConnectorConfig config, ConnectorSourceContext context) {
            this.config = config;
            this.context = context;
        }

        @Override
        public void run() {
            if (closed.get()) {
                sources.remove(this);
                return;
            }

            Consumer<Object, Object> consumer = null;
            try {
                consumer = consumerFactory.create(KafkaConnectorConfigSupport.consumerProperties(config));
                activeConsumer.set(consumer);
                if (!closed.get()) {
                    consume(consumer);
                }
            } catch (WakeupException e) {
                if (!closed.get()) {
                    throw new MessagingException("Kafka incoming connector failed", e);
                }
            } catch (RuntimeException e) {
                if (!closed.get()) {
                    if (e instanceof MessagingException messagingException) {
                        throw messagingException;
                    }
                    throw new MessagingException("Kafka incoming connector failed", e);
                }
            } finally {
                if (consumer != null) {
                    activeConsumer.compareAndSet(consumer, null);
                    closeConsumer(consumer);
                }
                sources.remove(this);
            }
        }

        private void consume(Consumer<Object, Object> consumer) {
            consumer.subscribe(List.of(config.topic()));
            while (!closed.get()) {
                ConsumerRecords<Object, Object> records = consumer.poll(config.pollTimeout());
                List<Message<Object>> messages = new ArrayList<>();
                for (ConsumerRecord<Object, Object> record : records) {
                    messages.add(toMessage(record));
                }
                if (messages.isEmpty()) {
                    continue;
                }
                try {
                    context.emitBatch(messages);
                } catch (RuntimeException e) {
                    if (closed.get()) {
                        return;
                    }
                    rewind(consumer, records);
                    LOGGER.log(System.Logger.Level.WARNING,
                               "Kafka incoming message processing failed; redelivering "
                                       + records.count() + " record(s)",
                               e);
                    if (!awaitRedeliveryDelay()) {
                        return;
                    }
                    continue;
                }
                if (closed.get()) {
                    return;
                }
                consumer.commitSync(records.nextOffsets());
            }
        }

        private void rewind(Consumer<Object, Object> consumer, ConsumerRecords<Object, Object> records) {
            for (TopicPartition partition : records.partitions()) {
                consumer.seek(partition, records.records(partition).getFirst().offset());
            }
        }

        private boolean awaitRedeliveryDelay() {
            try {
                long delayNanos = TimeUnit.NANOSECONDS.convert(config.redeliveryDelay());
                return !closeSignal.await(delayNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (closed.get()) {
                    return false;
                }
                throw new MessagingException("Kafka incoming connector redelivery wait interrupted", e);
            }
        }

        private Message<Object> toMessage(ConsumerRecord<Object, Object> record) {
            Message.Builder<Object> builder = Message.builder(record.value());
            for (Header header : record.headers()) {
                byte[] value = header.value();
                if (value != null) {
                    builder.header(header.key(), new String(value, StandardCharsets.UTF_8));
                }
            }
            return builder.build();
        }

        private void close() {
            closed.set(true);
            closeSignal.countDown();
            Consumer<Object, Object> consumer = activeConsumer.get();
            if (consumer != null) {
                consumer.wakeup();
            }
        }

        private void closeConsumer(Consumer<Object, Object> consumer) {
            try {
                consumer.close(config.closeTimeout());
            } catch (RuntimeException e) {
                if (!closed.get()) {
                    throw new MessagingException("Kafka incoming connector close failed", e);
                }
            }
        }
    }
}
