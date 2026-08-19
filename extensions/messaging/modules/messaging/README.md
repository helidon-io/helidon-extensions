# Helidon Messaging

Helidon Messaging provides typed logical channels, immutable message envelopes, bounded execution, and transport
connectors. Applications can assemble the channel graph declaratively with Helidon Service Registry code generation or
imperatively with `MessagingGraph`.

The default delivery and settlement contract is synchronous and at least once. For each delivery, required outputs are
invoked sequentially, and outputs completed before a later failure are not rolled back. Handlers and other
side-effecting code must therefore tolerate duplicate delivery. The `DROP` failure disposition explicitly opts into
discarding an exhausted incoming delivery.

## Declarative API

The declarative API builds a messaging graph from Service Registry services, annotated methods, named emitters, and
connector configuration.

### Dependencies and code generation

Import the messaging BOM, then add the runtime, code generator, and the connector artifacts used by the application:

```xml
<properties>
    <helidon.extension.messaging.version>27.0.0-SNAPSHOT</helidon.extension.messaging.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.messaging</groupId>
            <artifactId>helidon-extensions-messaging-bom</artifactId>
            <version>${helidon.extension.messaging.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.helidon.extensions.messaging</groupId>
        <artifactId>helidon-extensions-messaging</artifactId>
    </dependency>
    <dependency>
        <groupId>io.helidon.extensions.messaging</groupId>
        <artifactId>helidon-extensions-messaging-codegen</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>io.helidon.config</groupId>
        <artifactId>helidon-config-yaml</artifactId>
    </dependency>
    <dependency>
        <groupId>io.helidon.extensions.messaging.connectors</groupId>
        <artifactId>helidon-extensions-messaging-connectors-jms</artifactId>
    </dependency>
</dependencies>
```

Set `helidon.extension.messaging.version` to the Messaging extension version used by the application.
`helidon.version` in the build plugin examples is normally supplied by the Helidon application parent. Available
connector artifacts and modules are:

| Connector | Configuration identifier | Artifact suffix | JPMS module suffix |
| --- | --- | --- | --- |
| File | `helidon-file` | `file` | `file` |
| Kafka | `helidon-kafka` | `kafka` | `kafka` |
| JMS | `helidon-jms` | `jms` | `jms` |

Connector artifacts use group `io.helidon.extensions.messaging.connectors` and artifact name
`helidon-extensions-messaging-connectors-<suffix>`. Their JPMS modules are
`io.helidon.extensions.messaging.connectors.<suffix>`.

