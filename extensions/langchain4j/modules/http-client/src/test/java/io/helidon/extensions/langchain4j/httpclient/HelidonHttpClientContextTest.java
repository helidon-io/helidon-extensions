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

package io.helidon.extensions.langchain4j.httpclient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import dev.langchain4j.mcp.protocol.McpListToolsRequest;
import dev.langchain4j.spi.ExecutorProvider;
import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.context.WebClientContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class HelidonHttpClientContextTest {
    private static final String INITIALIZE_RESULT =
            "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"serverInfo\":{\"name\":\"proof\",\"version\":\"1\"}}}";

    private ExecutorProvider previousProvider;

    @BeforeEach
    void saveProvider() {
        previousProvider = ExecutorProvider.get();
    }

    @AfterEach
    void restoreProvider() {
        ExecutorProvider.set(previousProvider);
    }

    @Test
    void discovered_provider_keeps_connection_and_request_headers_isolated() throws Exception {
        runScenario(true);
    }

    @Test
    void without_capture_queued_requests_inherit_connection_context() throws Exception {
        runScenario(false);
    }

    private static void runScenario(boolean captureContext) throws Exception {
        if (captureContext) {
            // Exercise the production provider discovered from this module.
            ExecutorProvider.set(null);
        } else {
            // Disable capture explicitly even if a provider is discovered through ServiceLoader.
            ExecutorProvider.set(() -> null);
        }
        try (ProofServer server = new ProofServer()) {
            server.start();
            WebClient webClient = WebClient.create(builder -> builder
                    .baseUri(server.url())
                    .servicesDiscoverServices(false)
                    .addService(WebClientContextService.create(context -> context.addRecord(record -> record
                            .header(HeaderNames.create("X-Identity"))
                            .classifier("identity")))));
            try {
                HelidonHttpClient adapter = HelidonHttpClient.builder().webClient(webClient).build();
                try (adapter) {
                    try (StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
                            .url(server.url())
                            .httpClient(adapter)
                            .build()) {
                        transport.start(new McpOperationHandler(
                                new ConcurrentHashMap<>(),
                                List::of,
                                transport,
                                null,
                                () -> {},
                                null,
                                () -> {},
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
                        CompletableFuture<String> initialized = Contexts.runInContext(
                                context("connection"),
                                () -> transport.sendInitializeRequest(new McpInitializeRequest(0L)));
                        assertThat(server.firstInitializeSeen.await(5, TimeUnit.SECONDS), is(true));
                        CompletableFuture<String> a = Contexts.runInContext(
                                context("user-a"),
                                () -> transport.sendRequest(new McpListToolsRequest(1L, null)));
                        CompletableFuture<String> b = Contexts.runInContext(
                                context("user-b"),
                                () -> transport.sendRequest(new McpListToolsRequest(2L, null)));
                        CompletableFuture<String> unbound = transport.sendRequest(new McpListToolsRequest(3L, null));
                        server.releaseFirstInitialize.countDown();

                        assertThat(initialized.get(10, TimeUnit.SECONDS), is(INITIALIZE_RESULT));
                        assertThat(a.get(10, TimeUnit.SECONDS), is(toolsResult(1)));
                        assertThat(b.get(10, TimeUnit.SECONDS), is(toolsResult(2)));
                        assertThat(unbound.get(10, TimeUnit.SECONDS), is(toolsResult(3)));
                        List<Observation> observations = server.awaitObservations(8);
                        assertThat(header(observations, "initialize-1"), is("connection"));
                        assertThat(header(observations, "initialize-2"), is("connection"));
                        assertThat(header(observations, "initialized-1"), is("connection"));
                        assertThat(header(observations, "initialized-2"), is("connection"));
                        if (captureContext) {
                            assertThat(header(observations, "A-1"), is("user-a"));
                            assertThat(header(observations, "A-2"), is("user-a"));
                            assertThat(header(observations, "B"), is("user-b"));
                            assertThat(header(observations, "unbound"), is((String) null));
                        } else {
                            assertThat(header(observations, "A-1"), is("connection"));
                            assertThat(header(observations, "A-2"), is("connection"));
                            assertThat(header(observations, "B"), is("connection"));
                            assertThat(header(observations, "unbound"), is("connection"));
                        }
                    } finally {
                        server.releaseFirstInitialize.countDown();
                    }
                }
            } finally {
                webClient.closeResource();
            }
            assertThat("server handler failure", server.failure.get(), is((Throwable) null));
        }
    }

    private static Context context(String identity) {
        Context context = Context.create();
        context.register("identity", identity);
        return context;
    }

    private static String header(List<Observation> observations, String label) {
        return observations.stream()
                .filter(observation -> label.equals(observation.label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + label + " in " + observations))
                .identity;
    }

    private static String toolsResult(long id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[]}}";
    }

    private static final class Observation {
        private final String label;
        private final String identity;

        private Observation(String label, String identity) {
            this.label = label;
            this.identity = identity;
        }

        @Override
        public String toString() {
            return label + '=' + identity;
        }
    }

    private static final class ProofServer implements AutoCloseable {
        private final CountDownLatch firstInitializeSeen = new CountDownLatch(1);
        private final CountDownLatch releaseFirstInitialize = new CountDownLatch(1);
        private final BlockingQueue<Observation> arrivals = new LinkedBlockingQueue<>();
        private final AtomicInteger initializeCount = new AtomicInteger();
        private final AtomicInteger initializedCount = new AtomicInteger();
        private final AtomicInteger callsA = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private HttpServer server;
        private ExecutorService executor;

        @Override
        public void close() {
            releaseFirstInitialize.countDown();
            if (server != null) {
                server.stop(0);
            }
            if (executor != null) {
                executor.close();
            }
        }

        private void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/mcp", exchange -> {
                try {
                    handle(exchange);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    if (error instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IOException(error);
                }
            });
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        private List<Observation> awaitObservations(int count) throws InterruptedException {
            List<Observation> observed = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Observation next = arrivals.poll(5, TimeUnit.SECONDS);
                assertThat("physical request " + (i + 1), next != null, is(true));
                observed.add(next);
            }
            return observed;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String identity = exchange.getRequestHeaders().getFirst("X-Identity");
            String label;
            int status;
            String result;
            if (body.contains("\"method\":\"initialize\"")) {
                int ordinal = initializeCount.incrementAndGet();
                label = "initialize-" + ordinal;
                status = 200;
                result = INITIALIZE_RESULT;
                if (ordinal == 1) {
                    arrivals.add(new Observation(label, identity));
                    firstInitializeSeen.countDown();
                    await(releaseFirstInitialize);
                }
            } else if (body.contains("notifications/initialized")) {
                label = "initialized-" + initializedCount.incrementAndGet();
                status = 202;
                result = null;
            } else if (body.contains("\"id\":1")) {
                int ordinal = callsA.incrementAndGet();
                label = "A-" + ordinal;
                status = ordinal == 1 ? 404 : 200;
                result = ordinal == 1 ? "expired" : toolsResult(1);
            } else if (body.contains("\"id\":2")) {
                label = "B";
                status = 200;
                result = toolsResult(2);
            } else if (body.contains("\"id\":3")) {
                label = "unbound";
                status = 200;
                result = toolsResult(3);
            } else {
                throw new IOException("unexpected request: " + body);
            }
            if (!"initialize-1".equals(label)) {
                arrivals.add(new Observation(label, identity));
            }
            byte[] bytes = result == null ? new byte[0] : result.getBytes(StandardCharsets.UTF_8);
            if (result != null) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(status, result == null ? -1 : bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private void await(CountDownLatch latch) throws IOException {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("initialization was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

    }
}
