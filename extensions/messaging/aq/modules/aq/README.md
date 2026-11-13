# Oracle AQ connector

The `helidon-aq` connector connects Helidon Messaging channels to existing Oracle Advanced Queuing queues.
It is a standalone connector with its own configuration, lifecycle, settlement, and payload mapping.
It uses Oracle AQ's Java API and JDBC driver directly; it does not depend on either Helidon JMS connector.

## Dependency

Import the AQ extension BOM, then add:

```xml
<dependency>
    <groupId>io.helidon.extensions.messaging.connectors</groupId>
    <artifactId>helidon-extensions-messaging-connectors-aq</artifactId>
</dependency>
```

The application also needs the specification APIs used by Oracle AQ:
`jakarta.jms:jakarta.jms-api:2.0.3` (the `javax.jms` namespace) and
`javax.transaction:javax.transaction-api:1.3`.
These are specification API dependencies, not Helidon JMS connectors.

## Shared data source

Register the application-owned `javax.sql.DataSource` with Helidon's Service Registry. The connector resolves
the unqualified data source when no explicit connection resource is configured. Use `data-source-name` to
select a named data source. The same data source can serve multiple AQ channels and ordinary application JDBC code.
Default resource selection considers only unqualified services. Named data sources and connection factories require
an explicit name selection, so an unrelated named service cannot override the application's default AQ factory.
Statically named providers are excluded before initialization. Providers that determine instance qualifiers at
runtime still need to be initialized to select an unqualified instance.

```yaml
messaging:
  connector:
    orders-aq:
      type: helidon-aq
      data-source-name: orders
      schema: ORDERS
  incoming:
    orders-in:
      connector: orders-aq
      destination: INCOMING_ORDERS
  outgoing:
    orders-out:
      connector: orders-aq
      destination: OUTGOING_ORDERS
```

Programmatic construction accepts the same data source directly:

```java
AqConnector connector = AqConnector.builder()
        .name("orders-aq")
        .dataSource(sharedDataSource)
        .schema("ORDERS")
        .build();
```

Every channel borrows its own connection and owns its own AQ session. Closing a channel releases its
connections and sessions; it does not close the data source or its pool. Sharing a data source does not
share a connection or transaction between channels or application code. Atomic database changes plus AQ
receive/send across application resources require a separate transaction integration.

As alternatives, configure an application-supplied Oracle AQ `ConnectionFactory`, its registry name, or a
`jdbc-url` with `username` and `password`. Resource choices are mutually exclusive. A resource selected at
channel level overrides the connector's resource selection as a unit. Configure wallets and JDBC/pool-specific
properties on the supplied data source or connection factory.

## JNDI destination lookup

Use `jndi.destination` to look up an existing queue or topic through a naming provider:

```yaml
messaging:
  connector:
    orders-aq:
      type: helidon-aq
      data-source-name: orders
  outgoing:
    orders-out:
      connector: orders-aq
      destination-type: QUEUE
      jndi:
        destination: jms/Orders
        env-properties:
          java.naming.factory.initial: com.example.naming.OrdersContextFactory
          java.naming.provider.url: your-provider-url
```

Supply your naming provider implementation and its environment values. The connector uses the JDK `java.naming`
API; the environment map is optional, so normal naming-provider defaults and `jndi.properties` can also be used.
Programmatic configuration supports the same options:

```java
AqConnector connector = AqConnector.builder()
        .name("orders-aq")
        .dataSource(sharedDataSource)
        .jndi(jndi -> jndi.destination("jms/Orders")
                .putEnvProperty("java.naming.factory.initial", "com.example.naming.OrdersContextFactory"))
        .build();
```

The resolved object must be a `javax.jms.Queue` or `javax.jms.Topic` matching `destination-type`, and compatible with
the configured AQ connection factory. Oracle destination objects implement both interfaces, so the connector checks
their actual native kind. Jakarta Messaging 3.x objects use a different namespace and are not compatible.
The existing data source/factory/JDBC settings still select the database; JNDI resolves only the destination object.
The object must describe a queue in that database. No JNDI connection-factory lookup is performed.

At either connector or channel scope, `jndi` is mutually exclusive with direct `destination` and `schema` settings.
A channel's JNDI block replaces the connector's entire JNDI configuration, including environment properties, or
switches from inherited direct naming. An explicit channel `destination` or `schema` switches to direct naming;
when switching from JNDI, supply a direct destination. When both scopes use direct naming, the existing field-by-field
destination/schema inheritance applies. `destination-type` is inherited independently in either mode.

