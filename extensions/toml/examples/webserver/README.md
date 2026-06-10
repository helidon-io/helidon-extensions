TOML WebServer Example
---

This example shows a Helidon SE WebServer configured from `application.toml`.
The TOML file configures `server.host` and `server.port`, and the application
uses the TOML Config parser extension to load it.

# Running as jar

Build this application:

```shell
mvn clean package
```

Run from the command line:

```shell
java -jar target/helidon-extensions-toml-examples-webserver.jar
```

Expected output should be similar to the following:

```text
2026.06.09 12:00:00.000 INFO Logging at runtime configured using classpath: /logging.properties
2026.06.09 12:00:00.250 INFO [0x12345678] http://localhost:8080 bound for socket '@default'
Server started on: http://localhost:8080/hello
```

# Configuration

The WebServer host and port are configured in `application.toml`:

```toml
[server]
host = "localhost"
port = 8080
```

# Exercising the application

Request the greeting:

```shell
curl -i http://localhost:8080/hello
```

Expected response:

```text
HTTP/1.1 200 OK
Content-Type: text/plain

Hello from TOML
```
