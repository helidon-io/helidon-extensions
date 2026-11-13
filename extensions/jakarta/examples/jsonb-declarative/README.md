# Jakarta JSON-B declarative WebServer example

This application uses Helidon Declarative to serve JSON greetings. `HelloWorldEndpoint` declares the HTTP routes,
`GreetingDto` defines their JSON-B representation, and `Main` starts the generated application binding.
The Jakarta JSON-B extension is discovered automatically.

The example is adapted from
[the Helidon declarative JSON-B greeting example](https://github.com/helidon-io/helidon-examples/tree/19c72af29e87be037b063ca2ba176b348c53818c/examples/declarative/webserver/hello-world-jsonb),
copied at revision `19c72af29e87be037b063ca2ba176b348c53818c`.

## Build and run

Use JDK 27 with Maven: set `JAVA_HOME` to that JDK and put its `bin` directory on `PATH`.
Follow the [development build prerequisites](../../docs/README.md#development-build) to install the required
Helidon 28 and Jakarta extension snapshots. Then run these commands from this example directory:

```shell
mvn clean package
java -jar target/helidon-extensions-jakarta-examples-jsonb-declarative.jar
```

The server listens on port 8080 and keeps running until you stop it with Ctrl-C.
The initial greeting and server settings are in `src/main/resources/application.yaml`.

## Try the endpoints

In another terminal, request the default greeting:

```shell
curl http://localhost:8080/hello
```

```json
{"greeting":"Hello","name":"World"}
```

Supply a name in the path:

```shell
curl http://localhost:8080/hello/Reader
```

```json
{"greeting":"Hello","name":"Reader"}
```

Change the greeting using a plain-text request. The update returns HTTP 204.

```shell
curl -i -X POST -H 'Content-Type: text/plain' --data 'Hola' http://localhost:8080/hello
curl http://localhost:8080/hello/Reader
```

```json
{"greeting":"Hola","name":"Reader"}
```

## Run on the module path

The same application can also be launched as a named module:

```shell
java --module-path target/helidon-extensions-jakarta-examples-jsonb-declarative.jar:target/libs --module io.helidon.extensions.jakarta.examples.declarative/io.helidon.extensions.jakarta.examples.jsonb.Main
```

Use `;` instead of `:` as the module-path separator on Windows.
