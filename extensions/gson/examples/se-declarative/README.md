Gson Media Support SE Declarative Example
---

This example shows how to use `helidon-extensions-gson-media` with a Helidon SE WebServer built using declarative APIs.
The server reads JSON request entities into Java objects and sends Java objects back as JSON using Gson.

Unlike the imperative sample, this declarative version does not manually configure media support.
Because `helidon-extensions-gson-media` is on the classpath, Gson media support is discovered automatically.

The example also includes a small `gson.properties` section in `application.yaml`.
It enables `serialize-nulls`, so the `punctuation` field is rendered even when the application has not set a value yet.

# Running as jar

Build this application:

```shell
mvn clean package
```

Run from the command line:

```shell
java -jar target/helidon-extensions-gson-examples-se-declarative.jar
```

Expected output should be similar to the following:

```text
2026.04.24 12:00:00.000 INFO Logging at runtime configured using classpath: /logging.properties
2026.04.24 12:00:00.250 INFO [0x12345678] http://0.0.0.0:8080 bound for socket '@default'
Server started on: http://localhost:8080/hello
```

# Configuration

The example uses this Gson configuration:

```yaml
gson:
  properties:
    serialize-nulls: true
```

# Exercising the application

Request the default greeting:

```shell
curl -i -H "Accept: application/json" http://localhost:8080/hello
```

Expected response:

```text
HTTP/1.1 200 OK
Content-Type: application/json

{"greeting":"Hello","name":"World","punctuation":null}
```

Update the greeting using a JSON request body:

```shell
curl -i -X POST \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  http://localhost:8080/hello \
  -d '{"greeting":"Ahoj","punctuation":"!"}'
```

Expected response:

```text
HTTP/1.1 200 OK
Content-Type: application/json

{"greeting":"Ahoj","name":"World","punctuation":"!"}
```

Request a named greeting:

```shell
curl -i -H "Accept: application/json" http://localhost:8080/hello/Reader
```

Expected response:

```text
HTTP/1.1 200 OK
Content-Type: application/json

{"greeting":"Ahoj","name":"Reader","punctuation":"!"}
```
