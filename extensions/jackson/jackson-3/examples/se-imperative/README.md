# Jackson 3 SE imperative example

Requires JDK 27 and locally installed Helidon 28.0.0-SNAPSHOT and Jackson 3 extension 1.0.0-SNAPSHOT artifacts.
This project builds independently from the extension reactor with the `helidon-se` application parent and its own extension BOM import.

```shell
mvn package
java -jar target/helidon-extensions-jackson-jackson-3-examples-se-imperative.jar
```

In another terminal:

```shell
curl -i -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' http://localhost:8080/echo -d '{"message":"Hello Jackson 3"}'
```

The response is HTTP 200 with JSON containing `"message":"Hello Jackson 3"`.
Stop the server with Ctrl+C. `mvn test` checks a JavaBean client/server round trip on an ephemeral test port.

`Greeting` is an ordinary JavaBean serialized and deserialized by Jackson 3.
The server explicitly disables discovery and registers `JacksonSupport`; the test client discovers the extension.
