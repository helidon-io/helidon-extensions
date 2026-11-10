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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import io.helidon.common.configurable.Resource;
import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;
import io.helidon.config.Config;
import io.helidon.http.HeaderNames;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.Test;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_YAML;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpClientFactoryTest {

    @Test
    void testDefaultTransportUsesNativeConfigurationAndContextService() {
        try (McpServer server = new McpServer(false)) {
            McpClientFactory factory = factory(server, """
                    custom-headers:
                      Authorization: Bearer static-identity
                      X-Api-Key: static-key
                    web-client:
                      default-headers:
                        X-Client: native-client
                      services:
                        context:
                          records:
                            - header: Authorization
                              classifier: bearer
                    """);
            try {
                Context context = Context.create();
                context.register("bearer", "Bearer test-caller");
                assertThat(Contexts.runInContext(context, () -> {
                    McpClient client = factory.services().getFirst().get();
                    return client.listTools();
                }), empty());
                assertThat(server.initializeAuthorization.get(), is("Bearer test-caller"));
                assertThat(server.authorization.get(), is("Bearer test-caller"));
                assertThat(server.clientHeader.get(), is("native-client"));
                assertThat(server.customHeader.get(), is("static-key"));
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testStaticAuthorizationUsedWhenNoContextIsBound() {
        try (McpServer server = new McpServer(false)) {
            McpClientFactory factory = factory(server, """
                    custom-headers:
                      Authorization: Bearer static-identity
                    web-client:
                      services:
                        context:
                          records:
                            - header: Authorization
                              classifier: bearer
                    """);
            try {
                assertThat(factory.services().getFirst().get().listTools(), empty());
                assertThat(server.initializeAuthorization.get(), is("Bearer static-identity"));
                assertThat(server.authorization.get(), is("Bearer static-identity"));
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testDisabledAutomaticHealthCheck() throws Exception {
        try (McpServer server = new McpServer(false)) {
            McpClientFactory factory = factory(server, """
                    auto-health-check: false
                    auto-health-check-interval: PT0.1S
                    """);
            try {
                factory.services();
                assertThat(server.healthCheck.await(500, TimeUnit.MILLISECONDS), is(false));
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testAutomaticHealthCheckInterval() throws Exception {
        try (McpServer server = new McpServer(false)) {
            McpClientFactory factory = factory(server, """
                    auto-health-check: true
                    auto-health-check-interval: PT0.1S
                    """);
            try {
                factory.services();
                assertThat(server.healthCheck.await(5, TimeUnit.SECONDS), is(true));
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testSubsidiaryChannelOption() throws Exception {
        try (McpServer server = new McpServer(false)) {
            McpClientFactory factory = factory(server, """
                    protocol-version: "2025-11-25"
                    subsidiary-channel: true
                    auto-health-check: false
                    """);
            try {
                assertThat(factory.services().getFirst().get().listTools(), empty());
                assertThat(server.subsidiaryRequest.await(5, TimeUnit.SECONDS), is(true));
            } finally {
                factory.close();
            }
        }
        try (McpServer server = new McpServer(false)) {
            McpClientFactory factory = factory(server, """
                    protocol-version: "2025-11-25"
                    subsidiary-channel: false
                    auto-health-check: false
                    """);
            try {
                assertThat(factory.services().getFirst().get().listTools(), empty());
                assertThat(server.subsidiaryRequest.await(500, TimeUnit.MILLISECONDS), is(false));
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testDisabledToolListCache() {
        try (McpServer server = new McpServer(false)) {
            McpClientFactory factory = factory(server, "cache-tool-list: false\n");
            try {
                McpClient client = factory.services().getFirst().get();
                int before = server.toolLists.get();
                assertThat(client.listTools(), empty());
                assertThat(client.listTools(), empty());
                assertThat(server.toolLists.get() - before, is(2));
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testConfiguredRootsAreReturnedToServer() throws Exception {
        try (McpServer server = new McpServer(false)) {
            server.requestRoots.set(true);
            McpClientFactory factory = factory(server, """
                    protocol-version: "2025-11-25"
                    roots:
                      - uri: file:///tmp/project
                        name: project
                      - uri: file:///tmp/shared
                    """);
            try {
                assertThat(factory.services().getFirst().get().listTools(), empty());
                assertThat(server.rootsReturned.await(5, TimeUnit.SECONDS), is(true));
                String result = server.rootsResponse.get();
                assertThat(result, containsString("\"uri\":\"file:///tmp/project\""));
                assertThat(result, containsString("\"name\":\"project\""));
                assertThat(result, containsString("\"uri\":\"file:///tmp/shared\""));
                assertThat(result, containsString("\"name\":\"file:///tmp/shared\""));
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testProtocolDetectionTimeoutFallsBackWhileDiscoveryIsBlocked() throws Exception {
        try (McpServer server = new McpServer(false);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.blockDiscovery.set(true);
            var config = Config.just("""
                    langchain4j:
                      mcp-clients:
                        tools:
                          uri: %s
                          initialization-timeout: PT10S
                          protocol-detection-timeout: PT0.1S
                          auto-health-check: false
                    """.formatted(server.uri()), APPLICATION_YAML);
            var factory = new McpClientFactory(config);
            try {
                var result = executor.submit(factory::services);
                assertThat(server.discoveryStarted.await(5, TimeUnit.SECONDS), is(true));
                assertThat(result.get(5, TimeUnit.SECONDS).size(), is(1));
                assertThat(server.releaseDiscovery.getCount(), is(1L));
            } finally {
                server.releaseDiscovery.countDown();
                factory.close();
            }
        }
    }

    @Test
    void testProtocolDetectionTimeoutFollowsConfiguredInitializationTimeout() throws Exception {
        try (McpServer server = new McpServer(false);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.blockDiscovery.set(true);
            var config = Config.just("""
                    langchain4j:
                      mcp-clients:
                        tools:
                          uri: %s
                          initialization-timeout: PT1S
                          auto-health-check: false
                    """.formatted(server.uri()), APPLICATION_YAML);
            var factory = new McpClientFactory(config);
            try {
                var result = executor.submit(factory::services);
                assertThat(server.discoveryStarted.await(5, TimeUnit.SECONDS), is(true));
                assertThat(result.get(5, TimeUnit.SECONDS).size(), is(1));
                assertThat(server.releaseDiscovery.getCount(), is(1L));
            } finally {
                server.releaseDiscovery.countDown();
                factory.close();
            }
        }
    }

    @Test
    void testRootTlsOverridesNativeTls() {
        try (McpServer server = new McpServer(true)) {
            McpClientFactory factory = factory(server, """
                    tls:
                      trust-all: true
                      endpoint-identification-algorithm: NONE
                    web-client:
                      tls:
                        trust-all: false
                    """);
            try {
                assertThat(factory.services().getFirst().get().listTools(), empty());
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testNativeTlsConfiguration() {
        try (McpServer server = new McpServer(true)) {
            McpClientFactory factory = factory(server, """
                    web-client:
                      tls:
                        trust-all: true
                        endpoint-identification-algorithm: NONE
                    """);
            try {
                assertThat(factory.services().getFirst().get().listTools(), empty());
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testUntrustedServerIsRejected() {
        try (McpServer server = new McpServer(true)) {
            McpClientFactory factory = factory(server, "");
            try {
                assertThrows(RuntimeException.class, factory::services);
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testDisabledLegacyTlsDoesNotOverrideNativeTls() {
        try (McpServer server = new McpServer(true)) {
            McpClientFactory factory = factory(server, """
                    tls:
                      enabled: false
                    web-client:
                      tls:
                        trust-all: true
                        endpoint-identification-algorithm: NONE
                    """);
            try {
                assertThat(factory.services().getFirst().get().listTools(), empty());
            } finally {
                factory.close();
            }
        }
    }

    @Test
    void testFactoryShutdownClosesClientsAndPreventsRecreation() {
        try (McpServer server = new McpServer(false)) {
            McpClientFactory factory = factory(server, "");
            McpClient client = factory.services().getFirst().get();
            assertThat(factory.services().getFirst().get(), sameInstance(client));
            assertThat(client.listTools(), empty());
            factory.close();
            factory.close();
            assertThrows(IllegalStateException.class, client::listTools);
            assertThrows(IllegalStateException.class, factory::services);
        }
    }

    @Test
    void testShutdownBeforeDiscoveryDoesNotCreateClients() {
        McpClientFactory factory = new McpClientFactory(Config.just("""
                langchain4j:
                  mcp-clients:
                    unavailable:
                      uri: http://127.0.0.1:1/mcp
                """, APPLICATION_YAML));
        factory.close();
        assertThrows(IllegalStateException.class, factory::services);
    }

    @Test
    void testRegistryShutdownClosesConfiguredClient() {
        try (McpServer server = new McpServer(false)) {
            var manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                    .putContractInstance(Config.class, configuration(server, ""))
                    .build());
            McpClient client;
            try {
                client = manager.registry().get(McpClient.class, Qualifier.createNamed("tools"));
                assertThat(client.listTools(), empty());
            } finally {
                manager.shutdown();
            }
            assertThrows(IllegalStateException.class, client::listTools);
        }
    }

    private static McpClientFactory factory(McpServer server, String options) {
        return new McpClientFactory(configuration(server, options));
    }

    private static Config configuration(McpServer server, String options) {
        String config = """
                langchain4j:
                  mcp-clients:
                    tools:
                      uri: %s
                      initialization-timeout: PT2S
                      tool-execution-timeout: PT2S
                      resources-timeout: PT2S
                """.formatted(server.uri()) + options.indent(6);
        return Config.just(config, APPLICATION_YAML);
    }

    private static final class McpServer implements AutoCloseable {
        private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

        private final WebServer server;
        private final String scheme;
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> initializeAuthorization = new AtomicReference<>();
        private final AtomicReference<String> clientHeader = new AtomicReference<>();
        private final AtomicReference<String> customHeader = new AtomicReference<>();
        private final CountDownLatch healthCheck = new CountDownLatch(1);
        private final AtomicInteger toolLists = new AtomicInteger();
        private final AtomicBoolean requestRoots = new AtomicBoolean();
        private final CountDownLatch rootsReturned = new CountDownLatch(1);
        private final AtomicReference<String> rootsResponse = new AtomicReference<>();
        private final AtomicBoolean blockDiscovery = new AtomicBoolean();
        private final CountDownLatch discoveryStarted = new CountDownLatch(1);
        private final CountDownLatch releaseDiscovery = new CountDownLatch(1);
        private final CountDownLatch subsidiaryRequest = new CountDownLatch(1);

        private McpServer(boolean tls) {
            scheme = tls ? "https" : "http";
            var builder = WebServer.builder()
                    .host("localhost")
                    .port(0)
                    .routing(routing -> routing.post("/mcp", this::handle)
                            .get("/mcp", (_, response) -> {
                                subsidiaryRequest.countDown();
                                response.status(405).send();
                            }));
            if (tls) {
                builder.tls(config -> config
                        .privateKey(key -> key.keystore(store -> store
                                .keyAlias("mcp-server")
                                .passphrase("changeit")
                                .keystore(Resource.create("mcp-server.p12"))))
                        .privateKeyCertChain(key -> key.keystore(store -> store
                                .certChainAlias("mcp-server")
                                .passphrase("changeit")
                                .keystore(Resource.create("mcp-server.p12")))));
            }
            server = builder.build().start();
        }

        @Override
        public void close() {
            server.stop();
        }

        private String uri() {
            return scheme + "://localhost:" + server.port() + "/mcp";
        }

        private void handle(ServerRequest request, ServerResponse response) {
            String body = request.content().as(String.class);
            if (body.contains("\"id\":900") && body.contains("\"result\"")) {
                rootsResponse.set(body);
                rootsReturned.countDown();
                response.status(202).send();
                return;
            }
            var matcher = ID.matcher(body);
            if (!matcher.find()) {
                response.status(202).send();
                return;
            }
            if (body.contains("\"server/discover\"")) {
                if (blockDiscovery.get()) {
                    discoveryStarted.countDown();
                    try {
                        if (!releaseDiscovery.await(15, TimeUnit.SECONDS)) {
                            response.status(503).send();
                            return;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                response.header(HeaderNames.CONTENT_TYPE, "application/json")
                        .send("{\"jsonrpc\":\"2.0\",\"id\":" + matcher.group(1)
                                      + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
                return;
            }
            String result;
            if (body.contains("\"ping\"")) {
                healthCheck.countDown();
            }
            if (body.contains("\"tools/list\"")) {
                toolLists.incrementAndGet();
                if (requestRoots.getAndSet(false)) {
                    response.header(HeaderNames.CONTENT_TYPE, "text/event-stream")
                            .header(HeaderNames.create("Mcp-Session-Id"), "test-session")
                            .send("data: {\"jsonrpc\":\"2.0\",\"id\":900,\"method\":\"roots/list\"}\n\n"
                                          + "data: {\"jsonrpc\":\"2.0\",\"id\":" + matcher.group(1)
                                          + ",\"result\":{\"tools\":[]}}\n\n");
                    return;
                }
            }
            if (body.contains("\"initialize\"")) {
                initializeAuthorization.set(request.headers().first(HeaderNames.AUTHORIZATION).orElse(""));
                result = """
                        {"protocolVersion":"2025-11-25","capabilities":{"tools":{}},
                         "serverInfo":{"name":"factory-test","version":"1"}}
                        """;
            } else {
                authorization.set(request.headers().first(HeaderNames.AUTHORIZATION).orElse(""));
                clientHeader.set(request.headers().first(HeaderNames.create("X-Client")).orElse(""));
                customHeader.set(request.headers().first(HeaderNames.create("X-Api-Key")).orElse(""));
                result = "{\"tools\":[]}";
            }
            response.header(HeaderNames.CONTENT_TYPE, "application/json")
                    .header(HeaderNames.create("Mcp-Session-Id"), "test-session")
                    .send("{\"jsonrpc\":\"2.0\",\"id\":" + matcher.group(1) + ",\"result\":" + result + "}");
        }
    }
}
