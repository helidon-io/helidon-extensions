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

import java.util.Objects;
import java.util.Optional;

import io.helidon.http.Method;
import io.helidon.webclient.api.ClientUri;
import io.helidon.webclient.api.WebClientServiceRequest;
import io.helidon.webclient.api.WebClientServiceResponse;
import io.helidon.webclient.spi.WebClientService;

final class ChaosWebClientService implements WebClientService {
    private final String name;

    ChaosWebClientService(String name) {
        this.name = Objects.requireNonNull(name, "name is null");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "chaos";
    }

    @Override
    public WebClientServiceResponse handle(Chain chain, WebClientServiceRequest request) {
        Objects.requireNonNull(chain, "chain is null");
        Objects.requireNonNull(request, "request is null");

        Method method = request.method();
        ClientUri uri = request.uri();
        if (method != null && uri != null) {
            String scheme = uri.scheme();
            String host = uri.host();
            String path = uri.path() == null ? null : uri.path().path();
            if (path != null && path.isEmpty()) {
                path = "/";
            }
            var port = targetPort(scheme, host, path, uri.port());
            if (port.isPresent()) {
                var reserved = ChaosRuntimeBridge.reserveOutbound(method.text(), scheme, host, port.orElseThrow(), path);
                if (reserved.isPresent()) {
                    try (ChaosRunEngine.Reservation reservation = reserved.orElseThrow()) {
                        switch (reservation.action()) {
                        case ChaosLatencyAction latency -> latency.apply();
                        case ChaosSyntheticResponse syntheticResponse -> {
                            return ChaosWebClientSyntheticResponse.create(request, syntheticResponse);
                        }
                        }
                    }
                }
            }
        }
        return chain.proceed(request);
    }

    private static Optional<Integer> targetPort(String scheme, String host, String path, int port) {
        if (scheme == null || host == null || path == null) {
            return Optional.empty();
        }
        int effectivePort = effectivePort(scheme, port);
        return effectivePort > 0 ? Optional.of(effectivePort) : Optional.empty();
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 1 && port <= 65535) {
            return port;
        }
        if (port != -1) {
            return -1;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        return -1;
    }
}
