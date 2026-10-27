# Jackson 2

Jackson 2 JSON entity support and builder annotation generation for Helidon SE 28 (JDK 27).
The extension starts at `1.0.0-SNAPSHOT` and is versioned independently of Helidon and the other Jackson extension.

## Dependencies

Import this BOM in addition to your Helidon dependency management:

```xml
<properties>
    <jackson.jackson-2.extension.version>1.0.0-SNAPSHOT</jackson.jackson-2.extension.version>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.jackson.jackson-2</groupId>
            <artifactId>helidon-extensions-jackson-jackson-2-bom</artifactId>
            <version>${jackson.jackson-2.extension.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>io.helidon.extensions.jackson.jackson-2</groupId>
        <artifactId>helidon-extensions-jackson-jackson-2-http-media</artifactId>
    </dependency>
</dependencies>
```

The runtime supports server request/response entities and WebClient request/response entities.
The `builder-codegen` artifact is a compile-time processor extension; keep it on the annotation processor path.
Neither extension module requires the deprecated core Jackson modules.

## Migration map

| Surface | Core Jackson 2 | This extension |
| --- | --- | --- |
| Runtime Maven coordinates | `io.helidon.http.media:helidon-http-media-jackson` | `io.helidon.extensions.jackson.jackson-2:helidon-extensions-jackson-jackson-2-http-media` |
| Processor Maven coordinates | `io.helidon.builder:helidon-builder-codegen-jackson` | `io.helidon.extensions.jackson.jackson-2:helidon-extensions-jackson-jackson-2-builder-codegen` |
| Version alignment | Helidon BOM | `io.helidon.extensions.jackson.jackson-2:helidon-extensions-jackson-jackson-2-bom` |
| Runtime imports | `io.helidon.http.media.jackson.*` | `io.helidon.extensions.jackson.jackson2.http.media.*` |
| Processor package | `io.helidon.builder.codegen.jackson` | `io.helidon.extensions.jackson.jackson2.builder.codegen` |
| Runtime JPMS module | `io.helidon.http.media.jackson` | `io.helidon.extensions.jackson.jackson2.http.media` |
| Processor JPMS module | `io.helidon.builder.codegen.jackson` | `io.helidon.extensions.jackson.jackson2.builder.codegen` |
| Databind imports | `com.fasterxml.jackson.databind.*` | `com.fasterxml.jackson.databind.*` |
| Common annotation imports | `com.fasterxml.jackson.annotation.*` | `com.fasterxml.jackson.annotation.*` |
| Provider/configuration type | `jackson` | `jackson-2` |

The public application API names map as follows, including generated builder types:

| Core API | Extension API |
| --- | --- |
| `io.helidon.http.media.jackson.JacksonSupport` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonSupport` |
| `io.helidon.http.media.jackson.JacksonSupportConfig` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonSupportConfig` |
| `io.helidon.http.media.jackson.JacksonSupportConfig.Builder` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonSupportConfig.Builder` |
| `io.helidon.http.media.jackson.JacksonSupportConfig.BuilderBase` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonSupportConfig.BuilderBase` |
| `io.helidon.http.media.jackson.JacksonRuntimeException` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonRuntimeException` |

Update the corresponding `requires` declaration for a modular application.
Do not add the processor module to your application's runtime module descriptor.

Jackson 2 entity classes do not need to change when only the runtime dependency is replaced. Existing source models,
compiled models, and models generated using the core Jackson 2 processor remain supported by the extension runtime.
Old and newly generated models can share that runtime without the deprecated core runtime jar.
When rebuilding blueprint input, replace the old processor path with the extension processor; never run both
Jackson 2 processors over the same blueprint input.

## Media-support selection

Discovery registers provider `jackson-2` at weight 16. The core provider `jackson` has weight 15.
Jackson 3, when also installed, has weight 17.
Higher-weight providers are considered first among equally compatible JSON supports.
Choose a single provider explicitly when selection must be independent of other installed providers.
The extension does not register an alias named `jackson`.

Configuration selects the extension by its distinct provider ID:

```yaml
server:
  media-context:
    media-supports:
      jackson-2:
        properties:
          INDENT_OUTPUT: true