Configure the messaging code generator on the compiler annotation processor path together with the Helidon APT and
Service Registry processors:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.helidon.codegen</groupId>
                <artifactId>helidon-codegen-apt</artifactId>
                <version>${helidon.version}</version>
            </path>
            <path>
                <groupId>io.helidon.service</groupId>
                <artifactId>helidon-service-codegen</artifactId>
                <version>${helidon.version}</version>
            </path>
            <path>
                <groupId>io.helidon.extensions.messaging</groupId>
                <artifactId>helidon-extensions-messaging-codegen</artifactId>
                <version>${helidon.extension.messaging.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

To generate the `ApplicationBinding` used below, also run the Service Registry application generator:

```xml
<plugin>
    <groupId>io.helidon.service</groupId>
    <artifactId>helidon-service-maven-plugin</artifactId>
    <version>${helidon.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>create-application</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

JPMS applications require the runtime module, each selected configuration parser module, and each selected connector
module, for example YAML with JMS:

```java
requires io.helidon.config.yaml;
requires io.helidon.extensions.messaging;
requires io.helidon.extensions.messaging.connectors.jms;
```

Keep the YAML dependency on the compile path because generated `ApplicationBinding` code references its parser. The
code generator is a build-time dependency and does not need a `requires` directive.

### Start the application

Generate an application binding and start the Service Registry. The registry discovers the generated messaging
registrations and connector providers, validates the complete topology, and starts the graph:

```java
@Service.GenerateBinding
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        ServiceRegistryManager.start(ApplicationBinding.create());
    }
}
```

### Receive and process messages

A receiving method must belong to a concrete Service Registry service:

```java
@Service.Singleton
final class OrderHandler {
    @Messaging.ReceiveFrom("orders")
    void receive(Order order) {
        // Process one order.
    }
}
```

The primary method parameter can expose one of three views of a delivery:

| Parameter | Invocation |
| --- | --- |
| `T` | Once for each message, with its payload. |
| `Message<T>` | Once for each message, with its immutable envelope and portable headers. |
| `MessageBatch<T>` | Once for the complete, ordered delivery batch. |

Connector-specific immutable `Message` subtypes, such as the Kafka and JMS message types, can also be declared for
messages originating from that connector. A default message emitted locally does not satisfy a handler that requires
one of those subtypes. In a multi-parameter method, a payload is identified with `@Messaging.Entity`; alternatively,
an unannotated `Message<T>` or connector-specific envelope can be the primary view. Other parameters use
`@Messaging.HeaderParam`. Header names are exact and case-sensitive; `String` declares a required header and
`Optional<String>` declares an optional one:

```java
@Messaging.ReceiveFrom("orders")
void receive(@Messaging.Entity Order order,
             @Messaging.HeaderParam("tenant") String tenant,
             @Messaging.HeaderParam("trace-id") Optional<String> traceId) {
    // Process one order and its selected headers.
}
```

Use `@Messaging.SendTo` for a synchronous one-to-one processor:

```java
@Messaging.ReceiveFrom("orders")
@Messaging.SendTo("validated-orders")
Message<Order> validate(Message<Order> incoming) {
    return Messaging.message(validate(incoming.entity()))
            .header("trace-id", incoming.header("trace-id").orElse("unknown"))
            .build();
}
```

A processor can return a payload or a `Message<T>`. A payload is wrapped in a new message without the input headers;
return a message envelope when headers must be retained or changed. Terminal receivers and batch handlers return
`void`, and a batch handler cannot use `@Messaging.SendTo`. Asynchronous return types and reactive publishers are not
supported.

Several services can receive from the same channel. Each receiver is a required output, so the delivery succeeds only
after all of them succeed. One service cannot declare two receivers for the same channel.

### Create messages and batches

`Message<T>` contains a payload and immutable, single-valued portable headers:

```java
Message<Order> message = Messaging.message(order)
        .header("trace-id", traceId)
        .header("tenant", tenant)
        .build();
```

Use `Message.create(order)` when no headers are needed. Setting the same header name again replaces its previous
value.

Every delivery is a non-empty, ordered `MessageBatch<T>`. Payload and message receivers are called once per item,
while a batch receiver is called once for the whole delivery:

```java
MessageBatch<Order> batch = MessageBatch.create(List.of(firstMessage, secondMessage));
```

A batch is a delivery and performance boundary, not necessarily a transport transaction. When batch delivery fails,
`BatchDeliveryException` describes each item as `SUCCEEDED`, `FAILED`, `NOT_ATTEMPTED`, or `INDETERMINATE`.

### Emit messages

Inject a generated `Emitter<T>` with exactly one named channel qualifier and a concrete payload type. Raw, wildcard,
and unresolved generic emitter payload types are rejected during code generation:

```java
@Service.Singleton
final class OrderPublisher {
    private final Emitter<Order> orders;

    @Service.Inject
    OrderPublisher(@Service.Named("orders") Emitter<Order> orders) {
        this.orders = orders;
    }

    void publish(Order order) {
        orders.emit(order);
    }

    void publish(Message<Order> order) {
        orders.emitMessage(order);
    }

    void publish(MessageBatch<Order> orders) {
        this.orders.emitBatch(orders);
    }
}
```

Emitter calls are synchronous. A successful return means every required local receiver, processor route, and outgoing
connector completed. The target channel must have at least one receiver or configured outgoing connector.

A service that emits during `@Service.PostConstruct` must start after the messaging runtime:

```java
@Service.Singleton
@Service.RunLevel(MessagingRuntime.RUN_LEVEL + 1)
final class StartupPublisher {
    // Inject an emitter and publish from @Service.PostConstruct.
}
```

### Configure connectors

Add external sources under `helidon.messaging.incoming` and external sinks under `helidon.messaging.outgoing`.
Connector-wide defaults under `helidon.messaging.connector.<type>` are overlaid by the corresponding channel values.

Helidon-provided connector identifiers use the `helidon-` prefix so they remain distinguishable from third-party
providers. For example:

```yaml
helidon:
  messaging:
    connector:
      helidon-jms:
        connection-factory: primary-jms

    incoming:
      orders:
        connector: helidon-jms
        destination: orders
        destination-type: QUEUE

    outgoing:
      validated-orders:
        connector: helidon-jms
        destination: validated-orders
        destination-type: QUEUE