Each initialization attempt creates and closes its own naming context. Lookup runs within `reconnect.connect-timeout`
and repeats after a connection failure, so a directory rebind is observed by the recovered channel. Contexts are also
closed on lookup/type failure and after a cancelled lookup returns late. Providers that ignore interruption retain
the existing cleanup limit: another recovery attempt waits for the unfinished initializer to clean up.
Configure provider-specific network timeouts through `env-properties` when needed.

Missing names, invalid destination types, authentication and naming configuration errors fail explicitly. During
established-channel recovery, recognized communication/service-unavailable naming failures use the configured backoff.
Initial startup still performs one bounded attempt. Lookup failures never fall back to direct destination naming.
Environment values are excluded from generated configuration string representations.

## Payloads and metadata

| Queue storage | Incoming payload before conversion | Outgoing payload after conversion | Configuration |
| --- | --- | --- | --- |
| AQ JMS text | `String` | `String` | Default `payload-type: JMS` |
| AQ JMS bytes | `byte[]` | `byte[]` or `ByteBuffer` | Default `payload-type: JMS` |
| AQ JMS map | `Map<String, Object>` | `Map<String, Object>` with supported JMS map values | Default `payload-type: JMS` |
| AQ JMS object | `Serializable` | Other `Serializable` values, subject to selection rules below | `payload-type: JMS`, `allow-object-messages: true` |
| SQL object type | `AqAdt` with kind `STRUCT` | `AqAdt` with kind `STRUCT` | `payload-type: ADT` |
| `SYS.XMLTYPE` | `AqXml` | `AqXml` or XML text as `String` | `payload-type: XML` |

The database queue's storage type must match the payload. The connector does not provision database queues,
types, users, or grants. Configured durable subscriptions are created through Oracle AQ.
An `AqPayloadConverter` can convert application payloads to and from
detached connector payloads without exposing a live JMS session or JDBC connection.
Application maps can contain domain-specific keys and values, including nested collections. The converter can
map these values to another supported payload, such as text. JMS MapMessage key/value restrictions are checked
when reading or writing the native map payload. Message envelopes copy the map structure and byte-array values;
applications and converters must keep map keys and other nested values stable.

For JMS output, built-in mapping applies after payload conversion and takes precedence over Java serialization:
`String` becomes a text message, `byte[]` and `ByteBuffer` become bytes messages, and any `Map` remaining after
conversion becomes a MapMessage, subject to the received ObjectMessage forwarding rule below.
Only remaining `Serializable` values use ObjectMessage, with the explicit opt-in enabled. Enabling object messages
does not force a Serializable map into ObjectMessage; there is no explicit wire-type override for a newly authored map.
A received ObjectMessage that was not passed through an incoming converter retains its ObjectMessage kind when its
`AqMessage` envelope is forwarded without an outgoing converter; the outgoing channel must also enable object messages.

Use `AqMessage.mapBuilder(map)`, the `AqMessage.create(Map)` overload, or a no-argument builder with a Map interface
entity type to construct a map envelope. The generic `AqMessage.builder(entity)` overload rejects map arguments.
For buffers, declare the entity type as `ByteBuffer`; snapshots do not retain a concrete buffer subclass. Bytes-message
mapping sends the buffer's remaining bytes without advancing its position.

Native MapMessage keys must be non-empty strings. Its values may be `null`, `String`, `byte[]`, or boxed primitive
values (`Boolean`, `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`, `Character`). Nested collections, nested maps
and arbitrary objects need application conversion into a supported native payload before sending.
Incoming StreamMessage and ordinary header-only JMS messages are unsupported; native decoding fails before an
incoming `AqPayloadConverter` is invoked.

`AqMessage<T>` is the common sealed interface for detached incoming and outgoing message envelopes.
The generated `AqMessageConfig.Builder<T>` creates an `AqOutgoingMessage<T>`. Use the existing
`AqMessage.builder(...)` conveniences, or construct the outgoing runtime directly with
`AqOutgoingMessage.create(config)` or `AqOutgoingMessage.create(builderConsumer)`.
Its `prototype()` returns the configuration supplied at construction; the runtime retains independent snapshots of
binary payloads, map structure and application properties. The prototype itself does not deep-freeze application
objects. Incoming messages use a separate connector-owned snapshot implementation and have no construction prototype.

