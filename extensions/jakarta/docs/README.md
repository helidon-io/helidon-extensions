# Jakarta JSON integrations

## Overview

The Jakarta extension provides JSON-P HTTP media, JSON-B HTTP media, and DBClient JSON-P mapping for Helidon 28.
Its modules share one extension version and release cycle, while applications select only the integrations they use.
The JSON modules do not depend on persistence or transaction integrations.

This extension supports Helidon 28 only and preserves the repository's Java 27 compiler baseline. The current development
coordinates use extension version `1.0.0-SNAPSHOT` and Helidon version `28.0.0-SNAPSHOT`. These are development artifacts;
the final extension release version is selected independently of the Helidon version.

## Modules and dependencies

All extension artifacts use group ID `io.helidon.extensions.jakarta`.

| Artifact | Purpose | Required specification APIs |
| --- | --- | --- |
| `helidon-extensions-jakarta-bom` | Version alignment for the Jakarta modules, their JSON dependencies, and Helidon core modules. | None by itself. |
| `helidon-extensions-jakarta-http-media-jsonp` | Read and write Jakarta JSON-P objects and arrays over HTTP. | `jakarta.json:jakarta.json-api` |
| `helidon-extensions-jakarta-http-media-jsonb` | Bind Java objects to and from JSON over HTTP. | `jakarta.json.bind:jakarta.json.bind-api` and `jakarta.json:jakarta.json-api` |
| `helidon-extensions-jakarta-dbclient-jsonp` | Map DBClient rows and parameters to and from `jakarta.json.JsonObject`. | `jakarta.json:jakarta.json-api` |

The BOM pins these versions:

