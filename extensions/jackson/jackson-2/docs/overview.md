# Jackson 2

Jackson 2 JSON entity support for Helidon SE 28 (JDK 27).
The extension starts at `1.0.0-SNAPSHOT` and is versioned independently of Helidon and the other Jackson extension.

## Dependencies

Import this BOM in addition to your Helidon dependency management. It manages only the extension's
HTTP media module; Jackson dependencies remain managed by Helidon or a separately
imported upstream Jackson BOM. The extension uses Jackson `2.21.8`.

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
The HTTP media module does not require the deprecated core Jackson integration.

## Migration map

| Surface | Core Jackson 2 | This extension |
| --- | --- | --- |
| Runtime Maven coordinates | `io.helidon.http.media:helidon-http-media-jackson` | `io.helidon.extensions.jackson.jackson-2:helidon-extensions-jackson-jackson-2-http-media` |
| Version alignment | Helidon BOM | `io.helidon.extensions.jackson.jackson-2:helidon-extensions-jackson-jackson-2-bom` |
| Runtime imports | `io.helidon.http.media.jackson.*` | `io.helidon.extensions.jackson.jackson2.http.media.*` |
| Runtime JPMS module | `io.helidon.http.media.jackson` | `io.helidon.extensions.jackson.jackson2.http.media` |
| Databind imports | `com.fasterxml.jackson.databind.*` | `com.fasterxml.jackson.databind.*` |
| Common annotation imports | `com.fasterxml.jackson.annotation.*` | `com.fasterxml.jackson.annotation.*` |
| Provider/configuration type | `jackson` | `jackson-2` |

The public HTTP media API names map as follows:

| Core API | Extension API |
| --- | --- |
| `io.helidon.http.media.jackson.JacksonSupport` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonSupport` |
| `io.helidon.http.media.jackson.JacksonSupportConfig` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonSupportConfig` |
| `io.helidon.http.media.jackson.JacksonSupportConfig.Builder` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonSupportConfig.Builder` |
| `io.helidon.http.media.jackson.JacksonSupportConfig.BuilderBase` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonSupportConfig.BuilderBase` |
| `io.helidon.http.media.jackson.JacksonRuntimeException` | `io.helidon.extensions.jackson.jackson2.http.media.JacksonRuntimeException` |

Update the corresponding `requires` declaration for a modular application.
Jackson 2 entity classes retain their Jackson annotations when the HTTP media dependency is replaced.

The former Jackson builder-codegen module has no replacement in these extensions.
Use ordinary Java records or beans as entity models.

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

## Examples and configuration reference

- [Imperative example](https://github.com/helidon-io/helidon-extensions/tree/main/extensions/jackson/jackson-2/examples/se-imperative): explicit runtime registration and an echo handler.
- [Declarative example](https://github.com/helidon-io/helidon-extensions/tree/main/extensions/jackson/jackson-2/examples/se-declarative): generated application binding and endpoint discovery.

Both examples use ordinary Java entity models and include typed client/server JSON round-trip checks.
See the generated [configuration reference](config/io.helidon.extensions.jackson.jackson2.http.media.JacksonSupport.md)
and [manifest](config/manifest.md).
Run `mvn generate-sources` from this docs directory after installing the extension runtime to regenerate
Markdown configuration reference files under `config/` from its actual config metadata.

## Source provenance

The initial HTTP media implementation and integration tests are derived from
[Helidon core revision ffa7ebba4a39edbb757a10b8e41962f8a606cce5](https://github.com/helidon-io/helidon/tree/ffa7ebba4a39edbb757a10b8e41962f8a606cce5),
respectively `http/media/jackson` and `http/tests/media/jackson`.
The extension relocates the integration to its own namespace while preserving its Jackson 2 HTTP media behavior.
