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
import java.util.Set;

/**
 * Normalized inbound HTTP method and path scope.
 *
 * @param methods normalized HTTP methods
 * @param pathMatch path matching mode
 * @param path normalized absolute path
 */
record ChaosHttpScope(Set<String> methods, PathMatch pathMatch, String path) implements ChaosScope {

    ChaosHttpScope {
        methods = ChaosScopeSupport.normalizeMethods(methods);
        Objects.requireNonNull(pathMatch, "pathMatch is null");
        Objects.requireNonNull(path, "path is null");
    }

    boolean matches(String method, String requestPath) {
        Objects.requireNonNull(method, "method is null");
        Objects.requireNonNull(requestPath, "requestPath is null");
        return ChaosScopeSupport.methodMatches(methods, method)
                && ChaosScopeSupport.pathMatches(pathMatch, path, requestPath);
    }

    @Override
    public boolean overlaps(ChaosScope other) {
        return other instanceof ChaosHttpScope scope
                && ChaosScopeSupport.overlaps(methods,
                                              pathMatch,
                                              path,
                                              scope.methods,
                                              scope.pathMatch,
                                              scope.path);
    }

    /**
     * Supported path matching modes.
     */
    enum PathMatch {
        /** Exact normalized path. */
        EXACT,
        /** Normalized path-segment prefix. */
        PREFIX
    }
}
