# JTA and Narayana transactions

In Helidon 28, the Jakarta extension provides the JTA integration and its Narayana provider.
Keep using the core `io.helidon.transaction.Tx` API, the `jta` transaction type, and your existing Narayana configuration.

## Migration mappings

Import the [Jakarta extension BOM](README.md#modules-and-dependencies) alongside your Helidon dependency management.
If you use Narayana, declare the replacement Narayana dependency. It brings in the JTA integration.
If you supply your own provider, declare the replacement JTA dependency.

| Old coordinates | Replacement coordinates |
| --- | --- |
| `io.helidon.transaction:helidon-transaction-jta` | `io.helidon.extensions.jakarta:helidon-extensions-jakarta-transaction-jta` |
| `io.helidon.transaction:helidon-transaction-narayana` | `io.helidon.extensions.jakarta:helidon-extensions-jakarta-transaction-narayana` |

Run `mvn dependency:tree -Dscope=runtime` to check whether another dependency still brings in either old artifact.
Exclude it so Helidon discovers only the extension implementation.

Jakarta Transactions API imports stay in `jakarta.transaction`. If your application uses those types directly,
declare `jakarta.transaction:jakarta.transaction-api`. For code that uses Helidon's integration types, update these imports.

| Old integration type or package | Replacement |
| --- | --- |
| `io.helidon.transaction.jta.JtaProvider` | `io.helidon.extensions.jakarta.transaction.jta.JtaProvider` |
| `io.helidon.transaction.jta.TransactionManagerSupplier` | `io.helidon.extensions.jakarta.transaction.jta.TransactionManagerSupplier` |
| `io.helidon.transaction.jta.TransactionSynchronizationRegistrySupplier` | `io.helidon.extensions.jakarta.transaction.jta.TransactionSynchronizationRegistrySupplier` |
| `io.helidon.transaction.narayana` | `io.helidon.extensions.jakarta.transaction.narayana` |

The provider and supplier types are internal APIs. If you implement `JtaProvider`, recompile your provider
with the updated import to regenerate its Helidon Service Registry metadata.

For a named Java module, update the corresponding `requires` directive in `module-info.java`.

| Old JPMS module | Replacement JPMS module |
| --- | --- |
| `io.helidon.transaction.jta` | `io.helidon.extensions.jakarta.transaction.jta` |
| `io.helidon.transaction.helidon.narayana` | `io.helidon.extensions.jakarta.transaction.narayana` |

Existing JNDI lookups continue to use `java:comp/TransactionManager` and
`java:comp/TransactionSynchronizationRegistry`. If you use Narayana's optional Infinispan transaction store,
continue to supply its libraries separately.

The [Narayana example](../examples/transaction-narayana/README.md) includes a complete POM and module descriptor.
Run it on the classpath or module path to see a successful commit and a rollback after an application exception.

The modules were migrated from Helidon core `transaction/jta` and `transaction/narayana` at `ecc7e16e3e37b48c3aca684126eee982ecc8d51b`.
