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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.helidon.extensions.mcp.server.McpServerFeature;
import io.helidon.extensions.mcp.server.McpTool;
import io.helidon.extensions.mcp.server.McpToolRequest;
import io.helidon.extensions.mcp.server.McpToolResult;
import io.helidon.security.SecurityContext;
import io.helidon.tracing.SpanContext;
import io.helidon.tracing.Tracer;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.observe.ObserveFeature;
import io.helidon.webserver.observe.tracing.TracingObserver;
import io.helidon.webserver.security.PathsConfig;
import io.helidon.webserver.security.SecurityFeature;

/**
 * A real Helidon MCP server protected by the shared Keycloak-backed verifier.
 */
final class SecuredMcpServer implements AutoCloseable {
    private static final String MCP_PATH = "/mcp";
    private static final String TOOL_NAME = "whoami";
    private static final String TOOL_SCHEMA = """
            {"type":"object","properties":{"nonce":{"type":"string"}},"required":["nonce"]}
            """;

    private final BlockingQueue<Observation> observations;
    private final WebServer server;

    private SecuredMcpServer(WebServer server, BlockingQueue<Observation> observations) {
        this.server = server;
        this.observations = observations;
    }

    static SecuredMcpServer start(URI issuerUri, String audience, Tracer tracer) {
        Objects.requireNonNull(issuerUri);
        Objects.requireNonNull(audience);
        Objects.requireNonNull(tracer);

        BlockingQueue<Observation> observations = new LinkedBlockingQueue<>();
        McpTool tool = new WhoAmITool(observations);
        WebServer server = WebServer.builder()
                .host("127.0.0.1")
                .port(0)
                .featuresDiscoverServices(false)
                .addFeature(ObserveFeature.just(TracingObserver.create(tracer)))
                .addFeature(SecurityFeature.builder()
                        .security(PropagationTestSupport.security(issuerUri, audience))
                        .addPath(PathsConfig.builder()
                                .path(MCP_PATH)
                                .handler(SecurityFeature.authenticate())
                                .build())
                        .addPath(PathsConfig.builder()
                                .path(MCP_PATH + "/*")
                                .handler(SecurityFeature.authenticate())
                                .build())
                        .build())
                .routing(routing -> routing.addFeature(McpServerFeature.builder()
                        .path(MCP_PATH)
                        .name("secured-mcp-e2e")
                        .addTool(tool)
                        .build()))
                .build()
                .start();
        return new SecuredMcpServer(server, observations);
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.port() + MCP_PATH);
    }

    Observation takeObservation(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);
        return observations.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        server.stop();
    }

    record Observation(String principal,
                       String tokenFingerprint,
                       String traceId,
                       String parentSpanId,
                       String serverSpanId,
                       String traceState,
                       String nonce) {
    }

    private static final class WhoAmITool implements McpTool {
        private final BlockingQueue<Observation> observations;
        private final CountDownLatch concurrentCalls = new CountDownLatch(2);

        private WhoAmITool(BlockingQueue<Observation> observations) {
            this.observations = observations;
        }

        @Override
        public String name() {
            return TOOL_NAME;
        }

        @Override
        public String description() {
            return "Return the authenticated caller and nonce";
        }

        @Override
        public String schema() {
            return TOOL_SCHEMA;
        }

        @Override
        public McpToolResult tool(McpToolRequest request) {
            SecurityContext security = request.requestContext()
                    .get(SecurityContext.class)
                    .filter(SecurityContext::isAuthenticated)
                    .orElseThrow(() -> new IllegalStateException("MCP request was not authenticated"));
            String nonce = request.arguments().get("nonce").asString().orElseThrow();
            if (nonce.endsWith("-concurrent")) {
                concurrentCalls.countDown();
                try {
                    if (!concurrentCalls.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent MCP tools did not overlap");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Concurrent MCP tool interrupted", e);
                }
            }
            Map<String, List<String>> headers = security.env().headers();
            String authorization = firstHeader(headers, "Authorization");
            if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new IllegalStateException("MCP request did not use bearer authentication");
            }
            String fingerprint = fingerprint(authorization.substring(7));
            String[] traceparent = firstHeader(headers, "traceparent").split("-");
            if (traceparent.length != 4) {
                throw new IllegalStateException("Invalid incoming traceparent");
            }
            String traceState = firstHeader(headers, "tracestate");
            SpanContext serverSpan = request.requestContext().get(SpanContext.class).orElseThrow();
            String principal = security.userName();
            observations.add(new Observation(
                    principal, fingerprint, traceparent[1], traceparent[2], serverSpan.spanId(), traceState, nonce));
            return McpToolResult.create(principal + ":" + nonce);
        }

        private static String firstHeader(Map<String, List<String>> headers, String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing MCP request header: " + name));
        }

        private static String fingerprint(String token) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(token.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }
    }
}
