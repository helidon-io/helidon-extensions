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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import io.helidon.common.tls.Tls;
import io.helidon.config.Config;
import io.helidon.extensions.langchain4j.httpclient.HelidonHttpClient;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.api.WebClientConfig;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpRoot;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

@Service.Singleton
class McpClientFactory implements Service.ServicesFactory<McpClient> {
    private static final System.Logger LOGGER = System.getLogger(McpClientFactory.class.getName());
    private static final String CONFIG_ROOT = "langchain4j.mcp-clients";
    private final Config config;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final List<ManagedClient> managedClients = new ArrayList<>();
    private List<Service.QualifiedInstance<McpClient>> clients = List.of();
    private boolean initialized;
    private boolean closed;

    McpClientFactory(Config config) {
        this.config = config;
    }

    static Stream<McpClientConfig> configure(Config config) {
        return config.get(CONFIG_ROOT)
                .asNodeList()
                .orElse(List.of())
                .stream()
                .map(c -> McpClientConfig.builder()
                        .key(c.key().name())
                        .config(c)
                        .build()
                );
    }

    @Override
    public List<Service.QualifiedInstance<McpClient>> services() {
        lifecycleLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("MCP client factory is closed");
            }
            if (!initialized) {
                try {
                    for (McpClientConfig clientConfig : configure(config).toList()) {
                        managedClients.add(create(clientConfig));
                    }
                    clients = managedClients.stream()
                            .map(client -> Service.QualifiedInstance.create(client.client,
                                                                          Qualifier.createNamed(client.client.key())))
                            .toList();
                    initialized = true;
                } catch (RuntimeException | Error failure) {
                    managedClients.reversed().forEach(ManagedClient::close);
                    managedClients.clear();
                    throw failure;
                }
            }
            return clients;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Service.PreDestroy
    void close() {
        List<ManagedClient> toClose;
        lifecycleLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            toClose = List.copyOf(managedClients);
            managedClients.clear();
            clients = List.of();
        } finally {
            lifecycleLock.unlock();
        }
        toClose.reversed().forEach(ManagedClient::close);
    }

    private static StreamableHttpMcpTransport createTransport(McpClientConfig mcpClientConfig,
                                                            HelidonHttpClient httpClient) {
        StreamableHttpMcpTransport.Builder transport = new StreamableHttpMcpTransport.Builder()
                .url(mcpClientConfig.uri().toString())
                .httpClient(httpClient);

        mcpClientConfig.logRequests().ifPresent(transport::logRequests);
        mcpClientConfig.logResponses().ifPresent(transport::logResponses);
        transport.subsidiaryChannel(mcpClientConfig.subsidiaryChannel());
        transport.customHeaders(mcpClientConfig.customHeaders());
        return transport.build();
    }

    private static ManagedClient create(McpClientConfig mcpClientConfig) {
        WebClientConfig.Builder webClientBuilder = WebClient.builder().from(mcpClientConfig.webClientConfig());
        mcpClientConfig.tls().filter(Tls::enabled).ifPresent(webClientBuilder::tls);
        WebClient webClient = webClientBuilder.build();
        HelidonHttpClient adapter = HelidonHttpClient.builder().webClient(webClient).build();
        StreamableHttpMcpTransport transport = null;
        try {
            transport = createTransport(mcpClientConfig, adapter);
            DefaultMcpClient.Builder builder = new DefaultMcpClient.Builder().transport(transport);

            mcpClientConfig.key().ifPresent(builder::key);
            mcpClientConfig.clientVersion().ifPresent(builder::clientVersion);
            mcpClientConfig.clientName().ifPresent(builder::clientName);
            mcpClientConfig.protocolVersion().ifPresent(builder::protocolVersion);
            mcpClientConfig.toolExecutionTimeoutErrorMessage().ifPresent(builder::toolExecutionTimeoutErrorMessage);
            mcpClientConfig.initializationTimeout().ifPresent(builder::initializationTimeout);
            mcpClientConfig.protocolDetectionTimeout().ifPresent(builder::protocolDetectionTimeout);
            mcpClientConfig.toolExecutionTimeout().ifPresent(builder::toolExecutionTimeout);
            mcpClientConfig.pingTimeout().ifPresent(builder::pingTimeout);
            mcpClientConfig.promptsTimeout().ifPresent(builder::promptsTimeout);
            mcpClientConfig.reconnectInterval().ifPresent(builder::reconnectInterval);
            mcpClientConfig.resourcesTimeout().ifPresent(builder::resourcesTimeout);
            builder.autoHealthCheck(mcpClientConfig.autoHealthCheck());
            builder.autoHealthCheckInterval(mcpClientConfig.autoHealthCheckInterval());
            builder.cacheToolList(mcpClientConfig.cacheToolList());
            builder.cacheResourceList(mcpClientConfig.cacheResourceList());
            builder.cachePromptList(mcpClientConfig.cachePromptList());
            builder.subscribeToToolListChanges(mcpClientConfig.subscribeToToolListChanges());
            builder.subscribeToResourceListChanges(mcpClientConfig.subscribeToResourceListChanges());
            builder.subscribeToPromptListChanges(mcpClientConfig.subscribeToPromptListChanges());
            builder.multiRoundTripMaxRetries(mcpClientConfig.multiRoundTripMaxRetries());
            builder.roots(mcpClientConfig.roots().stream()
                                  .map(root -> new McpRoot(root.name().orElseGet(() -> root.uri().toString()),
                                                          root.uri().toString()))
                                  .toList());

            return new ManagedClient(builder.build(), adapter, webClient);
        } catch (RuntimeException | Error failure) {
            if (transport != null) {
                closeResource(transport);
            }
            closeResource(adapter);
            closeResource(webClient::closeResource);
            throw failure;
        }
    }

    private static void closeResource(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to close MCP client resource", e);
        }
    }

    private static final class ManagedClient {
        private final McpClient client;
        private final HelidonHttpClient adapter;
        private final WebClient webClient;

        private ManagedClient(McpClient client, HelidonHttpClient adapter, WebClient webClient) {
            this.client = client;
            this.adapter = adapter;
            this.webClient = webClient;
        }

        private void close() {
            closeResource(client);
            closeResource(adapter);
            closeResource(webClient::closeResource);
        }
    }
}
