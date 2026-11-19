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
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.http.Method;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpErrorResponseReceived;
import dev.langchain4j.http.client.sse.HttpResponseReceived;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

/**
 * A LangChain4j HTTP client backed by a configured Helidon WebClient.
 * The WebClient is owned by the caller; closing this adapter only cancels its in-flight requests.
 * Each streaming subscription uses a virtual thread for WebClient's blocking response body API.
 */
public final class HelidonHttpClient implements HttpClient, AutoCloseable, RuntimeType.Api<HelidonHttpClientConfig> {
    private static final System.Logger LOGGER = System.getLogger(HelidonHttpClient.class.getName());
    private static final int READ_BUFFER_SIZE = 8192;
    private static final int MAX_ERROR_BODY_SIZE = 65536;

    private final HelidonHttpClientConfig config;
    private final WebClient webClient;
    private final Set<ActiveCall> activeCalls = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    private HelidonHttpClient(HelidonHttpClientConfig config) {
        this.config = Objects.requireNonNull(config);
        this.webClient = config.webClient();
    }

    /**
     * Creates a builder for this adapter.
     *
     * @return adapter builder
     */
    public static HelidonHttpClientConfig.Builder builder() {
        return HelidonHttpClientConfig.builder();
    }

    /**
     * Creates an adapter using a caller-owned WebClient.
     *
     * @param config adapter configuration
     * @return new adapter
     */
    public static HelidonHttpClient create(HelidonHttpClientConfig config) {
        return new HelidonHttpClient(config);
    }

    /**
     * Creates an adapter by customizing its builder.
     *
     * @param consumer builder customizer
     * @return new adapter
     */
    public static HelidonHttpClient create(Consumer<HelidonHttpClientConfig.Builder> consumer) {
        Objects.requireNonNull(consumer);
        return builder().update(consumer).build();
    }

