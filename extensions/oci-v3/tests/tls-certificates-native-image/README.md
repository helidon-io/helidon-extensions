# OCI TLS certificate native-image regression

This application validates matching RSA and EC certificate/private-key pairs and rejects a mismatched pair through the
public downloader SPI. It uses the TLS module's test fixtures and does not contact OCI.

The native-image profile initializes the default downloader and SPI at image build time. This catches a reintroduction
of eager `SecureRandom` state into certificate key-pair validation, while running the resulting executable checks that
validation works at runtime.

Run JVM validation from the repository root:

```shell
mvn -Ptests -pl extensions/oci-v3/tests/tls-certificates-native-image -am verify
```

With a GraalVM JDK compatible with the Helidon version in use, build and run the native executable:

```shell
mvn -Ptests,native-image -pl extensions/oci-v3/tests/tls-certificates-native-image -am verify
```
