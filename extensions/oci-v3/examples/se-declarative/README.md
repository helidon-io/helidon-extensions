# OCI SDK v3 SE Declarative Example

This example shows how to use the OCI SDK v3 extension from a Helidon SE WebServer built using declarative APIs.
The application injects OCI authentication and region services from Helidon Service Registry, provides an OCI Object
Storage client as an application service, and injects that client into a declarative HTTP endpoint.

## Prerequisites

- An OCI tenancy with Object Storage access.
- A configured OCI authentication method, such as `~/.oci/config`, or an edited `src/main/resources/oci-config.yaml`.

## Build and Run

```shell
mvn clean package
java -jar target/helidon-extensions-oci-v3-examples-se-declarative.jar
```

The application starts on localhost port `8080`.

```shell
curl http://localhost:8080/objectstorage/namespace
```

The response is the Object Storage namespace visible to the configured OCI principal.
