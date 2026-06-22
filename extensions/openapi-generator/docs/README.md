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
The `javaVersion` option controls the generated Maven compiler source and target
values and the generated Gradle Java toolchain version, and its current default is
`21`.

For CI-style verification of generated Maven projects in this module, enable
the `it-tests` Maven profile:

```bash
mvn -pl openapi-generator/modules/openapi-generator -Pit-tests verify
```

That profile drives checked-in integration harnesses under
`openapi-generator/modules/openapi-generator/src/it/projects/test1`
using `maven-invoker-plugin`.

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
                    <javaVersion>21</javaVersion>
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
  --additional-properties helidonVersion=4.4.1,javaVersion=21,apiPackage=com.example.api,modelPackage=com.example.model,invokerPackage=com.example
```

Here again, the jar version (`4.0.0-SNAPSHOT`) is the generator version, while
`helidonVersion=4.4.1` controls the generated Helidon dependencies and
`javaVersion=21` controls the generated project Java compilation level.

Example with the optional-list flag enabled:

```bash
java -jar openapi-generator/modules/openapi-generator/target/helidon-extensions-openapi-generator-4.0.0-SNAPSHOT.jar \
  generate \
  -g helidon-declarative \
  -i /path/to/openapi.yaml \
  -o /tmp/generated-openapi \
  --additional-properties helidonVersion=4.4.1,serializationLibrary=jackson,avoidOptionalListParams=true
```

## Generator Options

Set these under Maven `<configOptions>` or CLI `--additional-properties`.

| Option | Default | Description |
|--------|---------|-------------|
| `helidonVersion` | `4.4.1` | Helidon version written into generated Maven and Gradle builds |
| `javaVersion` | `21` | Java version written into generated Maven compiler source/target and Gradle toolchain builds |
| `serializationLibrary` | `helidon` | JSON serialization library for generated models and media dependency: `helidon`, `jsonb`, or `jackson` |
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

For composed schemas, `jackson` supports discriminator-based `oneOf`/`anyOf`
models. Structural `oneOf`/`anyOf` models without a discriminator require the default
`helidon` serialization library. JSON-B is supported for regular object models,
but not for composed `oneOf`/`anyOf` models.

For example, this structural `oneOf` has no discriminator and fails generation
with `serializationLibrary=jackson`:

```yaml
components:
  schemas:
    EmailContact:
      type: object
      required:
        - email
      properties:
        email:
          type: string
    PhoneContact:
      type: object
      required:
        - phone
      properties:
        phone:
          type: string
    Contact:
      oneOf:
        - $ref: '#/components/schemas/EmailContact'
        - $ref: '#/components/schemas/PhoneContact'
```

This discriminator-based `anyOf` is supported by `helidon` and `jackson`, but
fails generation with `serializationLibrary=jsonb` because JSON-B composed
models are not supported:

```yaml
components:
  schemas:
    Cat:
      type: object
      required:
        - kind
        - whiskers
      properties:
        kind:
          type: string
        whiskers:
          type: integer
    Dog:
      type: object
      required:
        - kind
        - bark
      properties:
        kind:
          type: string
        bark:
          type: boolean
    Pet:
      anyOf:
        - $ref: '#/components/schemas/Cat'
        - $ref: '#/components/schemas/Dog'
      discriminator:
        propertyName: kind
        mapping:
          cat: '#/components/schemas/Cat'
          dog: '#/components/schemas/Dog'
