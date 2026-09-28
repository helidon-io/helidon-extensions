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

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Normalized outbound HTTP method, authority, and path scope.
 *
 * @param methods normalized HTTP methods
 * @param scheme normalized URI scheme
 * @param host normalized URI host
 * @param port effective URI port
 * @param pathMatch path matching mode
 * @param path normalized absolute path
 */
record ChaosOutboundHttpScope(Set<String> methods,
                              String scheme,
                              String host,
                              int port,
                              ChaosHttpScope.PathMatch pathMatch,
                              String path) implements ChaosScope {

    ChaosOutboundHttpScope {
        methods = ChaosScopeSupport.normalizeMethods(methods);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("methods must not be empty");
        }
        Objects.requireNonNull(scheme, "scheme is null");
        if (scheme.isBlank()) {
            throw new IllegalArgumentException("scheme must not be blank");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        Objects.requireNonNull(host, "host is null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        Objects.requireNonNull(pathMatch, "pathMatch is null");
        Objects.requireNonNull(path, "path is null");
    }

    boolean matches(String method,
                    String requestScheme,
                    String requestHost,
                    int requestPort,
                    String requestPath) {
        Objects.requireNonNull(method, "method is null");
        Objects.requireNonNull(requestScheme, "requestScheme is null");
        Objects.requireNonNull(requestHost, "requestHost is null");
        Objects.requireNonNull(requestPath, "requestPath is null");
        return ChaosScopeSupport.methodMatches(methods, method)
                && scheme.equals(requestScheme.toLowerCase(Locale.ROOT))
                && host.equals(requestHost.toLowerCase(Locale.ROOT))
                && port == requestPort
                && ChaosScopeSupport.pathMatches(pathMatch, path, requestPath);
    }

    @Override
    public boolean overlaps(ChaosScope other) {
        return other instanceof ChaosOutboundHttpScope scope
                && scheme.equals(scope.scheme)
                && host.equals(scope.host)
                && port == scope.port
                && ChaosScopeSupport.overlaps(methods,
                                              pathMatch,
                                              path,
                                              scope.methods,
                                              scope.pathMatch,
                                              scope.path);
    }
}