    @Override
    public HelidonHttpClientConfig prototype() {
        return config;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        Objects.requireNonNull(request);
        ensureOpen();
        BlockingCall call = new BlockingCall();
        call.worker(Thread.currentThread());
        activeCalls.add(call);
        if (closed.get()) {
            call.cancel();
        }
        try {
            if (call.cancelled()) {
                throw new IllegalStateException("HTTP client is closed");
            }
            HttpClientResponse response = submit(request);
            if (!call.attach(response)) {
                throw new IllegalStateException("HTTP client is closed");
            }
            byte[] body = response.inputStream().readAllBytes();
            int status = response.status().code();
            if (status < 200 || status >= 300) {
                throw new HttpException(status, new String(body, StandardCharsets.UTF_8));
            }
            return successful(response, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            call.finish();
        }
    }

    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
        Objects.requireNonNull(request);
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("HTTP client is closed"));
        }
        Context context = captureContext();
        CompletableFuture<SuccessfulHttpResponse> result = new CompletableFuture<>();
        AsyncCall call = new AsyncCall(result);
        activeCalls.add(call);
        result.whenComplete((_, _) -> {
            if (result.isCancelled()) {
                call.cancel();
            }
        });
        call.start(context, () -> {
            try {
                HttpClientResponse response = submit(request);
                if (!call.attach(response)) {
                    return;
                }
                byte[] body = response.inputStream().readAllBytes();
                int status = response.status().code();
                if (status < 200 || status >= 300) {
                    result.completeExceptionally(new HttpException(status, new String(body, StandardCharsets.UTF_8)));
                } else {
                    result.complete(successful(response, body));
                }
            } catch (Throwable error) {
                if (!call.cancelled()) {
                    result.completeExceptionally(error);
                }
            } finally {
                call.finish();
            }
        });
        return result;
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(parser);
        Objects.requireNonNull(listener);
        if (closed.get()) {
            listener.onError(new IllegalStateException("HTTP client is closed"));
            return;
        }
        Context context = captureContext();
        ListenerCall call = new ListenerCall();
        AtomicBoolean signaledError = new AtomicBoolean();
        activeCalls.add(call);
        call.start(context, () -> {
            try {
                HttpClientResponse response = submit(request);
                if (!call.attach(response)) {
                    return;
                }
                int status = response.status().code();
                if (status < 200 || status >= 300) {
                    String body = errorBody(response);
                    signaledError.set(true);
                    call.invokeUser(() -> listener.onError(new HttpException(status, body)));
                    return;
                }
                call.invokeUser(() -> listener.onOpen(successful(response, null)));
                parser.parse(response.inputStream(), new ServerSentEventListener() {
                    @Override
                    public void onEvent(ServerSentEvent event) {
                        if (!call.cancelled()) {
                            call.invokeUser(() -> listener.onEvent(event));
                        }
                    }

                    @Override
                    public void onEvent(ServerSentEvent event, ServerSentEventContext eventContext) {
                        if (!call.cancelled()) {
                            call.invokeUser(() -> listener.onEvent(event, eventContext));
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (!call.cancelled() && signaledError.compareAndSet(false, true)) {
                            call.invokeUser(() -> listener.onError(error));
                        }
                    }
                });
                if (!call.cancelled() && !signaledError.get()) {
                    call.invokeUser(listener::onClose);
                }
            } catch (Throwable error) {
                if (!call.cancelled() && !call.callbackFailed() && signaledError.compareAndSet(false, true)) {
                    call.invokeUser(() -> listener.onError(error));
                }
            } finally {
                call.finish();
            }
        });
    }

    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(parser);
        Context context = captureContext();
        return subscriber -> {
            Objects.requireNonNull(subscriber);
            StreamCall call = new StreamCall(request, parser, subscriber);
            activeCalls.add(call);
            try {
                subscriber.onSubscribe(call);
            } catch (RuntimeException error) {
                call.cancel();
                return;
            }
            if (closed.get()) {
                call.fail(new IllegalStateException("HTTP client is closed"));
            } else {
                call.start(context, call::read);
            }
        };
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            activeCalls.forEach(ActiveCall::cancel);
        }
    }

    private static Context captureContext() {
        return Contexts.context().orElseGet(Context::create);
    }

    private static Map<String, List<String>> headers(HttpClientResponse response) {
        Map<String, List<String>> result = new HashMap<>();
        for (Header header : response.headers()) {
            result.put(header.name(), header.allValues());
        }
        return result;
    }

    private static SuccessfulHttpResponse successful(HttpClientResponse response, byte[] body) {
        return SuccessfulHttpResponse.builder()
                .statusCode(response.status().code())
                .headers(headers(response))
                .body(body)
                .build();
    }

    private static String errorBody(HttpClientResponse response) throws IOException {
        InputStream input = response.inputStream();
        return new String(input.readNBytes(MAX_ERROR_BODY_SIZE), StandardCharsets.UTF_8);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("HTTP client is closed");
        }
    }

    private HttpClientResponse submit(HttpRequest request) {
        if (!request.formDataFields().isEmpty() || !request.formDataFiles().isEmpty()) {
            throw new UnsupportedOperationException("Multipart form data is not supported by HelidonHttpClient");
        }
        HttpClientRequest clientRequest = webClient.method(Method.create(request.method().name()))
                .uri(URI.create(request.url()));
        request.headers().forEach((name, values) -> clientRequest.header(HeaderNames.create(name), values));
        if (request.body() == null) {
            return clientRequest.request();
        }
        return clientRequest.submit(request.body().getBytes(StandardCharsets.UTF_8));
    }

    private abstract class ActiveCall {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpClientResponse> response = new AtomicReference<>();
        private volatile Thread worker;

        boolean cancelled() {
            return cancelled.get();
        }

        void worker(Thread worker) {
            this.worker = worker;
        }

        void start(Context context, Runnable action) {
            if (closed.get() || cancelled.get()) {
                cancel();
                return;
            }
            worker = Thread.ofVirtual().start(() -> Contexts.runInContext(context, action));
            if (cancelled.get()) {
                worker.interrupt();
            }
        }

        boolean attach(HttpClientResponse response) {
            this.response.set(response);
            if (cancelled.get() || closed.get()) {
                cancel();
                closeResponse();
                return false;
            }
            return true;
        }

        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                onCancel();
                closeResponse();
                Thread current = worker;
                if (current != null && current != Thread.currentThread()) {
                    current.interrupt();
                }
                activeCalls.remove(this);
            }
        }

        void finish() {
            activeCalls.remove(this);
            closeResponse();
        }

        void onCancel() {
        }

        private void closeResponse() {
            HttpClientResponse current = response.getAndSet(null);
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException e) {
                    LOGGER.log(System.Logger.Level.DEBUG, "Failed to close HTTP response", e);
                }
            }
        }
    }

    private final class BlockingCall extends ActiveCall {
    }

    private final class AsyncCall extends ActiveCall {
        private final CompletableFuture<SuccessfulHttpResponse> result;

        private AsyncCall(CompletableFuture<SuccessfulHttpResponse> result) {
            this.result = result;
        }

        @Override
        void onCancel() {
            result.cancel(false);
        }
    }

    private final class ListenerCall extends ActiveCall {
        private final AtomicBoolean callbackFailed = new AtomicBoolean();

        boolean callbackFailed() {
            return callbackFailed.get();
        }

        void invokeUser(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException e) {
                callbackFailed.set(true);
                throw e;
            }
        }
    }

    private final class StreamCall extends ActiveCall implements Flow.Subscription {
        private final HttpRequest request;
        private final ServerSentEventParser parser;
        private final Flow.Subscriber<? super HttpStreamingEvent> subscriber;
        private final ReentrantLock demandLock = new ReentrantLock();
        private final Condition demandChanged = demandLock.newCondition();
        private final ReentrantLock signalLock = new ReentrantLock();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean cancelledBySubscriber = new AtomicBoolean();
        private final AtomicReference<Throwable> pendingFailure = new AtomicReference<>();
        private long demand;

        private StreamCall(HttpRequest request,
                           ServerSentEventParser parser,
                           Flow.Subscriber<? super HttpStreamingEvent> subscriber) {
            this.request = request;
            this.parser = parser;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("Demand must be positive"));
                return;
            }
            demandLock.lock();
            try {
                if (cancelled() || terminated.get()) {
                    return;
                }
                long increased = demand + count;
                demand = increased < 0 ? Long.MAX_VALUE : increased;
                demandChanged.signal();
            } finally {
                demandLock.unlock();
            }
        }

        @Override
        public void cancel() {
            cancelledBySubscriber.set(true);
            terminated.set(true);
            super.cancel();
        }

        @Override
        void onCancel() {
            demandLock.lock();
            try {
                demandChanged.signalAll();
            } finally {
                demandLock.unlock();
            }
        }

        private boolean awaitDemand() throws InterruptedException {
            demandLock.lockInterruptibly();
            try {
                while (demand == 0 && !cancelled()) {
                    demandChanged.await();
                }
                if (cancelled() || terminated.get()) {
                    return false;
                }
                return true;
            } finally {
                demandLock.unlock();
            }
        }

        private boolean emit(HttpStreamingEvent event) throws InterruptedException {
            if (!awaitDemand()) {
                return false;
            }
            demandLock.lock();
            try {
                if (demand != Long.MAX_VALUE) {
                    demand--;
                }
            } finally {
                demandLock.unlock();
            }
            signalLock.lock();
            try {
                if (cancelled() || terminated.get()) {
                    return false;
                }
                try {
                    subscriber.onNext(event);
                } catch (RuntimeException error) {
                    // A subscriber that throws has violated its contract. Cancel it without
                    // invoking another callback on the same broken subscriber.
                    cancel();
                    return false;
                }
                return !cancelled();
            } finally {
                signalLock.unlock();
                Throwable failure = pendingFailure.getAndSet(null);
                if (failure != null && !cancelledBySubscriber.get()) {
                    signalError(failure);
                }
            }
        }

        private void read() {
            try {
                ServerSentEventParser.Incremental incremental = parser.incremental();
                if (cancelled()) {
                    return;
                }
                HttpClientResponse response = submit(request);
                if (!attach(response)) {
                    return;
                }
                int status = response.status().code();
                if (status < 200 || status >= 300) {
                    boolean emitHead;
                    demandLock.lock();
                    try {
                        emitHead = demand > 0;
                    } finally {
                        demandLock.unlock();
                    }
                    if (emitHead && !emit(new HttpErrorResponseReceived(status, headers(response)))) {
                        return;
                    }
                    if (cancelled()) {
                        return;
                    }
                    fail(new HttpException(status, errorBody(response)));
                    return;
                }
                if (!emit(new HttpResponseReceived(successful(response, null)))) {
                    return;
                }
                InputStream input = response.inputStream();
                byte[] buffer = new byte[READ_BUFFER_SIZE];
                int count;
                // One read of lookahead lets EOF complete without requiring demand for another event.
                // Parsed events from that read are still gated by emit(), so at most one byte batch
                // is held while downstream demand is exhausted.
                while (!cancelled() && (count = input.read(buffer)) != -1) {
                    for (ServerSentEvent event : incremental.feed(ByteBuffer.wrap(buffer, 0, count))) {
                        if (!emit(event)) {
                            return;
                        }
                    }
                }
                if (!cancelled()) {
                    for (ServerSentEvent event : incremental.flush()) {
                        if (!emit(event)) {
                            return;
                        }
                    }
                    complete();
                }
            } catch (Throwable error) {
                if (!cancelled()) {
                    fail(error);
                }
            } finally {
                finish();
            }
        }

        private void fail(Throwable error) {
            if (terminated.compareAndSet(false, true)) {
                super.cancel();
                if (signalLock.isHeldByCurrentThread()) {
                    pendingFailure.set(error);
                } else {
                    signalError(error);
                }
            }
        }

        private void signalError(Throwable error) {
            signalLock.lock();
            try {
                if (!cancelledBySubscriber.get()) {
                    subscriber.onError(error);
                }
            } finally {
                signalLock.unlock();
            }
        }

        private void complete() {
            signalLock.lock();
            try {
                if (!cancelled() && terminated.compareAndSet(false, true)) {
                    subscriber.onComplete();
                }
            } finally {
                signalLock.unlock();
            }
        }
    }
}
