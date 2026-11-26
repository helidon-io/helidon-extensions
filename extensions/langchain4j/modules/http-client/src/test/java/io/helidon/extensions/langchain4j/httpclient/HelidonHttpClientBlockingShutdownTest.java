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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import io.helidon.webclient.api.WebClient;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class HelidonHttpClientBlockingShutdownTest {
    @Test
    void shutdownCannotInterruptCallerAfterExecuteReturns() throws Exception {
        CountDownLatch responseFinished = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch responseReleased = new CountDownLatch(1);
        CountDownLatch interruptEntered = new CountDownLatch(1);
        CountDownLatch releaseInterrupt = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch finishUserWork = new CountDownLatch(1);
        AtomicBoolean interruptedDuringUserWork = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        WebClient webClient = WebClient.builder()
                .addService((chain, request) -> {
                    request.whenComplete().whenComplete((_, _) -> {
                        responseFinished.countDown();
                        try {
                            releaseResponse.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            responseReleased.countDown();
                        }
                    });
                    return chain.proceed(request);
                })
                .build();
        HelidonHttpClient client = HelidonHttpClient.builder().webClient(webClient).build();
        HttpRequest request = HttpRequest.builder().method(HttpMethod.GET)
                .url("http://127.0.0.1:" + server.getAddress().getPort() + "/").build();
        Thread caller = new Thread(() -> {
            try {
                assertThat(client.execute(request).body(), is("ok"));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                Thread.interrupted();
                returned.countDown();
                try {
                    finishUserWork.await();
                } catch (InterruptedException e) {
                    interruptedDuringUserWork.set(true);
                }
            }
        }) {
            @Override
            public void interrupt() {
                interruptEntered.countDown();
                try {
                    releaseInterrupt.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.interrupt();
            }
        };
        Thread shutdown = Thread.ofVirtual().unstarted(client::close);
        try {
            caller.start();
            assertThat("response consumed", responseFinished.await(5, TimeUnit.SECONDS), is(true));
            shutdown.start();
            assertThat("shutdown selected caller", interruptEntered.await(5, TimeUnit.SECONDS), is(true));
            releaseResponse.countDown();
            assertThat("response callback released", responseReleased.await(5, TimeUnit.SECONDS), is(true));
            boolean waiting = awaitsExecuteCompletion(caller, returned);
            assertThat("caller waits inside execute while cancellation is pending; returned=" + (returned.getCount() == 0),
                       waiting, is(true));
            assertThat("execute has not returned", returned.getCount(), is(1L));
            releaseInterrupt.countDown();
            assertThat("execute returns", returned.await(5, TimeUnit.SECONDS), is(true));
            assertThat("shutdown exits", shutdown.join(Duration.ofSeconds(5)), is(true));
            finishUserWork.countDown();
            assertThat("caller exits", caller.join(Duration.ofSeconds(5)), is(true));
            assertThat(failure.get(), nullValue());
            assertThat("unrelated work was not interrupted", interruptedDuringUserWork.get(), is(false));
        } finally {
            releaseResponse.countDown();
            releaseInterrupt.countDown();
            finishUserWork.countDown();
            caller.join(5000);
            shutdown.join(5000);
            client.close();
            webClient.closeResource();
            server.stop(0);
        }
    }

    private static boolean awaitsExecuteCompletion(Thread caller, CountDownLatch returned) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && returned.getCount() != 0) {
            if (caller.getState() == Thread.State.WAITING
                    && Arrays.stream(caller.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(HelidonHttpClient.class.getName())
                            && frame.getMethodName().equals("execute"))) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return false;
    }
}
