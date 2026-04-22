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

# Architecture

## Goals

This module implements an `openapi-generator` SPI plugin that generates Helidon SE
declarative server code from OpenAPI 3 documents.

The design goal is to stay close to standard `openapi-generator` extension points while
keeping the generated output aligned with Helidon declarative HTTP APIs:

- `@RestServer.Endpoint`
- `@Http.Path`, `@Http.GET`, `@Http.POST`, and related annotations
- generated server and client contracts per tag
- generated model classes with Helidon-oriented validation and JSON binding

## Project Structure

```text
openapi-generator/
├── bom/
│   └── pom.xml
├── docs/
│   ├── ARCHITECTURE.md
│   └── README.md
├── modules/
│   ├── pom.xml
│   └── openapi-generator/
│       ├── pom.xml
│       └── src/
│           ├── main/
│           │   ├── java/io/helidon/openapi/generator/
│           │   │   ├── HelidonDeclarativeCodegen.java
│           │   │   └── GeneratorCli.java
│           │   └── resources/
│           │       ├── META-INF/services/org.openapitools.codegen.CodegenConfig
│           │       └── helidon-declarative/*.mustache
│           └── test/
│               ├── java/io/helidon/openapi/generator/*.java
│               └── resources/*.yaml
└── pom.xml
```

Key published artifacts:

- `io.helidon.extensions.openapi-generator:helidon-extensions-openapi-generator`
- `io.helidon.extensions.openapi-generator:helidon-extensions-openapi-generator-bom`

## Core Class

`HelidonDeclarativeCodegen` extends `AbstractJavaCodegen`.

Its responsibilities are:

- register templates and supporting files
- expose generator options
- preprocess the OpenAPI document
- enrich `CodegenOperation` and `CodegenModel` instances with vendor extensions
- adjust naming and file layout
- drive template rendering through standard `openapi-generator` hooks

Important option constants currently supported:

- `helidonVersion`
- `generateClient`
- `generateErrorHandler`
- `serverOpenApi`
- `serverBasePath`
- `corsEnabled`
- `ftEnabled`
- `tracingEnabled`
- `metricsEnabled`
- `avoidOptionalListParams`

Compatibility aliases:

- `serveOpenApi`
- `serveBasePath`

## Generation Flow

```text
OpenAPI document
  -> preprocessOpenAPI()
  -> fromModel()
  -> fromOperation()
  -> postProcessAllModels()
  -> postProcessOperationsWithModels()
  -> Mustache templates
  -> generated source tree
```

### `preprocessOpenAPI`

This stage extracts global metadata before per-operation processing:

- server base path from `servers[0].url`
- top-level security requirements
- YAML serialization of the source spec for generated `META-INF/openapi.yaml`

### `fromModel`

Model generation starts from the default OpenAPI Java model conversion, then removes
imports that are not appropriate for the Helidon-generated output, such as Swagger 1.x
annotation shorthands and Jackson-specific nullable wrappers that are not used by the
templates.

### `fromOperation`

Operation processing enriches each `CodegenOperation` with Helidon-specific vendor
extensions. This is where most HTTP-specific mapping happens, including:

- HTTP method annotations
- media type constants for `@Http.Consumes` and `@Http.Produces`
- synthetic form or multipart body parameters
- response status overrides
- static and computed response headers
- operation-level security roles
- optional query parameter wrapping

Optional query params are normally generated as `Optional<T>`. When
`avoidOptionalListParams=true`, that behavior is narrowed so only optional list params
stay as bare `List<T>`. Scalar optional query params continue to use `Optional<T>`.

### `postProcessAllModels`

This stage computes model-level template flags and validation annotations.

Notable behavior:

- required properties are marked for JSON-required rendering
- default values are converted into Java literals
- validation annotations are derived from OpenAPI constraints
- model validation annotations are emitted on getter methods rather than private fields

### `postProcessOperationsWithModels`

This stage performs per-tag aggregation and template shaping:

- computes the shared tag-level base path
- determines method sub-paths
- tracks whether optional query params, validation, multipart, form, or security are present
- identifies generated error model usage
- exposes tag-level booleans used by the Mustache templates

## Templates

Templates live under:

```text
openapi-generator/modules/openapi-generator/src/main/resources/helidon-declarative/
```

Current templates:

- `api-interface.mustache`
- `api.mustache`
- `restClient.mustache`
- `apiException.mustache`
- `errorHandler.mustache`
- `model.mustache`
- `api-test.mustache`
- `Main.java.mustache`
- `pom.xml.mustache`
- `build.gradle.mustache`
- `settings.gradle.mustache`
- `application.yaml.mustache`
- `application-test.yaml.mustache`
- `logging.properties.mustache`
- `openapi.yaml.mustache`

### Design Notes

- `api-interface.mustache` defines the shared annotated contract.
- `api.mustache` emits the endpoint stub implementation.
- `restClient.mustache` extends the shared API contract to reuse HTTP annotations.
- `model.mustache` generates model classes rather than records.
- `api.mustache` does not emit `@RestServer.Listener`, because the default listener is
  already implied.

## Generated Output Model

Per tag:

- `{Tag}Api.java`
- `{Tag}Endpoint.java`
- `{Tag}Client.java` when enabled
- `{Tag}Exception.java` and `{Tag}ErrorHandler.java` when enabled
- `{Tag}EndpointTest.java`

Per schema:

- `{Model}.java`

Project-level support:

- `pom.xml`
- `build.gradle`
- `settings.gradle`
- `Main.java`
- `application.yaml`
- `application-test.yaml`
- `logging.properties`
- `META-INF/openapi.yaml` when enabled

## Validation Mapping

The current implementation supports Helidon validation generation for:

- `minLength`
- `maxLength`
- `pattern`
- `minimum`
- `maximum`
- exclusive integer and long bounds
- `minItems`
- `maxItems`
- `multipleOf`

Validation is handled for both model properties and eligible parameters.

## Testing Strategy

The implementation is primarily covered by integration-style generator tests. These run
the full `DefaultGenerator` pipeline against small OpenAPI documents and assert on the
generated output.

Representative tests:

- `PetstoreGenerationIT`
- `FeaturesGenerationIT`
- `FeaturesAvoidOptionalListParamsIT`
- `FormGenerationIT`
- `ValidationGenerationIT`
- `ValidationPrivateFieldIT`
- `SecurityGenerationIT`
- `GlobalSecurityGenerationIT`
- `ObservabilityGenerationIT`
- `MultipleOfGenerationIT`

There is also a focused unit test for the codegen class itself:

- `HelidonDeclarativeCodegenTest`

Generated-project build verification is intentionally opt-in through system properties:

- `helidon.codegen.it.buildsWithMaven`
- `helidon.codegen.it.buildsWithGradle`

## Packaging

The implementation module is packaged as a thin jar with:

- `Main-Class: io.helidon.openapi.generator.GeneratorCli`
- runtime dependencies copied under `target/libs`

This supports both:

- plugin-based Maven usage
- direct `java -jar ... generate ...` CLI usage

## Extension Points

Typical change points:

- add or update a generator option in `HelidonDeclarativeCodegen`
- add a vendor extension in `fromOperation` or model post-processing
- expose a tag-level flag in `postProcessOperationsWithModels`
- consume the new flag in a Mustache template
- add an integration test with a focused YAML fixture
