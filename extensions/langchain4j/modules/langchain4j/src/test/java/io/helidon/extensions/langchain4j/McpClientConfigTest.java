/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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

import io.helidon.common.tls.Tls;
import io.helidon.config.Config;
import io.helidon.webclient.api.WebClientConfig;
import io.helidon.webclient.context.WebClientContextService;

import org.junit.jupiter.api.Test;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_YAML;
import static io.helidon.common.testing.junit5.OptionalMatcher.optionalValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class McpClientConfigTest {

    @Test
    void testMcpClientConfig() {
        //language=YAML
        var cfg = """
                langchain4j:
                  mcp-clients:
                    cli-tools-mcp-server:
                      key: cli-tools-mcp-server
                      uri: http://localhost:9999/cli
                      client-name: test-client
                      client-version: 2.2.2
                      initialization-timeout: PT15M
                      prompts-timeout: PT5M
                      ping-timeout: PT10M
                      protocol-version: 1.2.3
                      reconnect-interval: PT15S
                      resources-timeout: PT10S
                      tool-execution-timeout: PT20S
                      tool-execution-timeout-error-message: Tool timeout!!!
                      log-requests: true
                      log-responses: true
                      tls:
                        trust-all: true
                        endpoint-identification-algorithm: NONE
                """;
        var c = Config.just(cfg, APPLICATION_YAML);
        var mcpClientConfig = McpClientFactory.configure(c).findFirst().get();
        assertThat(mcpClientConfig.uri(), is(URI.create("http://localhost:9999/cli")));
        assertThat(mcpClientConfig.key(), optionalValue(is("cli-tools-mcp-server")));
        assertThat(mcpClientConfig.clientName(), optionalValue(is("test-client")));
        assertThat(mcpClientConfig.clientVersion(), optionalValue(is("2.2.2")));
        assertThat(mcpClientConfig.initializationTimeout(), optionalValue(is(Duration.ofMinutes(15))));
        assertThat(mcpClientConfig.pingTimeout(), optionalValue(is(Duration.ofMinutes(10))));
        assertThat(mcpClientConfig.promptsTimeout(), optionalValue(is(Duration.ofMinutes(5))));
        assertThat(mcpClientConfig.protocolVersion(), optionalValue(is("1.2.3")));
        assertThat(mcpClientConfig.reconnectInterval(), optionalValue(is(Duration.ofSeconds(15))));
        assertThat(mcpClientConfig.resourcesTimeout(), optionalValue(is(Duration.ofSeconds(10))));
        assertThat(mcpClientConfig.toolExecutionTimeout(), optionalValue(is(Duration.ofSeconds(20))));
        assertThat(mcpClientConfig.toolExecutionTimeoutErrorMessage(), optionalValue(is("Tool timeout!!!")));
        assertThat(mcpClientConfig.logRequests(), optionalValue(is(true)));
        assertThat(mcpClientConfig.logResponses(), optionalValue(is(true)));
        assertThat(mcpClientConfig.tls().isPresent(), is(true));
        assertThat(mcpClientConfig.tls().get().enabled(), is(true));
        assertThat(mcpClientConfig.tls().get().prototype().trustAll(), is(true));
        assertThat(mcpClientConfig.tls().get().prototype().endpointIdentificationAlgorithm(),
                   is(Tls.ENDPOINT_IDENTIFICATION_NONE));
    }

    @Test
    void testMcpClientDeprecatedConfig() {
        //language=YAML
        var cfg = """
                langchain4j:
                  mcp-clients:
                    cli-tools-mcp-server:
                      sse-uri: http://localhost:9999/cli
                      log-requests: false
                      log-responses: false
                """;
        var c = Config.just(cfg, APPLICATION_YAML);
        var mcpClientConfig = McpClientFactory.configure(c).findFirst().get();
        assertThat(mcpClientConfig.uri(), is(URI.create("http://localhost:9999/cli")));
        assertThat(mcpClientConfig.key(), optionalValue(is("cli-tools-mcp-server")));
    }

    @Test
    void testMcpClientCustomKey() {
        //language=YAML
        var cfg = """
                langchain4j:
                  mcp-clients:
                    cli-tools-mcp-server:
                      key: different-cli-tools-mcp-server
                      uri: http://localhost:9999/cli
                      log-requests: false
                      log-responses: false
                """;
        var c = Config.just(cfg, APPLICATION_YAML);
        var mcpClientConfig = McpClientFactory.configure(c).findFirst().get();
        assertThat(mcpClientConfig.uri(), is(URI.create("http://localhost:9999/cli")));
        assertThat(mcpClientConfig.key(), optionalValue(is("different-cli-tools-mcp-server")));
    }

    @Test
    void testWebClientDefaults() {
        var config = McpClientConfig.builder()
                .uri(URI.create("http://localhost/mcp"))
                .build()
                .webClientConfig();
        assertThat(config.followRedirects(), is(false));
        assertThat(config.connectTimeout(), optionalValue(is(Duration.ofSeconds(60))));
        assertThat(config.readTimeout(), optionalValue(is(Duration.ZERO)));
    }

    @Test
    void testNativeServicesPreserveMcpDefaults() {
        var config = McpClientConfig.create(Config.just("""
                uri: http://localhost/mcp
                web-client:
                  default-headers:
                    X-Client: configured
                  services:
                    context:
                      records:
                        - header: Authorization
                          classifier: bearer
                """, APPLICATION_YAML));
        assertThat(config.webClientConfig().defaultHeadersMap().get("X-Client"), is("configured"));
        assertThat(config.webClientConfig().services().size(), is(1));
        var contextService = (WebClientContextService) config.webClientConfig().services().getFirst();
        assertThat(contextService.prototype().records().size(), is(1));
        assertThat(contextService.prototype().records().getFirst().classifier(), optionalValue(is("bearer")));
        assertThat(config.webClientConfig().followRedirects(), is(false));
        assertThat(config.webClientConfig().connectTimeout(), optionalValue(is(Duration.ofSeconds(60))));
        assertThat(config.webClientConfig().readTimeout(), optionalValue(is(Duration.ZERO)));
    }

    @Test
    void testNativeTimeoutAndRedirectOverrides() {
        var config = McpClientConfig.create(Config.just("""
                uri: http://localhost/mcp
                web-client:
                  connect-timeout: PT5S
                  read-timeout: PT20S
                  follow-redirects: true
                """, APPLICATION_YAML));
        assertThat(config.webClientConfig().followRedirects(), is(true));
        assertThat(config.webClientConfig().connectTimeout(), optionalValue(is(Duration.ofSeconds(5))));
        assertThat(config.webClientConfig().readTimeout(), optionalValue(is(Duration.ofSeconds(20))));
    }

    @Test
    void testProgrammaticNativeConfiguration() {
        var config = McpClientConfig.builder()
                .uri(URI.create("http://localhost/mcp"))
                .webClientConfig(WebClientConfig.builder()
                        .connectTimeout(Duration.ofSeconds(4))
                        .readTimeout(Duration.ZERO)
                        .followRedirects(false)
                        .buildPrototype())
                .build();
        assertThat(config.webClientConfig().connectTimeout(), optionalValue(is(Duration.ofSeconds(4))));
        assertThat(config.webClientConfig().readTimeout(), optionalValue(is(Duration.ZERO)));
    }

    @Test
    void testAdditionalClientOptions() {
        var config = McpClientConfig.create(Config.just("""
                uri: http://localhost/mcp
                auto-health-check: false
                auto-health-check-interval: PT9S
                protocol-detection-timeout: PT3S
                cache-tool-list: false
                cache-resource-list: false
                cache-prompt-list: false
                subscribe-to-tool-list-changes: false
                subscribe-to-resource-list-changes: false
                subscribe-to-prompt-list-changes: false
                multi-round-trip-max-retries: 7
                subsidiary-channel: true
                custom-headers:
                  X-Api-Key: sample-key
                roots:
                  - uri: file:///tmp/project
                    name: project
                  - uri: file:///tmp/shared
                """, APPLICATION_YAML));
        assertThat(config.autoHealthCheck(), is(false));
        assertThat(config.autoHealthCheckInterval(), is(Duration.ofSeconds(9)));
        assertThat(config.protocolDetectionTimeout(), optionalValue(is(Duration.ofSeconds(3))));
        assertThat(config.cacheToolList(), is(false));
        assertThat(config.cacheResourceList(), is(false));
        assertThat(config.cachePromptList(), is(false));
        assertThat(config.subscribeToToolListChanges(), is(false));
        assertThat(config.subscribeToResourceListChanges(), is(false));
        assertThat(config.subscribeToPromptListChanges(), is(false));
        assertThat(config.multiRoundTripMaxRetries(), is(7));
        assertThat(config.subsidiaryChannel(), is(true));
        assertThat(config.customHeaders().get("X-Api-Key"), is("sample-key"));
        assertThat(config.roots().size(), is(2));
        assertThat(config.roots().get(0).uri(), is(URI.create("file:///tmp/project")));
        assertThat(config.roots().get(0).name(), optionalValue(is("project")));
        assertThat(config.roots().get(1).uri(), is(URI.create("file:///tmp/shared")));
        assertThat(config.roots().get(1).name().isEmpty(), is(true));
    }

    @Test
    void testMcpClientDefaults() {
        var config = McpClientConfig.builder().uri(URI.create("http://localhost/mcp")).build();
        assertThat(config.clientName(), optionalValue(is("langchain4j")));
        assertThat(config.clientVersion(), optionalValue(is("1.0")));
        assertThat(config.initializationTimeout(), optionalValue(is(Duration.ofSeconds(30))));
        assertThat(config.toolExecutionTimeout(), optionalValue(is(Duration.ofSeconds(60))));
        assertThat(config.resourcesTimeout(), optionalValue(is(Duration.ofSeconds(60))));
        assertThat(config.promptsTimeout(), optionalValue(is(Duration.ofSeconds(60))));
        assertThat(config.pingTimeout(), optionalValue(is(Duration.ofSeconds(10))));
        assertThat(config.reconnectInterval(), optionalValue(is(Duration.ofSeconds(5))));
        assertThat(config.toolExecutionTimeoutErrorMessage(), optionalValue(is("There was a timeout executing the tool")));
        assertThat(config.logRequests(), optionalValue(is(false)));
        assertThat(config.logResponses(), optionalValue(is(false)));
        assertThat(config.autoHealthCheck(), is(true));
        assertThat(config.autoHealthCheckInterval(), is(Duration.ofSeconds(30)));
        assertThat(config.protocolDetectionTimeout().isEmpty(), is(true));
        assertThat(config.cacheToolList(), is(true));
        assertThat(config.cacheResourceList(), is(true));
        assertThat(config.cachePromptList(), is(true));
        assertThat(config.subscribeToToolListChanges(), is(true));
        assertThat(config.subscribeToResourceListChanges(), is(true));
        assertThat(config.subscribeToPromptListChanges(), is(true));
        assertThat(config.multiRoundTripMaxRetries(), is(3));
        assertThat(config.subsidiaryChannel(), is(false));
        assertThat(config.customHeaders().isEmpty(), is(true));
        assertThat(config.roots().isEmpty(), is(true));
    }

    @Test
    void testDerivedProtocolDetectionTimeoutRemainsUnset() {
        var config = McpClientConfig.create(Config.just("""
                uri: http://localhost/mcp
                initialization-timeout: PT8S
                """, APPLICATION_YAML));
        assertThat(config.initializationTimeout(), optionalValue(is(Duration.ofSeconds(8))));
        assertThat(config.protocolDetectionTimeout().isEmpty(), is(true));
    }

    @Test
    void testExplicitZeroOverrides() {
        var config = McpClientConfig.create(Config.just("""
                uri: http://localhost/mcp
                tool-execution-timeout: PT0S
                resources-timeout: PT0S
                prompts-timeout: PT0S
                multi-round-trip-max-retries: 0
                """, APPLICATION_YAML));
        assertThat(config.toolExecutionTimeout(), optionalValue(is(Duration.ZERO)));
        assertThat(config.resourcesTimeout(), optionalValue(is(Duration.ZERO)));
        assertThat(config.promptsTimeout(), optionalValue(is(Duration.ZERO)));
        assertThat(config.multiRoundTripMaxRetries(), is(0));
    }
}