| Dependency | Version | Role |
| --- | --- | --- |
| `jakarta.json:jakarta.json-api` | `2.1.3` | [JSON-P 2.1](https://jakarta.ee/specifications/jsonp/2.1/) specification API. |
| `jakarta.json.bind:jakarta.json.bind-api` | `3.0.3` | [JSON-B 3.0](https://jakarta.ee/specifications/jsonb/3.0/) specification API. |
| `org.eclipse.parsson:parsson` | `1.1.9` | Default JSON-P implementation. |
| `org.eclipse.yasson:yasson` | `3.0.5` | Default JSON-B implementation. |

The integration modules declare specification APIs with Maven `provided` scope; applications declare the APIs they use
directly. Parsson and Yasson are runtime dependencies of their integrations. Yasson uses JSON-P, provided by Parsson.
JSON-B's optional CDI integration does not require adding CDI, Inject, Annotation, Resource, JAXB, or Activation APIs to
ordinary JSON consumers. The JSON modules do not add those ancillary APIs.

Import the Jakarta BOM and select one integration. For example, a JSON-P application can use:

```xml
<parent>
    <groupId>io.helidon.applications</groupId>
    <artifactId>helidon-se</artifactId>
    <version>28.0.0-SNAPSHOT</version>
    <relativePath/>
</parent>

<properties>
    <jakarta.extension.version>1.0.0-SNAPSHOT</jakarta.extension.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.jakarta</groupId>
            <artifactId>helidon-extensions-jakarta-bom</artifactId>
            <version>${jakarta.extension.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.helidon.extensions.jakarta</groupId>
        <artifactId>helidon-extensions-jakarta-http-media-jsonp</artifactId>
    </dependency>
    <dependency>
        <groupId>jakarta.json</groupId>
        <artifactId>jakarta.json-api</artifactId>
    </dependency>
</dependencies>
```

Add the neutral Helidon WebServer, WebClient, or DBClient modules needed by the application. For JSON-B, select
`helidon-extensions-jakarta-http-media-jsonb` and declare both JSON APIs from the table. For DBClient mapping, select
`helidon-extensions-jakarta-dbclient-jsonp` and declare the JSON-P API. Importing a BOM manages versions; it does not add
all Jakarta integrations to the application's runtime.

## Migration mappings

Replace the old dependency instead of adding the extension alongside it. A migrated application uses only the extension
implementation for each integration. Remove old integration dependencies introduced transitively as well as directly.
The old and new JSON media providers use the same configuration keys, so retaining both runtimes would introduce
competing registrations.

| Old core coordinates | Replacement coordinates |
| --- | --- |
| `io.helidon.http.media:helidon-http-media-jsonp` | `io.helidon.extensions.jakarta:helidon-extensions-jakarta-http-media-jsonp` |
| `io.helidon.http.media:helidon-http-media-jsonb` | `io.helidon.extensions.jakarta:helidon-extensions-jakarta-http-media-jsonb` |
| `io.helidon.dbclient:helidon-dbclient-jsonp` | `io.helidon.extensions.jakarta:helidon-extensions-jakarta-dbclient-jsonp` |

Update imports and any fully qualified configuration references:

| Old public type | Replacement public type |
| --- | --- |
| `io.helidon.http.media.jsonp.JsonpSupport` | `io.helidon.extensions.jakarta.http.media.jsonp.JsonpSupport` |
| `io.helidon.http.media.jsonp.JsonpSupportConfig` | `io.helidon.extensions.jakarta.http.media.jsonp.JsonpSupportConfig` |
| `io.helidon.http.media.jsonp.JsonpMediaSupportProvider` | `io.helidon.extensions.jakarta.http.media.jsonp.JsonpMediaSupportProvider` |
| `io.helidon.http.media.jsonb.JsonbSupport` | `io.helidon.extensions.jakarta.http.media.jsonb.JsonbSupport` |
| `io.helidon.http.media.jsonb.JsonbSupportConfig` | `io.helidon.extensions.jakarta.http.media.jsonb.JsonbSupportConfig` |
| `io.helidon.http.media.jsonb.JsonbMediaSupportProvider` | `io.helidon.extensions.jakarta.http.media.jsonb.JsonbMediaSupportProvider` |
| `io.helidon.dbclient.jsonp.JsonProcessingMapper` | `io.helidon.extensions.jakarta.dbclient.jsonp.JsonProcessingMapper` |
| `io.helidon.dbclient.jsonp.JsonProcessingMapperProvider` | `io.helidon.extensions.jakarta.dbclient.jsonp.JsonProcessingMapperProvider` |

Nested types of the generated `JsonpSupportConfig` and `JsonbSupportConfig`, including their builders, move with their
enclosing type. Jakarta API imports such as `jakarta.json.JsonObject`, `jakarta.json.JsonReaderFactory`, and
`jakarta.json.bind.Jsonb` are unchanged. The three integrations do not require an application annotation processor.

Update `module-info.java` when using JPMS:

| Old JPMS module | Replacement JPMS module |
| --- | --- |
| `io.helidon.http.media.jsonp` | `io.helidon.extensions.jakarta.http.media.jsonp` |
| `io.helidon.http.media.jsonb` | `io.helidon.extensions.jakarta.http.media.jsonb` |
| `io.helidon.dbclient.jsonp` | `io.helidon.extensions.jakarta.dbclient.jsonp` |

For example:

```java
module example.jsonp {
    requires io.helidon.extensions.jakarta.http.media.jsonp;
    requires io.helidon.webserver;
}
```

The JSON-P HTTP and DBClient modules re-export `jakarta.json`. The JSON-B HTTP module re-exports `jakarta.json.bind`;
JSON-B applications that also use JSON-P types directly need `requires jakarta.json;` in their module declaration.
Provider implementations are discovered using JPMS service declarations; the build also generates `META-INF/services`
registrations for classpath applications.
For JSON-B on the module path, export packages containing public model properties: Yasson uses a public method-handle
lookup for their accessors. Also open model packages to `org.eclipse.yasson` when reflective binding needs access.
The JSON-B example demonstrates both declarations.

## Configuration and customization

The provider IDs remain `jsonp` and `jsonb`. Existing application-facing media keys remain unchanged, including
`server.media-context.media-supports.jsonp`, `server.media-context.media-supports.jsonb`, and corresponding client and
named-socket media contexts. `accepted-media-types`, `content-type`, and `name` retain their meanings.

JSON-B retains the `properties`, `boolean-properties`, and `class-properties` maps. For example:

```yaml
server:
  media-context:
    media-supports:
      jsonb:
        boolean-properties:
          jsonb.formatting: true
```

Pass a support-scoped configuration node when creating support directly:

```java
var support = JsonbSupport.create(config.get("jsonb"));
```

JSON-P reader and writer factories remain customizable through the builder:

```java
var support = JsonpSupport.builder()
        .readerFactory(customReaderFactory)
        .writerFactory(customWriterFactory)
        .build();
```

JSON-B accepts a custom `jakarta.json.bind.Jsonb` instance through `JsonbSupport.create(customJsonb)` or the builder's
`jsonb(...)` method. A supplied instance takes precedence over the configuration property maps; its lifecycle remains the
application's responsibility. Without a custom instance, the integration uses the configured properties to create one.

Both media supports can be discovered automatically. For explicit registration and customization, disable discovery in
that media context and add the selected support:

```java
server.mediaContext(context -> context
        .mediaSupportsDiscoverServices(false)
        .addMediaSupport(support));
```

DBClient retains discovery by mapped Java type: requesting `jakarta.json.JsonObject` selects the JSON-P mapper provider.
Programmatic use remains available through `JsonProcessingMapper.create()`, with row conversion, named parameters, and
indexed parameters. This integration does not add a separate database-provider key.

Generated configuration reference:

- [JSON-P support](config/io.helidon.extensions.jakarta.http.media.jsonp.JsonpSupport.md)
- [JSON-B support](config/io.helidon.extensions.jakarta.http.media.jsonb.JsonbSupport.md)
- [Configuration manifest](config/manifest.md)

## Examples and independent consumers

The extension owns examples for each selectable integration:

- [JSON-P imperative client and server](../examples/jsonp/README.md)
- [JSON-B imperative client and server](../examples/jsonb/README.md)
- [DBClient JSON-P mapping](../examples/dbclient-jsonp/README.md)
- [JSON-P declarative endpoint](../examples/jsonp-declarative/README.md)

Each example declares its own Helidon SE parent and Jakarta BOM. It can be copied outside this repository and built
against installed or published dependencies. From an example directory, run `mvn clean verify` to build, test, and launch
its JPMS entry point. The example READMEs also document commands for launching the built applications directly.

The HTTP examples start a loopback server on a dynamic port, make a real client request to `/echo`, print the result, and
shut down. The declarative example generates its endpoint and service binding and uses automatic JSON-P discovery.
The DBClient example demonstrates a discovered mapper using an in-memory row and statement parameters. Every example
performs a finite demonstration and exits; none requires a database or container.

The `tests` profile includes independent-consumer builds of these projects. Their runtime dependency checks reject the
old core integration artifacts and unrelated persistence or transaction integrations. Each consumer selects only its
own Jakarta integration module.

When migrating an existing application, inspect its runtime graph with `mvn dependency:tree -Dscope=runtime` and check
that only the selected extension integrations and their required APIs and implementations are present. A JSON-B
application needs JSON-P internally, but it does not need the JSON-P HTTP media or DBClient integration.

## Development build

The migration sources were copied from Helidon core revision
`ffa7ebba4a39edbb757a10b8e41962f8a606cce5`, using extensions base revision
`d22b6693f68f36463ee41d08e44715d10d1bc6fa`. The port includes production code, unit tests, module declarations,
configuration blueprints, and JSON-B test configuration. Build-time processors regenerate service and configuration
metadata under the extension namespace.

Use JDK 27 for Maven and the example Java launches: set `JAVA_HOME` to that JDK and put its `bin` directory on `PATH`.
Make the shared repository parents available first by building the repository, or by installing its `parent/pom.xml` and
root `pom.xml` as POM projects. Helidon `28.0.0-SNAPSHOT` dependencies must also be available from the configured snapshot
repository or a local core build. Then, from `extensions/jakarta`, run:

```shell
mvn clean install -Ptests,examples,docs
```

The `tests` and `examples` profiles include the extension's consumer checks and runnable examples. The `docs` profile
regenerates the configuration reference from built extension metadata. It does not require the deprecated JSON
integration runtimes. The release unit builds against core artifacts without a core-to-extension build dependency.

Repository CI and release tooling discover this release unit through `extensions/jakarta/bom/pom.xml` and the
`helidon.version` declared in its root POM. The extension ID is `jakarta`; see the repository
[release instructions](../../../etc/scripts/RELEASE.md). The release root and BOM are shared foundations for additional
Jakarta integrations; adding such modules does not make them dependencies of the JSON modules.
