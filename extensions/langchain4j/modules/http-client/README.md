# Helidon HTTP client for LangChain4j

This module adapts a configured Helidon `WebClient` to LangChain4j's HTTP client SPI.
It targets the JSON and SSE traffic used by the MCP Streamable HTTP transport.
The WebClient's native services, TLS settings and timeouts apply to requests.
Helidon WebClient is the default backend for MCP clients configured through the
Helidon LangChain4j integration.

## Building

The extension uses released LangChain4j 1.23.0 and MCP 1.23.0-beta33 artifacts.
The adapter is part of the normal reactor; no HTTP-client profile or locally installed
LangChain4j snapshots are needed.

```shell
mvn -pl extensions/langchain4j/modules/http-client -am test
```

## MCP context and header propagation

The module registers a Helidon `ExecutorProvider` through `ServiceLoader`. MCP uses
its `captureContext()` hook before deferring work for initialization or retries; the
adapter carries that Helidon Context into its own virtual thread. Applications do not
need to register the provider manually. An application-supplied `ExecutorProvider`
takes precedence and must preserve Helidon Context to retain this behavior.

Configure native WebClient services under each MCP client's `web-client` node:

```yaml
langchain4j:
  mcp-clients:
    tools:
      uri: https://tools.example.com/mcp
      web-client:
        services:
          context:
            records:
              - header: Authorization
                classifier: inboundAuthorization
          tracing: {}
```

Add `helidon-webclient-context` for the header service, and `helidon-webclient-tracing`
plus the application's tracing provider for native tracing. Configure the server's
`ContextFeature` with the same `Authorization` header/classifier to capture inbound
bearer values. The normal Helidon tracing observer establishes the incoming span;
`WebClientTracing` creates the outbound client span and injects W3C tracing headers.
Neither the application handler nor MCP needs to copy headers or supply `customHeaders`.

The native WebClient configuration accepts its usual services, timeouts and TLS options.
MCP defaults to a 60-second connect timeout, no read timeout for long-lived streams,
and no redirects. Configuring services preserves these defaults; explicit native
options override them. The existing MCP-level `tls` setting remains supported and,
when enabled, takes precedence over `web-client.tls`.

The adapter can also be used directly with an application-owned WebClient:

```java
WebClient webClient = WebClient.builder()
        .addService(WebClientContextService.create(config -> config.addRecord(record -> record
                .header(HeaderNames.create("X-Identity"))
                .classifier("identity"))))
        .build();

HelidonHttpClient httpClient = HelidonHttpClient.builder()
        .webClient(webClient)
        .build();

StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
        .url("http://localhost:8080/mcp")
        .httpClient(httpClient)
        .build();
```

The types above come from `io.helidon.webclient.api`, `io.helidon.webclient.context`,
`io.helidon.http`, `io.helidon.extensions.langchain4j.httpclient` and
`dev.langchain4j.mcp.client.transport.http`. Add `helidon-webclient-context` to use
the native header service. Register the desired value under the `identity` classifier
in the caller's Helidon Context; no MCP `customHeaders` supplier is needed.

The fresh empty context for an unbound caller is deliberate: it prevents that caller
from inheriting a connection or completion thread's headers. Initialize a shared MCP
client under an application or session context with the appropriate lifetime. MCP uses
that capture for initialization and connection traffic; individual requests and retries
retain their caller's capture. Servers that bind a session to one principal may require
separate MCP clients for different principals.
The integration propagates native Helidon Context, including native span context;
arbitrary standalone OpenTelemetry scopes outside Helidon Context are not captured.

## Streaming and ownership

The publisher uses the supplied incremental SSE parser and reads bounded byte chunks.
Downstream demand controls event delivery. After draining a parsed batch, one further
chunk can be read to discover EOF or the next events; those events must be delivered
before another read. A partial event may span multiple chunks and is retained by the
parser. Each subscription creates a separate
HTTP exchange. Response callbacks run on a virtual thread with the captured Helidon context.

Cancellation terminates the exchange and releases its response resources. Close the
transport when finished, then close the adapter to cancel any remaining adapter work.
The supplied WebClient remains caller-owned and should be closed separately with
`webClient.closeResource()` when no longer shared.
This also applies to WebClients created through the generated builder's configuration,
consumer or supplier overloads; access that client through `httpClient.prototype().webClient()`.
Supplying an existing WebClient, as above, makes ownership explicit.
For clients created by the Helidon service registry, the factory owns and closes
MCP clients, adapters and native WebClients during registry shutdown. Direct adapter
users retain ownership of their supplied WebClients.

Multipart requests are outside this module's current MCP scope and are rejected explicitly.
Configure WebClient's connect/read timeouts before passing it to the adapter.

For a non-2xx response, the adapter emits `HttpErrorResponseReceived` before reading the
error body when the subscriber has outstanding demand. MCP cancels from that event to fail
or recover its session immediately. A consumer that ignores the event still receives the
normal error with its diagnostic body; with no demand, the advisory is omitted and the
terminal error remains deliverable.

## Keycloak end-to-end test

The opt-in `keycloak-e2e` profile tests the complete path:

```text
Keycloak JWT -> secured Helidon application -> LangChain4j MCP client
            -> Helidon WebClient -> secured Helidon MCP server -> whoami tool
```

Both servers use Helidon OIDC authentication and independently verify Keycloak's JWT
signature, issuer and audience. Native `ContextFeature` and `WebClientContextService`
forward the bearer header. Native `TracingObserver` and `WebClientTracing` create and
propagate real OpenTelemetry spans. The application handler does not copy headers and
the MCP transport has no `customHeaders` supplier.

The test checks two users with overlapping calls through one MCP client, exact JWT
fingerprints at the MCP tool, distinct `traceparent` and `tracestate` values, and the
parent chain from the incoming trace through both servers and the outbound client.
Missing tokens and real tokens with corrupted signatures must be rejected at both
servers. Test output never prints access tokens.

Prerequisites: Java 27 and Docker Compose. The test uses released LangChain4j artifacts,
Helidon and Helidon MCP 27.0.0, and Keycloak 26.8.0. It exercises the production provider
discovered through `ServiceLoader`; no test-only context provider is installed.

From the repository root:

```shell
docker compose --project-name lc4j-2910-e2e \
    -f extensions/langchain4j/modules/http-client/src/it/keycloak/compose.yaml up -d

docker compose --project-name lc4j-2910-e2e \
    -f extensions/langchain4j/modules/http-client/src/it/keycloak/compose.yaml port keycloak 8080
```

Use the printed loopback address below in place of `127.0.0.1:PORT`. Wait until realm
discovery responds before running Maven:

```shell
curl --fail http://127.0.0.1:PORT/realms/lc4j-mcp-e2e/.well-known/openid-configuration

mvn -Pkeycloak-e2e -pl extensions/langchain4j/modules/http-client -am \
    -Dkeycloak.url=http://127.0.0.1:PORT clean verify
```

Always remove the fixture container afterward, including after a failed test:

```shell
docker compose --project-name lc4j-2910-e2e \
    -f extensions/langchain4j/modules/http-client/src/it/keycloak/compose.yaml down --volumes
```

The realm contains only test credentials and is exposed on a loopback port. The integration
profile fails if `keycloak.url` is missing; the regular unit-test build does not require Docker.
Container startup/import follows the [Keycloak container guide](https://www.keycloak.org/server/containers).
