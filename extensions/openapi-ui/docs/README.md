# OpenAPI UI

## Contents

- [Overview](#overview)
- [Artifacts](#artifacts)
- [Maven Coordinates](#maven-coordinates)
- [Configuration](#configuration)
- [Minimal Usage](#minimal-usage)
- [Programmatic Customization](#programmatic-customization)
- [Routing and Runtime Notes](#routing-and-runtime-notes)
- [Current Coverage](#current-coverage)
- [References](#references)

## Overview

The OpenAPI UI extension integrates the SmallRye OpenAPI UI with Helidon SE OpenAPI support.

With `io.helidon.openapi:helidon-openapi` and
`io.helidon.extensions.openapi-ui:helidon-extensions-openapi-ui` on the classpath, Helidon can expose the OpenAPI
document and a browser UI for it. By default, the UI is served at `/openapi/ui`, and browser requests to the OpenAPI
endpoint itself are redirected to the UI while non-HTML clients continue to receive the OpenAPI document.

This extension is primarily useful for local development, demos, and testing.

Do not expose the UI in production unless you have explicitly decided to own the security posture yourself. The
extension is not production-ready out of the box; if you choose to use it in a production environment, you are
responsible for authentication, authorization, network exposure, and any other safeguards needed to protect the UI and
the underlying OpenAPI document.

## Artifacts

| Artifact | Use it when | Notes |
| --- | --- | --- |
| `io.helidon.openapi:helidon-openapi` | Your application needs Helidon OpenAPI endpoint support. | Required base feature; the UI builds on top of it. |
| `io.helidon.extensions.openapi-ui:helidon-extensions-openapi-ui-bom` | You want version alignment for the OpenAPI UI extension artifacts. | Import it in `dependencyManagement`. |
| `io.helidon.extensions.openapi-ui:helidon-extensions-openapi-ui` | Your application needs the OpenAPI browser UI. | Publishes `OpenApiUi` and transitively brings the SmallRye UI assets used at runtime. |

## Maven Coordinates

If you want explicit version alignment for the extension artifact, import the BOM:

```xml
<properties>
    <helidon.extensions.openapi-ui.version>27.0.0-SNAPSHOT</helidon.extensions.openapi-ui.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.openapi-ui</groupId>
            <artifactId>helidon-extensions-openapi-ui-bom</artifactId>
            <version>${helidon.extensions.openapi-ui.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Add the required OpenAPI feature and the UI extension:

```xml
<dependencies>
    <dependency>
        <groupId>io.helidon.openapi</groupId>
        <artifactId>helidon-openapi</artifactId>
    </dependency>
    <dependency>
        <groupId>io.helidon.extensions.openapi-ui</groupId>
        <artifactId>helidon-extensions-openapi-ui</artifactId>
    </dependency>
</dependencies>
```

The extension artifact already depends on `smallrye-open-api-ui`, so you do not need to declare a separate SmallRye UI
dependency in your application.

## Configuration

The UI service is configured under `server.features.openapi.services.ui`:

```yaml
server:
  features:
    openapi:
      services:
        ui:
          enabled: true
          web-context: /my-ui
          options:
            title: Example OpenAPI UI
```

The supported configuration keys are:

- `enabled`: defaults to `true`; set this to `false` to disable the UI service while keeping the OpenAPI feature itself.
- `web-context`: optional full UI path. If unset, the UI uses the OpenAPI feature web context with `/ui` appended.
- `options`: `Map<String, String>` of SmallRye UI option names and values. The keys must match the SmallRye
  `io.smallrye.openapi.ui.Option` enum names.

Be careful with `options`: the extension already sets several SmallRye options automatically, including the document
URL and Helidon branding. Values you provide in `options` override those defaults.

## Minimal Usage

Most applications do not need to create `OpenApiUi` manually. If OpenAPI service discovery remains enabled, adding the
dependencies above is enough for Helidon to discover the UI service automatically.

Once your application is serving an OpenAPI document through `OpenApiFeature`, start the server and open:

- `http://localhost:8080/openapi/ui` for the UI
- `http://localhost:8080/openapi` for the document itself, or for browser access that redirects to the UI

If you change the OpenAPI feature web context, the default UI path follows it. For example, if the OpenAPI document is
served from `/my-openapi`, the default UI path becomes `/my-openapi/ui`.

## Programmatic Customization

If you are already creating `OpenApiFeature` explicitly and want to customize the UI path in code, add the UI service
yourself:

```java
Config config = Config.create();

WebServer server = WebServer.builder()
        .config(config.get("server"))
        .addFeature(OpenApiFeature.builder()
                .servicesDiscoverServices(false)
                .addService(OpenApiUi.builder()
                        .webContext("/my-ui")
                        .build())
                .build())
        .routing(routing -> {
        })
        .build()
        .start();
```

If you want configuration-driven overrides as well, apply your `server.features.openapi` and
`server.features.openapi.services.ui` config subtrees to the corresponding builders before `build()`.

## Routing and Runtime Notes

- The default UI path is `/openapi/ui`.
- `server.features.openapi.services.ui.web-context` sets the entire UI path, not a suffix relative to the OpenAPI
  endpoint.
- A request to the UI base path, such as `/openapi/ui`, redirects to `/openapi/ui/index.html`.
- Browser requests to the OpenAPI endpoint itself, such as `/openapi`, are routed to the UI; JSON, YAML, or plain-text
  clients continue to receive the OpenAPI document.
- The extension serves a generated `index.html` plus static assets from its bundled `helidon-openapi-ui` resources and
  the SmallRye `META-INF/resources/openapi-ui` assets on the classpath.
- The generated page uses Helidon defaults for the title, logo, and document URL. Overriding those through `options`
  is supported, but it can interfere with normal UI behavior if done carelessly.

## References

- [SmallRye OpenAPI UI](https://github.com/smallrye/smallrye-open-api/tree/3.3.4/ui/open-api-ui)