The common envelope carries the payload, correlation identifier, JMS type,
reply-to destination, supported application properties, and explicit recipient list. Incoming metadata also
includes the provider message ID, timestamp, expiry, priority, delivery mode, redelivery state, delivery
attempts, and AQ sender when available. Availability depends on the queue payload and provider. Properties
are also exposed through Messaging headers. Native ADT/XML messages support AQ correlation and recipient
metadata but reject JMS type, reply-to, and application properties that their storage cannot represent.

Application properties accept non-null `String`, `Boolean`, `Byte`, `Short`, `Integer`, `Long`, `Float` and `Double`
values. Unlike MapMessage body entries, they cannot contain `Character`, binary arrays or null values. Property names
follow Java identifier character rules and cannot be the selector keywords `NULL`, `TRUE`, `FALSE`, `NOT`, `AND`, `OR`,
`BETWEEN`, `LIKE`, `IN`, `IS` or `ESCAPE` (case-insensitive). Names beginning with `JMS` are reserved, except for
`JMSXGroupID` (a `String`) and `JMSXGroupSeq` (an `Integer`). Use the documented local metadata controls for the other
supported Oracle options.

Generic Messaging headers accept `TextValue`, `BooleanValue`, `IntegerValue`, `Float32Value` and `Float64Value` when
mapped to JMS properties. Ordinary integer headers must fit a signed 64-bit value and are sent as JMS long properties;
`JMSXGroupSeq` instead requires a signed 32-bit value and also accepts an integer text header. `JMSXGroupID` requires
text. Duplicate exact header names and other value kinds, including binary, null, decimal, timestamp, UUID and native
header values, are rejected. Forwarding an unchanged `AqMessage` preserves the numeric widths of its original
application properties.

Forwarding an `AqMessage` preserves its available correlation ID, JMS type, reply-to and application properties when
the target payload supports them. Incoming recipient lists are not reconstructed; an empty outgoing recipient list
uses the destination's configured subscribers. Each send uses the outgoing channel's `delivery-mode`, `priority` and
`time-to-live`, including when forwarding. Received delivery mode, priority and absolute expiration are observations,
not overrides for those outgoing settings. Provider-assigned message IDs, timestamps and redelivery information
are not copied into the new native message.

```java
AqMessage<String> message = AqMessage.builder("order created")
        .correlationId("order-42")
        .putProperty("region", "EU")
        .addRecipient(AqRecipient.builder().name("BILLING").build())
        .addRecipient(AqRecipient.builder().name("SHIPPING").build())
        .build();
```

Recipient lists apply to multi-consumer queues, configured as `destination-type: TOPIC`. An incoming channel
can use `recipient-name` to consume for a named AQ recipient. Ordinary topic subscriptions can instead use
`durable: true` and `subscription-name`; durable subscriptions remain in the database after channel shutdown.
An empty outgoing recipient list uses AQ's configured subscribers. Incoming `message-selector` supports
provider selectors. `no-local` suppresses messages published by the same connection for ordinary topic subscriptions
and cannot be combined with an explicit recipient name. Each AQ channel owns a separate connection, so sharing a
connector or DataSource does not make another outgoing channel's messages local to an incoming channel.

Build SQL objects using their qualified database type name and ordered attributes:

```java
AqAdt event = AqAdt.create("ORDERS.ORDER_EVENT", List.of("order-42", BigDecimal.valueOf(12)));
AqXml xml = AqXml.create("<order id=\"42\"/>");
```

`AqAdt.NullValue.INSTANCE` represents a SQL NULL attribute. Nested SQL objects and named arrays use nested
`AqAdt` values; use `AqAdt.createArray(typeName, elements)` for a named SQL array. The four detached value types
use static factories and do not require configuration objects or builders. XML is represented as text after
the provider value is detached. Native objects requiring
unsupported JDBC attribute types fail conversion; they are not exposed as live JDBC objects.

SQL LOB attributes retain their type in detached `AqBlob` and `AqClob` values, including inside nested objects and arrays:

```java
AqBlob binary = AqBlob.create(new byte[] {1, 2, 3});
AqClob text = AqClob.create("CLOB content");
AqClob nationalText = AqClob.createNational("NCLOB content");
```

