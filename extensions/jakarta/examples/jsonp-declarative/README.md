# Jakarta jsonp-declarative example

Generates a REST endpoint and service binding, starts the Helidon service registry and WebServer on a dynamic loopback port, exchanges a JSON-P entity with Helidon WebClient, and shuts down. This demonstrates automatic extension discovery with declarative HTTP APIs.

Requires Java 27, Maven, the Helidon 28 artifacts, and the Jakarta extension artifacts.
For a source checkout, first install the Jakarta release unit from `extensions/jakarta` with
`mvn_27 clean install -Ptests -DskipTests`. This example has its own Helidon SE parent,
imports the Jakarta BOM, and can then be built outside the extension reactor.

Build, test, and run the module-path smoke check:

```shell
mvn_27 clean verify
```

Run the built example again on Linux or macOS:

```shell
java --module-path target/classes:target/libs --module io.helidon.extensions.jakarta.examples.declarative/io.helidon.extensions.jakarta.examples.declarative.Main
```

On Windows replace the module-path separator `:` with `;`.
The example exits after printing the successful response. It binds no fixed port.

The runtime dependency tree is written to `target/runtime-dependencies.txt`.
The independent-consumer suite also checks this tree to reject deprecated core integrations,
other Jakarta integration modules, persistence, and transaction integrations.
