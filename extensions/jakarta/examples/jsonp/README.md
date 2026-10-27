# Jakarta jsonp example

Starts a loopback server on a dynamic port, POSTs a JSON-P object using Helidon WebClient, prints the response, and stops. Tests exercise custom reader and writer factories on both sides of the connection.

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
java --module-path target/classes:target/libs --module io.helidon.extensions.jakarta.examples.jsonp/io.helidon.extensions.jakarta.examples.jsonp.Main
```

On Windows replace the module-path separator `:` with `;`.
The example exits after printing the successful response. It binds no fixed port.

Inspect the runtime dependencies with `mvn dependency:tree -Dscope=runtime`.
