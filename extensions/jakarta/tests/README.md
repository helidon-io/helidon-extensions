# Jakarta integration tests

The `tests` profile includes this aggregator, following the shared extension layout.
JSON-P, JSON-B, and DBClient JSON-P
unit tests reside in their production modules under `src/test/java`.

The [examples](../examples/README.md) include application tests and module-path launches.
Enable the `examples` profile to build and test them with the release unit.

Use JDK 27 with Maven: set `JAVA_HOME` to that JDK and put its `bin` directory on `PATH`.
Follow the [development build prerequisites](../docs/README.md#development-build), then run
from the Jakarta release root:

```shell
mvn clean install -Ptests,examples
```

## MySQL transaction integration

The opt-in `transaction-mysql` profile adds Hibernate and EclipseLink suites ported from
core revision `ecc7e16e3e37b48c3aca684126eee982ecc8d51b`. Each suite runs the original
transaction-method and transaction-annotation tests against MySQL, using the extension
JTA and Narayana modules. This covers transaction propagation, persistence after commit,
and failure behavior through the repository API.

The suites reuse core's test-only data model, generated repositories, and common assertions.
Those fixtures require the core Jakarta persistence runtime pending its separate migration.
The deprecated core JTA and Narayana implementations are absent from these test dependencies.
Transaction consumers and the independent transaction example have no persistence dependency.

With an available Docker runtime and the core test fixtures installed, run from the Jakarta root:

```shell
mvn clean verify -Ptests,transaction-mysql
```

The profile is explicit because these tests start MySQL containers. Tests use the same pinned
MySQL image as the recorded core revision. Check the test reports for skipped tests when Docker
is unavailable.
