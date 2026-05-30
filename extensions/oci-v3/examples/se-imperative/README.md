# OCI SDK v3 SE Imperative Example

This example shows how to use the OCI SDK v3 extension from a Helidon SE WebServer built using imperative APIs.
The application obtains `BasicAuthenticationDetailsProvider` and, when configured, `Region` from Helidon Service Registry,
then builds an OCI Object Storage client directly from the OCI Java SDK.

## Prerequisites

- An OCI tenancy with Object Storage access.
- A configured OCI authentication method, such as `~/.oci/config`, or an edited `src/main/resources/oci-config.yaml`.

## Build and Run

```shell
mvn clean package
java -jar target/helidon-extensions-oci-v3-examples-se-imperative.jar
```

The application starts on localhost port `8080`.

```shell
curl http://localhost:8080/objectstorage/namespace
```

The response is the Object Storage namespace visible to the configured OCI principal.