```

Connector options other than `connector` are connector-specific. The `failure` subtree of an incoming channel is
portable messaging configuration; it is not a connector option and cannot be placed in connector-wide defaults. See
the [JMS connector documentation](../connectors/jms/README.md) for a complete JMS configuration example.

### Retry, drop, and dead-letter handling

`@Messaging.OnFailure` supplies a default policy for a configured incoming connector channel:

```java
@Messaging.ReceiveFrom("orders")
@Messaging.OnFailure(
        retryDelay = "PT0.25S",
        maxAttempts = 3,
        onExhausted = FailureDisposition.DEAD_LETTER,
        deadLetterChannel = "orders-dlq")
void receive(Order order) {
    throw new IllegalArgumentException("Invalid order");
}

@Messaging.ReceiveFrom("orders-dlq")
void deadLetter(DeadLetterMessage<Order> failed) {
    System.err.printf("Order failed after %d attempts: %s%n",
                      failed.attempts(), failed.failureMessage());
}
```

`maxAttempts` includes the initial attempt. Zero means unlimited attempts and is valid only with `FAIL`. `DROP` and
`DEAD_LETTER` require a positive limit, and `DEAD_LETTER` also requires a distinct logical target channel with an
actual output.

A dead-letter target must use the source payload type. Its local receivers must accept `DeadLetterMessage<T>` or a
compatible `Message<T>` envelope, and dead-letter routes cannot form cycles. These constraints are validated before
the messaging graph starts.

The policy belongs to the incoming channel and retained delivery, not only to the annotated method call. It covers
sibling receivers and downstream outputs reached by that delivery. If several receivers on one channel declare a
policy, their effective policies must agree.

Configuration overrides annotation members independently:

```yaml
helidon:
  messaging:
    incoming:
      orders:
        connector: helidon-jms
        destination: orders
        destination-type: QUEUE
        failure:
          retry:
            max-attempts: 1
          on-exhausted: DROP
```

This retains the annotation's retry delay, changes the total attempts to one, and replaces `DEAD_LETTER` with `DROP`.
The inherited dead-letter target is cleared. Without either an annotation or configuration, an incoming connector uses
a one-second retry delay, unlimited attempts, and `FAIL`.

Exhaustion has these results:

- `FAIL` propagates the failure and leaves the transport delivery unsettled.
- `DROP` logs the failure and settles the transport delivery without forwarding it.
- `DEAD_LETTER` routes a `DeadLetterMessage<T>` with the original envelope, source channel, attempt count, and failure
  details. The source is settled only after dead-letter delivery succeeds.

`@Messaging.OnFailure` does not retry calls made through a local `Emitter`; an emitter returns its delivery exception
directly.

### Configure execution limits

Messaging uses bounded admission rather than a Reactive Streams protocol. Global limits are configured under
`helidon.messaging.execution`; channel-specific values under `helidon.messaging.channel.<channel>.execution` override
them:

```yaml
helidon:
  messaging:
    execution:
      queue-capacity: 0
      max-pending-admissions: 64
      max-pending-messages: 1024
      max-in-flight-messages: 1024
      admission-timeout: PT5S
      shutdown-timeout: PT10S

    channel:
      orders:
        execution:
          queue-capacity: 32
