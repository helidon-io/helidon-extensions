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
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;
import io.helidon.security.SecurityContext;
import io.helidon.tracing.SpanContext;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.security.SecurityFeature;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.spi.ExecutorProvider;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Opt-in end-to-end check of bearer and trace propagation through real Helidon ingress,
 * LangChain4j MCP transport, Helidon WebClient, and a secured Helidon MCP server.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class HelidonMcpKeycloakIT {
    private static final String REALM = "lc4j-mcp-e2e";
    private static final String AUDIENCE = "lc4j-mcp-test";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private ExecutorProvider previousProvider;

    @BeforeEach
    void useDiscoveredContextCapture() {
        previousProvider = ExecutorProvider.get();
        // Exercise the production provider discovered from this module.
        ExecutorProvider.set(null);
    }

    @AfterEach
    void restoreProvider() {
        ExecutorProvider.set(previousProvider);
    }

    @Test
    void propagatesKeycloakIdentityAndTraceAcrossConcurrentMcpCalls() throws Exception {
        String baseUrl = System.getProperty("keycloak.url");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("keycloak.url is required when keycloak-e2e is active");
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        URI issuerUri = URI.create(normalizedBase + "/realms/" + REALM);
        try (HttpClient rawHttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            String aliceToken = token(rawHttpClient, issuerUri, ALICE, "alice-test-only");
            String bobToken = token(rawHttpClient, issuerUri, BOB, "bob-test-only");
            assertThat("different principals must receive different tokens", aliceToken.equals(bobToken), is(false));

            try (PropagationTestSupport propagation = PropagationTestSupport.create();
                    SecuredMcpServer mcpServer = SecuredMcpServer.start(issuerUri, AUDIENCE, propagation.tracer())) {
                var webClient = propagation.createWebClient(mcpServer.url());
                try (HelidonHttpClient adapter = HelidonHttpClient.builder().webClient(webClient).build();
                        StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
                                .url(mcpServer.url().toString())
                                .httpClient(adapter)
                                .build()) {
                    IngressState ingressState = new IngressState(transport);
                    WebServerConfig.Builder ingressBuilder = WebServer.builder().host("127.0.0.1").port(0);
                    propagation.configureIngress(ingressBuilder, issuerUri, AUDIENCE);
                    ingressBuilder.routing(routing -> routing.post(
                            "/invoke",
                            SecurityFeature.secure(),
                            (request, response) -> invoke(request, response, ingressState)));
                    WebServer ingress = ingressBuilder.build().start();
                    try {
                        URI ingressUri = URI.create("http://127.0.0.1:" + ingress.port() + "/invoke");
                        assertRejected(rawHttpClient, ingressUri, aliceToken);
                        assertRejected(rawHttpClient, mcpServer.url(), aliceToken);
                        assertThat("rejected requests must not invoke ingress", ingressState.failure.get(), is((Throwable) null));
                        assertThat("rejected requests must not invoke MCP tools",
                                mcpServer.takeObservation(Duration.ofMillis(200)),
                                is((SecuredMcpServer.Observation) null));

                        // DefaultMcpClient initializes in its constructor, inside Alice's authenticated request.
                        String bootstrapNonce = "bootstrap-alice";
                        String bootstrapTrace = "33333333333333333333333333333333";
                        HttpResponse<String> bootstrap = callIngress(
                                rawHttpClient,
                                ingressUri,
                                aliceToken,
                                bootstrapNonce,
                                traceparent(bootstrapTrace, "3333333333333333"),
                                "lc4j=alice");
                        assertThat(bootstrap.statusCode(), is(200));
                        assertThat(bootstrap.body(), is(ALICE + ':' + bootstrapNonce));

                        String aliceNonce = "alice-concurrent";
                        String bobNonce = "bob-concurrent";
                        String aliceTrace = "11111111111111111111111111111111";
                        String bobTrace = "22222222222222222222222222222222";
                        CompletableFuture<HttpResponse<String>> aliceCall = CompletableFuture.supplyAsync(() -> callIngress(
                                rawHttpClient,
                                ingressUri,
                                aliceToken,
                                aliceNonce,
                                traceparent(aliceTrace, "aaaaaaaaaaaaaaaa"),
                                "lc4j=alice"));
                        CompletableFuture<HttpResponse<String>> bobCall = CompletableFuture.supplyAsync(() -> callIngress(
                                rawHttpClient,
                                ingressUri,
                                bobToken,
                                bobNonce,
                                traceparent(bobTrace, "bbbbbbbbbbbbbbbb"),
                                "lc4j=bob"));
                        HttpResponse<String> aliceResponse = aliceCall.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        HttpResponse<String> bobResponse = bobCall.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        assertThat(aliceResponse.statusCode(), is(200));
                        assertThat(bobResponse.statusCode(), is(200));
                        assertThat(aliceResponse.body(), is(ALICE + ':' + aliceNonce));
                        assertThat(bobResponse.body(), is(BOB + ':' + bobNonce));
                        assertThat("ingress handler", ingressState.failure.get(), is((Throwable) null));

                        Map<String, IngressObservation> ingressByNonce = new HashMap<>();
                        Map<String, SecuredMcpServer.Observation> mcpByNonce = new HashMap<>();
                        for (int i = 0; i < 3; i++) {
                            IngressObservation ingressObservation =
                                    ingressState.observations.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                            assertThat(ingressObservation, notNullValue());
                            ingressByNonce.put(ingressObservation.nonce, ingressObservation);
                            SecuredMcpServer.Observation mcpObservation = mcpServer.takeObservation(TIMEOUT);
                            assertThat(mcpObservation, notNullValue());
                            mcpByNonce.put(mcpObservation.nonce(), mcpObservation);
                        }
                        assertThat(mcpByNonce.get(bootstrapNonce).traceState(), is("lc4j=alice"));
                        assertPropagation(
                                ingressByNonce.get(aliceNonce),
                                mcpByNonce.get(aliceNonce),
                                new ExpectedCall(aliceTrace, "aaaaaaaaaaaaaaaa", ALICE, fingerprint(aliceToken)),
                                propagation.spans());
                        assertThat(mcpByNonce.get(aliceNonce).traceState(), is("lc4j=alice"));
                        assertPropagation(
                                ingressByNonce.get(bobNonce),
                                mcpByNonce.get(bobNonce),
                                new ExpectedCall(bobTrace, "bbbbbbbbbbbbbbbb", BOB, fingerprint(bobToken)),
                                propagation.spans());
                        assertThat(mcpByNonce.get(bobNonce).traceState(), is("lc4j=bob"));
                    } finally {
                        ingress.stop();
                        McpClient client = ingressState.clients.get();
                        if (client != null) {
                            client.close();
                        }
                    }
                }
            }
        }
    }

    @Test
    void explicitHealthCheckRetainsKeycloakAuthorizationWithBackgroundChecksDisabled() throws Exception {
        String baseUrl = System.getProperty("keycloak.url");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("keycloak.url is required when keycloak-e2e is active");
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        URI issuerUri = URI.create(normalizedBase + "/realms/" + REALM);
        try (HttpClient rawHttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            String aliceToken = token(rawHttpClient, issuerUri, ALICE, "alice-test-only");
            Context sessionContext = Context.create();
            sessionContext.register(PropagationTestSupport.AUTHORIZATION_CLASSIFIER, "Bearer " + aliceToken);
            AtomicInteger healthyPings = new AtomicInteger();
            McpClientListener listener = new McpClientListener() {
                @Override
                public void afterPing(McpCallContext context) {
                    healthyPings.incrementAndGet();
                }
            };
            try (PropagationTestSupport propagation = PropagationTestSupport.create();
                    SecuredMcpServer mcpServer = SecuredMcpServer.start(issuerUri, AUDIENCE, propagation.tracer())) {
                var webClient = propagation.createWebClient(mcpServer.url());
                try (HelidonHttpClient adapter = HelidonHttpClient.builder().webClient(webClient).build();
                        StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
                                .url(mcpServer.url().toString())
                                .httpClient(adapter)
                                .build();
                        DefaultMcpClient client = Contexts.runInContext(sessionContext, () -> new DefaultMcpClient.Builder()
                                .transport(transport)
                                .protocolVersion("2025-11-25")
                                // Equivalent to auto-health-check: false until the pinned dependency fixes
                                // https://github.com/langchain4j/langchain4j/issues/6984.
                                .autoHealthCheck(false)
                                .addListener(listener)
                                .build())) {
                    Contexts.runInContext(sessionContext, client::checkHealth);
                    assertThat("explicit health check completed with authentication", healthyPings.get(), is(1));
                }
            }
        }
    }

    private static void invoke(ServerRequest request, ServerResponse response, IngressState state) {
        try {
            String nonce = new String(request.content().inputStream().readAllBytes(), StandardCharsets.UTF_8);
            String principal = request.context()
                    .get(SecurityContext.class)
                    .orElseThrow()
                    .userPrincipal()
                    .orElseThrow()
                    .getName();
            SpanContext span = request.context().get(SpanContext.class).orElseThrow();
            McpClient client = getOrCreate(state.clients, state.creationLock, state.transport);
            if (nonce.endsWith("-concurrent")) {
                state.concurrentArrivals.countDown();
                if (!state.concurrentArrivals.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Concurrent ingress calls did not overlap");
                }
            }
            String result = client.executeTool(ToolExecutionRequest.builder()
                            .name("whoami")
                            .arguments("{\"nonce\":\"" + nonce + "\"}")
                            .build())
                    .resultText();
            state.observations.add(new IngressObservation(nonce, principal, span.traceId(), span.spanId()));
            response.send(result);
        } catch (Throwable error) {
            state.failure.compareAndSet(null, error);
            response.status(500).send("MCP invocation failed");
        }
    }

    private static McpClient getOrCreate(
            AtomicReference<McpClient> clients,
            ReentrantLock lock,
            StreamableHttpMcpTransport transport) {
        McpClient client = clients.get();
        if (client != null) {
            return client;
        }
        lock.lock();
        try {
            client = clients.get();
            if (client == null) {
                client = new DefaultMcpClient.Builder()
                        .transport(transport)
                        // Workaround for https://github.com/langchain4j/langchain4j/issues/6984:
                        // background maintenance has no caller context in the pinned dependency.
                        .autoHealthCheck(false)
                        .build();
                clients.set(client);
            }
            return client;
        } finally {
            lock.unlock();
        }
    }

    private static String token(HttpClient client, URI issuerUri, String username, String password) throws Exception {
        String form = "grant_type=password&client_id=" + encode(AUDIENCE)
                + "&scope=openid&username=" + encode(username)
                + "&password=" + encode(password);
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuerUri + "/protocol/openid-connect/token"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat("Keycloak password grant for " + username, response.statusCode(), is(200));
        Matcher matcher = ACCESS_TOKEN.matcher(response.body());
        assertThat("Keycloak access token present for " + username, matcher.find(), is(true));
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> callIngress(
            HttpClient client, URI uri, String token, String nonce, String traceparent, String tracestate) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("traceparent", traceparent)
                .header("tracestate", tracestate)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(nonce))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ingress call interrupted", e);
        }
    }

    private static String traceparent(String traceId, String parentSpanId) {
        return "00-" + traceId + '-' + parentSpanId + "-01";
    }

    private static void assertRejected(HttpClient client, URI uri, String validToken) throws Exception {
        HttpRequest missing = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpRequest tampered = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tamperSignature(validToken))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        assertThat(client.send(missing, HttpResponse.BodyHandlers.discarding()).statusCode(), anyOf(is(401), is(403)));
        assertThat(client.send(tampered, HttpResponse.BodyHandlers.discarding()).statusCode(), anyOf(is(401), is(403)));
    }

    private static String tamperSignature(String token) {
        int firstSignatureCharacter = token.lastIndexOf('.') + 1;
        if (firstSignatureCharacter == 0 || firstSignatureCharacter >= token.length()) {
            throw new IllegalArgumentException("Keycloak did not return a signed JWT");
        }
        char replacement = token.charAt(firstSignatureCharacter) == 'A' ? 'B' : 'A';
        return token.substring(0, firstSignatureCharacter) + replacement + token.substring(firstSignatureCharacter + 1);
    }

    private static String fingerprint(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertPropagation(
            IngressObservation ingress,
            SecuredMcpServer.Observation mcp,
            ExpectedCall expected,
            List<SpanData> spans) {
        assertThat(ingress, notNullValue());
        assertThat(mcp, notNullValue());
        assertThat(ingress.principal, is(expected.principal));
        assertThat(mcp.principal(), is(expected.principal));
        assertThat(mcp.tokenFingerprint(), is(expected.fingerprint));
        assertThat(ingress.traceId, is(expected.traceId));
        assertThat(mcp.traceId(), is(expected.traceId));
        SpanData ingressSpan = spans.stream()
                .filter(span -> span.getKind() == SpanKind.SERVER
                        && expected.traceId.equals(span.getTraceId())
                        && ingress.spanId.equals(span.getSpanId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing ingress SERVER span for " + expected.principal));
        assertThat(ingressSpan.getParentSpanId(), is(expected.injectedParentSpanId));
        SpanData clientSpan = spans.stream()
                .filter(span -> span.getKind() == SpanKind.CLIENT
                        && expected.traceId.equals(span.getTraceId())
                        && ingressSpan.getSpanId().equals(span.getParentSpanId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing outbound WebClient CLIENT span for " + expected.principal));
        assertThat(mcp.parentSpanId(), is(clientSpan.getSpanId()));
        assertThat(spans.stream().anyMatch(span -> span.getKind() == SpanKind.SERVER
                && expected.traceId.equals(span.getTraceId())
                && mcp.serverSpanId().equals(span.getSpanId())
                && clientSpan.getSpanId().equals(span.getParentSpanId())), is(true));
    }

    private static final class ExpectedCall {
        private final String traceId;
        private final String injectedParentSpanId;
        private final String principal;
        private final String fingerprint;

        private ExpectedCall(String traceId, String injectedParentSpanId, String principal, String fingerprint) {
            this.traceId = traceId;
            this.injectedParentSpanId = injectedParentSpanId;
            this.principal = principal;
            this.fingerprint = fingerprint;
        }
    }

    private static final class IngressState {
        private final StreamableHttpMcpTransport transport;
        private final AtomicReference<McpClient> clients = new AtomicReference<>();
        private final ReentrantLock creationLock = new ReentrantLock();
        private final CountDownLatch concurrentArrivals = new CountDownLatch(2);
        private final BlockingQueue<IngressObservation> observations = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private IngressState(StreamableHttpMcpTransport transport) {
            this.transport = transport;
        }
    }

    private static final class IngressObservation {
        private final String nonce;
        private final String principal;
        private final String traceId;
        private final String spanId;

        private IngressObservation(String nonce, String principal, String traceId, String spanId) {
            this.nonce = nonce;
            this.principal = principal;
            this.traceId = traceId;
            this.spanId = spanId;
        }
    }
}
