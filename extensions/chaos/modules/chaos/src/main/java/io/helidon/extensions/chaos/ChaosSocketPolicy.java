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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import io.helidon.webserver.ListenerConfig;
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

        boolean anonymousLoopback = config.security().allowUnauthenticatedLoopback();
        if (anonymousLoopback) {
            InetAddress address = controlListener.address();
            if (address == null || address.isAnyLocalAddress() || !address.isLoopbackAddress()) {
                throw new IllegalStateException("Anonymous chaos control access requires an explicit loopback binding");
            }
        } else if (!hasEnabledSecurityFeature(context)) {
            throw new IllegalStateException("Enabled chaos requires an enabled Helidon SecurityFeature");
        }

        return new Result(controlSocket, config.applicationSockets(), anonymousLoopback);
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
        return context.socket(socket).listener();
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
     * @param anonymousLoopback whether explicit anonymous loopback mode is active
     */
    record Result(String controlSocket, Set<String> applicationSockets, boolean anonymousLoopback) {
        Result {
            applicationSockets = Collections.unmodifiableSet(new LinkedHashSet<>(applicationSockets));
        }
    }
}
