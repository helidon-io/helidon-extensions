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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.helidon.config.Config;
import io.helidon.security.AuthenticationResponse;
import io.helidon.security.Security;
import io.helidon.webserver.ListenerConfig;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.Filter;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.security.SecurityFeature;
import io.helidon.webserver.spi.ServerFeature;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosServerFeatureTest {

    @Test
    void disabledFeatureDoesNotInspectOrMutateSockets() {
        TestFeatureContext context = context("127.0.0.1", false, true);

        ChaosServerFeature.create(ChaosConfig.builder().buildPrototype()).setup(context);

        assertThat(context.requestedSockets(), is(empty()));
        assertRuntimeAvailable();
    }

    @Test
    void rejectsNullFeatureContextWhenDisabled() {
        ChaosServerFeature feature = ChaosServerFeature.create(ChaosConfig.builder().buildPrototype());

        assertThrows(NullPointerException.class, () -> feature.setup(null));
    }

    @Test
    void rejectsUnknownControlAndApplicationSockets() {
        ChaosConfig unknownControl = enabledConfig("unknown", Set.of(WebServer.DEFAULT_SOCKET_NAME), true);
        ChaosConfig unknownApplication = enabledConfig("chaos-control", Set.of("unknown"), true);

        assertThrows(NoSuchElementException.class,
                     () -> ChaosSocketPolicy.validate(unknownControl, context("127.0.0.1", false, true)));
        assertThrows(NoSuchElementException.class,
                     () -> ChaosSocketPolicy.validate(unknownApplication, context("127.0.0.1", false, true)));
    }

    @Test
    void anonymousModeRequiresActualLocalBinding() {
        ChaosConfig config = enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true);

        assertThat(ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true)).anonymousLocal(), is(true));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("0.0.0.0", false, true)));
        assertThat(ChaosSocketPolicy.validate(config,
                                              context(new InetSocketAddress("127.0.0.1", 0), false, true))
                           .anonymousLocal(),
                   is(true));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config,
                                                       context(new InetSocketAddress("0.0.0.0", 0), false, true)));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config,
                                                       context(new InetSocketAddress("192.0.2.1", 0), false, true)));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config,
                                                       context(InetSocketAddress.createUnresolved("localhost", 0),
                                                               false,
                                                               true)));
        SocketAddress unixDomainSocket = UnixDomainSocketAddress.of(Path.of("target", "chaos-control.sock"));
        assertThat(ChaosSocketPolicy.validate(config, context(unixDomainSocket, false, true))
                           .anonymousLocal(),
                   is(true));
    }

    @Test
    void controlSocketRequiresFinitePayloadCeilingWithinChaosLimit() {
        ChaosConfig config = enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true);

        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true, -1)));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true, 65_537)));
        assertThat(ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true, 65_536)).anonymousLocal(),
                   is(true));
    }

    @Test
    void securedModeRequiresEnabledHelidonSecurityFeature() {
        ChaosConfig config = enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), false);

        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true)));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("127.0.0.1", true, false)));
        assertThat(ChaosSocketPolicy.validate(config, context("0.0.0.0", true, true)).anonymousLocal(), is(false));
    }

    @Test
    void socketAndSecurityValidationFailBeforeRuntimeRegistration() {
        TestFeatureContext unknownSocketContext = context("127.0.0.1", false, true);
        TestFeatureContext unsecuredContext = context("0.0.0.0", false, true);
        ChaosServerFeature unknownSocket = ChaosServerFeature.create(
                enabledConfig("unknown", Set.of(WebServer.DEFAULT_SOCKET_NAME), true));
        ChaosServerFeature unsecured = ChaosServerFeature.create(
                enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), false));

        try {
            assertThrows(NoSuchElementException.class, () -> unknownSocket.setup(unknownSocketContext));
            assertRuntimeAvailable();

            assertThrows(IllegalStateException.class, () -> unsecured.setup(unsecuredContext));
            assertRuntimeAvailable();
        } finally {
            unknownSocketContext.applicationFilters().forEach(Filter::afterStop);
            unsecuredContext.applicationFilters().forEach(Filter::afterStop);
        }
    }

    @Test
    void setupTargetsOnlyControlAndApplicationSockets() {
        TestFeatureContext context = context("127.0.0.1", false, true);
        ChaosServerFeature feature = ChaosServerFeature.create(
                enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true));

        try {
            feature.setup(context);

            assertRuntimeRegistered();
            assertThat(new LinkedHashSet<>(context.requestedSockets()),
                       containsInAnyOrder("chaos-control", WebServer.DEFAULT_SOCKET_NAME));
            assertThat(context.applicationFilters(), hasSize(1));
            assertThat(feature.name(), is("chaos"));
            assertThat(feature.type(), is("chaos"));
            assertThat(feature.weight(), is(700.0));

            context.applicationFilters().forEach(Filter::afterStop);
            assertRuntimeAvailable();
        } finally {
            context.applicationFilters().forEach(Filter::afterStop);
        }
    }

    @Test
    void duplicateEnabledSetupPreservesFirstRuntime() {
        TestFeatureContext firstContext = context("127.0.0.1", false, true);
        TestFeatureContext secondContext = context("127.0.0.1", false, true);
        ChaosServerFeature feature = ChaosServerFeature.create(
                enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true));

        try {
            feature.setup(firstContext);

            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> feature.setup(secondContext));

            assertThat(exception.getMessage(), is("A chaos runtime is already registered"));
            assertRuntimeRegistered();
        } finally {
            firstContext.applicationFilters().forEach(Filter::afterStop);
            secondContext.applicationFilters().forEach(Filter::afterStop);
        }
    }

    @Test
    void setupFailureWhileRegisteringControlServiceClosesRuntime() {
        TestFeatureContext context = context("127.0.0.1", false, true)
                .failWhenMutating(TestFeatureContext.RoutingFailure.CONTROL_SERVICE);
        ChaosServerFeature feature = ChaosServerFeature.create(
                enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true));

        try {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> feature.setup(context));

            assertThat(exception.getMessage(), is("control service registration failed"));
            assertRuntimeAvailable();
        } finally {
            context.applicationFilters().forEach(Filter::afterStop);
        }
    }

    @Test
    void setupFailureWhileAddingApplicationFilterClosesRuntime() {
        TestFeatureContext context = context("127.0.0.1", false, true)
                .failWhenMutating(TestFeatureContext.RoutingFailure.APPLICATION_FILTER);
        ChaosServerFeature feature = ChaosServerFeature.create(
                enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true));

        try {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> feature.setup(context));

            assertThat(exception.getMessage(), is("application filter addition failed"));
            assertRuntimeAvailable();
        } finally {
            context.applicationFilters().forEach(Filter::afterStop);
        }
    }

    @Test
    void applicationFiltersShareIdempotentRuntimeLifecycle() throws Exception {
        TestFeatureContext context = contextWithAdditionalApplicationSocket("application-two");
        ChaosServerFeature feature = ChaosServerFeature.create(
                enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME, "application-two"), true));
        ChaosRuntimeRegistration replacement = null;
        ChaosRunEngine replacementEngine = null;
        try {
            feature.setup(context);
            assertThat(context.applicationFilters(), hasSize(2));

            closeFiltersConcurrently(context.applicationFilters());
            assertRuntimeAvailable();

            replacementEngine = ChaosRunEngine.create(ChaosLimitsConfig.builder().build());
            replacement = ChaosRuntimeBridge.register(replacementEngine);
            context.controlService().afterStop();
            context.applicationFilters().forEach(Filter::afterStop);

            assertRuntimeRegistered();
            assertOpen(replacementEngine);
        } finally {
            context.applicationFilters().forEach(Filter::afterStop);
            if (replacement != null) {
                replacement.close();
            }
            if (replacementEngine != null) {
                replacementEngine.close();
            }
        }
    }

    @Test
    void controlServiceFirstShutdownClosesSharedRuntime() {
        TestFeatureContext context = context("127.0.0.1", false, true);
        ChaosServerFeature feature = ChaosServerFeature.create(
                enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true));

        try {
            feature.setup(context);

            context.controlService().afterStop();

            assertRuntimeClosed();
            context.applicationFilters().get(0).afterStop();
            context.controlService().afterStop();
            assertRuntimeClosed();
        } finally {
            context.stopLifecycle();
        }
    }

    @Test
    void applicationFilterFirstShutdownClosesSharedRuntime() {
        TestFeatureContext context = context("127.0.0.1", false, true);
        ChaosServerFeature feature = ChaosServerFeature.create(
                enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true));

        try {
            feature.setup(context);

            context.applicationFilters().get(0).afterStop();

            assertRuntimeClosed();
            context.controlService().afterStop();
            context.applicationFilters().get(0).afterStop();
            assertRuntimeClosed();
        } finally {
            context.stopLifecycle();
        }
    }

    @Test
    void providerUsesHelidonConfiguredProviderContract() {
        ChaosServerFeatureProvider provider = new ChaosServerFeatureProvider();

        ChaosServerFeature feature = provider.create(Config.empty(), "custom-chaos");

        assertThat(provider.configKey(), is("chaos"));
        assertThat(feature.name(), is("custom-chaos"));
        assertThat(feature.prototype().enabled(), is(false));
    }

    @Test
    void unsafeSocketConfigurationsFailDuringServerConstruction() {
        var wildcard = WebServer.builder()
                .featuresDiscoverServices(false)
                .host("127.0.0.1")
                .port(0)
                .putSocket("chaos-control", socket -> socket.host("0.0.0.0").port(0))
                .addFeature(ChaosServerFeature.create(enabledConfig("chaos-control",
                                                                      Set.of(WebServer.DEFAULT_SOCKET_NAME),
                                                                      true)));
        assertThat(wildcard.sockets().get("chaos-control").host(), is("0.0.0.0"));
        assertThat(wildcard.sockets().get("chaos-control").address().isAnyLocalAddress(), is(true));
        assertThrows(IllegalStateException.class, wildcard::build);

        var missingControl = WebServer.builder()
                .featuresDiscoverServices(false)
                .host("127.0.0.1")
                .port(0)
                .addFeature(ChaosServerFeature.create(enabledConfig("missing-control",
                                                                      Set.of(WebServer.DEFAULT_SOCKET_NAME),
                                                                      true)));
        assertThrows(NoSuchElementException.class, missingControl::build);
        assertRuntimeAvailable();
    }

    private static void assertOpen(ChaosRunEngine engine) {
        engine.create(plan(), "test");
    }

    private static void assertClosed(ChaosRunEngine engine) {
        assertThrows(ChaosRunEngine.ConflictException.class, () -> engine.create(plan(), "test"));
    }

    private static void assertRuntimeClosed() {
        assertRuntimeAvailable();
        assertThat(ChaosRuntimeBridge.reserveOutbound("GET", "https", "inventory.example.com", 443, "/items"),
                   is(Optional.empty()));
    }

    private static void assertRuntimeRegistered() {
        ChaosRunEngine candidate = ChaosRunEngine.create(ChaosLimitsConfig.builder().build());
        try {
            assertThrows(IllegalStateException.class, () -> ChaosRuntimeBridge.register(candidate));
            assertOpen(candidate);
        } finally {
            candidate.close();
        }
    }

    private static void assertRuntimeAvailable() {
        ChaosRunEngine candidate = ChaosRunEngine.create(ChaosLimitsConfig.builder().build());
        try (ChaosRuntimeRegistration registration = ChaosRuntimeBridge.register(candidate)) {
            assertOpen(candidate);
        }
        assertClosed(candidate);
    }

    private static ChaosRunPlan plan() {
        return new ChaosRunPlan("test",
                                Duration.ofSeconds(1),
                                1,
                                List.of(new ChaosRunPlan.ChaosStage("stage",
                                                                    Duration.ofSeconds(1),
                                                                    Optional.empty())));
    }

    private static void closeFiltersConcurrently(List<Filter> filters) throws Exception {
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> closeFilterAfterStart(filters.get(0), ready, start));
            var second = executor.submit(() -> closeFilterAfterStart(filters.get(1), ready, start));
            var repeated = executor.submit(() -> closeFilterAfterStart(filters.get(0), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS), is(true));
            start.countDown();
            first.get();
            second.get();
            repeated.get();
        }
    }

    private static void closeFilterAfterStart(Filter filter, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to close application filters");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while closing application filters", exception);
        }
        filter.afterStop();
    }

    private static ChaosConfig enabledConfig(String controlSocket,
                                             Set<String> applicationSockets,
                                             boolean anonymousLocal) {
        return ChaosConfig.builder()
                .enabled(true)
                .controlSocket(controlSocket)
                .applicationSockets(applicationSockets)
                .security(ChaosSecurityConfig.builder()
                                  .allowUnauthenticatedLocal(anonymousLocal)
                                  .build())
                .buildPrototype();
    }

    private static TestFeatureContext context(String controlHost,
                                              boolean securityFeature,
                                              boolean securityEnabled) {
        return context(controlHost, securityFeature, securityEnabled, 65_536);
    }

    private static TestFeatureContext context(String controlHost,
                                              boolean securityFeature,
                                              boolean securityEnabled,
                                              long maximumPayloadSize) {
        WebServerConfig.Builder builder = WebServerConfig.builder()
                .featuresDiscoverServices(false)
                .host("127.0.0.1")
                .port(0)
                .putSocket("chaos-control", socket -> socket.host(controlHost)
                        .port(0)
                        .maxPayloadSize(maximumPayloadSize));
        if (securityFeature) {
            Security security = Security.builder()
                    .enabled(securityEnabled)
                    .addAuthenticationProvider(request -> AuthenticationResponse.abstain())
                    .build();
            builder.addFeature(SecurityFeature.builder().security(security).build());
        }
        return new TestFeatureContext(builder.buildPrototype());
    }

    private static TestFeatureContext context(SocketAddress controlBindAddress,
                                              boolean securityFeature,
                                              boolean securityEnabled) {
        WebServerConfig.Builder builder = WebServerConfig.builder()
                .featuresDiscoverServices(false)
                .host("127.0.0.1")
                .port(0)
                .putSocket("chaos-control", socket -> socket.bindAddress(controlBindAddress)
                        .maxPayloadSize(65_536));
        if (securityFeature) {
            Security security = Security.builder()
                    .enabled(securityEnabled)
                    .addAuthenticationProvider(request -> AuthenticationResponse.abstain())
                    .build();
            builder.addFeature(SecurityFeature.builder().security(security).build());
        }
        return new TestFeatureContext(builder.buildPrototype());
    }

    private static TestFeatureContext contextWithAdditionalApplicationSocket(String applicationSocket) {
        WebServerConfig config = WebServerConfig.builder()
                .featuresDiscoverServices(false)
                .host("127.0.0.1")
                .port(0)
                .putSocket("chaos-control", socket -> socket.host("127.0.0.1").port(0).maxPayloadSize(65_536))
                .putSocket(applicationSocket, socket -> socket.host("127.0.0.1").port(0))
                .buildPrototype();
        return new TestFeatureContext(config);
    }

    private static final class TestFeatureContext implements ServerFeature.ServerFeatureContext {
        private final WebServerConfig config;
        private final Map<String, ServerFeature.SocketBuilders> socketBuilders = new LinkedHashMap<>();
        private final List<String> requestedSockets = new ArrayList<>();
        private final List<Filter> applicationFilters = new ArrayList<>();
        private ChaosControlService controlService;
        private RoutingFailure routingFailure = RoutingFailure.NONE;

        private TestFeatureContext(WebServerConfig config) {
            this.config = config;
            socketBuilders.put(WebServer.DEFAULT_SOCKET_NAME, socketBuilders(WebServer.DEFAULT_SOCKET_NAME, config));
            config.sockets().forEach((name, listener) -> socketBuilders.put(name, socketBuilders(name, listener)));
        }

        @Override
        public WebServerConfig serverConfig() {
            return config;
        }

        @Override
        public Set<String> sockets() {
            return Set.copyOf(socketBuilders.keySet());
        }

        @Override
        public boolean socketExists(String socketName) {
            return socketBuilders.containsKey(socketName);
        }

        @Override
        public ServerFeature.SocketBuilders socket(String socketName) {
            requestedSockets.add(socketName);
            ServerFeature.SocketBuilders builders = socketBuilders.get(socketName);
            if (builders == null) {
                throw new NoSuchElementException("Unknown test socket: " + socketName);
            }
            return builders;
        }

        private ServerFeature.SocketBuilders socketBuilders(String socketName, ListenerConfig listener) {
            HttpRouting.Builder routing = recordingRouting(socketName);
            return new ServerFeature.SocketBuilders() {
                @Override
                public ListenerConfig listener() {
                    return listener;
                }

                @Override
                public HttpRouting.Builder httpRouting() {
                    return routing;
                }

                @Override
                public ServerFeature.RoutingBuilders routingBuilders() {
                    return null;
                }
            };
        }

        private List<String> requestedSockets() {
            return List.copyOf(requestedSockets);
        }

        private List<Filter> applicationFilters() {
            return List.copyOf(applicationFilters);
        }

        private ChaosControlService controlService() {
            if (controlService == null) {
                throw new AssertionError("Expected setup to register the chaos control service");
            }
            return controlService;
        }

        private void stopLifecycle() {
            if (controlService != null) {
                controlService.afterStop();
            }
            applicationFilters.forEach(Filter::afterStop);
        }

        private TestFeatureContext failWhenMutating(RoutingFailure failure) {
            routingFailure = failure;
            return this;
        }

        private HttpRouting.Builder recordingRouting(String socketName) {
            HttpRouting.Builder delegate = HttpRouting.builder();
            return (HttpRouting.Builder) Proxy.newProxyInstance(HttpRouting.Builder.class.getClassLoader(),
                                                                 new Class<?>[] {HttpRouting.Builder.class},
                                                                 (proxy, method, arguments) -> {
                                                                     if ("register".equals(method.getName())
                                                                             && "chaos-control".equals(socketName)
                                                                             && arguments != null
                                                                             && arguments.length > 0
                                                                             && arguments[0] instanceof String) {
                                                                         if (arguments.length == 2
                                                                                 && arguments[1] instanceof HttpService[] services
                                                                                 && services.length == 1
                                                                                 && services[0] instanceof ChaosControlService service) {
                                                                             controlService = service;
                                                                         }
                                                                         failIfRequested(RoutingFailure.CONTROL_SERVICE,
                                                                                         "control service registration failed");
                                                                     }
                                                                     if ("addFilter".equals(method.getName())
                                                                             && arguments != null
                                                                             && arguments.length == 1
                                                                             && arguments[0] instanceof Filter filter) {
                                                                         failIfRequested(RoutingFailure.APPLICATION_FILTER,
                                                                                         "application filter addition failed");
                                                                         applicationFilters.add(filter);
                                                                     }
                                                                     try {
                                                                         return method.invoke(delegate, arguments);
                                                                     } catch (InvocationTargetException exception) {
                                                                         throw exception.getCause();
                                                                     }
                                                                 });
        }

        private void failIfRequested(RoutingFailure failure, String message) {
            if (routingFailure == failure) {
                throw new IllegalStateException(message);
            }
        }

        private enum RoutingFailure {
            NONE,
            CONTROL_SERVICE,
            APPLICATION_FILTER
        }
    }
}
