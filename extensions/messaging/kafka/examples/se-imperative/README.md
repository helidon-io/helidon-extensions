<!--
Copyright (c) 2026 Oracle and/or its affiliates.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Imperative Kafka messaging

This example builds a messaging graph and an HTTP server using typed Java
builders. The [declarative example](../se-declarative/README.md)
implements the same behavior using annotations and configuration.

One `KafkaConnector` holds shared connection settings. Register it with the
graph and supply typed configurations for the incoming and outgoing channels:

```java
KafkaConnector kafka = KafkaConnector.builder()
        .name("orders-kafka")
        .addBootstrapServer("localhost:9092")
        .build();

MessagingChannel<String> orders = MessagingChannel.create("orders", String.class);
MessagingChannel<String> httpMessages = MessagingChannel.create("http-messages", String.class);
MessagingConfig.Builder builder = MessagingGraph.builder()
        .channel(orders)
        .channel(httpMessages);

KafkaIncomingConfig ordersConfig = KafkaIncomingConfig.builder()
        .connector(kafka.name())
        .channelName(orders.name())
        .execution(execution -> execution.maxInFlightMessages(64))
        .topic("orders")
        .groupId("inventory-service")
        .autoOffsetReset("earliest")
        .build();

KafkaOutgoingConfig messagesConfig = KafkaOutgoingConfig.builder()
        .connector(kafka.name())
        .channelName(httpMessages.name())
        .topic("http-messages")
        .putProperty("linger.ms", "5")
        .build();

MessagingGraph graph = builder.addConnector(kafka)
        .incoming(Map.of(orders.name(), ordersConfig))
        .outgoing(Map.of(httpMessages.name(), messagesConfig))
        .messageSink(orders, message -> System.out.println(message.entity()))
        .build();
```

The graph creates and manages the channel connections from these configurations.
The incoming connection limits orders to 64 in-flight messages; unspecified
execution settings use the messaging defaults. Each configuration identifies
its connector instance and logical channel explicitly.

Bootstrap servers are a list: use `addBootstrapServer` for one entry or
`bootstrapServers(List<String>)` for the complete list. Additional native Kafka
client settings use `putProperty`, retaining their dotted names such as
`linger.ms`.

`graph.emitter(httpMessages)` supplies the emitter used by the HTTP handler.
A payload can be sent directly with `emitter.emit(text)`.
Use `Message.builder(text)` when headers or other metadata are needed.

See [KafkaService.java](src/main/java/io/helidon/extensions/messaging/examples/imperative/KafkaService.java)
for the complete graph and HTTP routes. The service starts the graph in
`beforeStart()` and closes it in `afterStop()`. WebServer's standard shutdown
hook stops HTTP requests before draining and closing the graph's channel
connections; no separate application shutdown handler is needed.
Kafka connection values are read from `application.yaml` and passed to the
typed builders. For example, override the broker with
`-Dapp.kafka-bootstrap-servers=other-host:9092` before `-jar`.

## Build and run

Use JDK 26 or later, Maven, and Docker. This example targets Helidon
`27.0.0-SNAPSHOT` and the matching messaging extension snapshot.

Run these commands from this example's directory. Start the local Kafka broker:

```shell
docker compose up -d --wait
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic orders --partitions 1 --replication-factor 1
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic http-messages --partitions 1 --replication-factor 1
```

Build and start the application:

```shell
mvn clean package
java -jar target/helidon-extensions-messaging-examples-se-imperative.jar
```

The HTTP server listens on port 8080. The broker configuration is for local
development. Only one copy of the supplied broker can bind port 9092.

## Try both directions

In another terminal, consume the application's outgoing topic:

```shell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic http-messages --from-beginning
```

Publish a message using HTTP:

```shell
curl -i -H "Content-Type: text/plain" --data "created from HTTP" http://localhost:8080/messages
```

The response is `204 No Content`; the Kafka console consumer prints
`created from HTTP`. The HTTP response follows completion of the Kafka send;
it does not wait for a separate Kafka consumer to process the message.

To exercise the incoming channel, run the console producer and type `order-42`,
then press Enter:

```shell
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic orders
```

The application prints `Received order: order-42`. Inspect its last received
order using HTTP:

```shell
curl http://localhost:8080/orders/latest
```

The response is `order-42`. Before any order arrives, it is
`No orders received`. This small example keeps only the last order in memory.

Stop the application and console clients with Ctrl+C, then remove the example
broker:

```shell
docker compose down
```

## Integration test

```shell
mvn clean verify
```

The test starts its own Kafka broker on a random port. It verifies an HTTP POST
with an independent Kafka consumer, then publishes an order with a Kafka
producer and checks the application's HTTP endpoint. It starts the application
only after the broker is ready and shuts it down before stopping the broker.
The test skips when Docker is unavailable. Run Docker-backed tests serially,
without Maven's `-T` option.
