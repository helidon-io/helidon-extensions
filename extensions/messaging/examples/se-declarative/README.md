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

# Declarative Kafka messaging

This example connects annotated messaging and HTTP methods to Kafka.
The [imperative example](../se-imperative/README.md) implements the same
behavior using typed connector and graph builders.

[MessagingEndpoint.java](src/main/java/io/helidon/extensions/messaging/examples/declarative/MessagingEndpoint.java)
injects a named `Emitter<String>` to publish HTTP request bodies, and receives
orders through `@Messaging.ReceiveFrom("orders")`. The service registry manages
the messaging runtime and webserver lifecycle using the generated application
binding.

The configuration separates a reusable connector instance from its channels:

```yaml
messaging:
  connector:
    orders-kafka:
      type: helidon-kafka
      bootstrap-servers:
        - localhost:9092
  incoming:
    orders:
      connector: orders-kafka
      execution:
        max-in-flight-messages: 64
      topic: orders
      group-id: declarative-inventory-service
      auto-offset-reset: earliest
  outgoing:
    http-messages:
      connector: orders-kafka
      topic: http-messages
      properties:
        linger.ms: "5"
```

`helidon-kafka` selects the provider, while `orders-kafka` names this configured
connector instance. Both channels reference that instance and inherit its
bootstrap server list. Helidon options use kebab-case names such as
`bootstrap-servers`, `group-id`, and `auto-offset-reset`. Additional native Kafka
client settings go under `properties` and retain their dotted names, such as
`linger.ms`.

The `connector` node also accepts a list. Replace only that node with the
following equivalent configuration:

```yaml
connector:
  - type: helidon-kafka
    name: orders-kafka
    bootstrap-servers:
      - localhost:9092
```

Incoming and outgoing channel names are the keys under their respective nodes.
The runtime reads each connection's connector reference and execution settings;
the Kafka connector reads topic and client settings from the same node. The
incoming connection limits orders to 64 in-flight messages; unspecified
execution settings use the messaging defaults.

For example, replace the first shared bootstrap server with
`-Dmessaging.connector.orders-kafka.bootstrap-servers.0=other-host:9092` before
`-jar`.

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
java -jar target/helidon-extensions-messaging-examples-se-declarative.jar
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