An incoming ADT can be forwarded with these values intact. The connector creates temporary LOBs on the sending
connection and releases them after use. Plain byte arrays and strings remain appropriate for RAW and character
attributes. `max-body-bytes` is a Helidon `Size` that limits each incoming bytes-message body and detached SQL BLOB
in bytes. `max-lob-characters` independently limits each detached CLOB or NCLOB by its character count.
Both default to 1,048,576 in their respective units and inherit independently from connector defaults to channels.
Neither is an aggregate message or process-memory limit. They do not bound JMS text, XML, map or serialized object
payload sizes, or the total size of an ADT containing multiple LOBs.
An existing explicit `max-body-bytes` value no longer changes the CLOB/NCLOB limit; configure
`max-lob-characters` to retain a previous explicit character limit.

Java object messages are disabled by default because deserialization executes application class code.
Enable them only for queues whose publishers and serialized classes the application trusts.

## Delayed delivery

Set a per-message delay before Oracle makes the enqueued message available for consumption:

```java
AqMessage<String> message = AqMessage.builder("order reminder")
        .deliveryDelay(Duration.ofSeconds(30))
        .build();
```

The convenience method sets one local metadata entry and preserves other entries. Ordinary Messaging envelopes
can set the same option directly:

```java
Message<String> message = Message.builder("order reminder")
        .localMetadata(AqMessage.DELIVERY_DELAY_METADATA,
                       MessageHeaderValue.IntegerValue.create(30))
        .build();
```

`AqMessage.DELIVERY_DELAY_METADATA` identifies `helidon.messaging.aq.delivery-delay-seconds`. Its value must be
an integer between zero and `Integer.MAX_VALUE`, in seconds. Missing or zero means no added delay. The Duration
convenience method rejects negative, fractional-second, and oversized durations. Invalid metadata fails the send
before enqueue. The option works with JMS, ADT, and XML payloads and is applied through Oracle's `JMS_OracleDelay`
system property. Other local metadata is not copied into native message headers, properties, or payloads.

Send completion means successful enqueue/commit; Oracle controls when the message becomes available. The channel
can send subsequent messages immediately. Reusing the same outgoing envelope applies the delay to each new enqueue.
Received messages do not reconstruct this local option, so forwarding a received message does not restart its delay.
The connector ignores delay metadata on `DeadLetterMessage` envelopes, whose local metadata includes copied values
from the original message. Create a new outgoing envelope to request explicitly delayed error routing.

Oracle's queue type determines support for delayed delivery. Persistent AQ queues support it; buffered queue support
requires separate validation for the database and queue type. Oracle queue-monitor scheduling can affect when a delayed
message becomes available. Expiration follows Oracle AQ semantics; the expiration interval starts when the message
becomes ready for dequeue.

## Oracle-specific metadata

Set an application identifier and an Oracle exception queue through the AQ message builder:

```java
AqMessage<String> message = AqMessage.builder("order created")
        .applicationId("orders-service")
        .exceptionQueue("ORDERS.ORDER_ERRORS")
        .build();
```

These convenience methods set `AqMessage.APPLICATION_ID_METADATA` and `AqMessage.EXCEPTION_QUEUE_METADATA` in local
metadata, preserving other entries. Generic `Message` envelopes can set the same entries directly:

```java
Message<String> message = Message.builder("order created")
        .localMetadata(AqMessage.APPLICATION_ID_METADATA, "orders-service")
        .localMetadata(AqMessage.EXCEPTION_QUEUE_METADATA, "ORDERS.ORDER_ERRORS")
        .build();
```

Both options require non-blank text. Application ID maps to `JMSXAppID` and is supported only for JMS payloads;
requesting it for ADT/XML fails before native message creation. Exception queue maps to `JMS_OracleExcpQ` and supports
JMS, ADT and XML payloads. The destination must already exist as an Oracle AQ exception queue. Use a schema-qualified
name to select its owner; the connector preserves the supplied name and Oracle validates it at enqueue.
This option controls database routing after expiration or exhausted database dequeue retries. Messaging's application
failure policy and logical dead-letter channel are configured separately.

Incoming messages snapshot the following provider observations into local metadata. All keys use the
`helidon.messaging.aq.received.` prefix and are exposed as constants on `AqMessage`:

| Constant | Native property | Metadata value |
| --- | --- | --- |
| `RECEIVED_APPLICATION_ID_METADATA` | `JMSXAppID` | Text; JMS payloads only |
| `RECEIVED_USER_ID_METADATA` | `JMSXUserID` | Text; JMS payloads only |
| `RECEIVED_EXCEPTION_QUEUE_METADATA` | `JMS_OracleExcpQ` | Text |
| `RECEIVED_STATE_METADATA` | `JMSXState` | Integer: 0 READY, 1 WAITING, 2 PROCESSED, 3 EXPIRED |
| `RECEIVED_TIMESTAMP_METADATA` | `JMSXRcvTimestamp` | Integer milliseconds since the epoch, recorded by the receiving Java client |
| `RECEIVED_ORIGINAL_MESSAGE_ID_METADATA` | `JMS_OracleOriginalMessageID` | Text, when supplied by the provider |
| `RECEIVED_DELIVERY_DELAY_METADATA` | `JMS_OracleDelay` | Integer seconds; original delay, not remaining wait |

