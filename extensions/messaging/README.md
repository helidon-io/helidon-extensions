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

# Messaging connectors

Each connector is an independently versioned extension under this grouping directory:

* [Kafka](kafka/modules/kafka/README.md), including [runnable examples](kafka/examples/README.md)
* [Jakarta JMS](jms/modules/jms/README.md),
  including [runnable Jakarta JMS examples](jms/examples/README.md)
* [javax JMS](jms-javax/modules/jms-javax/README.md)
* [Pulsar](pulsar/modules/pulsar/README.md), including [runnable examples](pulsar/examples/README.md)

Kafka's Java APIs are preview APIs; Jakarta JMS, javax JMS, and Pulsar are incubating connectors.

Build all connectors with `mvn -f extensions/messaging/pom.xml install`, or build one
with `mvn -f extensions/messaging/kafka/pom.xml install`. Add `-Ptests` to include
integration tests and `-Pexamples` to include the Kafka, JMS, and Pulsar examples.

Each connector has its own `bom` and `modules` directories. Import
`io.helidon.extensions.messaging.kafka:helidon-extensions-messaging-kafka-bom` for
Kafka, or replace `kafka` with `jms`, `jms-javax`, or `pulsar` for the other connectors. Connector
JAR coordinates remain under `io.helidon.extensions.messaging.connectors`.
Applications using both JMS APIs import both JMS BOMs; their versions are independent.

Release IDs are the directory paths relative to `extensions`, such as
`messaging/kafka`, `messaging/jms`, and `messaging/jms-javax`.
See the [release instructions](../../etc/scripts/RELEASE.md).

Third-party attributions for the connectors are in
[THIRD_PARTY_LICENSES.txt](THIRD_PARTY_LICENSES.txt).
