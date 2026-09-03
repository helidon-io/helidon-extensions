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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Normalized inbound HTTP method and path scope.
 *
 * @param methods normalized HTTP methods
 * @param pathMatch path matching mode
 * @param path normalized absolute path
 */
record ChaosHttpScope(Set<String> methods, PathMatch pathMatch, String path) {

    ChaosHttpScope {
        methods = Collections.unmodifiableSet(new LinkedHashSet<>(methods));
        Objects.requireNonNull(pathMatch);
        Objects.requireNonNull(path);
    }

    boolean matches(String method, String requestPath) {
        if (!methods.contains(method.toUpperCase(Locale.ROOT))) {
            return false;
        }
        return switch (pathMatch) {
        case EXACT -> requestPath.equals(path);
        case PREFIX -> path.equals("/") || requestPath.equals(path) || requestPath.startsWith(path + "/");
        };
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
