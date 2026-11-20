# JTA and Narayana transactions

The Jakarta extension connects the Helidon Transaction API to Jakarta Transactions 2.0 and supplies a Narayana provider.
Both modules share the Jakarta extension version, BOM, tests, examples, and release cycle. They support Helidon 28 and
use the repository's Java 27 compiler baseline.

## Dependencies

Import `io.helidon.extensions.jakarta:helidon-extensions-jakarta-bom:1.0.0-SNAPSHOT` alongside the Helidon 28 dependency
management, as shown in the [shared dependency setup](README.md#modules-and-dependencies). Select Narayana with this
dependency, whose JTA sibling is included transitively.

```xml
<dependency>
    <groupId>io.helidon.extensions.jakarta</groupId>
    <artifactId>helidon-extensions-jakarta-transaction-narayana</artifactId>
</dependency>
```

An application supplying its own provider selects `helidon-extensions-jakarta-transaction-jta` and supplies an extension
`JtaProvider` service. Applications using Jakarta Transactions API types directly should declare
`jakarta.transaction:jakarta.transaction-api` explicitly and manage its version through their Jakarta specification BOM
or a direct version declaration. The independently buildable extension release root pins that API to `2.0.1` and
`org.jboss.narayana.jta:narayana-jta` to `7.3.4.Final`.

Both modules declare the Transactions API with Maven compile scope, preserving its availability to runtime consumers.
The JTA module uses CDI only with `provided` scope for compilation. The Narayana module declares its JTA sibling and
Narayana implementation with compile scope.

The dependency roles are recorded below. Maven compile dependencies are available transitively at runtime. Provided
dependencies support compiling the integration, while test dependencies support the module's tests.

| Dependency | Resolved version | JTA integration | Narayana integration |
| --- | --- | --- | --- |
| `jakarta.transaction:jakarta.transaction-api` | `2.0.1` | Compile | Compile |
| `jakarta.enterprise:jakarta.enterprise.cdi-api` and `jakarta.enterprise.lang-model` | `4.1.0` | Provided | Compile through Narayana |
| `jakarta.annotation:jakarta.annotation-api` | `3.0.0` | Provided through CDI | Compile for downstream module-path resolution |
| `jakarta.el:jakarta.el-api` | `6.0.1` | Provided through CDI | Compile through Narayana |
| `jakarta.interceptor:jakarta.interceptor-api` | `2.2.0` | Provided through CDI | Compile through Narayana |
| `jakarta.inject:jakarta.inject-api` | `2.0.1` | Provided through CDI | Compile through Narayana |
| `jakarta.resource:jakarta.resource-api` | `2.1.0` | None | Compile through Narayana |
| `org.jboss:jboss-transaction-spi` | `8.0.0.Final` | None | Compile through Narayana |
| `com.fasterxml.jackson.core:jackson-core` | `2.21.8` | None | Compile through Narayana |
| `org.jboss.logging:jboss-logging` | `3.6.3.Final` | None | Compile for provider logging and module-path resolution |

JAXB and Activation are absent from these modules' resolved dependency graphs. Narayana's other implementation
dependencies, including its reactive-streams and transaction-store support libraries, retain their compile scope.
The extension integration module descriptors require the Jakarta Transactions API and their actual integration
siblings. The Narayana module resolves `narayana.jta`, `org.jboss.logging`, and the JDK's `java.sql` module,
which Narayana uses when initializing its transaction store. Ancillary APIs resolve through their own module descriptors.

Selecting either transaction integration does not add the Jakarta JSON or persistence integration modules. Narayana's
implementation dependencies, including its Jackson dependency, remain part of its dependency graph.

The [independent transaction example](../examples/transaction-narayana/README.md) contains a complete consumer POM,
classpath and module-path launch instructions, and transaction completion and rollback checks. It uses installed or
published dependencies and can be copied outside this repository.

## Migration mappings

Replace each deprecated core integration dependency with its extension counterpart. Remove deprecated integrations
introduced transitively as well, so service discovery resolves one JTA integration and one selected implementation.

| Old coordinates | Replacement coordinates |
| --- | --- |
| `io.helidon.transaction:helidon-transaction-jta` | `io.helidon.extensions.jakarta:helidon-extensions-jakarta-transaction-jta` |
| `io.helidon.transaction:helidon-transaction-narayana` | `io.helidon.extensions.jakarta:helidon-extensions-jakarta-transaction-narayana` |

The core `io.helidon.transaction:helidon-transaction` dependency and `io.helidon.transaction.Tx` API remain the entry point
for transaction execution and annotations. Jakarta API types retain their `jakarta.transaction` package.

| Old integration type or package | Replacement |
| --- | --- |
| `io.helidon.transaction.jta.JtaProvider` | `io.helidon.extensions.jakarta.transaction.jta.JtaProvider` |
| `io.helidon.transaction.jta.TransactionManagerSupplier` | `io.helidon.extensions.jakarta.transaction.jta.TransactionManagerSupplier` |
| `io.helidon.transaction.jta.TransactionSynchronizationRegistrySupplier` | `io.helidon.extensions.jakarta.transaction.jta.TransactionSynchronizationRegistrySupplier` |
| `io.helidon.transaction.narayana` | `io.helidon.extensions.jakarta.transaction.narayana` |

The provider and supplier contracts are internal integration APIs. Custom providers that implement the former
`JtaProvider` must update their import and recompile their service metadata against the extension contract. The Narayana
provider implementation is package-private and is discovered through the Helidon Service Registry.

Update module descriptors when using JPMS.

| Old JPMS module | Replacement JPMS module |
| --- | --- |
| `io.helidon.transaction.jta` | `io.helidon.extensions.jakarta.transaction.jta` |
| `io.helidon.transaction.helidon.narayana` | `io.helidon.extensions.jakarta.transaction.narayana` |

An application can declare the selected provider and its directly used APIs explicitly.

```java
module example.transactions {
    requires io.helidon.transaction;
    requires io.helidon.extensions.jakarta.transaction.narayana;
    requires jakarta.transaction;
}
```

Keep the selected provider resolved on the module path. The annotation processor generates Service Registry descriptors
and discovery metadata under the extension namespace. Applications using the imperative `Tx` API need no new annotation
processor. Applications using Helidon's transaction annotations retain their core transaction code generation setup.

## Transaction behavior and configuration

The transaction support type remains `jta`. The core `Tx` API retains `MANDATORY`, `NEW`, `NEVER`, `REQUIRED`, `SUPPORTED`,
and `UNSUPPORTED` behavior. Successful work in a new transaction commits. An exception from its task causes rollback,
while an exception from a task participating in an existing transaction marks that transaction for rollback.
A suspended transaction is resumed when the nested operation completes.
Transaction lifecycle listeners continue to receive the corresponding begin, completion, suspend, and resume events.

Service Registry lookups for `jakarta.transaction.TransactionManager` and
`jakarta.transaction.TransactionSynchronizationRegistry` retain these JNDI supplier names.

| Supplied type | Service name |
| --- | --- |
| `jakarta.transaction.TransactionManager` | `helidon:jndi/TransactionManager` |
| `jakarta.transaction.TransactionSynchronizationRegistry` | `helidon:jndi/TransactionSynchronizationRegistry` |

The optional core `helidon-service-jndi` integration retains the corresponding JNDI lookups. Narayana obtains its
transaction manager, user transaction, and synchronization registry through its existing `JTAEnvironmentBean`.
Existing Narayana property and object-store configuration continues to apply.

Narayana uses virtual threads for asynchronous two-phase processing on Java 21 and later. Asynchronous processing
remains disabled by default. The extension preserves the existing exclusion of `infinispan-core` and
`infinispan-commons` because their service metadata prevents module-path loading. Applications configuring Narayana's
optional Infinispan transaction store must supply those libraries and a compatible deployment.

These two integration modules define no Helidon configuration blueprint or configuration metadata. Their migration
therefore adds no generated configuration page. The shared [configuration reference](config/config_reference.md)
continues to document the extension's configuration-backed integrations.

When checking a migrated application, use `mvn dependency:tree -Dscope=runtime` to confirm the selected extension and its
required sibling are present, and the deprecated `helidon-transaction-jta` and `helidon-transaction-narayana` runtimes are
absent. Inspect the module path as well when using JPMS. Include the dependencies required by the selected Narayana
configuration, such as a durable transaction store or database driver.

## Source and development build

The transaction integrations were migrated from core revision `ecc7e16e3e37b48c3aca684126eee982ecc8d51b`, including
`transaction/jta`, `transaction/narayana`, their module declarations, service registrations, resources, and tests.
The package names and artifact coordinates move into the shared Jakarta extension.

From the Jakarta release root, build with the [shared development prerequisites](README.md#development-build).

```shell
mvn clean install -Ptests,examples,docs
```

The ported module tests exercise provider lookup and transaction behavior. The independent example demonstrates
completion and failure with transaction synchronization callbacks. The optional
[MySQL transaction integration suites](../tests/README.md#mysql-transaction-integration) exercise the original
Hibernate and EclipseLink transaction-method and annotation fixtures through the extension transaction implementation.
Enable their explicit `transaction-mysql` profile when the required database containers and core test fixtures are
available. The test-only persistence integration remains a core dependency until its separate migration.
