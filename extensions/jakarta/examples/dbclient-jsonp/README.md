# Jakarta dbclient-jsonp example

Maps a DBClient row to a JSON-P object and maps it to named statement parameters using the discovered extension provider. It uses an in-memory row to demonstrate the mapping without requiring a database. Tests also cover indexed parameters and JSON null parameters.

Requires JDK 27 and Maven, with `JAVA_HOME` set to that JDK and its `bin` directory on `PATH`.
The Helidon 28 and Jakarta extension artifacts must be available. For a source checkout, follow the
[development build prerequisites](../../docs/README.md#development-build), then install the Jakarta release unit
from `extensions/jakarta` with `mvn clean install -Ptests -DskipTests`. This example has its own Helidon SE parent,
imports the Jakarta BOM, and can then be built outside the extension reactor.

Build, test, and run the module-path smoke check:

```shell
mvn clean verify
```

Run the built example again on Linux or macOS:

```shell
java --module-path target/classes:target/libs --module io.helidon.extensions.jakarta.examples.dbclientjsonp/io.helidon.extensions.jakarta.examples.dbclientjsonp.Main
```

On Windows replace the module-path separator `:` with `;`.
The example exits after printing the successful response. It binds no fixed port.

The runtime dependency tree is written to `target/runtime-dependencies.txt`.
The independent-consumer suite also checks this tree to reject deprecated core integrations,
other Jakarta integration modules, persistence, and transaction integrations.
