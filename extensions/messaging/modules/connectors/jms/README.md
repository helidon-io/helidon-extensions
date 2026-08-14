# Helidon Declarative Messaging JMS Connector

The JMS connector connects Helidon declarative messaging channels to Jakarta Messaging 3.1 queues and topics. It is
provider-neutral: your application supplies a JMS provider and a `jakarta.jms.ConnectionFactory`.

## Dependency

```xml
<dependency>
    <groupId>io.helidon.extensions.messaging.connectors</groupId>
    <artifactId>helidon-extensions-messaging-connectors-jms</artifactId>
</dependency>
```

Add your JMS provider client separately. The connector depends on no JMS implementation and does not select a broker
implementation.

## Connection factory

The connector resolves a connection factory in one of three ways.

### Imperative provider

For an imperatively assembled messaging graph, construct the provider directly:

```java
ConnectionFactory factory = createVendorConnectionFactory();
JmsConnectorProvider provider = new JmsConnectorProvider(factory);
```

Every binding created by this provider uses that factory.

### Service Registry

A declarative application can register one default connection factory as a contract instance before starting the
registry:

```java
ConnectionFactory factory = createVendorConnectionFactory();
ServiceRegistryConfig registryConfig = ServiceRegistryConfig.builder()
        .putContractInstance(ConnectionFactory.class, factory)
        .build();
ServiceRegistryManager.start(ApplicationBinding.create(), registryConfig);
```

`ApplicationBinding` is the application's generated binding. Applications that use service discovery rather than a
generated binding can pass the same configuration to their normal `ServiceRegistryManager.start` call.

When several factories are needed, expose qualified instances through a
`Service.ServicesFactory<ConnectionFactory>` and select one with `connection-factory` in channel configuration. A
configured name must resolve exactly; it does not fall back to the default factory.

### JNDI

Configure `jndi.connection-factory` to look up a connection factory from a fresh `InitialContext` during every
connection attempt. `jndi.destination` can independently look up the destination. Provider-specific JNDI settings go
under `jndi.environment`.

```yaml
jndi:
  connection-factory: jms/ConnectionFactory
  destination: jms/queue/orders
  environment:
    java.naming.factory.initial: com.example.jms.InitialContextFactory
    java.naming.provider.url: tcp://broker.example:61616
```

Do not combine `connection-factory` with `jndi.connection-factory`, or `destination` with `jndi.destination`.

## Configuration

Connector defaults can be shared under `helidon.messaging.connector.jms`. Channel settings override them.

```yaml
helidon:
  messaging:
    connector:
      jms:
        connection-factory: primary-jms
        reconnect:
          initial-delay: PT0.1S
          max-delay: PT30S
          jitter: 0.2
        receive-timeout: PT0.1S
        close-timeout: PT10S

    incoming:
      orders:
        connector: jms
        destination: orders
        destination-type: QUEUE
        message-selector: "region = 'EU'"

    outgoing:
      order-results:
        connector: jms
        destination: order-results
        destination-type: QUEUE
```

Connection credentials are optional but must be supplied together:

```yaml
username: app-user
password: ${JMS_PASSWORD}
```

This example resolves `JMS_PASSWORD` from Helidon's default environment-variable config source. Applications can use
any configured secret-capable source or config filter instead.

The connector snapshots the configured password as `char[]`, converts it to the `String` required by the Jakarta JMS
API only at connection creation, and clears its private copy when shutdown is requested. It does not log credentials.
An application-owned typed configuration remains reusable and retains its own defensive password copy.

### Topic subscriptions

A non-durable topic consumer needs only `destination-type: TOPIC`. A durable topic subscription additionally requires
`durable`, `subscription-name`, and a client identifier. Configure `client-id` when the application assigns the
identifier:

```yaml
helidon:
  messaging:
    incoming:
      notifications:
        connector: jms
        destination: notifications
        destination-type: TOPIC
        durable: true
        client-id: inventory-service
        subscription-name: inventory-notifications
        no-local: false
```

Omit `client-id` when the `ConnectionFactory` supplies an administratively configured client identifier. An explicitly
configured `client-id` is set immediately after creating the connection and cannot override an administered identifier.

`no-local` is valid only for topics. Subscription options are rejected for outgoing bindings.

## Declarative usage

The connector participates in the normal declarative messaging API; no JMS-specific annotation is required.

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

Send to an outgoing JMS binding through its named emitter:

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

Use `JmsMessage` when native correlation, type, or typed application properties are needed:

```java
JmsMessage<String> message = JmsMessage.<String>builder("accepted")
        .correlationId("order-42")
        .type("order-result")
        .property("region", "EU")
        .property("attempt", 1)
        .property("JMSXGroupID", "order-42")
        .property("JMSXGroupSeq", 1)
        .build();
results.emitMessage(message);
```

Incoming `JmsMessage` instances expose immutable snapshots of the body, typed application properties, and selected
JMS metadata: message and String correlation identifiers, type, timestamp, expiration, delivery time, priority, and
redelivery state. They never expose the live native `Message`, `Session`, or `Connection`. Portable message headers
are the string representation of JMS application properties.

