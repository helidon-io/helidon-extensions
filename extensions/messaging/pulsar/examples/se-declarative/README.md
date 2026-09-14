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

# Declarative Pulsar messaging

This example sends an HTTP request body through Pulsar and receives it back in
the same application using annotated messaging and HTTP methods:

```text
POST /messages
  -> messages-to-pulsar
  -> Pulsar topic: persistent://public/default/http-messages
  -> messages-from-pulsar
  -> GET /messages/latest
```

[MessagingEndpoint.java](src/main/java/io/helidon/extensions/messaging/pulsar/examples/declarative/MessagingEndpoint.java)
injects a named `Emitter<String>` to publish HTTP request bodies, and receives
the same messages through `@Messaging.ReceiveFrom("messages-from-pulsar")`.
The service registry manages the messaging runtime and webserver lifecycle
using the generated application binding.

The configuration separates a reusable connector instance from its channels:

```yaml
messaging:
  connector:
    pulsar-1:
      type: helidon-pulsar
      service-url: pulsar://localhost:6650
      schema: STRING
  incoming:
    messages-from-pulsar:
      connector: pulsar-1
      execution:
        max-in-flight-messages: 64
      topic: persistent://public/default/http-messages
      subscription-name: declarative-messaging-example
      subscription-initial-position: EARLIEST
  outgoing:
    messages-to-pulsar:
      connector: pulsar-1
      topic: persistent://public/default/http-messages
```

`helidon-pulsar` selects the provider, while `pulsar-1` names this configured
connector instance. Both channels reference that instance and inherit its
service URL and string schema. Helidon options use kebab-case names such as
`service-url`, `subscription-name`, and `subscription-initial-position`.

The `connector` node also accepts a list. Replace only that node with the
following equivalent configuration:

```yaml
connector:
  - type: helidon-pulsar
    name: pulsar-1
    service-url: pulsar://localhost:6650
    schema: STRING
```

Incoming and outgoing channel names are the keys under their respective nodes.
The runtime reads each connection's connector reference and execution settings;
the Pulsar connector reads topic and client settings from the same node. The
incoming connection limits processing to 64 in-flight messages; unspecified
execution settings use the messaging defaults.

For example, replace the shared broker service URL with
`-Dmessaging.connector.pulsar-1.service-url=pulsar://other-host:6650` before
`-jar`.

## Build and run

Use JDK 26 or later, Maven, and Docker. This example targets Helidon
`27.0.0-SNAPSHOT` and the matching messaging extension snapshot. The example
runs on the classpath because the Pulsar client dependencies contain split packages.

Run these commands from this example's directory. Start the local Pulsar broker:

```shell
docker compose up -d --wait
```

Build and start the application:

```shell
mvn clean package
java -jar target/helidon-extensions-messaging-pulsar-examples-se-declarative.jar
```

The HTTP server listens on port 8080. The broker configuration is for local
development. The broker listens on port 6650 and its admin endpoint on port 8081.
Only one copy of the supplied broker can bind those ports.

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
