/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.extensions.langchain4j;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClientConfig;

/**
 * Configuration for LangChain4j MCP (Model Context Protocol) clients.
 */
@Prototype.Configured
@Prototype.Blueprint(decorator = McpClientSupport.McpClientDecorator.class)
@Prototype.CustomMethods(McpClientSupport.class)
interface McpClientConfigBlueprint {

    /**
     * The initial URI where to connect to the server and request an SSE
     * channel.
     *
     * @return sse uri
     */
    @Option.Configured
    @Option.Deprecated("uri")
    @Option.Redundant
    @Option.Required
    @Deprecated(forRemoval = true, since = "4.4.0")
    URI sseUri();

    /**
     * The URL of the MCP server.
     *
     * @return mcp server uri
     */
    @Option.Required
    @Option.Configured
    URI uri();

    /**
     * TLS configuration for the MCP server connection. When enabled, this overrides
     * the TLS configuration in {@link #webClientConfig()}.
     *
     * @return tls configuration
     */
    @Option.Configured
    Optional<Tls> tls();

    /**
     * Native WebClient configuration, including services for security, context propagation,
     * and tracing. The factory owns the WebClient created from this configuration.
     * The default disables redirects and the read timeout for long-lived MCP streams,
     * and uses a 60-second connect timeout. Configuring individual native properties
     * preserves these MCP defaults for properties that are not specified.
     *
     * @return configuration of the MCP HTTP client
     */
    @Option.Configured("web-client")
    @Option.DefaultCode("McpClientSupport.defaultWebClientConfig()")
    WebClientConfig webClientConfig();

    /**
     * Sets the name that the client will use to identify itself to the
     * MCP server in the initialization message.
     * Defaults to {@code langchain4j}.
     *
     * @return client name
     */
    @Option.Configured
    @Option.Default("langchain4j")
    Optional<String> clientName();

    /**
     * Sets the version string that the client will use to identify
     * itself to the MCP server in the initialization message.
     * Defaults to {@code 1.0}.
     *
     * @return client version
     */
    @Option.Configured
    @Option.Default("1.0")
    Optional<String> clientVersion();

    /**
     * Sets a unique identifier for the client. For a client created from configuration,
     * the configuration entry name is used when no key is specified. Otherwise, LangChain4j
     * generates a UUID when the key is absent. This key identifies the client in the service registry.
     *
     * @return client key
     */
    @Option.Configured
    Optional<String> key();

    /**
     * Sets the protocol version that the client will advertise in the
     * initialization message. When absent, LangChain4j detects the server protocol version.
     *
     * @return protocol version
     */
    @Option.Configured
    Optional<String> protocolVersion();

    /**
     * Sets the timeout for initializing the client.
     * Defaults to 30 seconds.
     *
     * @return initialization timeout
     */
    @Option.Configured
    @Option.Default("PT30S")
    Optional<Duration> initializationTimeout();

    /**
     * Timeout for detecting the server protocol version. If not configured, LangChain4j
     * uses the initialization timeout.
     *
     * @return protocol detection timeout
     */
    @Option.Configured
    Optional<Duration> protocolDetectionTimeout();

    /**
     * Sets the timeout for tool execution.
     * This value applies to each tool execution individually.
     * A value of zero seconds means no timeout.
     * Defaults to 60 seconds.
     *
     * @return tool execution timeout
     */
    @Option.Configured
    @Option.Default("PT60S")
    Optional<Duration> toolExecutionTimeout();

    /**
     * Sets the timeout for resource-related operations (listing resources as well as reading the contents of a resource).
     * A value of zero seconds means no timeout.
     * Defaults to 60 seconds.
     *
     * @return resources timeout
     */
    @Option.Configured
    @Option.Default("PT60S")
    Optional<Duration> resourcesTimeout();

    /**
     * The timeout for prompt-related operations (listing prompts as well as rendering the contents of a prompt).
     * A value of zero seconds means no timeout.
     * Defaults to 60 seconds.
     *
     * @return prompts timeout
     */
    @Option.Configured
    @Option.Default("PT60S")
    Optional<Duration> promptsTimeout();

