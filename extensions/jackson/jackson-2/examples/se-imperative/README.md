# Jackson 2 SE imperative example

Requires JDK 27 and locally installed Helidon 28.0.0-SNAPSHOT and Jackson 2 extension 1.0.0-SNAPSHOT artifacts.
This project builds independently from the extension reactor with the `helidon-se` application parent and its own extension BOM import.

```shell
mvn package
java -jar target/helidon-extensions-jackson-jackson-2-examples-se-imperative.jar
```

In another terminal:

```shell
curl -i -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' http://localhost:8080/echo -d '{"message":"Hello Jackson 2"}'
```

The response is HTTP 200 with JSON containing `"message":"Hello Jackson 2"`.
Stop the server with Ctrl+C. `mvn test` checks a generated-model client/server round trip on an ephemeral test port.

`GreetingBlueprint` uses the Jackson 2 builder extension to generate Jackson serialization and deserialization annotations.
The compiler processor path contains the Helidon APT bundle and only the Jackson 2 extension processor.
The server explicitly disables discovery and registers `JacksonSupport`; the test client discovers the extension.
