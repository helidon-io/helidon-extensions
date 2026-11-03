# Jakarta dbclient-jsonp example

Uses DBClient with an embedded H2 database to insert and query a person. The JSON-P extension maps a `JsonObject` to named SQL parameters through `DbMapperManager.toNamedParameters(person, JsonObject.class)` and maps the selected row back to JSON through `as(JsonObject.class)`.

The database exists only in memory and closes with the DBClient. No external database or container is needed. H2 and the JDBC connection pool are dependencies of this example only.

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
java --module-path target/classes:target/libs --module io.helidon.extensions.jakarta.examples.dbclientjsonp/io.helidon.extensions.jakarta.examples.dbclientjsonp.Main
```

On Windows replace the module-path separator `:` with `;`.
The example prints the selected person as JSON and exits.

Inspect the runtime dependencies with `mvn dependency:tree -Dscope=runtime`.
