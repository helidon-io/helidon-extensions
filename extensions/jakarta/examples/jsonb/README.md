# Jakarta jsonb example

Registers `JsonbSupport` with Helidon WebServer and WebClient to exchange a `Greeting` Java object as JSON. The server echoes the object; the client prints its message, and the server stops. The example uses a dynamic loopback port.

Requires JDK 27 and Maven, with `JAVA_HOME` set to that JDK and its `bin` directory on `PATH`.
The Helidon 28 and Jakarta extension artifacts must be available. For a source checkout, follow the
[development build prerequisites](../../docs/README.md#development-build), then install the Jakarta release unit
from `extensions/jakarta` with `mvn clean install -Ptests -DskipTests`. This example has its own Helidon SE parent,
imports the Jakarta BOM, and can then be built outside the extension reactor.

Build and test the example:

```shell
mvn clean verify
```

Run the built example on Linux or macOS:

```shell
java --module-path target/classes:target/libs --module io.helidon.extensions.jakarta.examples.jsonb/io.helidon.extensions.jakarta.examples.jsonb.Main
```

On Windows replace the module-path separator `:` with `;`.
The example exits after printing the successful response. It binds no fixed port.

Inspect the runtime dependencies with `mvn dependency:tree -Dscope=runtime`.
