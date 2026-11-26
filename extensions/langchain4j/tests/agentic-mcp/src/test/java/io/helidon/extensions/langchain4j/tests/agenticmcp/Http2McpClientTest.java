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

package io.helidon.extensions.langchain4j.tests.agenticmcp;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.configurable.Resource;
import io.helidon.config.Config;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http2.Http2Config;
import io.helidon.webserver.http2.Http2ConnectionSelector;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.Test;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_YAML;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

class Http2McpClientTest {
    @Test
    void negotiatesHttp2OnFirstMcpRequest() {
        AtomicReference<String> firstProtocol = new AtomicReference<>();
        WebServer server = WebServer.builder()
                .host("localhost")
                .port(0)
                .protocolsDiscoverServices(false)
                .addConnectionSelector(Http2ConnectionSelector.builder().http2Config(Http2Config.create()).build())
                .tls(tls -> tls.applicationProtocols(List.of("h2"))
                        .privateKey(key -> key.keystore(store -> store.keyAlias("mcp-server")
                                .passphrase("changeit").keystore(Resource.create("mcp-server.p12"))))
                        .privateKeyCertChain(key -> key.keystore(store -> store.certChainAlias("mcp-server")
                                .passphrase("changeit").keystore(Resource.create("mcp-server.p12")))))
                .routing(rules -> {
                    rules.post("/cli", (req, res) -> {
                        firstProtocol.compareAndSet(null, req.prologue().protocolVersion());
                        res.next();
                    });
                    SimpleMcpServer.create().routing(rules);
                })
                .build()
                .start();
        try {
            Config config = Config.just("""
                    langchain4j:
                      mcp-clients:
                        tools:
                          uri: https://localhost:%d/cli
                          initialization-timeout: PT5S
                          auto-health-check: false
                          web-client:
                            read-timeout: PT10S
                          tls:
                            trust-all: true
                    """.formatted(server.port()), APPLICATION_YAML);
            var manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                    .putContractInstance(Config.class, config)
                    .build());
            try {
                McpClient client = manager.registry().get(McpClient.class, Qualifier.createNamed("tools"));
                assertThat(firstProtocol.get(), is("2.0"));
                assertThat(client.listTools().stream().map(ToolSpecification::name).toList(),
                           hasItem("getLatestHelidonVersion"));
            } finally {
                manager.shutdown();
            }
        } finally {
            server.stop();
        }
    }
}