## Body and property mapping

| Helidon payload | JMS message |
| --- | --- |
| `null` | bodyless `Message`; an incoming generic bodyless JMS `Message` maps back to a null payload |
| `String` | `TextMessage` |
| `byte[]` | `BytesMessage` |
| `Map<String, Object>` | `MapMessage` |
| `List<?>` | `StreamMessage` |
| other `Serializable` | `ObjectMessage`, only when explicitly enabled |

Map and stream values must use JMS-supported primitive wrapper, `String`, `Character`, `byte[]`, or null values. JMS
application properties support `Boolean`, numeric primitive wrappers, and `String`. Property names must be valid JMS
selector identifiers and must not use the provider-reserved `JMS` prefix, except for the standard client-settable
`JMSXGroupID` (`String`) and `JMSXGroupSeq` (`Integer`) properties. Other provider-owned `JMSX*` and `JMS_*` properties
are not exposed as portable application headers. Generic portable headers may also set these two grouping properties;
the `JMSXGroupSeq` header value must be a decimal integer. Invalid application property names or values fail the send
before the broker success point.

Java object messages are disabled by default. Enabling them permits native Java serialization and deserialization and
must be limited to trusted producers, trusted payload classes, and a properly restricted deserialization environment.
Wrapping an outgoing `Serializable` payload in `JmsMessage` does not itself invoke serialization callbacks. The disabled
object-message gate rejects it without serializing or deserializing it. When object messages are enabled, the connector
makes a serialization round-trip immediately before handing a defensive body snapshot to the JMS provider; applications
must not mutate the payload between building and sending the message. Deep immutability still depends on the serialized
object graph and application classes:

```yaml
allow-object-messages: true
```

Do not enable object messages for data from an untrusted broker, tenant, or producer.

## Incoming settlement

The connector uses one synchronous consumer and one outstanding message per JMS session. It reserves runtime delivery
capacity before calling `receive`, then holds that capacity until transport settlement is complete.

The Jakarta Messaging API does not provide a portable way to limit a provider's client-side prefetch. Runtime delivery
capacity therefore controls calls to `receive`, but a provider may already have moved additional messages from the
broker into its client buffer. If broker-side acquisition must follow runtime backpressure, configure the supplied
`ConnectionFactory` with provider-specific consumer credit or prefetch disabled. For Artemis, a consumer window size of
zero keeps the next message pending at the broker until the connector has another runtime reservation.

For a non-transacted session, the connector uses `CLIENT_ACKNOWLEDGE` and acknowledges only after runtime delivery,
including configured retry, drop, or dead-letter handling, has completed. For `transacted: true`, it commits the local
JMS transaction at the same point. A terminal processing failure recovers or rolls back the session so the broker can
redeliver the message according to its policy.

This provides at-least-once delivery. If the connection is lost after application processing but before acknowledgment
or commit is confirmed, the message can be delivered again. Consumers should be idempotent when duplicates matter.

## Outgoing completion and transactions

Without a local transaction, a batch sends messages sequentially and reports per-message completion. A successful
prefix can therefore precede an unsuccessful item.

With `transacted: true`, all messages in one `MessageBatch` are sent on one local transaction and committed together.
A successful commit is the batch success point. If send or commit fails and rollback cannot prove non-delivery, the
outcome is indeterminate and retrying can create duplicates.

The connector never automatically resends a message after `MessageProducer.send` or `Session.commit` has been invoked
and failed, because the broker might already have accepted it.

## Broker outages and reconnection

Reconnection is connector-owned. The messaging graph does not discard and recreate the binding.

When startup or an established connection fails, the connector closes the old connection, session, consumer, or
producer and retries with exponential backoff. The delay starts at `reconnect.initial-delay`, is capped at
`reconnect.max-delay`, and receives the configured fractional jitter. Reconnection continues while the graph remains
active; graph startup and shutdown deadlines can interrupt it.

A replacement connection is opened only after the previous resource generation closes successfully. A cleanup failure
or `close-timeout` expiry is terminal because continuing could overlap or leak provider resources.

After recovery:

- Queues retain unacknowledged and broker-persisted messages according to broker policy.
- Durable topic subscriptions retain messages published while the client is offline according to broker policy.
- Non-durable topic subscriptions do not receive messages published while disconnected.
- A message whose acknowledgment or transaction outcome was lost can be redelivered.

Provider-native reconnect settings can still affect detection and low-level recovery. The connector rebuilds its JMS
resources so it does not depend on provider-specific automatic-reconnect behavior.

## Shutdown

Graceful shutdown stops acquiring messages and allows the active delivery to settle before closing JMS resources.
`close-timeout` bounds connector-owned cleanup waits. Forced shutdown interrupts startup, reconnect backoff, receive,
and active waits and makes a best effort to close the current connection. Close operations are idempotent.