For example, `received.localMetadata().text(AqMessage.RECEIVED_APPLICATION_ID_METADATA)` returns the optional application
identifier. Unavailable values are omitted, including application/user IDs for ADT/XML. An absent original message ID
is not replaced with the current message ID. Snapshots remain available after native resources close and, when readable,
on envelopes whose payload mapping failed. Provider read failures follow the existing mapping/recovery handling.

Received observations are separate from outgoing controls and are never reapplied when forwarding a message. To reuse
an application ID or exception queue, explicitly set the outgoing option on a new envelope. `DeadLetterMessage`
envelopes ignore application ID, exception queue and delay controls inherited from the original message; explicitly
configured rerouting uses a new ordinary outgoing envelope. Unrecognized local metadata remains local, and raw reserved
`JMS*` application properties remain restricted.

## Database enqueue transformations

Select an existing Oracle AQ database transformation for an outgoing channel:

```yaml
messaging:
  outgoing:
    transformed-orders:
      connector: orders-aq
      destination: NORMALIZED_ORDERS
      payload-type: ADT
      enqueue-transformation:
        name: ORDERS.NORMALIZE_ORDER
```

The same policy can be a connector default or configured programmatically:

```java
AqOutgoingConfig outgoing = AqOutgoingConfig.builder()
        .connector("orders-aq")
        .channelName("transformed-orders")
        .enqueueTransformation(transformation -> transformation.name("ORDERS.NORMALIZE_ORDER"))
        .build();
```

The policy defaults to enabled and requires a non-blank name when enabled. Omitting the channel policy inherits the
connector policy; an explicit channel policy replaces the whole block. To disable an inherited transformation:

```yaml
enqueue-transformation:
  enabled: false
```

The application or database administrator must create the transformation and grant its required privileges in Oracle,
for example using `DBMS_TRANSFORM`. The connector selects its name through `AQjmsProducer.setTransformation` on every
new producer, including after reconnect. Oracle applies it during enqueue; it can change fields or map a source SQL
object type to the queue's target type. `AqPayloadConverter` still performs application-side conversion before the native
enqueue. Incoming channels do not apply this outgoing policy.

This option supports classic Oracle AQ queues and topics. An enabled policy requires native AQ session, destination
and producer implementations. The connector checks Oracle's queue metadata during initialization and rejects sharded
AQ/TxEventQ destinations because the pinned driver does not apply the transformation there. Disabled or absent policies
do not perform this extra metadata lookup. The inspection reads `SYS.ALL_QUEUES` through the channel's JDBC connection,
without changing its type map or transaction settings, and leaves the connection open.

Setting the producer option does not validate the transformation's database definition. Missing names, incompatible SQL
types and privilege errors are reported by Oracle at enqueue and follow the existing send/batch rollback behavior;
failed sends are not automatically replayed. Oracle caches transformation type metadata on destination objects, so live
changes to a definition under the same name can require refreshing provider resources, including a JNDI binding that
returns a previously used destination object.

## Processing and settlement

