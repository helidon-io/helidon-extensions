# Jackson 3

Jackson 3 JSON entity support for Helidon SE 28 (JDK 27).
The extension starts at `1.0.0-SNAPSHOT` and is versioned independently of Helidon and the other Jackson extension.

## Dependencies

The extension BOM manages only the extension's HTTP media module.
Import the upstream Jackson BOM separately to align Jackson dependencies. The extension uses
Jackson `3.2.4`, which requires the shared `com.fasterxml.jackson.core:jackson-annotations:2.22`.
Place the upstream Jackson 3 BOM before Helidon or Jackson 2 BOM imports in the same POM,
or import it in the application POM when Helidon dependency management is inherited from a parent.
This prevents older Jackson 2 annotation management from overriding the version required by Jackson 3.

```xml
<properties>
    <jackson.jackson-3.extension.version>1.0.0-SNAPSHOT</jackson.jackson-3.extension.version>
    <version.lib.jackson>3.2.4</version.lib.jackson>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>tools.jackson</groupId>
            <artifactId>jackson-bom</artifactId>
            <version>${version.lib.jackson}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>io.helidon.extensions.jackson.jackson-3</groupId>
            <artifactId>helidon-extensions-jackson-jackson-3-bom</artifactId>
            <version>${jackson.jackson-3.extension.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>io.helidon.extensions.jackson.jackson-3</groupId>
        <artifactId>helidon-extensions-jackson-jackson-3-http-media</artifactId>
    </dependency>
</dependencies>
```

The runtime supports server request/response entities and WebClient request/response entities.
The HTTP media module does not require the deprecated core Jackson integration.

## Migration map

| Surface | Core Jackson 2 | This extension |
| --- | --- | --- |
| Runtime Maven coordinates | `io.helidon.http.media:helidon-http-media-jackson` | `io.helidon.extensions.jackson.jackson-3:helidon-extensions-jackson-jackson-3-http-media` |
| Version alignment | Helidon BOM | `io.helidon.extensions.jackson.jackson-3:helidon-extensions-jackson-jackson-3-bom` |
| Runtime imports | `io.helidon.http.media.jackson.*` | `io.helidon.extensions.jackson.jackson3.http.media.*` |
| Runtime JPMS module | `io.helidon.http.media.jackson` | `io.helidon.extensions.jackson.jackson3.http.media` |
| Databind imports | `com.fasterxml.jackson.databind.*` | `tools.jackson.databind.*` |
| Common annotation imports | `com.fasterxml.jackson.annotation.*` | `com.fasterxml.jackson.annotation.*` |
| Provider/configuration type | `jackson` | `jackson-3` |

The public HTTP media API names map as follows:

| Core API | Extension API |
| --- | --- |
| `io.helidon.http.media.jackson.JacksonSupport` | `io.helidon.extensions.jackson.jackson3.http.media.JacksonSupport` |
| `io.helidon.http.media.jackson.JacksonSupportConfig` | `io.helidon.extensions.jackson.jackson3.http.media.JacksonSupportConfig` |
| `io.helidon.http.media.jackson.JacksonSupportConfig.Builder` | `io.helidon.extensions.jackson.jackson3.http.media.JacksonSupportConfig.Builder` |
| `io.helidon.http.media.jackson.JacksonSupportConfig.BuilderBase` | `io.helidon.extensions.jackson.jackson3.http.media.JacksonSupportConfig.BuilderBase` |
| `io.helidon.http.media.jackson.JacksonRuntimeException` | `io.helidon.extensions.jackson.jackson3.http.media.JacksonRuntimeException` |

Update the corresponding `requires` declaration for a modular application.

Jackson 3 is a major-version migration. Replace databind annotations such as `JsonSerialize` and `JsonDeserialize`
on application models with the `tools.jackson.databind.annotation` types.
Common annotations such as `JsonProperty` still come from the shared Jackson 2.x `com.fasterxml.jackson.annotation`
namespace. Jackson 2 databind annotations are not recognized by Jackson 3.

The former Jackson builder-codegen module has no replacement in these extensions.
Use ordinary Java records or beans as entity models.

## Media-support selection

Discovery registers provider `jackson-3` at weight 17. The core provider `jackson` has weight 15.
Jackson 2 extension support has weight 16.
Higher-weight providers are considered first among equally compatible JSON supports.
Choose a single provider explicitly when selection must be independent of other installed providers.
The extension does not register an alias named `jackson`.

Configuration selects the extension by its distinct provider ID:

```yaml
server:
  media-context:
    media-supports:
      jackson-3:
        properties:
          INDENT_OUTPUT: true
```

For a named socket, use `server.sockets.<socket-name>.media-context.media-supports.jackson-3`.
Properties are boolean values keyed by exact Jackson feature enum names. Unknown names are ignored.
`accepted-media-types` defaults to `application/json` and `application/json-patch+json`;
`content-type` defaults to `application/json`.
A supplied `objectMapper` takes precedence over the `properties` map.

