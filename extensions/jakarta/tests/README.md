# Jakarta integration tests

The `tests` profile includes this aggregator, following the shared extension layout.
There are currently no separate integration-test modules. JSON-P, JSON-B, and DBClient JSON-P
unit tests reside in their production modules under `src/test/java`.

The [examples](../examples/README.md) include application tests and module-path launches.
Enable the `examples` profile to build and test them with the release unit.

Use JDK 27 with Maven: set `JAVA_HOME` to that JDK and put its `bin` directory on `PATH`.
Follow the [development build prerequisites](../docs/README.md#development-build), then run
from the Jakarta release root:

```shell
mvn clean install -Ptests,examples
```