    /**
     * The timeout to apply when waiting for a ping response.
     * Defaults to 10 seconds.
     *
     * @return ping timeout
     */
    @Option.Configured
    @Option.Default("PT10S")
    Optional<Duration> pingTimeout();

    /**
     * The delay before attempting to reconnect after a failed connection.
     * Defaults to 5 seconds.
     *
     * @return reconnect interval
     */
    @Option.Configured
    @Option.Default("PT5S")
    Optional<Duration> reconnectInterval();

    /**
     * Whether to run automatic health checks. Defaults to {@code true}.
     *
     * @return automatic health check setting
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean autoHealthCheck();

    /**
     * Interval between automatic health checks. Defaults to 30 seconds.
     *
     * @return automatic health check interval
     */
    @Option.Configured
    @Option.Default("PT30S")
    Duration autoHealthCheckInterval();

    /**
     * Whether to cache the server's tool list. Defaults to {@code true}.
     *
     * @return tool list caching setting
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean cacheToolList();

    /**
     * Whether to cache server resource and resource template lists. If not configured,
     * caching is enabled.
     *
     * @return resource list caching setting
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean cacheResourceList();

    /**
     * Whether to cache the server's prompt list. Defaults to {@code true}.
     *
     * @return prompt list caching setting
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean cachePromptList();

    /**
     * Whether to subscribe to tool list changes for MCP protocol 2026-07-28 or later.
     * Defaults to {@code true}.
     *
     * @return tool list change subscription setting
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean subscribeToToolListChanges();

    /**
     * Whether to subscribe to resource list changes for MCP protocol 2026-07-28 or later.
     * Defaults to {@code true}.
     *
     * @return resource list change subscription setting
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean subscribeToResourceListChanges();

    /**
     * Whether to subscribe to prompt list changes for MCP protocol 2026-07-28 or later.
     * Defaults to {@code true}.
     *
     * @return prompt list change subscription setting
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean subscribeToPromptListChanges();

    /**
     * Maximum retries for multi-round-trip operations returning {@code input_required}
     * in MCP protocol 2026-07-28 or later. Defaults to three.
     *
     * @return maximum multi-round-trip retries
     */
    @Option.Configured
    @Option.DefaultInt(3)
    int multiRoundTripMaxRetries();

    /**
     * Initial roots advertised to the MCP server for the legacy MCP protocol.
     *
     * @return configured roots, or an empty list if none are configured
     */
    @Api.Incubating
    @Option.Configured
    @Option.Singular
    List<McpRootConfig> roots();

    /**
     * Static headers to include in MCP HTTP requests. Native WebClient services may
     * modify these headers before the request is sent.
     *
     * @return configured headers, or an empty map if none are configured
     */
    @Option.Configured
    @Option.Singular
    Map<String, String> customHeaders();

    /**
     * Whether to enable the subsidiary server-sent events channel for the legacy MCP protocol.
     * Defaults to {@code false}.
     *
     * @return subsidiary channel setting
     */
    @Option.Configured
    @Option.DefaultBoolean(false)
    boolean subsidiaryChannel();

    /**
     * The error message to return when a tool execution times out.
     * Defaults to {@code There was a timeout executing the tool}.
     *
     * @return time out error message
     */
    @Option.Configured
    @Option.Default("There was a timeout executing the tool")
    Optional<String> toolExecutionTimeoutErrorMessage();

    /**
     * Whether to log request traffic. Defaults to {@code false}.
     *
     * @return log request traffic
     */
    @Option.Configured
    @Option.DefaultBoolean(false)
    Optional<Boolean> logRequests();

    /**
     * Whether to log response traffic. Defaults to {@code false}.
     *
     * @return log response traffic
     */
    @Option.Configured
    @Option.DefaultBoolean(false)
    Optional<Boolean> logResponses();

}
