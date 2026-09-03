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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import io.helidon.webserver.ListenerConfig;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.security.SecurityFeature;
import io.helidon.webserver.spi.ServerFeature;

/**
 * Validates socket isolation and control-plane security before installation.
 */
final class ChaosSocketPolicy {

    private ChaosSocketPolicy() {
    }

    static Result validate(ChaosConfig config, ServerFeature.ServerFeatureContext context) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(context);
        String controlSocket = config.controlSocket()
                .orElseThrow(() -> new IllegalStateException("Enabled chaos requires a control socket"));
        ListenerConfig controlListener = listener(context, controlSocket);
        validateControlPayloadLimit(config.limits(), controlListener);
        for (String applicationSocket : config.applicationSockets()) {
            listener(context, applicationSocket);
            if (controlSocket.equals(applicationSocket)) {
                throw new IllegalStateException("The chaos control socket cannot carry application traffic");
            }
        }

        boolean anonymousLocal = config.security().allowUnauthenticatedLocal();
        if (anonymousLocal) {
            if (!isLocal(controlListener)) {
                throw new IllegalStateException("Anonymous chaos control access requires an explicit local binding");
            }
        } else if (!hasEnabledSecurityFeature(context)) {
            throw new IllegalStateException("Enabled chaos requires an enabled Helidon SecurityFeature");
        }

        return new Result(controlSocket, config.applicationSockets(), anonymousLocal);
    }

    private static void validateControlPayloadLimit(ChaosLimitsConfig limits, ListenerConfig listener) {
        long maximumPayloadSize = listener.maxPayloadSize();
        if (maximumPayloadSize <= 0) {
            throw new IllegalStateException("The chaos control socket requires a finite max-payload-size");
        }
        if (maximumPayloadSize > limits.maximumControlRequestBytes()) {
            throw new IllegalStateException("The chaos control socket max-payload-size exceeds "
                                                    + "maximum-control-request-bytes");
        }
    }

    private static ListenerConfig listener(ServerFeature.ServerFeatureContext context, String socket) {
        if (WebServer.DEFAULT_SOCKET_NAME.equals(socket)) {
            return context.serverConfig();
        }
        ListenerConfig listener = context.serverConfig().sockets().get(socket);
        if (listener == null) {
            throw new NoSuchElementException("There is no socket configuration for socket named \"" + socket + "\"");
        }
        return listener;
    }

    private static boolean isLocal(ListenerConfig listener) {
        return listener.bindAddress()
                .map(ChaosSocketPolicy::isLocal)
                .orElseGet(() -> isLoopback(listener.address()));
    }

    private static boolean isLocal(SocketAddress address) {
        if (address instanceof InetSocketAddress inetAddress) {
            return isLoopback(inetAddress.getAddress());
        }
        return address instanceof UnixDomainSocketAddress;
    }

    private static boolean isLoopback(InetAddress address) {
        return address != null && !address.isAnyLocalAddress() && address.isLoopbackAddress();
    }

    private static boolean hasEnabledSecurityFeature(ServerFeature.ServerFeatureContext context) {
        return context.serverConfig().features().stream()
                .filter(SecurityFeature.class::isInstance)
                .map(SecurityFeature.class::cast)
                .map(feature -> feature.prototype().security())
                .anyMatch(security -> security.enabled() && security.resolveAtnProvider(null).isPresent());
    }

    /**
     * Validated installation targets.
     *
     * @param controlSocket dedicated control socket
     * @param applicationSockets sockets eligible for disruption
     * @param anonymousLocal whether explicit anonymous local mode is active
     */
    record Result(String controlSocket, Set<String> applicationSockets, boolean anonymousLocal) {
        Result {
            applicationSockets = Collections.unmodifiableSet(new LinkedHashSet<>(applicationSockets));
        }
    }
}
