# io.<wbr>helidon.<wbr>extensions.<wbr>langchain4j.<wbr>McpClient<wbr>Config

## Description

Configuration for LangChain4j MCP (Model Context Protocol) clients

## Configuration options


<table>
<thead>
<tr>
<th>Key</th>
<th>Type</th>
<th>Description</th>
</tr>
</thead>
<tbody>
<tr><td><code>auto-health-check</code></td><td><code>Boolean</code></td><td>Enable periodic health checks and reconnect on failure. Defaults to true.</td></tr>
<tr><td><code>auto-health-check-interval</code></td><td><code>Duration</code></td><td>Interval between automatic health checks. Defaults to 30 seconds.</td></tr>
<tr><td><code>protocol-detection-timeout</code></td><td><code>Duration</code></td><td>Timeout for protocol discovery. Defaults to the initialization timeout.</td></tr>
<tr><td><code>cache-tool-list</code></td><td><code>Boolean</code></td><td>Cache the tool list. Defaults to true.</td></tr>
<tr><td><code>cache-resource-list</code></td><td><code>Boolean</code></td><td>Cache resource and resource template lists. Defaults to true.</td></tr>
<tr><td><code>cache-prompt-list</code></td><td><code>Boolean</code></td><td>Cache the prompt list. Defaults to true.</td></tr>
<tr><td><code>subscribe-to-tool-list-changes</code></td><td><code>Boolean</code></td><td>Subscribe to tool-list changes using MCP 2026-07-28 or later. Defaults to true.</td></tr>
<tr><td><code>subscribe-to-resource-list-changes</code></td><td><code>Boolean</code></td><td>Subscribe to resource-list changes using MCP 2026-07-28 or later. Defaults to true.</td></tr>
<tr><td><code>subscribe-to-prompt-list-changes</code></td><td><code>Boolean</code></td><td>Subscribe to prompt-list changes using MCP 2026-07-28 or later. Defaults to true.</td></tr>
<tr><td><code>multi-round-trip-max-retries</code></td><td><code>Integer</code></td><td>Maximum retries for modern protocol operations returning input_required. Defaults to 3.</td></tr>
<tr><td><code>subsidiary-channel</code></td><td><code>Boolean</code></td><td>Open the legacy protocol HTTP GET/SSE channel for server-initiated messages. Defaults to false.</td></tr>
<tr><td><code>custom-headers</code></td><td><code>Map&lt;String, String&gt;</code></td><td>Static HTTP headers for MCP transport requests. Defaults to an empty map.</td></tr>
<tr><td><a href="io.helidon.extensions.langchain4j.McpRootConfig.md"><code>roots</code></a></td><td><code>List&lt;McpRootConfig&gt;</code></td><td>Roots exposed to the server using the legacy MCP protocol. Defaults to an empty list.</td></tr>
<tr>
<td>
<code>web-<wbr>client</code>
</td>
<td>
<code>WebClientConfig</code>
</td>
<td>Native Helidon WebClient configuration, including services, TLS and timeouts. Defaults to a 60-second connect timeout, no read timeout and no redirects. The factory owns the WebClient created from this configuration.</td>
</tr>
<tr>
<td>
<code>ping-<wbr>timeout</code>
</td>
<td>
<code>Duration</code>
</td>
<td>The timeout to apply when waiting for a ping response. Defaults to 10 seconds.</td>
</tr>
<tr>
<td>
<code>client-<wbr>version</code>
</td>
<td>
<code>String</code>
</td>
<td>Sets the version string that the client will use to identify itself to the MCP server in the initialization message. Defaults to 1.0.</td>
</tr>
<tr>
<td>
<code>protocol-<wbr>version</code>
</td>
<td>
<code>String</code>
</td>
<td>Sets the protocol version that the client will advertise in the initialization message</td>
</tr>
<tr>
<td>
<code>log-<wbr>responses</code>
</td>
<td>
<code>Boolean</code>
</td>
<td>Whether to log response traffic. Defaults to false.</td>
</tr>
<tr>
<td>
<code>reconnect-<wbr>interval</code>
</td>
<td>
<code>Duration</code>
</td>
<td>The delay before attempting to reconnect after a failed connection. Defaults to 5 seconds.</td>
</tr>
<tr>
<td>
<code>uri</code>
</td>
<td>
<code>URI</code>
</td>
<td>The URL of the MCP server</td>
</tr>
<tr>
<td>
<code>prompts-<wbr>timeout</code>
</td>
<td>
<code>Duration</code>
</td>
<td>The timeout for prompt-related operations (listing prompts as well as rendering the contents of a prompt). Defaults to 60 seconds.</td>
</tr>
<tr>
<td>
<code>client-<wbr>name</code>
</td>
<td>
<code>String</code>
</td>
<td>Sets the name that the client will use to identify itself to the MCP server in the initialization message. Defaults to langchain4j.</td>
</tr>
<tr>
<td>
<code>resources-<wbr>timeout</code>
</td>
<td>
<code>Duration</code>
</td>
<td>Sets the timeout for resource-related operations (listing resources as well as reading the contents of a resource). Defaults to 60 seconds.</td>
</tr>
<tr>
<td>
<code>log-<wbr>requests</code>
</td>
<td>
<code>Boolean</code>
</td>
<td>Whether to log request traffic. Defaults to false.</td>
</tr>
<tr>
<td>
<a id="tls"></a>
<a href="io.helidon.common.tls.Tls.md">
<code>tls</code>
</a>
</td>
<td>
<code>Tls</code>
</td>
<td>TLS configuration for the MCP server connection. When enabled, overrides web-client.tls.</td>
</tr>
<tr>
<td>
<code>tool-<wbr>execution-<wbr>timeout-<wbr>error-<wbr>message</code>
</td>
<td>
<code>String</code>
</td>
<td>The error message to return when a tool execution times out. Defaults to "There was a timeout executing the tool".</td>
</tr>
<tr>
<td>
<code>key</code>
</td>
<td>
<code>String</code>
</td>
<td>Sets a unique identifier for the client</td>
</tr>
<tr>
<td>
<code>tool-<wbr>execution-<wbr>timeout</code>
</td>
<td>
<code>Duration</code>
</td>
<td>Sets the timeout for tool execution. Defaults to 60 seconds.</td>
</tr>
<tr>
<td>
<code>initialization-<wbr>timeout</code>
</td>
<td>
<code>Duration</code>
</td>
<td>Sets the timeout for initializing the client. Defaults to 30 seconds.</td>
</tr>
</tbody>
</table>


### Deprecated Options


<table>
<thead>
<tr>
<th>Key</th>
<th>Type</th>
<th>Description</th>
</tr>
</thead>
<tbody>
<tr>
<td>
<code>sse-<wbr>uri</code>
</td>
<td>
<code>URI</code>
</td>
<td>The initial URI where to connect to the server and request an SSE channel</td>
</tr>
</tbody>
</table>


---

See the [manifest](manifest.md) for all available types.
