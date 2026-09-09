# Helidon Declarative Messaging Kafka Connector

The Kafka connector connects Helidon declarative messaging channels to Apache Kafka topics with runtime-owned delivery
settlement and Kafka consumer-group maintenance.

## Dependency

```xml
<dependency>
    <groupId>io.helidon.extensions.messaging.connectors</groupId>
    <artifactId>helidon-extensions-messaging-connectors-kafka</artifactId>
</dependency>
```

The connector provider is discovered through the Helidon Service Registry or Java service loading. It creates a configured
`KafkaConnector`, which creates incoming and outgoing channel connections.

## Configuration

Each named connector captures its common Kafka configuration. Channels refer to the connector name and may override client
defaults. The connector provider type is `helidon-kafka`.

```yaml
messaging:
  connector:
    orders-kafka:
      type: helidon-kafka
      bootstrap.servers: broker-a:9092,broker-b:9092
      key.serializer: org.apache.kafka.common.serialization.StringSerializer
      value.serializer: org.apache.kafka.common.serialization.StringSerializer
      key.deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value.deserializer: org.apache.kafka.common.serialization.StringDeserializer
      poll.timeout: PT0.1S
      send.timeout: PT30S
      close.timeout: PT10S
      properties:
        security.protocol: SASL_SSL

  incoming:
    orders:
      connector: orders-kafka
      topic: orders
      group.id: inventory-service
      auto.offset.reset: earliest

  outgoing:
    order-results:
      connector: orders-kafka
      topic: order-results
```

`bootstrap.servers` is required on the connector. Each channel must have a `topic`, either configured on the channel or
inherited from the connector default. An incoming channel uses its channel name as `group.id` when neither the connector
nor the channel configures a group. The default serializers and deserializers handle String keys and values, and
`auto.offset.reset` defaults
to `latest`.

Additional Kafka client settings go under `properties`. Typed connector options take precedence over entries with the
same Kafka property name. The incoming connector also disables automatic offset commits and caps `max.poll.records` at
the runtime delivery limit. Kafka fetch and record byte limits remain Kafka client properties; runtime admission does not
bound transient client or deserializer allocation.

The generated builder and configuration descriptions redact the complete `properties` map because it may contain
credentials. Kafka client logging is controlled separately by Kafka and the application's logging configuration.

## Imperative usage

Create the connector once and configure each channel with its typed blueprint:

```java
KafkaConnector kafka = KafkaConnector.builder()
        .name("orders-kafka")
        .bootstrapServers("localhost:9092")
        .build();
MessagingGraph.Builder builder = MessagingGraph.builder();
MessagingChannel<String> orders = builder.channel("orders", String.class);
MessagingChannel<String> results = builder.channel("order-results", String.class);

builder.incomingChannel(orders, kafka.incoming(KafkaIncomingConfig.builder()
        .channelName("orders")
        .topic("orders")
        .groupId("inventory-service")
        .autoOffsetReset("earliest")
        .build()));
builder.messageSink(orders, message -> System.out.println(message.entity()));
builder.outgoingChannel(results, kafka.outgoing(KafkaOutgoingConfig.builder()
        .channelName("order-results")
        .topic("order-results")
        .build()));

MessagingGraph graph = builder.build();
graph.start();
graph.emitter(results).emit("accepted");
graph.close();
```

## Declarative usage

No Kafka-specific annotation is required:

```java
@Service.Singleton
class OrderConsumer {
    @Messaging.ReceiveFrom("orders")
    void onOrders(MessageBatch<String> orders) {
        for (Message<String> order : orders) {
            process(order.entity());
        }
    }
}
```

Send to an outgoing Kafka binding through its named emitter:

```java
@Service.Singleton
class ResultPublisher {
    private final Emitter<String> results;

    @Service.Inject
    ResultPublisher(@Service.Named("order-results") Emitter<String> results) {
        this.results = results;
    }

    void publish(String result) {
        results.emit(result);
    }
}
```

## Kafka message mapping

Incoming records are exposed as immutable `KafkaMessage<K, V>` instances. In addition to the non-null entity and portable
headers, they retain the Kafka key, topic, partition, offset, timestamp and type, leader epoch, and ordered native headers.

Use `KafkaMessage` for an outgoing key or Kafka-native headers:

```java
KafkaMessage<String, String> message = KafkaMessage.<String, String>builder("accepted")
        .key("order-42")
        .addHeader("region", "EU")
        .addRawHeader("trace", traceBytes)
        .addNullHeader("optional")
        .build();
results.emit(message);
```

Native header order and duplicate names are preserved. Non-null native values appear as binary values in the portable
header view; Kafka null-valued headers appear as portable null values. A plain Helidon `Message` maps its portable header
values to Kafka headers when the value has a supported portable representation.

Helidon message payloads are non-null, while a Kafka tombstone has a null value. An incoming tombstone therefore enters
the channel's pre-dispatch failure policy with a metadata-only `KafkaMessage`; its key, headers, topic, partition, offset,
timestamp, and leader epoch remain available, but calling `entity()` throws. DROP can settle the tombstone, DEAD_LETTER
can preserve its Kafka metadata, and FAIL leaves it eligible for Kafka redelivery. Applications cannot create an ordinary
outgoing `KafkaMessage` with a null entity.

## Incoming settlement and consumer groups

Each Kafka poll is one retained `MessageBatch`. Before polling, the connector reserves the runtime's maximum delivery
capacity and caps `max.poll.records` accordingly. While that batch is processed, assigned partitions remain paused and the
consumer owner continues maintenance polls for heartbeats and rebalance callbacks. This also applies while normal or
pre-dispatch-failure admission is temporarily unavailable.

After runtime retry, drop, or dead-letter handling settles the batch, the connector commits offsets asynchronously. A
structured partial failure advances each partition only through its contiguous successful prefix; records after an
unresolved offset remain eligible for redelivery. Retriable commit failures use Kafka's `retry.backoff.ms`, remain bounded
by `default.api.timeout.ms`, and continue consumer-group maintenance between attempts.

This provides at-least-once delivery. A rebalance, process failure, or lost commit response can redeliver a record after
application processing, so consumers should be idempotent when duplicates matter.

## Outgoing completion and dead letters

An outgoing batch completes only after every enqueued producer future reaches the success point configured by Kafka's
`acks` setting. A failed enqueue is reported as failed, later records are not attempted, and future failures or timeouts
are indeterminate because the broker might already have appended the record. Retrying an indeterminate outcome can create
duplicates.

When the source is a `KafkaMessage`, a Kafka dead-letter record retains its key and native headers and adds source metadata
under these reserved headers:

- `helidon_messaging_dead_letter_source_channel`
- `helidon_messaging_dead_letter_attempts`
- `dlq-orig-topic`
- `dlq-orig-partition`
- `dlq-orig-offset`
- `dlq-orig-timestamp`
- `dlq-orig-timestamp-type`
- `dlq-orig-leader-epoch`

The connector owns these names for dead-letter metadata. A tombstone routed to a Kafka dead-letter channel remains a
null-valued Kafka record while retaining the available source metadata.

## Shutdown

Graceful shutdown stops new polling and allows the active retained delivery to settle. Forced shutdown wakes the Kafka
consumer and interrupts connector-owned waits. `close.timeout` bounds active-delivery quiescence and Kafka client close;
zero requests shutdown without waiting. Close operations are idempotent.
