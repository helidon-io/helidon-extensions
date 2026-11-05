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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParsingHandle;
import dev.langchain4j.http.client.sse.HttpErrorResponseReceived;
import dev.langchain4j.http.client.sse.HttpResponseReceived;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import io.helidon.webclient.api.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class HelidonHttpClientStreamTest {
    private static final int BURST_EVENTS = 17_000;

    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicReference<Thread> workerThread = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private WebClient webClient;
    private HelidonHttpClient client;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (webClient != null) {
            webClient.closeResource();
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.close();
        }
        assertThat("server handler", serverFailure.get(), is((Throwable) null));
    }

    @Test
    void response_headers_precede_custom_parser_event_with_split_utf8() throws Exception {
        CountDownLatch firstChunkSent = new CountDownLatch(1);
        CountDownLatch releaseSecondChunk = new CountDownLatch(1);
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("X-Proof", "header-first");
            exchange.sendResponseHeaders(200, 0);
            byte[] body = "data: Příliš 🦙\n\n".getBytes(StandardCharsets.UTF_8);
            int split = "data: Příliš ".getBytes(StandardCharsets.UTF_8).length + 2;
            try (var output = exchange.getResponseBody()) {
                output.write(body, 0, split);
                output.flush();
                firstChunkSent.countDown();
                await(releaseSecondChunk);
                output.write(body, split, body.length - split);
            }
        });
        startClient();
        TrackingParser parser = new TrackingParser();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        try {
            client.stream(request("/stream"), parser).subscribe(subscriber);
            subscriber.request(1);
            Object first = subscriber.next();
            assertThat(first instanceof HttpResponseReceived, is(true));
            HttpResponseReceived received = (HttpResponseReceived) first;
            assertThat(received.response().statusCode(), is(200));
            assertThat(hasHeader(received.response().headers(), "X-Proof", "header-first"), is(true));
            assertThat(firstChunkSent.await(5, TimeUnit.SECONDS), is(true));
            subscriber.request(1);
            assertThat(parser.firstFeed.await(5, TimeUnit.SECONDS), is(true));
            assertThat(subscriber.events.poll(150, TimeUnit.MILLISECONDS), is((Object) null));
            releaseSecondChunk.countDown();

            Object second = subscriber.next();
            assertThat(second instanceof ServerSentEvent, is(true));
            assertThat(((ServerSentEvent) second).data(), is("custom:Příliš 🦙"));
            assertThat(subscriber.next(), is(RecordingSubscriber.COMPLETE));
            assertThat(parser.feeds.get() > 0, is(true));
            assertThat(parser.flushes.get(), is(1));
        } finally {
            releaseSecondChunk.countDown();
        }
    }

    @Test
    void downstream_demand_pauses_a_burst_without_losing_events() throws Exception {
        CountDownLatch responseSent = new CountDownLatch(1);
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            responseSent.countDown();
            StringBuilder body = new StringBuilder(BURST_EVENTS * 14);
            for (int i = 0; i < BURST_EVENTS; i++) {
                body.append("data: ").append(i).append("\n\n");
            }
            try (var output = exchange.getResponseBody()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        });
        startClient();
        RecordingSubscriber subscriber = new RecordingSubscriber();

        client.stream(request("/burst"), new DefaultServerSentEventParser()).subscribe(subscriber);
        subscriber.request(1);
        assertThat(subscriber.next() instanceof HttpResponseReceived, is(true));
        assertThat(responseSent.await(5, TimeUnit.SECONDS), is(true));
        subscriber.request(1);
        assertThat(((ServerSentEvent) subscriber.next()).data(), is("0"));
        assertThat(subscriber.events.poll(150, TimeUnit.MILLISECONDS), is((Object) null));
        subscriber.request(Long.MAX_VALUE);

        for (int i = 1; i < BURST_EVENTS; i++) {
            assertThat(((ServerSentEvent) subscriber.next()).data(), is(Integer.toString(i)));
        }
        assertThat(subscriber.next(), is(RecordingSubscriber.COMPLETE));
        assertThat(subscriber.failure.get(), is((Throwable) null));
    }

    @Test
    void cancelling_silent_body_returns_promptly_and_does_not_signal() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        startServer(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write("data: partial".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    await(releaseBody);
                    output.write("\ndata: late\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException _) {
                    // A cancelled client may close the socket before this write.
                }
            } finally {
                handlerFinished.countDown();
            }
        });
        startClient();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        try {
            client.stream(request("/silent"), new DefaultServerSentEventParser()).subscribe(subscriber);
            subscriber.request(1);
            assertThat(subscriber.next() instanceof HttpResponseReceived, is(true));
            subscriber.request(1);

            CompletableFuture<Void> cancellation = CompletableFuture.runAsync(subscriber::cancel);
            cancellation.get(5, TimeUnit.SECONDS);
            Thread worker = workerThread.get();
            assertThat(worker, notNullValue());
            assertThat(worker.isVirtual(), is(true));
            assertThat(worker.join(Duration.ofSeconds(5)), is(true));
            assertThat(subscriber.events.poll(150, TimeUnit.MILLISECONDS), is((Object) null));
            assertThat(subscriber.failure.get(), is((Throwable) null));
        } finally {
            releaseBody.countDown();
        }
        assertThat(handlerFinished.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    void closing_adapter_cancels_silent_body_and_preserves_borrowed_web_client() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        startServer(exchange -> {
            if ("/ping".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "still usable");
                return;
            }
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write("data: partial".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    await(releaseBody);
                    output.write("\ndata: late\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException _) {
                    // The adapter closes its response and releases the socket.
                }
            } finally {
                handlerFinished.countDown();
            }
        });
        startClient();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        try {
            client.stream(request("/silent"), new DefaultServerSentEventParser()).subscribe(subscriber);
            subscriber.request(1);
            assertThat(subscriber.next() instanceof HttpResponseReceived, is(true));
            subscriber.request(1);

            CompletableFuture<Void> close = CompletableFuture.runAsync(client::close);
            close.get(5, TimeUnit.SECONDS);
            Thread worker = workerThread.get();
            assertThat(worker, notNullValue());
            assertThat(worker.isVirtual(), is(true));
            assertThat(worker.join(Duration.ofSeconds(5)), is(true));
            assertThat(subscriber.events.poll(150, TimeUnit.MILLISECONDS), is((Object) null));
            assertThat(subscriber.failure.get(), is((Throwable) null));

            try (var response = webClient.get("/ping").request()) {
                assertThat(response.status().code(), is(200));
                assertThat(new String(response.inputStream().readAllBytes(), StandardCharsets.UTF_8), is("still usable"));
            }
        } finally {
            releaseBody.countDown();
        }
        assertThat(handlerFinished.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    void cancelling_before_response_headers_interrupts_the_native_worker() throws Exception {
        verifyPreHeaderCancellation(false);
    }

    @Test
    void closing_adapter_before_response_headers_interrupts_the_native_worker() throws Exception {
        verifyPreHeaderCancellation(true);
    }

    @Test
    void invalid_demand_fails_stream_without_a_second_signal() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "data: unexpected\n\n");
        });
        startClient();
        RecordingSubscriber subscriber = new RecordingSubscriber(true);

        client.stream(request("/invalid-demand"), new DefaultServerSentEventParser()).subscribe(subscriber);

        Object signal = subscriber.next();
        assertThat(signal instanceof IllegalArgumentException, is(true));
        assertThat(subscriber.events.poll(150, TimeUnit.MILLISECONDS), is((Object) null));
        assertThat(subscriber.failure.get(), is(signal));
        assertThat(requests.get(), is(0));
    }

    @Test
    void closing_before_subscription_does_not_start_a_cold_http_request() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });
        startClient();
        Flow.Publisher<HttpStreamingEvent> publisher = client.stream(request("/cold"), new DefaultServerSentEventParser());
        client.close();
        RecordingSubscriber subscriber = new RecordingSubscriber();

        publisher.subscribe(subscriber);

        assertThat(subscriber.next() instanceof IllegalStateException, is(true));
        assertThat(requests.get(), is(0));
    }

    @Test
    void cancelling_execute_async_stops_the_silent_virtual_worker() throws Exception {
        CountDownLatch responseSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        startServer(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write("partial".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    responseSent.countDown();
                    await(releaseBody);
                    output.write("late".getBytes(StandardCharsets.UTF_8));
                } catch (IOException _) {
                    // Cancellation can close the socket before the server writes.
                }
            } finally {
                handlerFinished.countDown();
            }
        });
        startClient();
        try {
            CompletableFuture<?> result = client.executeAsync(request("/silent"));
            assertThat(responseSent.await(5, TimeUnit.SECONDS), is(true));

            CompletableFuture.runAsync(() -> result.cancel(true)).get(5, TimeUnit.SECONDS);

            Thread worker = workerThread.get();
            assertThat(worker, notNullValue());
            assertThat(worker.isVirtual(), is(true));
            assertThat(worker.join(Duration.ofSeconds(5)), is(true));
            assertThat(result.isCancelled(), is(true));
        } finally {
            releaseBody.countDown();
        }
        assertThat(handlerFinished.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    void subscriber_failure_terminates_the_stream_worker() throws Exception {
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            respond(exchange, 200, "data: unobserved\n\n");
        });
        startClient();
        IllegalStateException consumerFailure = new IllegalStateException("consumer failed");
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicInteger events = new AtomicInteger();
        AtomicInteger terminalSignals = new AtomicInteger();
        client.stream(request("/subscriber-failure"), new DefaultServerSentEventParser())
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(HttpStreamingEvent item) {
                        events.incrementAndGet();
                        invoked.countDown();
                        throw consumerFailure;
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        terminalSignals.incrementAndGet();
                    }

                    @Override
                    public void onComplete() {
                        terminalSignals.incrementAndGet();
                    }
                });

        assertThat(invoked.await(5, TimeUnit.SECONDS), is(true));
        assertThat(events.get(), is(1));
        Thread worker = workerThread.get();
        assertThat(worker, notNullValue());
        assertThat(worker.join(Duration.ofSeconds(5)), is(true));
        assertThat(terminalSignals.get(), is(0));
    }

    @Test
    void legacy_listener_receives_both_parser_event_overloads_in_order() throws Exception {
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            respond(exchange, 200, "payload");
        });
        startClient();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);

        client.execute(request("/listener"), legacyParser(), new ServerSentEventListener() {
            @Override
            public void onOpen(dev.langchain4j.http.client.SuccessfulHttpResponse response) {
                callbacks.add("open");
            }

            @Override
            public void onEvent(ServerSentEvent event) {
                callbacks.add("one:" + event.data());
            }

            @Override
            public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                callbacks.add("two:" + event.data());
            }

            @Override
            public void onError(Throwable error) {
                callbacks.add("error");
                completed.countDown();
            }

            @Override
            public void onClose() {
                callbacks.add("close");
                completed.countDown();
            }
        });

        assertThat(completed.await(5, TimeUnit.SECONDS), is(true));
        assertThat(callbacks, is(List.of("open", "one:first", "two:second", "close")));
        Thread worker = workerThread.get();
        assertThat(worker, notNullValue());
        assertThat(worker.join(Duration.ofSeconds(5)), is(true));
    }

    @Test
    void failing_legacy_listener_receives_no_later_callback() throws Exception {
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            respond(exchange, 200, "payload");
        });
        startClient();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch failed = new CountDownLatch(1);

        client.execute(request("/listener-failure"), legacyParser(), new ServerSentEventListener() {
            @Override
            public void onOpen(dev.langchain4j.http.client.SuccessfulHttpResponse response) {
                callbacks.add("open");
            }

            @Override
            public void onEvent(ServerSentEvent event) {
                callbacks.add("event");
                failed.countDown();
                throw new IllegalStateException("listener failed");
            }

            @Override
            public void onError(Throwable error) {
                callbacks.add("error");
            }

            @Override
            public void onClose() {
                callbacks.add("close");
            }
        });

        assertThat(failed.await(5, TimeUnit.SECONDS), is(true));
        Thread worker = workerThread.get();
        assertThat(worker, notNullValue());
        assertThat(worker.join(Duration.ofSeconds(5)), is(true));
        assertThat(callbacks, is(List.of("open", "event")));
    }

    @Test
    void errorHeadCancellationStopsWorkerBeforeErrorBodyEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        startErrorServer(release, new CountDownLatch(1));
        startClient();
        RecordingSubscriber subscriber = new RecordingSubscriber(1, true);
        try {
            client.stream(request("/error"), new DefaultServerSentEventParser()).subscribe(subscriber);
            HttpErrorResponseReceived head = (HttpErrorResponseReceived) subscriber.next();
            assertThat(head.statusCode(), is(401));
            assertThat(head.headers().get("www-authenticate"), is(List.of("Bearer realm=\"test\"")));
            Thread worker = workerThread.get();
            assertThat(worker, notNullValue());
            assertThat(worker.join(Duration.ofSeconds(5)), is(true));
            assertThat("server error body is still open", release.getCount(), is(1L));
            assertThat(subscriber.failure.get(), is((Throwable) null));
            assertThat(subscriber.events.isEmpty(), is(true));
        } finally {
            release.countDown();
        }
    }

    @Test
    void ignoredErrorHeadPreservesFullBodyWithoutAdditionalDemand() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        startErrorServer(release, new CountDownLatch(1));
        startClient();
        RecordingSubscriber subscriber = new RecordingSubscriber(1, false);
        try {
            client.stream(request("/error"), new DefaultServerSentEventParser()).subscribe(subscriber);
            assertThat(subscriber.next() instanceof HttpErrorResponseReceived, is(true));
            assertThat(subscriber.failure.get(), is((Throwable) null));
            release.countDown();
            HttpException error = (HttpException) subscriber.next();
            assertThat(error.statusCode(), is(401));
            assertThat(error.getMessage(), is("denied: details"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void zeroDemandStillReceivesTerminalErrorWithoutAdvisory() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch headersSent = new CountDownLatch(1);
        startErrorServer(release, headersSent);
        startClient();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        try {
            client.stream(request("/error"), new DefaultServerSentEventParser()).subscribe(subscriber);
            assertThat(headersSent.await(5, TimeUnit.SECONDS), is(true));
            release.countDown();
            HttpException error = (HttpException) subscriber.next();
            assertThat(error.statusCode(), is(401));
            assertThat(error.getMessage(), is("denied: details"));
            assertThat(subscriber.events.isEmpty(), is(true));
        } finally {
            release.countDown();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("server latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static boolean hasHeader(Map<String, List<String>> headers, String name, String value) {
        return headers.entrySet().stream()
                .anyMatch(entry -> entry.getKey().equalsIgnoreCase(name) && entry.getValue().contains(value));
    }

    private static ServerSentEventParser legacyParser() {
        return new ServerSentEventParser() {
            @Override
            public void parse(InputStream body, ServerSentEventListener listener) {
                try {
                    body.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                listener.onEvent(new ServerSentEvent(null, "first"));
                listener.onEvent(
                        new ServerSentEvent(null, "second"),
                        new ServerSentEventContext(new DefaultServerSentEventParsingHandle(body)));
            }
        };
    }

    private void startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable failure) {
                serverFailure.compareAndSet(null, failure);
                if (failure instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException(failure);
            }
        });
        server.start();
    }

    private void startErrorServer(CountDownLatch release, CountDownLatch headersSent) throws IOException {
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"test\"");
            exchange.sendResponseHeaders(401, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("denied".getBytes(StandardCharsets.UTF_8));
                output.flush();
                headersSent.countDown();
                await(release);
                output.write(": details".getBytes(StandardCharsets.UTF_8));
            } catch (IOException _) {
                // Cancellation from the error-head callback may already have closed this connection.
            }
        });
    }

    private void startClient() {
        webClient = WebClient.create(builder -> builder
                .baseUri(baseUrl())
                .servicesDiscoverServices(false)
                .addService((chain, request) -> {
                    workerThread.compareAndSet(null, Thread.currentThread());
                    return chain.proceed(request);
                }));
        client = HelidonHttpClient.builder().webClient(webClient).build();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpRequest request(String path) {
        return HttpRequest.builder().method(HttpMethod.GET).url(baseUrl() + path).build();
    }

    private void verifyPreHeaderCancellation(boolean closeAdapter) throws Exception {
        CountDownLatch requestSeen = new CountDownLatch(1);
        CountDownLatch releaseHeaders = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        startServer(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                requestSeen.countDown();
                await(releaseHeaders);
                try {
                    respond(exchange, 200, "late");
                } catch (IOException _) {
                    // The cancelled request may close before headers are written.
                }
            } finally {
                handlerFinished.countDown();
            }
        });
        startClient();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        try {
            client.stream(request("/before-headers"), new DefaultServerSentEventParser()).subscribe(subscriber);
            subscriber.request(1);
            assertThat(requestSeen.await(5, TimeUnit.SECONDS), is(true));

            CompletableFuture.runAsync(closeAdapter ? client::close : subscriber::cancel).get(5, TimeUnit.SECONDS);

            Thread worker = workerThread.get();
            assertThat(worker, notNullValue());
            assertThat(worker.isVirtual(), is(true));
            assertThat(worker.join(Duration.ofSeconds(5)), is(true));
            assertThat(subscriber.events.poll(150, TimeUnit.MILLISECONDS), is((Object) null));
            assertThat(subscriber.failure.get(), is((Throwable) null));
        } finally {
            releaseHeaders.countDown();
        }
        assertThat(handlerFinished.await(5, TimeUnit.SECONDS), is(true));
    }

    private static final class TrackingParser implements ServerSentEventParser {
        private final DefaultServerSentEventParser delegate = new DefaultServerSentEventParser();
        private final AtomicInteger feeds = new AtomicInteger();
        private final AtomicInteger flushes = new AtomicInteger();
        private final CountDownLatch firstFeed = new CountDownLatch(1);

        @Override
        public void parse(InputStream body, ServerSentEventListener listener) {
            throw new AssertionError("stream must use the supplied incremental parser");
        }

        @Override
        public Incremental incremental() {
            Incremental parser = delegate.incremental();
            return new Incremental() {
                @Override
                public List<ServerSentEvent> feed(ByteBuffer bytes) {
                    feeds.incrementAndGet();
                    firstFeed.countDown();
                    return parser.feed(bytes).stream()
                            .map(event -> new ServerSentEvent(event.event(), "custom:" + event.data()))
                            .toList();
                }

                @Override
                public List<ServerSentEvent> flush() {
                    flushes.incrementAndGet();
                    return parser.flush().stream()
                            .map(event -> new ServerSentEvent(event.event(), "custom:" + event.data()))
                            .toList();
                }
            };
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<HttpStreamingEvent> {
        private static final Object COMPLETE = new Object();

        private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final boolean invalidOnSubscribe;
        private final long initialDemand;
        private final boolean cancelOnErrorHead;
        private Flow.Subscription subscription;

        private RecordingSubscriber() {
            this(false);
        }

        private RecordingSubscriber(boolean invalidOnSubscribe) {
            this.invalidOnSubscribe = invalidOnSubscribe;
            this.initialDemand = 0;
            this.cancelOnErrorHead = false;
        }

        private RecordingSubscriber(long initialDemand, boolean cancelOnErrorHead) {
            this.invalidOnSubscribe = false;
            this.initialDemand = initialDemand;
            this.cancelOnErrorHead = cancelOnErrorHead;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (invalidOnSubscribe) {
                subscription.request(0);
            } else if (initialDemand > 0) {
                subscription.request(initialDemand);
            }
        }

        @Override
        public void onNext(HttpStreamingEvent item) {
            events.add(item);
            if (cancelOnErrorHead && item instanceof HttpErrorResponseReceived) {
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
            events.add(throwable);
        }

        @Override
        public void onComplete() {
            events.add(COMPLETE);
        }

        private void request(long count) {
            assertThat(subscription, notNullValue());
            subscription.request(count);
        }

        private void cancel() {
            assertThat(subscription, notNullValue());
            subscription.cancel();
        }

        private Object next() throws InterruptedException {
            Object next = events.poll(10, TimeUnit.SECONDS);
            assertThat("next streaming event; failure=" + failure.get(), next, notNullValue());
            return next;
        }
    }
}
