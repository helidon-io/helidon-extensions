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

# Imperative Pulsar messaging

This example sends an HTTP request body through Pulsar and receives it back in
the same application using typed connector, messaging graph, and HTTP server
builders. The [declarative example](../se-declarative/README.md) implements the
same behavior using annotations and configuration.

```text
POST /messages
  -> messages-to-pulsar
  -> Pulsar topic: persistent://public/default/http-messages
  -> messages-from-pulsar
  -> GET /messages/latest
```

One `PulsarConnector` holds shared connection settings. Register it with the
graph and supply typed configurations for the incoming and outgoing channels:

```java
PulsarConnector pulsar = PulsarConnector.builder()
        .name("pulsar-1")
        .serviceUrl("pulsar://localhost:6650")
        .build();

MessagingChannel<String> incoming = MessagingChannel.create("messages-from-pulsar", String.class);
MessagingChannel<String> outgoing = MessagingChannel.create("messages-to-pulsar", String.class);

PulsarIncomingConfig incomingConfig = PulsarIncomingConfig.builder()
        .connector(pulsar.name())
        .channelName(incoming.name())
        .execution(execution -> execution.maxInFlightMessages(64))
        .topic("persistent://public/default/http-messages")
        .subscriptionName("imperative-messaging-example")
        .subscriptionInitialPosition(PulsarSubscriptionInitialPosition.EARLIEST)
        .build();

PulsarOutgoingConfig outgoingConfig = PulsarOutgoingConfig.builder()
        .connector(pulsar.name())
        .channelName(outgoing.name())
        .topic("persistent://public/default/http-messages")
        .build();

MessagingGraph graph = MessagingGraph.builder()
        .addConnector(pulsar)
        .channel(incoming)
        .channel(outgoing)
        .incoming(Map.of(incoming.name(), incomingConfig))
        .outgoing(Map.of(outgoing.name(), outgoingConfig))
        .messageSink(incoming, message -> System.out.println(message.entity()))
        .build();
```

The graph creates and manages the channel connections from these configurations.
The incoming connection limits processing to 64 in-flight messages; unspecified
execution settings use the messaging defaults. Each configuration identifies
its connector instance and logical channel explicitly.

The connector uses the default string schema. The incoming subscription starts
at the earliest message when it is first created.

`graph.emitter(outgoing)` supplies the emitter used by the HTTP handler.
A payload can be sent directly with `emitter.emit(text)`.
Use `Message.builder(text)` when headers or other metadata are needed.

See [PulsarService.java](src/main/java/io/helidon/extensions/messaging/pulsar/examples/imperative/PulsarService.java)
for the complete graph and HTTP routes. The service starts the graph in
`beforeStart()` and closes it in `afterStop()`. WebServer's standard shutdown
hook stops HTTP requests before draining and closing the graph's channel
connections; no separate application shutdown handler is needed.
Pulsar connection values are read from `application.yaml` and passed to the
typed builders. For example, override the broker with
`-Dapp.pulsar-service-url=pulsar://other-host:6650` before `-jar`.

## Build and run

Use JDK 26 or later, Maven, and Docker. This example targets Helidon
`27.0.0-SNAPSHOT` and the matching messaging extension snapshot.

Run these commands from this example's directory. Start the local Pulsar broker:

```shell
docker compose up -d --wait
```

Build and start the application:

```shell
mvn clean package
java -jar target/helidon-extensions-messaging-pulsar-examples-se-imperative.jar
```

The HTTP server listens on port 8080. The broker configuration is for local
development. Pulsar automatically creates the topic. Only one copy of the
supplied broker can bind ports 6650 and 8081. The broker admin endpoint uses
port 8081 to leave port 8080 available for the application.

## Send and receive a message

In another terminal, publish a message using HTTP:

```shell
curl -i -H "Content-Type: text/plain" --data "created from HTTP" http://localhost:8080/messages
```

The response is `204 No Content`. The application receives the message from
Pulsar and prints `Received message: created from HTTP`. Read it back using HTTP:

```shell
curl http://localhost:8080/messages/latest
```

The response is `created from HTTP`. Pulsar delivery is asynchronous, so repeat
the GET if the message has not arrived yet. Before any message arrives, the
response is `No messages received`. This example keeps only the last received
message in memory.

Stop the application with Ctrl+C, then remove the example
broker:

```shell
docker compose down
```

## Integration test

```shell
mvn clean verify
```

The test starts its own Pulsar broker on a random port. It posts a message over
HTTP, waits for it to pass through Pulsar back to the application, and checks
that `GET /messages/latest` returns the posted message. It starts the application
only after the broker is ready and shuts it down before stopping the broker.
The test skips when Docker is unavailable. Run Docker-backed tests serially,
without Maven's `-T` option.
