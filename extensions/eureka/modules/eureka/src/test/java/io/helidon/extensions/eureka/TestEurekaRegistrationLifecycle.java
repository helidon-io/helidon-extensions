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
package io.helidon.extensions.eureka;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.buffers.DataReader;
import io.helidon.common.buffers.DataWriter;
import io.helidon.common.socket.HelidonSocket;
import io.helidon.http.ClientResponseHeaders;
import io.helidon.http.Method;
import io.helidon.http.Status;
import io.helidon.http.WritableHeaders;
import io.helidon.webclient.api.ClientConnection;
import io.helidon.webclient.api.WebClientServiceRequest;
import io.helidon.webclient.api.WebClientServiceResponse;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webclient.spi.WebClientService;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class TestEurekaRegistrationLifecycle {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void testFirstHeartbeatUsesStartupStatus() throws Exception {
        var heartbeatStatus = new CompletableFuture<String>();
        var renewalThread = new AtomicReference<Thread>();
        var feature = feature((_, request) -> {
            if (request.method().equals(Method.PUT)) {
                renewalThread.set(Thread.currentThread());
                heartbeatStatus.complete(request.uri().query().first("status").orElseThrow());
            }
            return response(request, Status.NO_CONTENT_204);
        });
        try {
            feature.afterStart(8080, false);
            assertThat(heartbeatStatus.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is("UP"));
        } finally {
            feature.afterStop();
            if (renewalThread.get() != null) {
                join(renewalThread.get());
            }
        }
    }

    @Test
    void testNotFoundHeartbeatDuringShutdown() throws Exception {
        var heartbeatEntered = new CountDownLatch(1);
        var heartbeatInterrupted = new CountDownLatch(1);
        var releaseHeartbeat = new CountDownLatch(1);
        var renewalThread = new AtomicReference<Thread>();
        var requests = new CopyOnWriteArrayList<Method>();
        var feature = feature((_, request) -> {
            requests.add(request.method());
            if (request.method().equals(Method.PUT)) {
                renewalThread.set(Thread.currentThread());
                heartbeatEntered.countDown();
                try {
                    if (!releaseHeartbeat.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new AssertionError("Heartbeat was not released");
                    }
                } catch (InterruptedException _) {
                    // A completed response can still be delivered after cancellation interrupts its caller.
                    heartbeatInterrupted.countDown();
                    await(releaseHeartbeat, "release interrupted heartbeat");
                }
                return response(request, Status.NOT_FOUND_404);
            }
            return response(request, Status.NO_CONTENT_204);
        });
        Thread shutdown = null;
        try {
            feature.afterStart(8080, false);
            await(heartbeatEntered, "first heartbeat");
            shutdown = Thread.ofVirtual().start(feature::afterStop);
            await(heartbeatInterrupted, "shutdown interrupt");
            releaseHeartbeat.countDown();
            join(shutdown);
            join(renewalThread.get());
            assertThat("Requests after a heartbeat returned 404 during shutdown",
                       requests, is(List.of(Method.POST, Method.PUT, Method.DELETE)));
        } finally {
            releaseHeartbeat.countDown();
            feature.afterStop();
            if (shutdown != null) {
                join(shutdown);
            }
            if (renewalThread.get() != null) {
                join(renewalThread.get());
            }
        }
    }

    @Test
    void testShutdownDrainsReregistrationBeforeCancellation() throws Exception {
        var reregistrationEntered = new CountDownLatch(1);
        var reregistrationInterrupted = new CountDownLatch(1);
        var releaseReregistration = new CountDownLatch(1);
        var renewalThread = new AtomicReference<Thread>();
        var requests = new CopyOnWriteArrayList<String>();
        var feature = feature((_, request) -> {
            if (request.method().equals(Method.PUT)) {
                renewalThread.set(Thread.currentThread());
                requests.add("heartbeat");
                return response(request, Status.NOT_FOUND_404);
            }
            if (request.method().equals(Method.POST) && renewalThread.get() != null) {
                requests.add("reregister");
                reregistrationEntered.countDown();
                try {
                    if (!releaseReregistration.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new AssertionError("Reregistration was not released");
                    }
                } catch (InterruptedException _) {
                    reregistrationInterrupted.countDown();
                    await(releaseReregistration, "release interrupted reregistration");
                }
                requests.add("reregister completed");
            } else if (request.method().equals(Method.DELETE)) {
                requests.add("cancel");
            } else {
                requests.add("register");
            }
            return response(request, Status.NO_CONTENT_204);
        });
        Thread shutdown = null;
        try {
            feature.afterStart(8080, false);
            await(reregistrationEntered, "reregistration");
            shutdown = Thread.ofVirtual().start(feature::afterStop);
            await(reregistrationInterrupted, "shutdown interrupt during reregistration");
            releaseReregistration.countDown();
            join(shutdown);
            join(renewalThread.get());
            assertThat("Cancellation must follow the in-flight registration",
                       requests, is(List.of("register", "heartbeat", "reregister", "reregister completed", "cancel")));
        } finally {
            releaseReregistration.countDown();
            feature.afterStop();
            if (shutdown != null) {
                join(shutdown);
            }
            if (renewalThread.get() != null) {
                join(renewalThread.get());
            }
        }
    }

    private static EurekaRegistrationHttpFeature feature(WebClientService service) {
        return new EurekaRegistrationHttpFeature(EurekaRegistrationConfig.builder()
                .instanceInfo(InstanceInfoConfig.builder()
                                      .appName("lifecycle-test")
                                      .hostName("localhost")
                                      .trafficEnabled(false)
                                      .leaseInfo(LeaseInfoConfig.builder().renewalIntervalInSecs(0)))
                .clientBuilderSupplier(() -> Http1Client.builder()
                        .baseUri("http://localhost")
                        .servicesDiscoverServices(false)
                        .addService(service))
                .buildPrototype());
    }

    private static WebClientServiceResponse response(WebClientServiceRequest request, Status status) {
        return WebClientServiceResponse.builder()
                .serviceRequest(request)
                .whenComplete(new CompletableFuture<>())
                .connection(new TestConnection())
                .status(status)
                .headers(ClientResponseHeaders.create(WritableHeaders.create()))
                .build();
    }

    private static void await(CountDownLatch latch, String event) {
        try {
            assertThat("Timed out waiting for " + event,
                       latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for " + event, e);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        assertThat("Thread did not finish: " + thread.getName(), thread.join(TIMEOUT), is(true));
    }

    private static class TestConnection implements ClientConnection {
        private final DataReader reader = DataReader.create(() -> new byte[0]);

        @Override
        public DataReader reader() {
            return reader;
        }

        @Override
        public DataWriter writer() {
            throw new AssertionError("Synthetic response must not write to a connection");
        }

        @Override
        public String channelId() {
            return "eureka-lifecycle-test";
        }

        @Override
        public HelidonSocket helidonSocket() {
            throw new AssertionError("Synthetic response must not open a socket");
        }

        @Override
        public void readTimeout(Duration readTimeout) {
        }

        @Override
        public void closeResource() {
        }
    }
}
