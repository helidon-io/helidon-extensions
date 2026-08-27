# Helidon Declarative Messaging Apache Pulsar Connector

The Pulsar connector connects Helidon declarative messaging channels to Apache Pulsar topics. It uses the Apache
Pulsar 4.0 LTS Java client and supports both incoming and outgoing channel bindings.

## Dependency

```xml
<dependency>
    <groupId>io.helidon.extensions.messaging.connectors</groupId>
    <artifactId>helidon-extensions-messaging-connectors-pulsar</artifactId>
</dependency>
```

The connector includes the Pulsar client API and the runtime client implementation. Applications do not need to add a
second Pulsar client dependency.

## Configuration

Connector defaults can be shared under `helidon.messaging.connector.helidon-pulsar`. Channel settings override those
defaults.

```yaml
helidon:
  messaging:
    connector:
      helidon-pulsar:
        service-url: pulsar://pulsar.example:6650
        schema: STRING
        receive-timeout: PT0.1S
        send-timeout: PT30S
        settlement-timeout: PT30S
        negative-ack-redelivery-delay: PT1S
        close-timeout: PT10S

    incoming:
      orders:
        connector: helidon-pulsar
        topic: persistent://commerce/orders/order-events
        subscription-name: inventory-service
        subscription-type: EXCLUSIVE
        subscription-initial-position: LATEST

    outgoing:
      order-results:
        connector: helidon-pulsar
        topic: persistent://commerce/orders/order-results
```

`service-url` and `topic` are required. For an incoming binding, `subscription-name` defaults to the channel name.
Pulsar's durable subscription mode, `EXCLUSIVE` subscription type, and `LATEST` initial position are the defaults. The
initial position applies only when the broker creates a new subscription; reconnecting an existing durable subscription
continues at its stored cursor.

The connector uses `Schema.STRING` by default. The `schema` option supports these Pulsar built-in schemas:

| Connector value | Pulsar schema | Java payload |
| --- | --- | --- |
| `AUTO` | Incoming `AUTO_CONSUME`; outgoing `AUTO_PRODUCE_BYTES` | Incoming `GenericRecord`; outgoing encoded `byte[]` |
| `STRING` | `STRING` | `String` |
| `BYTES` | `BYTES` | `byte[]` |
| `BYTEBUFFER` | `BYTEBUFFER` | `ByteBuffer` |
| `BOOLEAN` | `BOOL` | `Boolean` |
| `INT8` | `INT8` | `Byte` |
| `INT16` | `INT16` | `Short` |
| `INT32` | `INT32` | `Integer` |
| `INT64` | `INT64` | `Long` |
| `FLOAT` | `FLOAT` | `Float` |
| `DOUBLE` | `DOUBLE` | `Double` |
| `DATE` | `DATE` | `java.util.Date` |
| `TIME` | `TIME` | `java.sql.Time` |
| `TIMESTAMP` | `TIMESTAMP` | `java.sql.Timestamp` |
| `INSTANT` | `INSTANT` | `Instant` |
| `LOCAL_DATE` | `LOCAL_DATE` | `LocalDate` |
| `LOCAL_TIME` | `LOCAL_TIME` | `LocalTime` |
| `LOCAL_DATE_TIME` | `LOCAL_DATE_TIME` | `LocalDateTime` |

Outgoing `AUTO` does not infer a schema or serialize an object. It uses the schema already registered for the topic and
requires the application to supply its encoded `byte[]` representation. Incoming `AUTO` exposes Pulsar's
`GenericRecord`, including for topics whose registered schema is a scalar type.

Use a named `PulsarSchemaProvider` for JSON, Avro, Protobuf, key/value, or application-defined schemas:

```java
@Service.Singleton
class OrderSchemaProvider implements PulsarSchemaProvider {
    @Override
    public String name() {
        return "orders-json";
    }

    @Override
    public Schema<?> schema() {
        return Schema.JSON(Order.class);
    }
}
```

```yaml
helidon:
  messaging:
    outgoing:
      orders:
        connector: helidon-pulsar
        service-url: pulsar://pulsar.example:6650
        topic: persistent://commerce/orders/order-events
        schema-provider: orders-json
```

Provider names are exact and case-sensitive. `schema-provider` overrides `schema`; missing or duplicate providers fail
when the binding is created, before a Pulsar client is allocated. The connector invokes `schema()` once per binding, so
the returned schema must be safe for that binding. Imperative applications can pass providers to
`new PulsarConnectorProvider(provider1, provider2)`.

Configure a compatible schema for every producer and consumer of a topic. The connector validates built-in payload
types without numeric widening or string coercion. It defensively snapshots mutable built-in values (`byte[]`,
`ByteBuffer`, and legacy date/time types); custom schema payloads otherwise retain their application-defined mutability.

Incoming buffering defaults to `receiver-queue-size: 1` per topic partition. Pulsar may prefetch that many messages for
each partition, while the connector acquires only one message after reserving Helidon retained-delivery capacity.
`max-message-bytes` defaults to 10 MiB and rejects an oversized encoded payload before runtime dispatch.
`settlement-timeout` bounds how long the connector waits for broker acknowledgment after successful runtime processing.

## Native messages

The connector participates in the normal declarative messaging API; no Pulsar-specific annotation is required.

```java
@Service.Singleton
class OrderConsumer {
    @Messaging.ReceiveFrom("orders")
    void onOrder(PulsarMessage<String> order) {
        process(order.entity(), order.key(), order.headers());
    }
}
```

