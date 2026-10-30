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

| Queue storage | Java payload | Configuration |
| --- | --- | --- |
| AQ JMS text | `String` | Default `payload-type: JMS` |
| AQ JMS bytes | `byte[]` | Default `payload-type: JMS` |
| AQ JMS map | `Map<String, Object>` with supported JMS map values | Default `payload-type: JMS` |
| AQ JMS object | `Serializable` | `allow-object-messages: true` |
| SQL object type | `AqAdt` | `payload-type: ADT` |
| `SYS.XMLTYPE` | `AqXml` | `payload-type: XML` |

The database queue's storage type must match the payload. The connector does not provision database queues,
types, users, or grants. Configured durable subscriptions are created through Oracle AQ.
An `AqPayloadConverter` can convert application payloads to and from
detached connector payloads without exposing a live JMS session or JDBC connection.
Application maps can contain domain-specific keys and values, including nested collections. The converter can
map these values to another supported payload, such as text. JMS MapMessage key/value restrictions are checked
when reading or writing the native map payload. Message envelopes copy the map structure and byte-array values;
applications and converters must keep map keys and other nested values stable.

`AqMessage<T>` is a detached message envelope. It carries the payload, correlation identifier, JMS type,
reply-to destination, supported application properties, and explicit recipient list. Incoming metadata also
includes the provider message ID, timestamp, expiry, priority, delivery mode, redelivery state, delivery
attempts, and AQ sender when available. Availability depends on the queue payload and provider. Properties
are also exposed through Messaging headers. Native ADT/XML messages support AQ correlation and recipient
metadata but reject JMS type, reply-to, and application properties that their storage cannot represent.

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
provider selectors. `no-local` applies to ordinary topic subscriptions and cannot be combined with an explicit
recipient name.

Build SQL objects using their qualified database type name and ordered attributes:

```java
AqAdt event = AqAdt.builder()
        .typeName("ORDERS.ORDER_EVENT")
        .addAttribute("order-42")
        .addAttribute(BigDecimal.valueOf(12))
        .build();
AqXml xml = AqXml.builder().text("<order id=\"42\"/>").build();
```

`AqAdt.NullValue.INSTANCE` represents a SQL NULL attribute. Nested SQL objects and named arrays use nested
`AqAdt` values. XML is represented as text after the provider value is detached. Native objects requiring
unsupported JDBC attribute types fail conversion; they are not exposed as live JDBC objects.

SQL LOB attributes retain their type in detached `AqBlob` and `AqClob` values, including inside nested objects and arrays:

```java
AqBlob binary = AqBlob.builder().bytes(new byte[] {1, 2, 3}).build();
AqClob text = AqClob.builder().text("CLOB content").build();
AqClob nationalText = AqClob.builder().text("NCLOB content").national(true).build();
```

An incoming ADT can be forwarded with these values intact. The connector creates temporary LOBs on the sending
connection and releases them after use. Plain byte arrays and strings remain appropriate for RAW and character
attributes. `max-body-bytes` bounds binary LOB bytes and character LOB characters retained per incoming value.

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

## Processing and settlement

An incoming channel reserves runtime delivery capacity before receiving. It receives one message, waits for
Messaging processing and failure handling to finish, then settles the delivery before receiving another.
There is no message `ack()`/`nack()` API and no independent polling executor.
ADT conversion failures, including incoming LOB size-limit violations, use the same configured drop, dead-letter,
or terminal-failure handling as other payload mapping failures. Failed mappings retain available native metadata
in an envelope whose body is unavailable.

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

Recovery uses exponential backoff with jitter and continues until shutdown or a non-recoverable error. Invalid
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
        initial-delay: PT0.1S
        max-delay: PT30S
        jitter: 0.2
        connect-timeout: PT10S
        wait-timeout: PT30S
```

The same policy is available programmatically with `.reconnect(reconnect -> reconnect.enabled(true))` and can be
overridden as a whole on a channel. `connect-timeout` bounds waiting for resource initialization; `wait-timeout` bounds
waiting for a recovered connection, not an in-progress database send. Configure JDBC connection/read timeouts on the
supplied data source or factory as appropriate. If a provider ignores cancellation, recovery waits for that attempt's
cleanup before opening another connection, and closes any late resource returned after shutdown.

## Configuration defaults

Settings can be declared on the connector and overridden on individual channels.

| Setting | Default or requirement |
| --- | --- |
| `destination` | Existing queue name; required when `jndi` is absent |
| `schema` | Connected database user |
| `jndi.destination` | Lookup name; mutually exclusive with direct `destination`/`schema` at the same scope |
| `jndi.env-properties` | Initial-context environment; empty by default |
| `destination-type` | `QUEUE`; use `TOPIC` for multi-consumer queues |
| `payload-type` | `JMS`; alternatives `ADT`, `XML` |
| `transacted` | `true` |
| `allow-object-messages` | `false` |
| `receive-timeout` | `PT0.1S` |
| `close-timeout` | `PT10S` |
| `reconnect.enabled` | `true`; applies after successful startup |
| `reconnect.initial-delay` | `PT0.1S`; doubles after each failed attempt |
| `reconnect.max-delay` | `PT30S`; includes jitter |
| `reconnect.jitter` | `0.2`; fraction in range `0` through `1` |
| `reconnect.connect-timeout` | `PT10S`; maximum wait for one connection attempt |
| `reconnect.wait-timeout` | `PT30S`; maximum outgoing wait for a recovered connection |
| `max-body-bytes` | `1048576`; bounds incoming bytes messages and each detached SQL LOB value |
| `durable` | `false`; when true requires `subscription-name` |
| `no-local` | `false` |
| `delivery-mode` | `2` (persistent), subject to Oracle queue support |
| `priority` | `4`, in range `0` through `9` |
| `time-to-live` | `PT0S` (no expiry) |

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