An incoming channel reserves runtime delivery capacity before receiving. It receives one message, waits for
Messaging processing and failure handling to finish, then settles the delivery before receiving another.
There is no message `ack()`/`nack()` API and no independent polling executor.
Configure application retry, drop and dead-letter policies using the shared
[Messaging failure handling](https://github.com/helidon-io/helidon/blob/helidon-27.x/messaging/README.md#retry-drop-and-dead-letter-handling).

ADT conversion failures, including incoming LOB size-limit violations, use the same configured drop, dead-letter,
or terminal-failure handling as other payload mapping failures. Failed mappings retain available native metadata
in an envelope whose body is unavailable.
Such an envelope reports `bodyAvailable() == false`, and accessing `entity()` throws `MessagingException`.
Forwarding it through an AQ outgoing channel configured with `payload-type: JMS` creates a header-only native message,
which requires a compatible destination. Direct forwarding to an ADT/XML channel fails because there is no typed body;
application failure handling must construct an appropriate replacement payload if it needs to send to one of those queues.

The default `transacted: true` gives each channel an AQ local transaction. Successful incoming processing
commits the dequeue; unhandled failure rolls it back. Outgoing batches commit together on that channel,
and a send failure rolls back the batch. A commit failure reports an indeterminate outcome because the
client cannot prove whether the database committed. The Messaging runtime may retry or publish a dead
letter before reporting processing success; a successfully handled failure can therefore commit the input.

With `transacted: false`, incoming messages use client acknowledgement after processing, and outgoing sends
are individually visible. A batch is not a transaction across multiple outgoing channels. Already committed
outputs remain visible if another output fails.

Shutdown drains active processing before releasing channel resources. A configured timeout bounds graceful
cleanup; interrupted or abandoned deliveries remain unsettled.
Draining cancels an idle dequeue independently of `receive-timeout`, while an already received message is allowed
to finish processing and settlement before its session closes.

## Connection recovery

Established channels reconnect automatically after recognized database or network connection failures. Recovery
obtains a fresh connection from the same application-owned data source or connection factory and restores the AQ
session, destination, selector, recipient or durable subscription. The data source and its pool remain open.
Initial startup makes one connection attempt, bounded by `reconnect.connect-timeout`.
If that attempt times out, graph startup throws `AqConnectionTimeoutException`, which applications can catch.
This startup failure differs from an outgoing send's `reconnect.wait-timeout` failure, which reports a
`BatchDeliveryException` with its items not attempted.

An outgoing connection failure fails the current send with its original per-message outcomes. The connector does
not replay that send. A later send waits up to `reconnect.wait-timeout` for recovery; if no connection becomes available,
it reports every item as not attempted and the channel continues recovering. An uncertain enqueue or commit remains
indeterminate. The caller or the incoming channel's Messaging failure policy decides whether to retry the failed message.
Concurrent sends wait for recovery independently, using one timeout budget per call. Native message creation and
sending remain serialized, and each caller rechecks the connection after obtaining its turn to send.

An incoming connection failure resumes receiving after recovery. If processing already finished but its commit or
acknowledgment could not be confirmed, the old session is abandoned and Oracle determines whether to redeliver the
message. Processing can therefore occur more than once. Durable subscriptions retain their existing identity;
non-durable topic subscriptions cannot recover messages published while they were disconnected.

Recovery uses Helidon Fault Tolerance `Retry` with exponential backoff and jitter. The first recovery attempt starts
immediately after the failed connection is cleaned up. By default, each recovery sequence allows up to 2,147,483,647
attempts over 365 days. Exhausting either limit fails the channel. The overall timeout is checked between failed
attempts and retry waits; it does not interrupt provider initialization or cleanup already in progress. Invalid
credentials, destination/configuration errors and application processing or payload conversion failures retain their
normal error handling. Set `reconnect.enabled: false` to disable automatic recovery. Draining or closing a channel
cancels recovery and prevents another connection attempt.

```yaml
messaging:
  connector:
    orders-aq:
      type: helidon-aq
      data-source-name: orders
      reconnect:
        enabled: true
        retry:
          calls: 2147483647
          overall-timeout: P365D
          delay: PT0.1S
          delay-factor: 2
          max-delay: PT30S
          jitter-factor: 0.2
        connect-timeout: PT10S
        wait-timeout: PT30S
```

The same policy is available programmatically with `.reconnect(reconnect -> reconnect.enabled(true))` and can be
overridden as a whole on a channel. `connect-timeout` bounds waiting for resource initialization; `wait-timeout` bounds
waiting for a recovered connection, not an in-progress database send. Configure JDBC connection/read timeouts on the
supplied data source or factory as appropriate. If a provider ignores cancellation, recovery waits for that attempt's
cleanup before opening another connection, and closes any late resource returned after shutdown.

Values configured under `reconnect.retry` override the AQ defaults above. Supplying a `Retry` or `RetryConfig`
programmatically replaces the retry policy. The generated `.retry(retry -> ...)` consumer starts with standard
Helidon FT defaults (three calls and a one-second overall timeout), so set the desired recovery limits explicitly
when using that builder. For example, to use the AQ default recovery policy:

```java
AqReconnectConfig recovery = AqReconnectConfig.builder()
        .retry(retry -> retry.calls(Integer.MAX_VALUE)
                .overallTimeout(Duration.ofDays(365))
                .delay(Duration.ofMillis(100))
                .delayFactor(2)
                .jitterFactor(0.2)
                .maxDelay(Duration.ofSeconds(30)))
        .build();
```

Omitting `.retry(...)` uses AQ's default policy automatically. Alternatively, initialize the retry builder with
`.from(AqReconnectConfig.create().retry().prototype())` before overriding individual settings.

## Configuration reference

Declare AQ defaults under `messaging.connector.<name>` with `type: helidon-aq`. Incoming and outgoing channels select
that connector using `connector: <name>` and can override the settings for their direction. In the tables, **Common**
applies to both directions; **Incoming** and **Outgoing** identify the channel behavior affected by the setting.
Defaults below apply after inheritance. Durations use the ISO-8601 format accepted by `java.time.Duration`.

Connection-resource choices are mutually exclusive at each scope: `data-source-name`, `connection-factory-name`,
`jdbc-url`, or a programmatically supplied data source or connection factory. Selecting a resource on a channel
replaces the inherited resource choice as a unit. Without an explicit choice, the connector selects an unqualified
Service Registry data source, then an unqualified connection factory. Credentials inherit independently; the effective
`username` and `password` must either both be present or both be absent.

| Setting | Scope | Default and constraints |
| --- | --- | --- |
| `data-source-name` | Common | Absent; when set, selects a nonblank Service Registry data-source name |
| `connection-factory-name` | Common | Absent; when set, selects a nonblank Service Registry AQ connection-factory name |
| `jdbc-url` | Common | Absent; when set, uses a nonblank Oracle JDBC URL |
| `username` | Common | Absent; when set, must be nonblank and accompanied by `password`; otherwise the selected resource supplies credentials |
| `password` | Common | Absent; must be configured together with `username` |
| `client-id` | Common | Absent; leaves the provider's client identifier unchanged. When set, must be nonblank and is applied to each channel connection |
| `destination` | Common | Nonblank existing queue name; required in direct naming mode after inheritance |
| `schema` | Common | Connected database user; when set, must be nonblank and selects the direct destination's owner |
| `destination-type` | Common | `QUEUE`; use `TOPIC` for multi-consumer queues, including durable or explicit-recipient consumers |
| `payload-type` | Common | `JMS`; alternatives are `ADT` and `XML` |
| `transacted` | Common | `true`; use a channel-owned local transaction. With `false`, incoming settlement uses client acknowledgement and outgoing sends are individually visible |
| `allow-object-messages` | Common | `false`; opt in to Java object serialization/deserialization only for trusted queues and classes |
| `close-timeout` | Common | `PT10S`; graceful resource-cleanup wait, at least one millisecond |
| `jndi.destination` | Common | Required nonblank lookup name within a configured `jndi` block |
| `jndi.env-properties` | Common | Empty map; naming-provider defaults still apply. Keys must be nonblank and values non-null; values are omitted from configuration string representations |
| `reconnect.enabled` | Common | `true`; recover established channels after recognized connection failures. Initial startup still makes one attempt |
| `reconnect.retry.calls` | Common | `2147483647`; positive maximum number of recovery attempts, including the first |
| `reconnect.retry.overall-timeout` | Common | `P365D`; positive time budget for the retry sequence, checked between failed attempts and retry waits |
| `reconnect.retry.delay` | Common | `PT0.1S`; non-negative base delay between recovery attempts |
| `reconnect.retry.delay-factor` | Common | `2`; exponential delay multiplier |
| `reconnect.retry.max-delay` | Common | `PT30S`; non-negative maximum delay including jitter |
| `reconnect.retry.jitter-factor` | Common | `0.2`; relative backoff variation from `0` inclusive to `1` exclusive; `-1` disables it |
| `reconnect.connect-timeout` | Common | `PT10S`; maximum wait for initial or recovery resource initialization, at least one millisecond |
| `reconnect.wait-timeout` | Outgoing | `PT30S`; maximum wait for a recovered connection per send call, at least one millisecond; does not time an in-progress native send |

At the same scope, `jndi` conflicts with direct `destination` or `schema`. A channel `jndi` block replaces the complete
inherited block, including its environment, and selects JNDI mode. An explicit channel `destination` or `schema`
selects direct mode; when switching from JNDI, a direct destination must be supplied. When both scopes use direct
naming, destination and schema inherit independently. `destination-type` inherits independently in either mode.
An explicit channel `reconnect` block replaces the whole connector recovery policy; omitted fields use the policy's
defaults above.

| Setting | Scope | Default and constraints |
| --- | --- | --- |
| `message-selector` | Incoming | Absent; when set, a nonblank provider selector using syntax supported for the queue's payload type |
| `durable` | Incoming | `false`; `true` requires `destination-type: TOPIC` and `subscription-name` |
| `subscription-name` | Incoming | Absent; a nonblank durable subscription name, allowed only with `durable: true` |
| `recipient-name` | Incoming | Absent; a nonblank explicit AQ recipient, requires `TOPIC` and cannot be combined with durable subscriptions or `no-local: true` |
| `no-local` | Incoming | `false`; requires `TOPIC` and excludes messages published by the receiving connection according to provider semantics |
| `max-body-bytes` | Incoming | `1048576` bytes (`1 MiB`); Helidon `Size` limited to a positive value at most 2,147,483,647 bytes, applied to each incoming bytes-message body and detached BLOB |
| `max-lob-characters` | Incoming | `1048576`; positive integer limit on each detached CLOB or NCLOB character value |
| `receive-timeout` | Incoming | `PT0.1S`; maximum duration of each receive call, at least one millisecond |

Explicit channel `durable: false` also suppresses an inherited `subscription-name`. An explicitly supplied channel
subscription name still requires durability. Each channel has its own connection, so `no-local` is not an
application-wide filter for all outgoing channels sharing the same data source.

| Setting | Scope | Default and constraints |
| --- | --- | --- |
| `delivery-mode` | Outgoing | `2` (persistent); `1` selects nonpersistent delivery, subject to Oracle queue support |
| `priority` | Outgoing | `4`; integer in the range `0` through `9` |
| `time-to-live` | Outgoing | `PT0S` (no expiry); otherwise at least one millisecond. Oracle AQ starts expiration when the message becomes ready |
| `enqueue-transformation.name` | Outgoing | Existing Oracle transformation name; required when the policy is enabled, and must be nonblank whenever present |
| `enqueue-transformation.enabled` | Outgoing | `true` within a configured policy; an absent policy applies no transformation, and `false` disables an inherited policy |

An explicit channel `enqueue-transformation` policy replaces the whole connector policy. Incoming channels do not
apply it. The selected transformation must satisfy the classic-AQ and database requirements described above.
The programmatic builders also accept data-source and connection-factory instances and a `payloadConverter`;
those object-valued settings have no configuration-file keys. Per-message delay, application ID and exception-queue
controls use message metadata and the message builder methods described above, rather than channel configuration.

## Real database verification

The integration test module uses a running Oracle database and creates a uniquely named disposable schema.
Its administrator login must be able to create/drop users, grant AQ privileges, and grant tablespace allocation.
The lifecycle and reconnect tests also require access to `V$SESSION` and permission to terminate test-schema sessions
with `ALTER SYSTEM`.
The fixture provisions queues from [`schema.sql`](../../tests/aq/src/test/resources/schema.sql) and removes
the schema afterward. It does not manage or stop the supplied database.

From the repository root:

```shell
mvn -B -ntp -f extensions/messaging/aq/pom.xml -Ptests verify \
    -Daq.test.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1 \
    -Daq.test.user=system -Daq.test.password=YOUR_TEST_DATABASE_PASSWORD
```

Without `aq.test.url`, database integration tests are explicitly skipped. Unit tests still run.
XMLType integration additionally requires `-Daq.test.xml=true` and a database with a working XDB installation.
Oracle Free `23.26.3.0-lite` cannot create the XMLType queue because its XDB library is absent.
The complete database suite, including XMLType, is verified against the full Oracle Free `23.26.3.0` image
with Oracle AQ API `19.3.0.0` and JDBC/XDB `23.26.1.0.0`.
The database suite covers JMS payloads, selectors, explicit recipient lists, ADT/XMLType payloads,
durable subscriptions, generated `@Messaging.ReceiveFrom` handlers and injected emitters, runtime settlement,
rollback/redelivery, and sharing an injected data source across application components and runtime shutdown.
Reconnect tests terminate only sessions belonging to the disposable schema, then verify incoming continuation,
uncertain-settlement redelivery, outgoing failure without hidden replay, and durable subscription/selector restoration.
JNDI tests use isolated in-process naming factories with real Oracle queue/topic objects, including objects obtained
from a closed session, incoming delivery and destination rebind after reconnect. External naming-server connectivity
and authentication depend on the deployment's naming provider and are not exercised by this fixture.
Other database releases and deployment-specific wallet/failover configurations need separate validation.
