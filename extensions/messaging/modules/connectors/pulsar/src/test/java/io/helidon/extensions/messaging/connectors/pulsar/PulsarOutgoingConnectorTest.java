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

package io.helidon.extensions.messaging.connectors.pulsar;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.messaging.BatchDeliveryException;
import io.helidon.messaging.BatchItemStatus;
import io.helidon.messaging.ConnectorDirection;
import io.helidon.messaging.DeadLetterMessage;
import io.helidon.messaging.HeaderValue;
import io.helidon.messaging.Message;
import io.helidon.messaging.MessageBatch;
import io.helidon.messaging.MessagingException;
import io.helidon.messaging.OutgoingConnector;

import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulsarOutgoingConnectorTest {
    @Test
    void sendsPayloadMetadataAndCompleteBatchBeforeReturning() {
        FakeTransport transport = new FakeTransport();
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(config(PulsarSchemaType.STRING));

        connector.start();
        connector.sendBatch(MessageBatch.create(List.of(
                PulsarMessage.builder("first")
                        .key("key-1")
                        .orderingKey(new byte[] {1, 2})
                        .header("trace-id", "first-trace")
                        .header("span-id", "first-span")
                        .eventTime(123)
                        .build(),
                Message.builder("second").header("trace-id", "second-trace").build())));
        connector.close();

        assertThat(transport.values, is(List.of("first", "second")));
        assertThat(transport.properties.get(0),
                   is(Map.of("trace-id", "first-trace", "span-id", "first-span")));
        assertThat(List.copyOf(transport.properties.get(0).keySet()), is(List.of("trace-id", "span-id")));
        assertThat(transport.properties.get(1), is(Map.of("trace-id", "second-trace")));
        assertThat(transport.keys, is(List.of("key-1", "")));
        assertThat(transport.eventTimes, is(List.of(123L, -1L)));
        assertThat(transport.closed.get(), is(true));
    }

    @Test
    void reportsSuccessfulPrefixSchemaFailureAndUntouchedSuffix() {
        FakeTransport transport = new FakeTransport();
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(config(PulsarSchemaType.STRING));
        connector.start();
        MessageBatch<Object> batch = MessageBatch.create(List.of(Message.create("first"),
                                                                  Message.create(42),
                                                                  Message.create("third")));

        BatchDeliveryException failure = assertThrows(BatchDeliveryException.class,
                                                       () -> connector.sendBatch(batch));

        assertThat(failure.outcome(0).status(), is(BatchItemStatus.SUCCEEDED));
        assertThat(failure.outcome(1).status(), is(BatchItemStatus.FAILED));
        assertThat(failure.outcome(2).status(), is(BatchItemStatus.NOT_ATTEMPTED));
        assertThat(transport.values, is(List.of("first")));
        connector.forceClose();
        assertThat(transport.shutdowns.get(), is(1));
    }

    @Test
    void rejectsTypedAndDuplicateHeadersThatPulsarPropertiesCannotRepresent() {
        FakeTransport transport = new FakeTransport();
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(config(PulsarSchemaType.STRING));
        connector.start();

        BatchDeliveryException typedFailure = assertThrows(
                BatchDeliveryException.class,
                () -> connector.sendBatch(MessageBatch.create(Message.builder("typed")
                                                                       .header("priority", HeaderValue.integer(7))
                                                                       .build())));
        assertThat(typedFailure.getCause().getMessage(), containsString("only text message headers"));

        BatchDeliveryException duplicateFailure = assertThrows(
                BatchDeliveryException.class,
                () -> connector.sendBatch(MessageBatch.create(Message.builder("duplicate")
                                                                       .addHeader("trace-id", "first")
                                                                       .addHeader("trace-id", "second")
                                                                       .build())));
        assertThat(duplicateFailure.getCause().getMessage(), containsString("duplicate message header 'trace-id'"));
        assertThat(transport.values, is(List.of()));
        connector.close();
    }

    @Test
    void passesResolvedBuiltInSchemaAndTypedPayloadToPulsar() {
        FakeTransport transport = new FakeTransport();
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(config(PulsarSchemaType.INT32));

        connector.start();
        connector.sendBatch(MessageBatch.create(Message.create(42)));
        connector.close();

        assertThat(transport.schema.get(), sameInstance(Schema.INT32));
        assertThat(transport.values, is(List.of(42)));
    }

    @Test
    void passesResolvedCustomSchemaAndPayloadMetadataToPulsar() {
        FakeTransport transport = new FakeTransport();
        Schema<CustomPayload> customSchema = Schema.JSON(CustomPayload.class);
        PulsarConnectorConfig config = configBuilder(PulsarSchemaType.STRING)
                .schemaProvider("custom-json")
                .build();
        PulsarSchemaResolver.ResolvedSchema resolved = PulsarSchemaResolver.resolve(
                config,
                ConnectorDirection.OUTGOING,
                () -> List.of(schemaProvider("custom-json", customSchema)));
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(config, resolved);
        CustomPayload payload = new CustomPayload("order", 7);

        connector.start();
        connector.sendBatch(MessageBatch.create(PulsarMessage.builder(payload)
                                                         .key("custom-key")
                                                         .header("trace-id", "custom-trace")
                                                         .eventTime(123)
                                                         .build()));
        connector.close();

        assertThat(transport.schema.get(), sameInstance(customSchema));
        assertThat(transport.values.getFirst(), sameInstance(payload));
        assertThat(transport.properties, is(List.of(Map.of("trace-id", "custom-trace"))));
        assertThat(transport.keys, is(List.of("custom-key")));
        assertThat(transport.eventTimes, is(List.of(123L)));
    }

    @Test
    void selectsAutoProduceSchemaAndSnapshotsBytes() {
        FakeTransport transport = new FakeTransport();
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(config(PulsarSchemaType.AUTO));
        byte[] payload = {1, 2, 3};

        connector.start();
        connector.sendBatch(MessageBatch.create(Message.create(payload)));
        connector.close();

        byte[] sent = (byte[]) transport.values.getFirst();
        assertThat(transport.schema.get().getClass().equals(Schema.AUTO_PRODUCE_BYTES().getClass()), is(true));
        assertNotSame(payload, sent);
        payload[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, sent);
    }

    @Test
    void byteBufferMappingPreservesSourceCursorAndSnapshotsEncodedRange() {
        FakeTransport transport = new FakeTransport();
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(config(PulsarSchemaType.BYTEBUFFER));
        ByteBuffer payload = ByteBuffer.wrap(new byte[] {0, 1, 2, 3, 4, 5});
        payload.position(2);
        payload.limit(5);

        connector.start();
        connector.sendBatch(MessageBatch.create(Message.create(payload)));
        connector.close();

        ByteBuffer sent = (ByteBuffer) transport.values.getFirst();
        assertThat(transport.schema.get(), sameInstance(Schema.BYTEBUFFER));
        assertNotSame(payload, sent);
        assertThat(payload.position(), is(2));
        assertThat(payload.limit(), is(5));
        payload.put(0, (byte) 9);
        assertArrayEquals(new byte[] {0, 1, 2, 3, 4}, Schema.BYTEBUFFER.encode(sent));
        assertThat(payload.position(), is(2));
        assertThat(payload.limit(), is(5));
    }

    @Test
    void sendsUnavailablePulsarDeadLetterAsNativeNullWithOriginalMetadata() {
        FakeTransport transport = new FakeTransport();
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(config(PulsarSchemaType.STRING));
        PulsarMessage<Object> original = PulsarMessageMapper.metadataOnly(
                PulsarTestSupport.nativeMessage("oversized", 9));
        DeadLetterMessage<Object> deadLetter = DeadLetterMessage.create(
                original,
                "orders-in",
                2,
                new MessagingException("Pulsar message payload is unavailable"));

        connector.start();
        connector.sendBatch(MessageBatch.create(deadLetter));
        connector.close();

        assertThat(transport.values.size(), is(1));
        assertThat(transport.values.getFirst(), nullValue());
        assertThat(transport.properties.getFirst().get("trace-id"), is("pulsar-trace"));
        assertThat(transport.properties.getFirst().get(DeadLetterMessage.SOURCE_CHANNEL_HEADER), is("orders-in"));
        assertThat(transport.properties.getFirst().get(DeadLetterMessage.ATTEMPTS_HEADER), is("2"));
        assertThat(transport.properties.getFirst().get(PulsarConnectorProvider.DLQ_ORIGINAL_TOPIC_HEADER),
                   is("persistent://public/default/input"));
    }

    @Test
    void zeroCloseTimeoutAcceptsCompletedProducerAndClientCloses() {
        FakeTransport transport = new FakeTransport();
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(configBuilder(PulsarSchemaType.STRING)
                                                 .closeTimeout(Duration.ZERO)
                                                 .build());

        connector.start();
        connector.close();

        assertThat(transport.closed.get(), is(true));
        assertThat(transport.shutdowns.get(), is(0));
    }

    @Test
    void zeroCloseTimeoutRejectsIncompleteClientCloseAndForcesShutdown() {
        FakeTransport transport = new FakeTransport();
        transport.clientCloseFuture = new CompletableFuture<>();
        OutgoingConnector connector = new PulsarOutgoingConnector(ignored -> transport.client())
                .createOutgoingConnector(configBuilder(PulsarSchemaType.STRING)
                                                 .closeTimeout(Duration.ZERO)
                                                 .build());

        connector.start();
        MessagingException failure = assertThrows(MessagingException.class, connector::close);

        assertThat(failure.getMessage(), containsString("Timed out closing Pulsar client"));
        assertThat(transport.shutdowns.get(), is(1));
        assertThat(transport.closed.get(), is(true));
    }

    private static PulsarConnectorConfig config(PulsarSchemaType schema) {
        return configBuilder(schema).build();
    }

    private static PulsarConnectorConfig.Builder configBuilder(PulsarSchemaType schema) {
        return PulsarConnectorConfig.builder()
                .direction(ConnectorDirection.OUTGOING)
                .channel("out")
                .connector(PulsarConnectorProvider.CONNECTOR_TYPE)
                .serviceUrl("pulsar://localhost:6650")
                .topic("persistent://public/default/out")
                .schema(schema);
    }

    private static PulsarSchemaProvider schemaProvider(String name, Schema<?> schema) {
        return new PulsarSchemaProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Schema<?> schema() {
                return schema;
            }
        };
    }

    private static final class FakeTransport {
        private final List<Object> values = new ArrayList<>();
        private final List<Map<String, String>> properties = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private final List<Long> eventTimes = new ArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger shutdowns = new AtomicInteger();
        private final AtomicReference<Schema<?>> schema = new AtomicReference<>();
        private CompletableFuture<Void> producerCloseFuture = CompletableFuture.completedFuture(null);
        private CompletableFuture<Void> clientCloseFuture = CompletableFuture.completedFuture(null);
        private final MessageId messageId = PulsarTestSupport.proxy(MessageId.class,
                                                                    (ignored, method, args) -> method.getName()
                                                                            .equals("compareTo")
                                                                            ? 0
                                                                            : PulsarTestSupport.defaultValue(method));
        private final Producer<Object> producer = producer();
        private final ProducerBuilder<Object> producerBuilder = producerBuilder();
        private final PulsarClient client = clientProxy();

        private PulsarClient client() {
            return client;
        }

        private PulsarClient clientProxy() {
            return PulsarTestSupport.proxy(PulsarClient.class, (ignored, method, args) -> switch (method.getName()) {
            case "newProducer" -> {
                schema.set((Schema<?>) args[0]);
                yield producerBuilder;
            }
            case "closeAsync" -> {
                clientCloseFuture.thenRun(() -> closed.set(true));
                yield clientCloseFuture;
            }
            case "shutdown" -> {
                shutdowns.incrementAndGet();
                closed.set(true);
                yield null;
            }
            case "isClosed" -> closed.get();
            default -> PulsarTestSupport.defaultValue(method);
            });
        }

        @SuppressWarnings("unchecked")
        private ProducerBuilder<Object> producerBuilder() {
            final ProducerBuilder<Object>[] reference = new ProducerBuilder[1];
            reference[0] = PulsarTestSupport.proxy(ProducerBuilder.class,
                                                   (ignored, method, args) -> method.getName().equals("create")
                                                           ? producer
                                                           : reference[0]);
            return reference[0];
        }

        @SuppressWarnings("unchecked")
        private Producer<Object> producer() {
            return PulsarTestSupport.proxy(Producer.class, (ignored, method, args) -> switch (method.getName()) {
            case "isConnected" -> true;
            case "newMessage" -> messageBuilder();
            case "closeAsync" -> producerCloseFuture;
            default -> PulsarTestSupport.defaultValue(method);
            });
        }

        @SuppressWarnings("unchecked")
        private TypedMessageBuilder<Object> messageBuilder() {
            Map<String, String> messageProperties = new LinkedHashMap<>();
            Object[] value = new Object[1];
            String[] key = {""};
            long[] eventTime = {-1};
            final TypedMessageBuilder<Object>[] reference = new TypedMessageBuilder[1];
            reference[0] = (TypedMessageBuilder<Object>) Proxy.newProxyInstance(
                    TypedMessageBuilder.class.getClassLoader(),
                    new Class<?>[] {TypedMessageBuilder.class},
                    (ignored, method, args) -> switch (method.getName()) {
                    case "value" -> {
                        value[0] = args[0];
                        yield reference[0];
                    }
                    case "properties" -> {
                        messageProperties.putAll((Map<String, String>) args[0]);
                        yield reference[0];
                    }
                    case "key" -> {
                        key[0] = (String) args[0];
                        yield reference[0];
                    }
                    case "keyBytes", "orderingKey" -> reference[0];
                    case "eventTime" -> {
                        eventTime[0] = (long) args[0];
                        yield reference[0];
                    }
                    case "sendAsync" -> {
                        values.add(value[0]);
                        properties.add(Collections.unmodifiableMap(new LinkedHashMap<>(messageProperties)));
                        keys.add(key[0]);
                        eventTimes.add(eventTime[0]);
                        yield CompletableFuture.completedFuture(messageId);
                    }
                    default -> PulsarTestSupport.defaultValue(method);
                    });
            return reference[0];
        }
    }

    public static final class CustomPayload {
        private String name;
        private int quantity;

        public CustomPayload() {
        }

        private CustomPayload(String name, int quantity) {
            this.name = name;
            this.quantity = quantity;
        }

        public String name() {
            return name;
        }

        public void name(String name) {
            this.name = name;
        }

        public int quantity() {
            return quantity;
        }

        public void quantity(int quantity) {
            this.quantity = quantity;
        }
    }
}
