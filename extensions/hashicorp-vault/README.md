Vault
----

Integration with Hashicorp Vault and with vaults compatible with its API.

# Usage

To use this extension, add the following dependency management to your project (and fill the `vault.version` property with
the version you want to use):

```xml

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.hashicorp.vault</groupId>
            <artifactId>helidon-extensions-hashicorp-vault-bom</artifactId>
            <version>${vault.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Vault extension requires Helidon 4.3.2 or later.

# Design

Why a custom implementation?
The `com.bettercloud:vault-java-driver` is dependency free, though it implements its own JSON parsing and HTTP client libraries.
As we have both of these libraries as part of Helidon, such code is a risk.

This implementation is based on Java Service Loader to load implementations of the vault's SPIs

- `io.helidon.extensions.hashicorp.vault.spi.SecretsEngineProvider` - secret engines (kv1, kv2, transit, PKI)
- `io.helidon.extensions.hashicorp.vault.spi.AuthMethodProvider` - authentication methods (k8s, AppRole, token) - to provide API
  to manage such methods
- `io.helidon.extensions.hashicorp.vault.spi.SysProvider` - sys operations (enabling engines and such)
- `io.helidon.extensions.hashicorp.vault.spi.VaultAuth` - authenticating the client (k8s, AppRole, token) - internal
  implementations that allow us to connect configuration free (k8s) or with different methods (AppRole)

The most commonly used engines are supported by Helidon (and we may add additional engines in the future):

- KV v2 engine - the current versioned Key/Value pair engine
- KV v1 engine - the deprecated Key/Value pair engine
- Cubbyhole - the per token secret provider engine
- Database - for obtaining connection data to a database
- PKI - issuing certificates
- Transit - encryption, signatures, HMAC

Not all available APIs are implemented in the first release of Vault integration.

# API

The main access point is `Vault` class using a builder pattern.

Example of obtaining vault instance using a token:

```java
 Vault vault = Vault.builder()
        .address("http://localhost:8200")
        .token("s.oZZ...")
        .build();
```

# Development notes

## Services ("Beans") provided

The following things can be injected or obtained from `io.helidon.service.registry.Services` methods:

- `Vault` - the vault instance, can be used to obtain secrets and sys operations
- `CubbyholeSecrets` - cubbyhole secrets API
- `Kv1Secrets` - kv1 secrets API
- `Kv2Secrets` - kv2 secrets API
- `DatabaseSecrets` - database secrets API
- `PkiSecrets` - PKI secrets API
- `TransitSecrets` - transit secrets API
- `Sys` - sys operations API
- `AppRoleAuth` - AppRole authentication API
- `K8sAuth` - Kubernetes authentication API
- `TokenAuth` - token authentication API