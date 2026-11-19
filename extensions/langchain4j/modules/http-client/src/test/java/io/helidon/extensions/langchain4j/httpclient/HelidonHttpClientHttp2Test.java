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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import dev.langchain4j.http.client.sse.HttpResponseReceived;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.api.WebClientServiceRequest;
import io.helidon.webclient.api.WebClientServiceResponse;
import io.helidon.webclient.http2.Http2Client;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;
import io.helidon.webclient.spi.WebClientService;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http2.Http2Config;
import io.helidon.webserver.http2.Http2ConnectionSelector;
import io.helidon.webserver.http2.Http2Route;
import org.junit.jupiter.api.Test;

import static io.helidon.http.Method.GET;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class HelidonHttpClientHttp2Test {

    @Test
    void cancellation_closes_native_h2_stream_and_keeps_client_usable() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        ProtocolObserver observer = new ProtocolObserver();
        WebServer server = WebServer.builder()
                .host("127.0.0.1")
                .port(0)
                .protocolsDiscoverServices(false)
                .addConnectionSelector(Http2ConnectionSelector.builder()
                        .http2Config(Http2Config.create())
                        .build())
                .routing(HttpRouting.builder()
                        .route(Http2Route.route(GET, "/sse", (request, response) -> {
                            response.header(HeaderNames.CONTENT_TYPE, "text/event-stream");
                            try (OutputStream output = response.outputStream()) {
                                output.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
                                output.flush();
                                bodyStarted.countDown();
                                if (!releaseBody.await(10, TimeUnit.SECONDS)) {
                                    throw new IOException("SSE server was not released");
                                }
                                output.write("data: late\n\n".getBytes(StandardCharsets.UTF_8));
                            } catch (IOException ignored) {
                                // A cancelled HTTP/2 stream may reset while the handler still owns its output.
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }))
                        .route(Http2Route.route(GET, "/ping", (request, response) -> response.send("pong"))))
                .build()
                .start();
        WebClient webClient = null;
        Http2Client bootstrap = null;
        HelidonHttpClient adapter = null;
        try {
            String baseUrl = "http://127.0.0.1:" + server.port();
            // A generic WebClient chooses h2 only after a compatible connection is cached.
            // Establish that connection with the version-specific prior-knowledge client.
            bootstrap = Http2Client.builder()
                    .baseUri(baseUrl)
                    .shareConnectionCache(true)
                    .protocolConfig(protocol -> protocol.priorKnowledge(true))
                    .build();
            try (var response = bootstrap.get("/ping").request()) {
                assertThat(response.protocolId(), is(Http2Client.PROTOCOL_ID));
                assertThat(response.as(String.class), is("pong"));
            }
            webClient = WebClient.builder()
                    .baseUri(baseUrl)
                    .addProtocolConfig(Http2ClientProtocolConfig.builder().priorKnowledge(true).build())
                    .addProtocolPreference(Http2Client.PROTOCOL_ID)
                    .addService(observer)
                    .build();
            try (var response = webClient.get("/ping").request()) {
                assertThat(response.protocolId(), is(Http2Client.PROTOCOL_ID));
                assertThat(response.as(String.class), is("pong"));
            }
            adapter = HelidonHttpClient.builder().webClient(webClient).build();
            RecordingSubscriber subscriber = new RecordingSubscriber();
            HttpRequest streamRequest = HttpRequest.builder()
                    .method(HttpMethod.GET)
                    .url(baseUrl + "/sse")
                    .build();

            adapter.stream(streamRequest, new DefaultServerSentEventParser()).subscribe(subscriber);
            Flow.Subscription subscription = subscriber.subscription.get(5, TimeUnit.SECONDS);
            subscription.request(Long.MAX_VALUE);
            assertThat(bodyStarted.await(5, TimeUnit.SECONDS), is(true));
            assertThat(subscriber.next() instanceof HttpResponseReceived, is(true));
            assertThat(((ServerSentEvent) subscriber.next()).data(), is("first"));
            assertThat(observer.protocol.get(5, TimeUnit.SECONDS), is(Http2Client.PROTOCOL_ID));
            Thread adapterWorker = observer.worker.get();
            assertThat(adapterWorker, notNullValue());

            subscription.cancel();
            adapterWorker.join(5_000);
            assertThat("SSE worker must exit before the server releases its body", adapterWorker.isAlive(), is(false));
            assertThat("server body remains deliberately stalled", releaseBody.getCount(), is(1L));
            assertThat(subscriber.terminal.poll(150, TimeUnit.MILLISECONDS), is((Object) null));

            try (var response = webClient.get("/ping").request()) {
                assertThat(response.protocolId(), is(Http2Client.PROTOCOL_ID));
                assertThat(response.as(String.class), is("pong"));
            }
        } finally {
            releaseBody.countDown();
            if (adapter != null) {
                adapter.close();
            }
            if (webClient != null) {
                webClient.closeResource();
            }
            if (bootstrap != null) {
                bootstrap.closeResource();
            }
            server.stop();
        }
    }

    private static final class ProtocolObserver implements WebClientService {
        private final CompletableFuture<String> protocol = new CompletableFuture<>();
        private final AtomicReference<Thread> worker = new AtomicReference<>();

        @Override
        public WebClientServiceResponse handle(Chain chain, WebClientServiceRequest request) {
            boolean streamingRequest = "/sse".equals(request.uri().path().path());
            if (streamingRequest) {
                worker.set(Thread.currentThread());
            }
            WebClientServiceResponse response = chain.proceed(request);
            if (streamingRequest) {
                protocol.complete(request.protocolId());
            }
            return response;
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<HttpStreamingEvent> {
        private final CompletableFuture<Flow.Subscription> subscription = new CompletableFuture<>();
        private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        private final BlockingQueue<Object> terminal = new LinkedBlockingQueue<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription.complete(subscription);
        }

        @Override
        public void onNext(HttpStreamingEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            terminal.add(throwable);
        }

        @Override
        public void onComplete() {
            terminal.add("complete");
        }

        private Object next() throws InterruptedException {
            Object event = events.poll(5, TimeUnit.SECONDS);
            assertThat("next event; terminal=" + terminal.peek(), event, notNullValue());
            return event;
        }
    }
}
