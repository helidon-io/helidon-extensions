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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import io.helidon.http.HeaderNames;
import io.helidon.security.Security;
import io.helidon.security.providers.oidc.OidcProvider;
import io.helidon.security.providers.oidc.common.OidcConfig;
import io.helidon.tracing.Tracer;
import io.helidon.tracing.providers.opentelemetry.HelidonOpenTelemetry;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.context.WebClientContextService;
import io.helidon.webclient.tracing.WebClientTracing;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.context.ContextFeature;
import io.helidon.webserver.observe.ObserveFeature;
import io.helidon.webserver.observe.tracing.TracingObserver;
import io.helidon.webserver.security.SecurityFeature;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Native Helidon security, context and tracing fixtures shared by the Keycloak end-to-end test.
 */
final class PropagationTestSupport implements AutoCloseable {
    static final String AUTHORIZATION_CLASSIFIER = "inboundAuthorization";

    private final RecordingSpanExporter exporter = new RecordingSpanExporter();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    private final Tracer tracer = HelidonOpenTelemetry.create(
            openTelemetry,
            openTelemetry.getTracer("helidon-mcp-keycloak-e2e"),
            Map.of());
    private final List<WebClient> clients = new CopyOnWriteArrayList<>();

    private PropagationTestSupport() {
    }

    static PropagationTestSupport create() {
        return new PropagationTestSupport();
    }

    /**
     * Creates the verifier used independently by ingress and MCP server. JWK keys come from
     * the Keycloak issuer metadata and are used to verify each bearer token's signature.
     */
    static Security security(URI issuerUri, String audience) {
        Objects.requireNonNull(issuerUri);
        Objects.requireNonNull(audience);
        OidcConfig config = OidcConfig.builder()
                .clientId(audience)
                .clientSecret("test-only-secret")
                .identityUri(issuerUri)
                .issuer(issuerUri.toString())
                .audience(audience)
                .useHeader(true)
                .useCookie(false)
                .redirect(false)
                .validateJwtWithJwk(true)
                .build();
        return Security.builder()
                .addProvider(OidcProvider.create(config))
                .build();
    }

    void configureIngress(WebServerConfig.Builder builder, URI issuerUri, String audience) {
        Objects.requireNonNull(builder);
        builder.featuresDiscoverServices(false)
                .addFeature(ContextFeature.create(context -> context.addRecord(record -> record
                        .header(HeaderNames.AUTHORIZATION)
                        .classifier(AUTHORIZATION_CLASSIFIER))))
                .addFeature(ObserveFeature.just(TracingObserver.create(tracer)))
                .addFeature(SecurityFeature.builder()
                        .security(security(issuerUri, audience))
                        .build());
    }

    WebClient createWebClient(URI mcpEndpoint) {
        Objects.requireNonNull(mcpEndpoint);
        WebClient client = WebClient.builder()
                .baseUri(mcpEndpoint)
                .servicesDiscoverServices(false)
                .addService(WebClientContextService.create(context -> context.addRecord(record -> record
                        .header(HeaderNames.AUTHORIZATION)
                        .classifier(AUTHORIZATION_CLASSIFIER))))
                .addService(WebClientTracing.create(tracer))
                .build();
        clients.add(client);
        return client;
    }

    Tracer tracer() {
        return tracer;
    }

    List<SpanData> spans() {
        return exporter.spans();
    }

    @Override
    public void close() {
        clients.forEach(WebClient::closeResource);
        tracerProvider.shutdown();
    }

    private static final class RecordingSpanExporter implements SpanExporter {
        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        List<SpanData> spans() {
            return List.copyOf(spans);
        }
    }
}
