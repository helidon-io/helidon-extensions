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

# Declarative JMS messaging

This example sends an HTTP request body through JMS and receives it back in
the same application using annotated messaging and HTTP methods:

```text
POST /messages
  -> messages-to-jms
  -> JMS queue: http-messages
  -> messages-from-jms
  -> GET /messages/latest
```

[MessagingEndpoint.java](src/main/java/io/helidon/extensions/messaging/jms/examples/declarative/MessagingEndpoint.java)
injects a named `Emitter<String>` to publish HTTP request bodies, and receives
the same messages through `@Messaging.ReceiveFrom("messages-from-jms")`.
The service registry manages the messaging runtime and webserver lifecycle
using the generated application binding.

[ConnectionFactoryProvider.java](src/main/java/io/helidon/extensions/messaging/jms/examples/declarative/ConnectionFactoryProvider.java)
provides the Artemis `ConnectionFactory` as a named service and closes it when
the application shuts down. The JMS connector uses this factory to create its
connections; the connector itself does not depend on a particular JMS provider.

The configuration separates a reusable connector instance from its channels:

```yaml
app:
  jms-broker-url: tcp://localhost:61616

messaging:
  connector:
    jms-1:
      type: helidon-jms
      connection-factory: artemis
      username: artemis
      password: artemis
  incoming:
    messages-from-jms:
      connector: jms-1
      execution:
        max-in-flight-messages: 64
      destination: http-messages
      destination-type: QUEUE
  outgoing:
    messages-to-jms:
      connector: jms-1
      destination: http-messages
      destination-type: QUEUE
```

`helidon-jms` selects the provider, while `jms-1` names this configured connector
instance. Both channels reference that instance and inherit its connection
factory and credentials. `connection-factory: artemis` selects the named
`ConnectionFactory` service. String payloads are sent as JMS text messages.

The `connector` node also accepts a list. Replace only that node with the
following equivalent configuration:

```yaml
connector:
  - type: helidon-jms
    name: jms-1
    connection-factory: artemis
    username: artemis
    password: artemis
```

Incoming and outgoing channel names are the keys under their respective nodes.
The runtime reads each connection's connector reference and execution settings;
the JMS connector reads destination and client settings from the same node. The
incoming connection limits processing to 64 in-flight messages; unspecified
execution settings use the messaging defaults.

For example, replace the broker URL with
`-Dapp.jms-broker-url=tcp://other-host:61616` before `-jar`.

## Build and run

Use JDK 26 or later, Maven, and Docker. This example targets Helidon
`27.0.0-SNAPSHOT` and the matching messaging extension snapshot.

Run these commands from this example's directory. Start the local Artemis broker:

```shell
docker compose up -d --wait
```

Build and start the application:

```shell
mvn clean package
java -jar target/helidon-extensions-messaging-jms-examples-se-declarative.jar
```

The HTTP server listens on port 8080. The broker configuration is for local
development, with username and password `artemis`. The broker listens on port
61616 and its web console on port 8161. Only one copy of the supplied broker
can bind those ports. Run one of the JMS examples at a time: consumers of the
same queue share messages.

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

Stop the application with Ctrl+C, then remove the example broker:

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