```

### Serialization Libraries

The `serializationLibrary` option controls both generated model annotations and
the JSON media dependency in generated Maven and Gradle builds.

| Value | Generated model annotations | Generated media dependency | Composed schema support |
|-------|-----------------------------|----------------------------|-------------------------|
| `helidon` | `io.helidon.json.binding.Json` annotations and generated converters | `io.helidon.http.media:helidon-http-media-json-binding` | `allOf`, discriminator `oneOf`/`anyOf`, and structural `oneOf`/`anyOf` |
| `jsonb` | JSON-B annotations with field visibility for generated fluent model methods | `io.helidon.http.media:helidon-http-media-jsonb` | regular object models only; composed `oneOf`/`anyOf` fails generation |
| `jackson` | Jackson annotations, using existing discriminator properties for polymorphism | `io.helidon.http.media:helidon-http-media-jackson` | `allOf` and discriminator-based `oneOf`/`anyOf`; structural `oneOf`/`anyOf` fails generation |

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
| `{Model}.java` | JSON model type using the selected serialization library, with generated builders, validation, and enum support |

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

The generator supports these schema-composition keywords:

- `allOf`: generates an inherited model when there is a single referenced parent
  component; otherwise falls back to a flattened merged model. When the parent
  schema declares a discriminator, the generator also emits library-specific
  polymorphism metadata on the base model and initializes discriminator values
  for `allOf` subtypes that provide `x-discriminator-value`
- `oneOf`: generates a Java interface for the composed schema, attaches a
  generated Helidon JSON converter when `serializationLibrary=helidon`, makes
  member models implement it, and requires exactly one matching subtype during
  deserialization. With `serializationLibrary=jackson`, discriminator-based
  `oneOf` uses Jackson polymorphism metadata. Members must be referenced object
  model schemas; primitive, array, map, and inline members fail generation with
  a clear unsupported-shape message.
- `anyOf`: generates a Java interface for the composed schema, attaches a
  generated Helidon JSON converter when `serializationLibrary=helidon`, makes
  member models implement it, and rejects ambiguous structural matches during
  deserialization. With `serializationLibrary=jackson`, discriminator-based
  `anyOf` uses Jackson polymorphism metadata. Members must be referenced object
  model schemas; primitive, array, map, and inline members fail generation with
  a clear unsupported-shape message.

For union schemas, generated converters use the OpenAPI discriminator when one is
present. Without a discriminator, Helidon JSON converters fall back to structural
matching based on the member models' required and declared properties. JSON-B
does not support generated composed `oneOf`/`anyOf` models.

### Model API

Generated object schemas are mutable JSON model classes with prefixless property
accessors and mutators. A schema property named `id` produces `id()` and
`id(value)` methods instead of JavaBean-style `getId()` and `setId(...)`.
The selected serialization library determines whether the generated class uses
Helidon JSON binding annotations, JSON-B field visibility, or Jackson property
annotations.

Each generated model also has a Helidon-style builder:

```java
Pet pet = Pet.builder()
        .id(1L)
        .name("Fluffy")
        .tag("cat")
        .build();

Long id = pet.id();
pet.name("Mochi");
```

Builders implement `io.helidon.common.Builder` through a generated
`BuilderBase<B, T>` class. For `allOf` inheritance, child builders extend the
parent model's builder base so fluent chains can set both inherited and local
properties:

```java
Extended extended = Extended.builder()
        .id("extended-1")
        .name("extended-name")
        .build();
```

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

For model classes, validation annotations are emitted on prefixless accessor
methods rather than private fields so generated projects compile cleanly with the
current validation API.

## Testing

The module test suite lives under
`openapi-generator/modules/openapi-generator/src/test/java/io/helidon/openapi/generator`
and covers:

- petstore-style generation
- feature coverage and validation
- form and multipart handling
- security
- observability options
- optional query list handling
- generated project build verification
- serialization library dependency and runtime round-trip coverage

Generated-project verification is opt-in and runs during `verify` under the
`it-tests` Maven profile:

```bash
mvn -pl openapi-generator/modules/openapi-generator -Pit-tests verify
```

The `it-tests` profile runs a checked-in Maven reactor of harness projects. Each
harness invokes `openapi-generator-maven-plugin` against one test spec, then
compiles or tests the generated sources in-place. This keeps verification close
to the `openapi-ui` module approach while still exercising the current generator
output rather than stale checked-in generated sources.

The `composed-schemas` harness runs a checked-in Helidon JSON-binding round-trip
test against freshly generated composed models. The `serialization-jsonb` and
`serialization-jackson` harnesses verify runtime JSON round trips for JSON-B
regular models and Jackson discriminator polymorphism.

## Notes

- Generated endpoint classes do not include `@RestServer.Listener`, because the default
  listener is already implied.
- Generated sample builds do not add `slf4j-jdk14`.
- `Error` schemas are renamed to `ApiError` to avoid clashing with `java.lang.Error`.

## Architecture

See [ARCHITECTURE.md](./ARCHITECTURE.md) for implementation details.
