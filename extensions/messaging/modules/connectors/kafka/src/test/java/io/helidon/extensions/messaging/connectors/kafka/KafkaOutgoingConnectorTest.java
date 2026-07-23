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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.helidon.extensions.messaging.ConnectorConfig;
import io.helidon.extensions.messaging.Message;
import io.helidon.extensions.messaging.MessagingException;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaOutgoingConnectorTest {
    private static final String TOPIC = "audit-events";

    @Test
    void testConnectorName() {
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> mockProducer(true));

        assertThat(connector.connectorName(), is(KafkaOutgoingConnector.CONNECTOR));
    }

    @Test
    void testSendsPayloadTopicAndUtf8Headers() {
        MockProducer<Object, Object> producer = mockProducer(true);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);

        connector.createSink(config())
                .send(Message.builder("audit event")
                              .header("trace-id", "Příliš žluťoučký")
                              .build());

        assertThat(producer.history().size(), is(1));
        ProducerRecord<Object, Object> record = producer.history().get(0);
        assertThat(record.topic(), is(TOPIC));
        assertThat(record.value(), is("audit event"));
        Header header = record.headers().lastHeader("trace-id");
        assertThat(new String(header.value(), StandardCharsets.UTF_8), is("Příliš žluťoučký"));
    }

    @Test
    void testBatchEnqueuesAllRecordsBeforeWaiting() throws Exception {
        MockProducer<Object, Object> producer = mockProducer(false);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> connector.createSink(config())
                .sendBatch(List.of(Message.create("first"), Message.create("second"))));

        awaitHistory(producer, 2);
        assertThat("send should wait for broker completion", sending.isDone(), is(false));
        assertThat(producer.completeNext(), is(true));
        assertThat(producer.completeNext(), is(true));
        sending.get(1, TimeUnit.SECONDS);

        assertThat(producer.history().stream().map(record -> (String) record.value()).toList(),
                   is(List.of("first", "second")));
    }

    @Test
    void testProducerFailureIsWrapped() throws Exception {
        MockProducer<Object, Object> producer = mockProducer(false);
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> producer);
        RuntimeException failure = new IllegalStateException("send failed");
        CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> connector.createSink(config())
                .send(Message.create("audit event")));

        awaitHistory(producer, 1);
        assertThat(producer.errorNext(failure), is(true));
        ExecutionException exception = assertThrows(ExecutionException.class,
                                                    () -> sending.get(1, TimeUnit.SECONDS));
        assertThat(exception.getCause(), instanceOf(MessagingException.class));
        assertThat(exception.getCause().getCause(), sameInstance(failure));
    }

    @Test
    void testCloseClosesEveryProducerAndIsIdempotent() {
        List<MockProducer<Object, Object>> created = new ArrayList<>();
        KafkaOutgoingConnector connector = new KafkaOutgoingConnector(ignored -> {
            MockProducer<Object, Object> producer = mockProducer(true);
            created.add(producer);
            return producer;
        });
        connector.createSink(config());
        connector.createSink(config());

        connector.close();
        connector.close();

        assertThat(created.size(), is(2));
        assertThat(created.stream().allMatch(MockProducer::closed), is(true));
    }

    private static KafkaConnectorConfig config() {
        return KafkaConnectorConfig.builder()
                .direction(ConnectorConfig.Direction.OUTGOING)
                .channel("audit")
                .connector(KafkaOutgoingConnector.CONNECTOR)
                .bootstrapServers("localhost:9092")
                .topic(TOPIC)
                .sendTimeout(Duration.ofSeconds(2))
                .closeTimeout(Duration.ofSeconds(1))
                .build();
    }

    private static MockProducer<Object, Object> mockProducer(boolean autoComplete) {
        Serializer<Object> serializer = (topic, data) -> data == null
                ? null
                : String.valueOf(data).getBytes(StandardCharsets.UTF_8);
        return new MockProducer<>(autoComplete, null, serializer, serializer);
    }

    private static void awaitHistory(MockProducer<?, ?> producer, int expectedSize) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (producer.history().size() < expectedSize && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(producer.history().size(), is(expectedSize));
    }
}
