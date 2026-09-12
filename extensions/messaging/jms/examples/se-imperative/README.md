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

# Imperative JMS messaging

This example sends an HTTP request body through JMS and receives it back in
the same application using typed connector, messaging graph, and HTTP server
builders. The [declarative example](../se-declarative/README.md) implements the
same behavior using annotations and configuration.

```text
POST /messages
  -> messages-to-jms
  -> JMS queue: http-messages
  -> messages-from-jms
  -> GET /messages/latest
```

One `JmsConnector` holds shared connection settings. Register it with the
graph and supply typed configurations for the incoming and outgoing channels:

```java
ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory("tcp://localhost:61616");
JmsConnector jms = JmsConnector.builder()
        .name("jms-1")
        .connectionFactory(connectionFactory)
        .username("artemis")
        .password("artemis")
        .build();

MessagingChannel<String> incoming = MessagingChannel.create("messages-from-jms", String.class);
MessagingChannel<String> outgoing = MessagingChannel.create("messages-to-jms", String.class);

JmsIncomingConfig incomingConfig = JmsIncomingConfig.builder()
        .connector(jms.name())
        .channelName(incoming.name())
        .execution(execution -> execution.maxInFlightMessages(64))
        .destination("http-messages")
        .build();

JmsOutgoingConfig outgoingConfig = JmsOutgoingConfig.builder()
        .connector(jms.name())
        .channelName(outgoing.name())
        .destination("http-messages")
        .build();

MessagingGraph graph = MessagingGraph.builder()
        .addConnector(jms)
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

Both channels use the same queue, the default JMS destination type. String
payloads are mapped to JMS text messages.

`graph.emitter(outgoing)` supplies the emitter used by the HTTP handler.
A payload can be sent directly with `emitter.emit(text)`.
Use `Message.builder(text)` when headers or other metadata are needed.

See [JmsService.java](src/main/java/io/helidon/extensions/messaging/jms/examples/imperative/JmsService.java)
for the complete graph and HTTP routes. The service starts the graph in
`beforeStart()` and closes it in `afterStop()`, then closes the application-owned
Artemis connection factory. WebServer's standard shutdown
hook stops HTTP requests before draining and closing the graph's channel
connections; no separate application shutdown handler is needed.
JMS connection values are read from `application.yaml` and passed to the
typed builders. For example, override the broker with
`-Dapp.jms-broker-url=tcp://other-host:61616` before `-jar`.

## Build and run

Use JDK 26 or later, Maven, and Docker. This example targets Helidon
`27.0.0-SNAPSHOT` and the matching messaging extension snapshot.

Run these commands from this example's directory. Start the local Apache Artemis broker:

```shell
docker compose up -d --wait
```

Build and start the application:

```shell
mvn clean package
java -jar target/helidon-extensions-messaging-jms-examples-se-imperative.jar
```

The HTTP server listens on port 8080. The broker configuration is for local
development, using username and password `artemis`. Artemis automatically
creates the queue. Only one copy of the supplied broker can bind ports 61616
and 8161. The broker console is available at http://localhost:8161/console.
Run one example at a time: JMS queues distribute messages among consumers.

The demo broker reserves 1 GiB of free disk space using
`JAVA_ARGS_APPEND=-Dbrokerconfig.minDiskFree=1073741824`. This overrides
Artemis's default 90% disk-usage limit, which can block producers on a large,
mostly used host filesystem even when substantial free space remains. The IT
broker uses the same setting.

If a send stalls with `AMQ212054`, inspect `docker compose logs artemis`.
`AMQ222210` indicates that the broker's storage limit was reached; free disk
space before continuing. After changing the Compose settings, apply them with
`docker compose up -d --force-recreate --wait`.

## Send and receive a message

In another terminal, publish a message using HTTP:

```shell
curl -i -H "Content-Type: text/plain" --data "created from HTTP" http://localhost:8080/messages
```

The response is `204 No Content`. The application receives the message from
JMS and prints `Received message: created from HTTP`. Read it back using HTTP:

```shell
curl http://localhost:8080/messages/latest
```

The response is `created from HTTP`. JMS delivery is asynchronous, so repeat
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

The test starts its own Artemis broker on a random port. It posts a message over
HTTP, waits for it to pass through JMS back to the application, and checks
that `GET /messages/latest` returns the posted message. It starts the application
only after the broker is ready and shuts it down before stopping the broker.
The test skips when Docker is unavailable. Run Docker-backed tests serially,
without Maven's `-T` option.
