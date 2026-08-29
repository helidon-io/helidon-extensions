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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.helidon.config.Config;
import io.helidon.security.AuthenticationResponse;
import io.helidon.security.Security;
import io.helidon.webserver.ListenerConfig;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.security.SecurityFeature;
import io.helidon.webserver.spi.ServerFeature;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosServerFeatureTest {

    @Test
    void disabledFeatureDoesNotInspectOrMutateSockets() {
        TestFeatureContext context = context("127.0.0.1", false, true);

        ChaosServerFeature.create(ChaosConfig.builder().buildPrototype()).setup(context);

        assertThat(context.requestedSockets(), is(empty()));
    }

    @Test
    void rejectsUnknownControlAndApplicationSockets() {
        ChaosConfig unknownControl = enabledConfig("unknown", Set.of(WebServer.DEFAULT_SOCKET_NAME), true);
        ChaosConfig unknownApplication = enabledConfig("chaos-control", Set.of("unknown"), true);

        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(unknownControl, context("127.0.0.1", false, true)));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(unknownApplication, context("127.0.0.1", false, true)));
    }

    @Test
    void anonymousModeRequiresActualLoopbackBinding() {
        ChaosConfig config = enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true);

        assertDoesNotThrow(() -> ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true)));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("0.0.0.0", false, true)));
    }

    @Test
    void controlSocketRequiresFinitePayloadCeilingWithinChaosLimit() {
        ChaosConfig config = enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true);

        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true, -1)));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true, 65_537)));
        assertDoesNotThrow(() -> ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true, 65_536)));
    }

    @Test
    void securedModeRequiresEnabledHelidonSecurityFeature() {
        ChaosConfig config = enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), false);

        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("127.0.0.1", false, true)));
        assertThrows(IllegalStateException.class,
                     () -> ChaosSocketPolicy.validate(config, context("127.0.0.1", true, false)));
        assertDoesNotThrow(() -> ChaosSocketPolicy.validate(config, context("0.0.0.0", true, true)));
    }

    @Test
    void setupTargetsOnlyControlAndApplicationSockets() {
        TestFeatureContext context = context("127.0.0.1", false, true);
        ChaosServerFeature feature = ChaosServerFeature.create(
                enabledConfig("chaos-control", Set.of(WebServer.DEFAULT_SOCKET_NAME), true));

        feature.setup(context);

        assertThat(new LinkedHashSet<>(context.requestedSockets()),
                   containsInAnyOrder("chaos-control", WebServer.DEFAULT_SOCKET_NAME));
        assertThat(feature.name(), is("chaos"));
        assertThat(feature.type(), is("chaos"));
        assertThat(feature.weight(), is(700.0));
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
        assertThrows(IllegalStateException.class, missingControl::build);
    }

    private static ChaosConfig enabledConfig(String controlSocket,
                                             Set<String> applicationSockets,
                                             boolean anonymousLoopback) {
        return ChaosConfig.builder()
                .enabled(true)
                .controlSocket(controlSocket)
                .applicationSockets(applicationSockets)
                .security(ChaosSecurityConfig.builder()
                                  .allowUnauthenticatedLoopback(anonymousLoopback)
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

    private static final class TestFeatureContext implements ServerFeature.ServerFeatureContext {
        private final WebServerConfig config;
        private final Map<String, ServerFeature.SocketBuilders> socketBuilders = new LinkedHashMap<>();
        private final List<String> requestedSockets = new ArrayList<>();

        private TestFeatureContext(WebServerConfig config) {
            this.config = config;
            socketBuilders.put(WebServer.DEFAULT_SOCKET_NAME, socketBuilders(config));
            config.sockets().forEach((name, listener) -> socketBuilders.put(name, socketBuilders(listener)));
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
                throw new IllegalArgumentException("Unknown test socket: " + socketName);
            }
            return builders;
        }

        private List<String> requestedSockets() {
            return List.copyOf(requestedSockets);
        }

        private static ServerFeature.SocketBuilders socketBuilders(ListenerConfig listener) {
            HttpRouting.Builder routing = HttpRouting.builder();
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
    }
}
