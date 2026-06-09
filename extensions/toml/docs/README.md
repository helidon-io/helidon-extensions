# TOML

## Contents

- [Overview](#overview)
- [Artifacts](#artifacts)
- [Maven Coordinates](#maven-coordinates)
- [Config Usage](#config-usage)
- [Parser Usage](#parser-usage)
- [Example](#example)

## Overview

The TOML extension provides a TOML parser, a document model, and Helidon Config
integration for `application/toml`.

The parser is implemented in this extension and does not depend on an external
TOML library. It targets the [TOML v1.0.0](https://toml.io/en/v1.0.0)
and [TOML v1.1.0](https://toml.io/en/v1.1.0) specifications.

## Artifacts

| Artifact | Use it when | Notes |
| --- | --- | --- |
| `io.helidon.extensions.toml:helidon-extensions-toml-bom` | You want version alignment for TOML extension artifacts. | Import it in `dependencyManagement`. |
| `io.helidon.extensions.toml:helidon-extensions-toml-config` | Your application needs TOML as a Helidon Config source format. | Registers `TomlConfigParser` through `ServiceLoader` and depends on the TOML artifact. |
| `io.helidon.extensions.toml:helidon-extensions-toml` | Your application needs direct TOML parsing without Helidon Config. | Provides the parser and document model. |

## Maven Coordinates

Import the TOML extension BOM:

```xml
<properties>
    <helidon.extensions.toml.version>27.0.0-SNAPSHOT</helidon.extensions.toml.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.toml</groupId>
            <artifactId>helidon-extensions-toml-bom</artifactId>
            <version>${helidon.extensions.toml.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Add the Config integration:

```xml
<dependency>
    <groupId>io.helidon.extensions.toml</groupId>
    <artifactId>helidon-extensions-toml-config</artifactId>
</dependency>
```

For parser-only usage, add:

```xml
<dependency>
    <groupId>io.helidon.extensions.toml</groupId>
    <artifactId>helidon-extensions-toml</artifactId>
</dependency>
```

## Config Usage

With `helidon-extensions-toml-config` on the classpath or module path, Helidon
Config discovers `TomlConfigParser` through `ServiceLoader` unless parser
services are disabled.

```java
Config config = Config.create();
String greeting = config.get("app.greeting").asString().get();
```

For source builders where the media type is explicit, use `application/toml`:

```java
Config config = Config.builder()
        .addSource(ConfigSources.classpath("application.toml")
                           .mediaType(MediaTypes.APPLICATION_TOML))
        .build();
```

The parser also advertises the `toml` suffix. File-name based discovery of
`application.toml`, `meta-config.toml`, and profile TOML files depends on the
Helidon media-type mapping for `.toml` to `application/toml`.

## Parser Usage

Use the parser directly when you need a TOML value tree instead of a Helidon
Config tree:

```java
TomlTable table = TomlParser.create().parse("""
        app.greeting = "Hello"
        app.page-size = 20
        """);

TomlTable app = (TomlTable) table.get("app").orElseThrow();
String greeting = ((TomlString) app.get("greeting").orElseThrow()).value();
```

TOML documents do not declare the specification version they use. Configure
version-specific parsing when strict validation is needed:

```java
TomlParser parser = TomlParser.create(builder -> builder.versionBehavior(TomlVersionBehavior.V1_0_0));
```

## Example

See the [TOML WebServer example](../examples/webserver/README.md) for an
application that uses `application.toml` to configure the WebServer host and
port.
