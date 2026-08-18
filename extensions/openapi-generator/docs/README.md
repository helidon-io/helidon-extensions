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
into generated Maven and Gradle projects, and its current default is `4.5.2`.
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
                    <helidonVersion>4.5.2</helidonVersion>
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
- `4.5.2` is the Helidon version used in the generated project

## CLI Usage

The module also exposes a minimal Java CLI:

```bash
java -jar openapi-generator/modules/openapi-generator/target/helidon-extensions-openapi-generator-4.0.0-SNAPSHOT.jar \
  generate \
  -g helidon-declarative \
  -i /path/to/openapi.yaml \
  -o /path/to/output \
  --additional-properties helidonVersion=4.5.2,javaVersion=21,apiPackage=com.example.api,modelPackage=com.example.model,invokerPackage=com.example
```

Here again, the jar version (`4.0.0-SNAPSHOT`) is the generator version, while
`helidonVersion=4.5.2` controls the generated Helidon dependencies and
`javaVersion=21` controls the generated project Java compilation level.

Example with the optional-list flag enabled:

```bash
java -jar openapi-generator/modules/openapi-generator/target/helidon-extensions-openapi-generator-4.0.0-SNAPSHOT.jar \
  generate \
  -g helidon-declarative \
  -i /path/to/openapi.yaml \
  -o /tmp/generated-openapi \
  --additional-properties helidonVersion=4.5.2,avoidOptionalListParams=true
```

## Generator Options

Set these under Maven `<configOptions>` or CLI `--additional-properties`.

| Option | Default | Description |
|--------|---------|-------------|
| `helidonVersion` | `4.5.2` | Helidon version written into generated Maven and Gradle builds |
| `javaVersion` | `21` | Java version written into generated Maven compiler source/target and Gradle toolchain builds |
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
| `discriminatorRepresentation` | schema-driven | Use `metadata` or `readOnlyProperty` for every discriminator unless a schema overrides it |

Legacy aliases `serveOpenApi` and `serveBasePath` are still accepted for compatibility.

The schema extension `x-helidon-discriminator-representation` accepts `metadata` or
`readOnlyProperty` and takes precedence over the global option. Without either setting,
an explicitly declared discriminator property becomes a derived read-only accessor;
a discriminator used only for polymorphic routing remains metadata-only.

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
| `{Model}.java` | Helidon JSON model type with generated builders, validation, and enum support |

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

### String enums

OpenAPI schemas with `type: string` and `enum` generate typed, immutable Java
enums for top-level schemas, model properties and collection elements, HTTP
parameters, and request entities. Each constant retains the exact OpenAPI wire
value, including case and punctuation. The generated `value()` method returns
that value, `toString()` uses it for declarative-client HTTP parameters, and
`fromValue(String)` performs a strict non-null lookup. Distinct wire values that
normalize to the same Java identifier receive deterministic numeric suffixes.

Helidon JSON remains the JSON implementation. A stateless generated
`JsonConverter` preserves exact values and JSON `null` for entity and model
binding. Enums used by path, query, or header parameters additionally receive a
typed `Mapper<String, EnumType>`; request-entity-only enums do not. Helidon 4.5
or later is required for declarative HTTP binding to translate a mapper failure
for a client-supplied enum value into the standard HTTP 400 response.
String-enum cookie parameters fail generation with an actionable diagnostic because
Helidon declarative HTTP does not provide a cookie-parameter annotation.

### Composed Schemas

The generator supports these schema-composition keywords:

- `allOf`: generates an inherited model when there is a single referenced parent
  component; otherwise falls back to a flattened merged model. When the parent
  schema declares a discriminator, the generator also emits `@Json.Polymorphic`
  and `@Json.Subtype` metadata on the base model and derives exact subtype values
  from the declared discriminator mapping. The discriminator is omitted from
  stored model state so Helidon JSON writes it exactly once. Helidon JSON processes
  `allOf` polymorphism as a stream, so the discriminator must be the first property
  in JSON input deserialized through the polymorphic base type. This ordering
  requirement does not apply to the generated `oneOf` and `anyOf` converters,
  which buffer each complete JSON object before selecting a member.
  In a multi-level hierarchy, each ancestor routes a concrete descendant using
  the canonical alias declared by that descendant's nearest discriminator-owning
  parent; duplicate descendant aliases fail generation as ambiguous.