```

For a named socket, use `server.sockets.<socket-name>.media-context.media-supports.jackson-2`.
Properties are boolean values keyed by exact Jackson feature enum names. Unknown names are ignored.
`accepted-media-types` defaults to `application/json` and `application/json-patch+json`;
`content-type` defaults to `application/json`.
A supplied `objectMapper` takes precedence over the `properties` map.

To guarantee exclusive API selection, disable discovery in each server or client media context and register the extension:

```java
import io.helidon.extensions.jackson.jackson2.http.media.JacksonSupport;

server.mediaContext(media -> media.mediaSupportsDiscoverServices(false)
        .addMediaSupport(JacksonSupport.create()));
client.mediaContext(media -> media.mediaSupportsDiscoverServices(false)
        .addMediaSupport(JacksonSupport.create()));
```

Here `server` is a `WebServerConfig.Builder` and `client` is an `Http1ClientConfig.Builder`.
To supply configuration directly, use `JacksonSupport.create(config.get("jackson-2"))`, passing a support-scoped
subtree rather than the entire server configuration.

## Mapper behavior

The default mapper retains the core Jackson 2 behavior: fast numeric parsing, parameter-name support,
JDK 8 optional support, and Java time support. Boolean feature configuration uses Jackson 2 enum names.
A custom `com.fasterxml.jackson.databind.ObjectMapper` can be supplied through `JacksonSupport.create(mapper)`.
Configure the mapper before sharing it with server and client contexts.

## Builder generation

For named application modules, open model packages to `com.fasterxml.jackson.databind`
so Jackson can access generated builder constructors. For an unchanged legacy module
that only exports its model package, use `--add-opens <module>/<model-package>=com.fasterxml.jackson.databind`.
This requires no source, descriptor, or binary rewrite; see the
[legacy compatibility fixture](https://github.com/helidon-io/helidon-extensions/tree/main/extensions/jackson/jackson-2/tests/compatibility).

Use a Helidon blueprint with Jackson databind extension annotations:

```java
import io.helidon.builder.api.Prototype;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@Prototype.Blueprint(beanStyle = true)
@Prototype.Extension(JsonSerialize.class)
@Prototype.Extension(JsonDeserialize.class)
interface GreetingBlueprint {
    @JsonProperty("message")
    String getMessage();
}
```

The generated `Greeting` type and builder carry the matching Jackson annotations.
Add `io.helidon.extensions.jackson.jackson-2:helidon-extensions-jackson-jackson-2-builder-codegen:${jackson.jackson-2.extension.version}`
to `maven-compiler-plugin`'s `annotationProcessorPaths` alongside
`io.helidon.bundles:helidon-bundles-apt:${helidon.version}`.
See the standalone example POMs for complete processor-path configuration.

## Examples and configuration reference

- [Imperative example](https://github.com/helidon-io/helidon-extensions/tree/main/extensions/jackson/jackson-2/examples/se-imperative): explicit runtime registration and an echo handler.
- [Declarative example](https://github.com/helidon-io/helidon-extensions/tree/main/extensions/jackson/jackson-2/examples/se-declarative): generated application binding and endpoint discovery.

Both examples use generated Jackson 2 models and include typed client/server JSON round-trip checks.
Run `mvn generate-sources` from this docs directory after installing the extension runtime to regenerate
Markdown configuration reference files under `config/` from its actual config metadata.

## Source provenance

The initial HTTP media and builder-codegen implementation is derived from
[Helidon core revision ffa7ebba4a39edbb757a10b8e41962f8a606cce5](https://github.com/helidon-io/helidon/tree/ffa7ebba4a39edbb757a10b8e41962f8a606cce5),
respectively `http/media/jackson` and `builder/codegen-jackson`.
The extension relocates the integration to its own namespace while preserving Jackson 2 model compatibility.
