# Gson

## Contents

- [Overview](#overview)
- [Modules](#modules)
- [Maven Coordinates](#maven-coordinates)
- [Configuration](#configuration)
- [Minimal Usage](#minimal-usage)
- [Manual Registration](#manual-registration)
- [Examples](#examples)
- [Scope and Limitations](#scope-and-limitations)
- [References](#references)

## Overview

The Gson extension adds Gson-backed JSON media support for Helidon SE WebServer.

With `io.helidon.extensions.gson:helidon-extensions-gson-media` on the classpath, WebServer can deserialize JSON
request entities into Java objects and serialize Java objects back to JSON. This follows the Helidon SE media-support
model: classpath discovery is the default, and explicit `MediaContext` registration is
only needed when you want to customize the media context yourself.

## Modules

| Artifact | Use it when | Notes |
| --- | --- | --- |
| `io.helidon.extensions.gson:helidon-extensions-gson-bom` | You want version alignment for the Gson extension artifacts. | Import it in `dependencyManagement`. |
| `io.helidon.extensions.gson:helidon-extensions-gson-media` | Your application needs Gson-backed JSON request and response handling in Helidon SE WebServer. | Publishes `GsonSupport` and a service-loaded `MediaSupportProvider`. |

## Maven Coordinates

If you want explicit version alignment, import the BOM:

```xml
<properties>
    <helidon.extensions.gson.version>27.0.0-SNAPSHOT</helidon.extensions.gson.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.gson</groupId>
            <artifactId>helidon-extensions-gson-bom</artifactId>
            <version>${helidon.extensions.gson.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Add the media-support module:

```xml
<dependencies>
    <dependency>
        <groupId>io.helidon.extensions.gson</groupId>
        <artifactId>helidon-extensions-gson-media</artifactId>
    </dependency>
</dependencies>
```

## Configuration

Gson support can be configured either as a support-scoped subtree that you pass to `GsonSupport.create(...)`:

```yaml
gson:
  properties:
    serialize-nulls: true
```

If you configure media support through the WebServer configuration tree, the same support appears under
`server.media-context.media-supports.gson` (or `server.sockets.media-context.media-supports.gson`):

```yaml
server:
  media-context:
    media-supports:
      gson:
        properties:
          serialize-nulls: true
```

The supported configuration surface is intentionally small:

- `properties`: a `Map<String, Boolean>` of supported `GsonBuilder` toggles.
- `accepted-media-types`: media types this support accepts when Helidon chooses a reader or writer; defaults to
  `application/json` and `application/json-patch+json`.
- `content-type`: the default content type written to response headers and client request headers; defaults to
  `application/json`.
- Supported boolean keys are `pretty-printing`, `disable-html-escaping`, `disable-inner-class-serialization`,
  `disable-jdk-unsafe`, `enable-complex-map-key-serialization`, `exclude-fields-without-expose-annotation`,
  `generate-non-executable-json`, `serialize-special-floating-point-values`, `lenient`, and `serialize-nulls`.
- Unknown property names are ignored.
- If you provide a concrete `Gson` instance in code, the `properties` map is ignored.
- Additional `TypeAdapterFactory` implementations can be contributed through the service registry or service loader and
  are registered on the generated `GsonBuilder`.

## Minimal Usage

In the default discovery path, adding the dependency is enough. Your handlers can work with Java objects instead of raw
JSON:

```java
class Person {
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

routing.post("/echo", (req, res) -> {
    Person person = req.content().as(Person.class);
    res.send(person);
});
```

Exercise the endpoint with JSON:

```shell
curl -i -X POST \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  http://localhost:8080/echo \
  -d '{"name":"Joe"}'
```

Expected response:

```text
HTTP/1.1 200 OK
Content-Type: application/json

{"name":"Joe"}
```

## Manual Registration

If you want to customize the media context explicitly, register `GsonSupport` yourself and disable media-support
discovery for that context first:

```java
server.mediaContext(context -> {
    context.mediaSupportsDiscoverServices(false);
    context.addMediaSupport(GsonSupport.create(config.get("gson")));
});
```

This is the pattern used by the imperative example in this repository.

## Examples

Two SE examples are included:

- [`../examples/se-imperative/README.md`](../examples/se-imperative/README.md): explicit `GsonSupport.create(...)`
  registration in `MediaContext`, explicit routing, and a small `gson.properties` config block.
- [`../examples/se-declarative/README.md`](../examples/se-declarative/README.md): classpath-discovered Gson support
  with declarative Helidon SE endpoints.

Both examples expose JSON endpoints under `/hello` and show `serialize-nulls` in action.

## Scope and Limitations

- This extension is for Helidon SE WebServer media support.
- The published runtime surface is a single module, `helidon-extensions-gson-media`.
- The provider is intentionally low-weight. If other JSON media supports are also present, a more specific or
  higher-weight support may be selected first.
- Config-driven customization is intentionally narrow: the supported boolean `properties` keys, media-support settings
  such as accepted media types and content type, and service-loaded `TypeAdapterFactory` implementations.
- If you need broader Gson customization, create a `Gson` instance yourself and register it with
  `GsonSupport.create(customGson)`.

## References

- [Gson](https://github.com/google/gson)
