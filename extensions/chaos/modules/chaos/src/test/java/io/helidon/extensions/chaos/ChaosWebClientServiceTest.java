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
package io.helidon.extensions.chaos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.HeaderNames;
import io.helidon.http.Method;
import io.helidon.http.Status;
import io.helidon.webclient.api.ClientUri;
import io.helidon.webclient.api.WebClientServiceRequest;
import io.helidon.webclient.api.WebClientServiceResponse;
import io.helidon.webclient.spi.WebClientService;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosWebClientServiceTest {
    private static final WebClientServiceResponse RESPONSE = (WebClientServiceResponse) Proxy.newProxyInstance(
            ChaosWebClientServiceTest.class.getClassLoader(),
            new Class<?>[] {WebClientServiceResponse.class},
            (proxy, invoked, arguments) -> switch (invoked.getName()) {
                case "toString" -> "response";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new UnsupportedOperationException(invoked.getName());
            });

    @Test
    void proceedsOnceWithoutRegisteredEngine() {
        ChaosWebClientService service = new ChaosWebClientService("chaos");
        AtomicInteger proceeds = new AtomicInteger();

        assertThat(service.handle(chain(proceeds),
                                  request(Method.GET, "https", "inventory.example.com", 443, "/v1/items")),
                   sameInstance(RESPONSE));

        assertThat(proceeds.get(), is(1));
    }

    @Test
    void nonmatchingRequestDoesNotChangeMatchedCount() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            var run = engine.create(plan(latency(), 2), "test");
            AtomicInteger proceeds = new AtomicInteger();

            new ChaosWebClientService("chaos").handle(chain(proceeds),
                                                         request(Method.POST, "https", "inventory.example.com", 443, "/v1/items"));

            assertThat(proceeds.get(), is(1));
            assertThat(engine.get(run.id()).orElseThrow().matched(), is(0L));
        } finally {
            registration.close();
        }
    }

    @Test
    void matchingLatencyReleasesReservationBeforeProceeding() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            engine.create(plan(latency(), 1), "test");
            AtomicInteger proceeds = new AtomicInteger();
            WebClientService.Chain chain = request -> {
                proceeds.incrementAndGet();
                try (ChaosRunEngine.Reservation reservation = ChaosRuntimeBridge.reserveOutbound("GET",
                                                                                                    "https",
                                                                                                    "inventory.example.com",
                                                                                                    443,
                                                                                                    "/v1/items").orElseThrow()) {
                    assertThat(reservation.action(), instanceOf(ChaosLatencyAction.class));
                }
                return RESPONSE;
            };

            assertThat(new ChaosWebClientService("chaos").handle(chain,
                                                                   request(Method.GET,
                                                                           "https",
                                                                           "inventory.example.com",
                                                                           443,
                                                                           "/v1/items")),
                       sameInstance(RESPONSE));

            assertThat(proceeds.get(), is(1));
        } finally {
            registration.close();
        }
    }

    @Test
    void matchingWeightedChoiceLatencyProceeds() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            ChaosWeightedChoice effect = new ChaosWeightedChoice(List.of(
                    new ChaosWeightedChoice.Outcome(1, latency())));
            engine.create(plan(effect, 1), "test");
            AtomicInteger proceeds = new AtomicInteger();

            new ChaosWebClientService("chaos").handle(chain(proceeds),
                                                         request(Method.GET, "https", "inventory.example.com", 443, "/v1/items"));

            assertThat(proceeds.get(), is(1));
        } finally {
            registration.close();
        }
    }

    @Test
    void preservesDownstreamResponseAndException() {
        ChaosWebClientService service = new ChaosWebClientService("chaos");
        AtomicInteger proceeds = new AtomicInteger();
        RuntimeException failure = new IllegalArgumentException("downstream");

        assertThat(service.handle(chain(proceeds),
                                  request(Method.GET, "https", "inventory.example.com", 443, "/v1/items")),
                   sameInstance(RESPONSE));
        RuntimeException thrown = assertThrows(RuntimeException.class,
                                                () -> service.handle(request -> {
                                                    proceeds.incrementAndGet();
                                                    throw failure;
                                                }, request(Method.GET, "https", "inventory.example.com", 443, "/v1/items")));

        assertThat(thrown, sameInstance(failure));
        assertThat(proceeds.get(), is(2));
    }

    @Test
    void interruptedLatencyRestoresInterruptAndProceedsOnce() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            engine.create(plan(new ChaosLatency(Duration.ofSeconds(1), Duration.ZERO), 1), "test");
            AtomicInteger proceeds = new AtomicInteger();
            AtomicReference<Boolean> interrupted = new AtomicReference<>();
            Thread.currentThread().interrupt();

            new ChaosWebClientService("chaos").handle(request -> {
                proceeds.incrementAndGet();
                interrupted.set(Thread.currentThread().isInterrupted());
                return RESPONSE;
            }, request(Method.GET, "https", "inventory.example.com", 443, "/v1/items"));

            assertThat(proceeds.get(), is(1));
            assertThat(interrupted.get(), is(true));
        } finally {
            Thread.interrupted();
            registration.close();
        }
    }

    @Test
    void usesEffectivePortAndNormalizesEmptyPath() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            engine.create(plan(latency(), 3, "/"), "test");
            AtomicInteger proceeds = new AtomicInteger();
            ChaosWebClientService service = new ChaosWebClientService("chaos");

            service.handle(chain(proceeds), request(Method.GET, "https", "inventory.example.com", -1, ""));
            service.handle(chain(proceeds), request(Method.GET, "https", "inventory.example.com", 443, ""));
            service.handle(chain(proceeds), request(Method.GET, "http", "inventory.example.com", -1, ""));
            service.handle(chain(proceeds), request(Method.GET, "unknown", "inventory.example.com", -1, ""));

            assertThat(proceeds.get(), is(4));
            assertThat(engine.list().getFirst().matched(), is(2L));
        } finally {
            registration.close();
        }
    }

    @Test
    void implicitHttpPortMatchesHttpScope() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            engine.create(plan(latency(), 1, "/v1/items", "http", 80), "test");
            AtomicInteger proceeds = new AtomicInteger();

            new ChaosWebClientService("chaos").handle(chain(proceeds),
                                                         request(Method.GET, "http", "inventory.example.com", -1, "/v1/items"));

            assertThat(proceeds.get(), is(1));
            assertThat(engine.list().getFirst().matched(), is(1L));
        } finally {
            registration.close();
        }
    }

    @Test
    void nullSchemeHostOrUriProceedsOnceWithoutMatching() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            var run = engine.create(plan(latency(), 3), "test");
            AtomicInteger proceeds = new AtomicInteger();
            ChaosWebClientService service = new ChaosWebClientService("chaos");

            service.handle(chain(proceeds), request(Method.GET, ClientUri.create().host("inventory.example.com").path("/v1/items")));
            service.handle(chain(proceeds), request(Method.GET, ClientUri.create().scheme("https").path("/v1/items")));
            service.handle(chain(proceeds), request(Method.GET, null));

            assertThat(proceeds.get(), is(3));
            assertThat(engine.get(run.id()).orElseThrow().matched(), is(0L));
        } finally {
            registration.close();
        }
    }

    @Test
    void returnsCompleteSyntheticResponseWithoutProceeding() throws IOException {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            byte[] body = "{\"title\":\"Inventory unavailable\"}".getBytes(StandardCharsets.UTF_8);
            engine.create(plan(new ChaosSyntheticResponse(503,
                                                          Map.of("Retry-After", "3"),
                                                          Optional.of(MediaTypes.APPLICATION_JSON),
                                                          body),
                               1),
                          "test");
            AtomicInteger proceeds = new AtomicInteger();

            WebClientServiceResponse response = new ChaosWebClientService("chaos").handle(chain(proceeds),
                                                                                            inventoryRequest());

            assertThat(response.status(), is(Status.SERVICE_UNAVAILABLE_503));
            assertThat(response.headers().first(HeaderNames.RETRY_AFTER).orElseThrow(), is("3"));
            assertThat(response.headers().contentType().orElseThrow().mediaType(), is(MediaTypes.APPLICATION_JSON));
            assertThat(response.headers().contentLength().orElseThrow(), is((long) body.length));
            assertThat(response.inputStream().orElseThrow().readAllBytes(), is(body));
            assertThat(response.serviceRequest().uri().host(), is("inventory.example.com"));
            assertThat(proceeds.get(), is(0));
        } finally {
            registration.close();
        }
    }

    @Test
    void returnsEmptySyntheticResponseWithoutProceeding() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            engine.create(plan(new ChaosSyntheticResponse(429, Map.of(), Optional.empty(), new byte[0]), 1), "test");
            AtomicInteger proceeds = new AtomicInteger();

            WebClientServiceResponse response = new ChaosWebClientService("chaos").handle(chain(proceeds),
                                                                                            inventoryRequest());

            assertThat(response.status(), is(Status.TOO_MANY_REQUESTS_429));
            assertThat(response.headers().contentLength().orElseThrow(), is(0L));
            assertThat(response.inputStream(), is(Optional.empty()));
            assertThat(proceeds.get(), is(0));
        } finally {
            registration.close();
        }
    }

    @Test
    void returnsSyntheticResponseSelectedByWeightedChoice() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            ChaosSyntheticResponse synthetic = new ChaosSyntheticResponse(502,
                                                                           Map.of(),
                                                                           Optional.empty(),
                                                                           new byte[0]);
            ChaosWeightedChoice effect = new ChaosWeightedChoice(List.of(
                    new ChaosWeightedChoice.Outcome(1, synthetic)));
            engine.create(plan(effect, 1), "test");
            AtomicInteger proceeds = new AtomicInteger();

            WebClientServiceResponse response = new ChaosWebClientService("chaos").handle(chain(proceeds),
                                                                                            inventoryRequest());

            assertThat(response.status(), is(Status.BAD_GATEWAY_502));
            assertThat(proceeds.get(), is(0));
        } finally {
            registration.close();
        }
    }

    @Test
    void throwsConnectFailureWithoutProceedingAndReleasesReservation() {
        ChaosRunEngine engine = engine();
        ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(engine);
        try {
            var run = engine.create(plan(ChaosConnectFailure.instance(), 1), "test");
            AtomicInteger proceeds = new AtomicInteger();

            UncheckedIOException exception = assertThrows(UncheckedIOException.class,
                                                          () -> new ChaosWebClientService("chaos")
                                                                  .handle(chain(proceeds), inventoryRequest()));

            assertThat(exception.getCause(), instanceOf(ConnectException.class));
            assertThat(exception.getCause().getMessage(), is("Connection refused by chaos disruption"));
            assertThat(proceeds.get(), is(0));
            ChaosRunView view = engine.get(run.id()).orElseThrow();
            assertThat(view.matched(), is(1L));
            assertThat(view.activated(), is(1L));
            assertThat(view.completed(), is(1L));
            assertThat(view.inFlight(), is(0L));
        } finally {
            registration.close();
        }
    }

    @Test
    void rejectsNullChainAndRequest() {
        ChaosWebClientService service = new ChaosWebClientService("chaos");

        assertThrows(NullPointerException.class, () -> service.handle(null, request(Method.GET, "https", "inventory.example.com", 443, "/")));
        assertThrows(NullPointerException.class, () -> service.handle(chain(new AtomicInteger()), null));
    }

    private static WebClientService.Chain chain(AtomicInteger proceeds) {
        return request -> {
            proceeds.incrementAndGet();
            return RESPONSE;
        };
    }

    private static WebClientServiceRequest inventoryRequest() {
        return request(Method.GET, "https", "inventory.example.com", 443, "/v1/items");
    }

    private static WebClientServiceRequest request(Method method, String scheme, String host, int port, String path) {
        ClientUri uri = ClientUri.create().scheme(scheme).host(host).port(port).path(path);
        return request(method, uri);
    }

    private static WebClientServiceRequest request(Method method, ClientUri uri) {
        return (WebClientServiceRequest) Proxy.newProxyInstance(ChaosWebClientServiceTest.class.getClassLoader(),
                                                                 new Class<?>[] {WebClientServiceRequest.class},
                                                                 (proxy, invoked, arguments) -> switch (invoked.getName()) {
                                                                     case "method" -> method;
                                                                     case "uri" -> uri;
                                                                     case "toString" -> "request";
                                                                     case "hashCode" -> System.identityHashCode(proxy);
                                                                     case "equals" -> proxy == arguments[0];
                                                                     default -> throw new UnsupportedOperationException(invoked.getName());
                                                                 });
    }

    private static ChaosRunEngine engine() {
        TestChaosScheduler scheduler = new TestChaosScheduler();
        return ChaosRunEngine.create(ChaosLimitsConfig.builder().build(), scheduler.clock(), scheduler, UUID::randomUUID);
    }

    private static ChaosLatency latency() {
        return new ChaosLatency(Duration.ofMillis(1), Duration.ZERO);
    }

    private static ChaosRunPlan plan(ChaosEffect effect, int maximumConcurrent) {
        return plan(effect, maximumConcurrent, "/v1/items");
    }

    private static ChaosRunPlan plan(ChaosEffect effect, int maximumConcurrent, String path) {
        return plan(effect, maximumConcurrent, path, "https", 443);
    }

    private static ChaosRunPlan plan(ChaosEffect effect, int maximumConcurrent, String path, String scheme, int port) {
        ChaosOutboundHttpScope scope = new ChaosOutboundHttpScope(Set.of("GET"), scheme, "inventory.example.com", port,
                                                                   ChaosHttpScope.PathMatch.EXACT, path);
        ChaosRunPlan.ChaosDisruption disruption = new ChaosRunPlan.ChaosDisruption("outbound", scope, ChaosActivation.always(), effect,
                                                                                      new ChaosBudget(10, maximumConcurrent));
        return new ChaosRunPlan("outbound", Duration.ofSeconds(30), 42,
                                List.of(new ChaosRunPlan.ChaosStage("stage", Duration.ofSeconds(20), Optional.of(disruption))));
    }
}