- `oneOf`: generates a Java interface for the composed schema, attaches a
  generated `@Json.Converter`, makes member models implement it, and requires
  exactly one matching subtype during deserialization. Members must be referenced
  object model schemas; primitive, array, map, and inline members fail generation
  with a clear unsupported-shape message.
- `anyOf`: generates a Java interface for the composed schema, attaches a
  generated `@Json.Converter`, makes member models implement it, and rejects
  ambiguous structural matches during deserialization. Members must be referenced
  object model schemas; primitive, array, map, and inline members fail generation
  with a clear unsupported-shape message.

For union schemas, generated converters buffer the JSON object and use the OpenAPI
discriminator when one is present, so the discriminator can appear anywhere in
the object. The converter accepts the declared mapping value, writes one canonical
discriminator, and rejects missing or unknown values. A mapping with multiple
aliases for one subtype is rejected because no single canonical output value is
defined. Without a discriminator, converters fall back to structural matching
based on the member models' required and declared properties.

### Model API

Generated object schemas are mutable Helidon JSON entities with prefixless
property accessors and mutators. A schema property named `id` produces `id()`
and `id(value)` methods instead of JavaBean-style `getId()` and `setId(...)`.

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

Validation participation is computed transitively across generated model references.
Participating models receive `@Validation.Validated`. Required, non-null direct model
boundaries use method-level `@Validation.Valid`. Nullable direct model properties use
`Optional<T>` accessors backed by an `Optional<@Validation.Valid T>` validation field so
validation skips absent values safely. `Optional`, collection, map-value, and array
boundaries use type-use `@Validation.Valid`. Map keys are not cascade targets.
Inherited `allOf` constraints and cascades are repeated on generated child overrides so
the concrete child validator executes the complete parent contract. Constraints on a
same-name child property are composed conjunctively with constraints from every `allOf`
ancestor; combinations that cannot be represented by one Helidon annotation fail generation.
Request entities
use the same boundary rules and add `helidon-webserver-validation` so invalid entities
follow Helidon's standard HTTP rejection behavior.

Arbitrary `Iterable` or custom container mappings cannot guarantee Helidon cascade
semantics and are rejected before source rendering with an actionable diagnostic,
including request-entity containers. Schema-declared nullable request entities or nullable
nested request model values are rejected because Helidon 4.5 does not null-guard a direct
`@Validation.Valid` validator call; use `Optional`, a supported container, or a required
non-null property.
Helidon 4.5 also does not convert a contract-violating JSON `null` at a non-nullable
`@Validation.Valid` boundary into a validation response. Rejecting that case requires a
null guard in Helidon validation code generation and is outside this generator-only feature.
Model properties and request entities whose union type has constrained concrete members
are rejected because Helidon cannot statically dispatch that validation boundary to a
runtime subtype; use a concrete DTO or application validation.
Recursive references are allowed when they do not participate in generated validation.
A cycle between participating models, including one introduced by inherited `allOf`
properties, is also rejected before rendering because Helidon
4.5 creates eager `TypeValidator` dependencies for `@Validation.Valid` boundaries and
cannot activate a recursive validator graph. Break the validation cycle, map the
recursive boundary to a non-validating DTO, or validate that boundary in application
logic.

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

## Notes

- Generated endpoint classes do not include `@RestServer.Listener`, because the default
  listener is already implied.
- Generated sample builds do not add `slf4j-jdk14`.
- `Error` schemas are renamed to `ApiError` to avoid clashing with `java.lang.Error`.

## Architecture

See [ARCHITECTURE.md](./ARCHITECTURE.md) for implementation details.
