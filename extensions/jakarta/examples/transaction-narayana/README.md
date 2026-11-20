# Jakarta Narayana transaction example

The application discovers Narayana through the Helidon service registry and runs transactions using the core `Tx` API.
It prints `committed` after a successful transaction and `rolled back` after an application failure, using Jakarta
`TransactionSynchronizationRegistry` completion callbacks. The transaction implementation type remains `jta`.

Requires JDK 27 and Maven, with `JAVA_HOME` set to that JDK and its `bin` directory on `PATH`.
Follow the [development build prerequisites](../../docs/README.md#development-build), then install the Jakarta release unit
from `extensions/jakarta` with `mvn clean install -Ptests -DskipTests`. This example uses its own Helidon 28 application
parent and imports the Jakarta EE 11 API BOM and Jakarta extension BOM. It can then build independently of the extension
reactor.

Build and test the example with `mvn clean verify` from this directory.

Run the built example on Linux or macOS with the module path.

```shell
java --module-path target/classes:target/libs --module io.helidon.extensions.jakarta.examples.transaction.narayana/io.helidon.extensions.jakarta.examples.transaction.narayana.Main
```

Alternatively, run it on the classpath.

```shell
java -cp 'target/classes:target/libs/*' io.helidon.extensions.jakarta.examples.transaction.narayana.Main
```

On Windows replace the path separator `:` with `;`. The example exits after the two transactions complete.
It requires no external database or container and demonstrates in-process transaction completion. Database enlistment,
crash recovery, and distributed transactions require additional provider-specific setup.

Inspect runtime dependencies with `mvn dependency:tree -Dscope=runtime`. Selecting the Narayana module brings its sibling
JTA integration and the Narayana implementation, including its Jackson dependencies. The deprecated
`io.helidon.transaction:helidon-transaction-jta` and `helidon-transaction-narayana` artifacts and unrelated Jakarta JSON
and persistence integration modules must be absent. The core `io.helidon.transaction:helidon-transaction` API remains
required. The example's `module-info.java` names the new extension module.