```

Admitted deliveries execute sequentially in FIFO order within each channel, so messaging methods handling that channel
are never invoked concurrently. Different channels have independent dispatchers, so deliveries on different channels
may execute at the same time. `shutdown-timeout` is runtime-wide and cannot be overridden per channel. Capacity,
timeout, cancellation, and shutdown admission failures are reported as `MessagingRejectedException` with a typed
reason.

For a complete application bootstrap and generated emitter example, see the
[declarative SE example](../../examples/se-declarative/).

## Imperative API

The imperative API builds and owns a typed messaging graph directly in Java. It uses the
`helidon-extensions-messaging` runtime dependency but does not require messaging code generation.

### Build and run a graph

```java
try (MessagingGraph.Builder builder = MessagingGraph.builder()) {
    MessagingChannel<String> input = builder.channel("input", String.class);
    MessagingChannel<String> output = builder.channel("output", String.class);

    builder.messageProcessor(input, output, message ->
                    Message.builder(message.entity().toUpperCase())
                            .header("trace-id", message.header("trace-id").orElse("unknown"))
                            .build())
            .messageSink(output, message -> System.out.println(message.entity()));

    try (MessagingGraph graph = builder.build()) {
        graph.start();

        Emitter<String> emitter = graph.emitter(input);
        emitter.emitMessage(Message.builder("hello")
                                    .header("trace-id", "123")
                                    .build());
    }
}
```

`MessagingChannel<T>` is an opaque handle owned by its graph. Use `Class<T>` for a simple payload type or
`GenericType<T>` to retain a parameterized payload type. `build()` freezes and validates the topology, and `start()`
must complete before an emitter can emit.

### Assemble a topology

The builder supports these elements:

| Method | Purpose |
| --- | --- |
| `payloadSource` | Feed payloads from a builder/graph-owned `Stream`. |
| `messageSource` | Feed message envelopes from a builder/graph-owned `Stream`. |
| `route` | Forward a batch unchanged between channels of the same payload type. |
| `payloadProcessor` | Transform each payload; input headers are not propagated. |
| `messageProcessor` | Transform each message and explicitly control the resulting headers. |
| `payloadSink` | Consume each payload. |
| `messageSink` | Consume each message envelope. |
| `batchSink` | Consume a complete batch once. |
| `outgoingConnector` | Add a builder/graph-owned connector as a required output. |

Every channel must have at least one output. Synchronous routing cycles are rejected. A channel can have at most one
stream source, and downstream paths from distinct stream sources cannot converge.

The imperative builder registers streams as sources and can attach an `OutgoingConnector` directly. The built graph
exposes typed emitters for application-originated input. The builder owns registered streams and connectors until a
successful build transfers them to the graph. The builder does not currently expose an incoming-connector registration
method. Declarative connector configuration and `@Messaging.OnFailure` policies are not applied to an imperative graph.

### Emit batches

The same `Message<T>`, `MessageBatch<T>`, and `Emitter<T>` contracts are used by both APIs:

```java
MessageBatch<String> batch = MessageBatch.create(List.of(
        Message.create("first"),
        Message.builder("second").header("trace-id", "123").build()));

graph.emitter(input).emitBatch(batch);
```

`Emitter.emit` and `Emitter.emitMessage` create singleton batches. All emitter methods wait for end-to-end completion.
A partial or indeterminate failure throws `BatchDeliveryException` with an outcome aligned to every original item.

### Configure execution and lifecycle

Configure graph-wide defaults before declaring the first channel:

```java
MessagingExecutionConfig execution = MessagingExecutionConfig.builder()
        .queueCapacity(32)
        .maxInFlightMessages(256)
        .shutdownTimeout(Duration.ofSeconds(10))
        .build();

MessagingGraph.Builder builder = MessagingGraph.builder()
        .executionConfig(execution);
```

The `channel` overload that accepts a `GenericType<T>` and `MessagingExecutionConfig` supplies channel-specific
admission and message limits; the shutdown timeout remains graph-wide. Delivery remains sequential within every
channel, while different channels may execute concurrently.

Closing a running graph stops new external admission, drains admitted work, and closes graph-owned streams and
connectors. Closing an unbuilt builder releases resources already transferred to it. Failures from asynchronous stream
sources are reported when the graph closes.

An imperative emission has the same at-least-once behavior as a declarative emission: for each delivery, outputs run
sequentially, the first failure prevents later outputs from running, and earlier outputs are not rolled back. Retrying
an unsuccessful or indeterminate delivery can therefore produce duplicates.