To guarantee exclusive API selection, disable discovery in each server or client media context and register the extension:

```java
import io.helidon.extensions.jackson.jackson3.http.media.JacksonSupport;

server.mediaContext(media -> media.mediaSupportsDiscoverServices(false)
        .addMediaSupport(JacksonSupport.create()));
client.mediaContext(media -> media.mediaSupportsDiscoverServices(false)
        .addMediaSupport(JacksonSupport.create()));
```

Here `server` is a `WebServerConfig.Builder` and `client` is an `Http1ClientConfig.Builder`.
To supply configuration directly, use `JacksonSupport.create(config.get("jackson-3"))`, passing a support-scoped
subtree rather than the entire server configuration.

## Mapper behavior

Jackson 3 mappers are immutable after construction. Configure a `tools.jackson.databind.json.JsonMapper.Builder`
and pass its completed mapper to `JacksonSupport.create(mapper)` rather than mutating an installed mapper.
The extension uses `JsonMapper.builderWithJackson2Defaults()`, disables inferred final-field mutation,
enables parameter-name detection and default view inclusion, and enables fast numeric parsing.
Java time and JDK 8 optional handling are built into Jackson 3;
the Jackson 2 datatype and parameter-name modules are not added.
Boolean configuration uses Jackson 3 feature names, including `JsonReadFeature` and `JsonWriteFeature` instead
of the removed `JsonParser.Feature` and `JsonGenerator.Feature` enums.
Jackson 3 API, exception, coercion, and feature differences still apply; this extension does not make Jackson 3
a binary replacement for Jackson 2.

| Behavior | Extension default or migration requirement |
| --- | --- |
| Property ordering | Preserve Jackson 2 declaration order instead of Jackson 3 alphabetical sorting. |
| Unknown properties | Fail deserialization, as core Jackson 2 does. |
| Inferred final-field writes | Disabled by default, unlike core Jackson 2. A getter alone does not make its final field writable during deserialization. |
| Null primitive values | Preserve the Jackson 2 zero/false coercion. |
| Enums and dates | Preserve enum names and timestamp defaults; `DateTimeFeature` properties such as `WRITE_DATES_AS_TIMESTAMPS` can override them. |
| Parameter names and views | Detect constructor parameter names and include properties without a view annotation. |
| Databind annotations | Use `tools.jackson.databind.annotation` on application models; old Jackson 2 databind annotations are not recognized. |
| Mapper changes | Build or rebuild a mapper before supplying it; an installed mapper cannot be mutated. |
| Capability probing | Jackson 3 removed `canSerialize`/`canDeserialize`; an applicable JSON entity is attempted and unsupported types fail during reading/writing instead of being rejected by a preliminary mapper probe. |
| Exceptions | Jackson 3 exceptions are unchecked. The integration still wraps Jackson reader/writer failures in its own `JacksonRuntimeException`. |

Feature keys follow the Jackson 3 enums. For example, use `ALLOW_UNESCAPED_CONTROL_CHARS`
instead of the removed `JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS` spelling.
Custom mappers retain their own defaults and override the integration's property map.

Applications that intentionally need inferred final-field writes can explicitly enable them:

```yaml
server:
  media-context:
    media-supports:
      jackson-3:
        properties:
          ALLOW_FINAL_FIELDS_AS_MUTATORS: true
```

The same override is available through `JacksonSupport.builder().putProperty("ALLOW_FINAL_FIELDS_AS_MUTATORS", true)`.
Leave it disabled for untrusted input unless the application intentionally accepts values for those fields.
This setting controls inferred field writes; it does not prevent constructor-based deserialization or make an object graph immutable.

See the [official Jackson 3 migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md)
for third-party API changes outside this integration.

## Examples and configuration reference

- [Imperative example](https://github.com/helidon-io/helidon-extensions/tree/main/extensions/jackson/jackson-3/examples/se-imperative): explicit runtime registration and an echo handler.
- [Declarative example](https://github.com/helidon-io/helidon-extensions/tree/main/extensions/jackson/jackson-3/examples/se-declarative): generated application binding and endpoint discovery.

Both examples use ordinary Java entity models and include typed client/server JSON round-trip checks.
See the generated [configuration reference](config/io.helidon.extensions.jackson.jackson3.http.media.JacksonSupport.md)
and [manifest](config/manifest.md).
Run `mvn generate-sources` from this docs directory after installing the extension runtime to regenerate
Markdown configuration reference files under `config/` from its actual config metadata.

## Source provenance

The initial HTTP media implementation and integration tests are derived from
[Helidon core revision ffa7ebba4a39edbb757a10b8e41962f8a606cce5](https://github.com/helidon-io/helidon/tree/ffa7ebba4a39edbb757a10b8e41962f8a606cce5),
respectively `http/media/jackson` and `http/tests/media/jackson`.
The extension adapts that integration to Jackson 3 APIs and mapper defaults.
