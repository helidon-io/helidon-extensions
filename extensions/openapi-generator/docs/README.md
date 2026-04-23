<!--

    Copyright (c) 2026 Oracle and/or its affiliates.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

-->

# Helidon SE Declarative OpenAPI Generator

An [openapi-generator](https://openapi-generator.tech) SPI plugin that generates
Helidon SE declarative server code from an OpenAPI 3 specification.

The upstream `JavaHelidonServerCodegen` targets the older imperative `HttpService`
style. This generator targets the newer annotation-based model built around
`@RestServer.Endpoint`, `@Http.GET`, and related declarative APIs.

## Repository Layout

```text
openapi-generator/
├── bom/                                    BOM for published artifacts
├── docs/                                   User and architecture docs
├── modules/
│   └── openapi-generator/                  Generator implementation module
└── pom.xml                                 Top-level project POM
```

The main implementation lives in
`openapi-generator/modules/openapi-generator`.

## Build

From the repo root:

```bash
mvn -pl openapi-generator/modules/openapi-generator package
```

This produces:

```text
openapi-generator/modules/openapi-generator/target/helidon-extensions-openapi-generator-4.0.0-SNAPSHOT.jar
```

The module is packaged as a thin jar. Runtime dependencies are copied to
`target/libs`, and the jar manifest points to them.

In the examples below, `4.0.0-SNAPSHOT` is the version of this extension artifact.
The separate `helidonVersion` option controls which Helidon version is written
into generated Maven and Gradle projects, and its current default is `4.4.1`.

## Maven Plugin Usage

Add the generator as a dependency of `openapi-generator-maven-plugin`:

```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <version>7.11.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <generatorName>helidon-declarative</generatorName>
                <inputSpec>${project.basedir}/src/main/resources/openapi.yaml</inputSpec>
                <output>${project.build.directory}/generated-sources/openapi</output>
                <configOptions>
                    <helidonVersion>4.4.1</helidonVersion>
                    <apiPackage>com.example.api</apiPackage>
                    <modelPackage>com.example.model</modelPackage>
                    <invokerPackage>com.example</invokerPackage>
                </configOptions>
            </configuration>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.openapi-generator</groupId>
            <artifactId>helidon-extensions-openapi-generator</artifactId>
            <version>4.0.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</plugin>
```

Then run:

```bash
mvn generate-sources
```

In that example:

- `4.0.0-SNAPSHOT` is the version of `helidon-extensions-openapi-generator`
- `4.4.1` is the Helidon version used in the generated project

## CLI Usage

The module also exposes a minimal Java CLI:

```bash
java -jar openapi-generator/modules/openapi-generator/target/helidon-extensions-openapi-generator-4.0.0-SNAPSHOT.jar \
  generate \
  -g helidon-declarative \
  -i /path/to/openapi.yaml \
  -o /path/to/output \
  --additional-properties helidonVersion=4.4.1,apiPackage=com.example.api,modelPackage=com.example.model,invokerPackage=com.example
```

Here again, the jar version (`4.0.0-SNAPSHOT`) is the generator version, while
`helidonVersion=4.4.1` controls the generated project.

Example with the optional-list flag enabled:

```bash
java -jar openapi-generator/modules/openapi-generator/target/helidon-extensions-openapi-generator-4.0.0-SNAPSHOT.jar \
  generate \
  -g helidon-declarative \
  -i /path/to/openapi.yaml \
  -o /tmp/generated-openapi \
  --additional-properties helidonVersion=4.4.1,avoidOptionalListParams=true
```

## Generator Options

Set these under Maven `<configOptions>` or CLI `--additional-properties`.

| Option | Default | Description |
|--------|---------|-------------|
| `helidonVersion` | `4.4.1` | Helidon version written into generated Maven and Gradle builds |
| `apiPackage` | `io.helidon.example.api` | Package for generated API, endpoint, client, and error classes |
| `modelPackage` | `io.helidon.example.model` | Package for generated model classes |
| `invokerPackage` | `io.helidon.example` | Package for generated `Main.java` |
| `generateClient` | `true` | Generate `{Tag}Client.java` declarative REST client interfaces |
| `generateErrorHandler` | `true` | Generate `{Tag}Exception.java` and `{Tag}ErrorHandler.java` |
| `serverOpenApi` | `true` | Copy the OpenAPI document into `META-INF/openapi.yaml` and add server-side OpenAPI support |
| `serverBasePath` | derived from spec | Base path prefix prepended to generated endpoint paths |
| `corsEnabled` | `false` | Add `@Cors.Defaults` to generated endpoint classes |
| `ftEnabled` | `false` | Add `@Ft.Retry` to generated REST client interfaces |
| `tracingEnabled` | `false` | Add `@Tracing.Traced` to generated endpoint classes |
| `metricsEnabled` | `false` | Add `@Metrics.Timed` to generated endpoint methods |
| `avoidOptionalListParams` | `false` | Generate `List<T>` instead of `Optional<List<T>>` for optional query list params |

Legacy aliases `serveOpenApi` and `serveBasePath` are still accepted for compatibility.

## What Gets Generated

Per OpenAPI tag:

| File | Description |
|------|-------------|
| `{Tag}Api.java` | Shared HTTP contract interface with `@Http.Path` and method annotations |
| `{Tag}Endpoint.java` | `@RestServer.Endpoint` implementation stub |
| `{Tag}Client.java` | `@RestClient.Endpoint` declarative client, if `generateClient=true` |
| `{Tag}Exception.java` | Runtime exception type, if `generateErrorHandler=true` |
| `{Tag}ErrorHandler.java` | Error handler implementation, if `generateErrorHandler=true` |
| `{Tag}EndpointTest.java` | Generated unit test |

Per schema:

| File | Description |
|------|-------------|
| `{Model}.java` | Helidon JSON model type with generated validation, enum support, and composed-schema handling |

Supporting files:

| File | Description |
|------|-------------|
| `pom.xml` | Generated Maven build |
| `build.gradle` | Generated Gradle build |
| `settings.gradle` | Generated Gradle settings |
| `Main.java` | Generated application entry point |
| `src/main/resources/application.yaml` | Runtime configuration |
| `src/test/resources/application-test.yaml` | Test configuration |
| `src/main/resources/logging.properties` | JUL logging configuration |
| `src/main/resources/META-INF/openapi.yaml` | Copied spec, if `serverOpenApi=true` |

## OpenAPI Mapping Notes

### Composed Schemas

The generator supports OpenAPI composed schemas:

- `allOf` generates a single POJO with merged properties and combined required fields
- `oneOf` generates a wrapper model with generated typed accessors, factory methods, and exact-one validation
- `anyOf` generates a wrapper model with generated typed accessors, factory methods, and at-least-one validation

For `oneOf` and `anyOf`, the generated model stores the raw JSON payload and uses a generated
Helidon `@Json.Converter` to preserve composed-schema semantics during serialization and
deserialization. This avoids flattening distinct variants into a single permissive POJO.

### Parameters

| OpenAPI | Generated |
|---------|-----------|
| required path param | `@Http.PathParam("name") Type name` |
| required query param | `@Http.QueryParam("name") Type name` |
| optional query param | `@Http.QueryParam("name") Optional<Type> name` |
| optional query list param with `avoidOptionalListParams=false` | `@Http.QueryParam("name") Optional<List<T>> name` |
| optional query list param with `avoidOptionalListParams=true` | `@Http.QueryParam("name") List<T> name` |
| header param | `@Http.HeaderParam("Name") Type name` |
| JSON request body | `@Http.Entity Type body` |
| form-urlencoded body | `@Http.Entity Parameters formBody` |
| multipart body | `@Http.Entity ReadableEntity formBody` |

### Validation

The generator currently emits Helidon validation annotations for:

- string length and pattern
- numeric minimum and maximum
- exclusive integer and long bounds via adjusted inclusive bounds
- collection size
- `multipleOf` for integer, long, and number constraints

For model classes, validation annotations are emitted on accessor methods rather than
private fields so generated projects compile cleanly with the current validation API.

## Testing

The module test suite lives under
`openapi-generator/modules/openapi-generator/src/test/java/io/helidon/openapi/generator`
and covers:

- petstore-style generation
- feature coverage and validation
- composed-schema support (`oneOf`, `anyOf`, `allOf`)
- form and multipart handling
- security
- observability options
- optional query list handling
- generated project build verification

Generated-project build checks are opt-in:

```bash
mvn -pl openapi-generator/modules/openapi-generator test \
  -Dhelidon.codegen.it.buildsWithMaven=true \
  -Dhelidon.codegen.it.buildsWithGradle=true
```

## Notes

- Generated endpoint classes do not include `@RestServer.Listener`, because the default
  listener is already implied.
- Generated sample builds do not add `slf4j-jdk14`.
- `Error` schemas are renamed to `ApiError` to avoid clashing with `java.lang.Error`.

## Architecture

See [ARCHITECTURE.md](./ARCHITECTURE.md) for implementation details.