An incoming `PulsarMessage` is an immutable snapshot of its metadata: key, properties, topic, message identifier,
publish and event times, producer name, sequence identifier, ordering key, and redelivery count exposed by the Pulsar
client. Built-in mutable payloads are also defensively copied. Values decoded by `AUTO` or a custom schema are retained
as supplied by that schema and can remain mutable. Pulsar string properties are also exposed as portable Helidon message
text headers in the order supplied by the Pulsar client.

Use the Pulsar message builder when an outgoing message needs a key, ordering key, event time, or native properties.
Ordinary Helidon text headers are written as Pulsar string properties without reordering. Pulsar properties cannot
represent typed values or duplicate names, so the connector rejects those outbound header shapes rather than silently
stringifying, replacing, or dropping them.

## Subscription and settlement semantics

Incoming messages use durable subscriptions by default. Closing or restarting a connector closes its consumer but does
not unsubscribe it, so the broker retains the subscription cursor and backlog. Give each independent fan-out consumer a
different subscription name. Multiple application instances that intentionally share work must use the same name with
`SHARED`, `FAILOVER`, or `KEY_SHARED`, as appropriate.

The connector acknowledges every source message individually and only after runtime delivery, including configured
retry, drop, or dead-letter handling, has completed successfully. It never uses cumulative acknowledgment, because that
could acknowledge an earlier message that is still being processed and is not supported by `SHARED` or `KEY_SHARED`
subscriptions. A terminal processing failure is negatively acknowledged and becomes eligible for broker redelivery
after `negative-ack-redelivery-delay` while the consumer remains connected. That delay is best-effort on a terminal
graph failure: closing or losing the consumer can make its unacknowledged message eligible for redelivery sooner.

If decoding fails, the decoded value is null, or the encoded size exceeds `max-message-bytes`, the connector retains an
immutable metadata-only envelope without reading or copying the raw payload and submits it through the configured failure
policy. Its `entity()` method throws `MessagingException`. A same-Pulsar dead-letter route publishes that unavailable
entity as a native null value while retaining available keys, properties, and source metadata. Other outgoing connector
types must reject the unavailable entity before reporting transport success.

The connector enables broker acknowledgment receipts, disables acknowledgment timeouts, client-side retry/dead-letter
handling, and pooled messages, and starts the consumer paused until the messaging graph is running. These settlement
controls remain connector-owned and cannot be overridden through `consumer-properties`.

Batch-index acknowledgment is disabled by default, matching a stock Pulsar broker. With that default, acknowledged
messages from one producer-side Pulsar batch can be redelivered until every index in the broker entry is acknowledged;
this preserves at-least-once delivery at broker-entry granularity. Set
`batch-index-acknowledgment-enabled: true` only when the broker also has
`acknowledgmentAtBatchIndexLevelEnabled=true`. That opt-in lets successful indexes settle independently when another
message from the same producer batch remains unresolved.

This provides at-least-once delivery. A connection loss after application processing but before acknowledgment is
confirmed can result in a duplicate, and negative acknowledgment can disturb ordering for ordered subscription types.
Consumers should be idempotent when duplicates matter. Pulsar producer batching is a transport optimization rather than
an atomic Helidon batch transaction; an outgoing batch reports per-message outcomes.

For `KEY_SHARED` subscriptions, every message should have a key or ordering key. Producers must disable batching or use
Pulsar key-based batching so one broker batch does not combine unrelated keys.

## Client security and advanced properties

TLS, authentication, proxy, connection, producer, and consumer settings that are not modeled as typed connector options
can be passed to the corresponding Pulsar client builder through `client-properties`, `producer-properties`, and
`consumer-properties`. These maps use the property names accepted by Pulsar's `ClientBuilder.loadConf`,
`ProducerBuilder.loadConf`, and `ConsumerBuilder.loadConf` methods. Typed connector options take precedence over
pass-through values that control the same behavior.

Treat authentication parameters, tokens, private-key paths, trust-store passwords, and similar values as secrets. Load
them through a secret-capable Helidon config source or config filter rather than committing them to application YAML.
Pass-through property maps are treated as confidential configuration and are not logged by the connector.

## Shutdown

Graceful shutdown stops new receives and lets the active retained delivery settle before closing the Pulsar consumer,
producer, and client. `close-timeout` bounds connector-owned cleanup waits. Forced shutdown interrupts receive and
delivery waits and closes the active client resources; any source message that was not successfully acknowledged remains
eligible for redelivery. `PT0S` performs a non-blocking completion check: already-completed closes succeed, while an
unfinished close times out and uses the existing forced client-shutdown fallback. Close operations are idempotent, and
shutdown never deletes the durable subscription.

## JPMS limitation in the upstream client

This connector is deliberately a class-path-only artifact: it has no `module-info.java`, and its compilation and tests
disable the Maven module path. The upstream shaded `org.apache.pulsar:pulsar-client` implementation and
`org.apache.pulsar:pulsar-client-api` must remain on the class path with the connector. Apache Pulsar 4.0.13 does not
provide a clean module-path distribution; putting those client artifacts on the module path exposes overlapping
`org.apache.pulsar.client.api` and `org.apache.pulsar.common.schema` packages and JPMS rejects the configuration.

Applications using this connector should run the Helidon application, connector, and Pulsar client dependencies from
the class path. Do not place the connector or either Pulsar client artifact on a strict module path. A mixed
module-path/class-path launch can require application-specific readability flags and is not supported by this connector.
Ordinary Maven class-path launches require no special configuration.
