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
 * Shared normalized HTTP scope operations.
 */
final class ChaosScopeSupport {
    private ChaosScopeSupport() {
    }

    static Set<String> normalizeMethods(Set<String> methods) {
        Objects.requireNonNull(methods, "methods is null");
        Set<String> normalized = new LinkedHashSet<>();
        for (String method : methods) {
            normalized.add(Objects.requireNonNull(method, "method is null").toUpperCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(normalized);
    }

    static boolean methodMatches(Set<String> methods, String method) {
        return methods.contains(Objects.requireNonNull(method, "method is null").toUpperCase(Locale.ROOT));
    }

    static boolean pathMatches(ChaosHttpScope.PathMatch pathMatch, String path, String requestPath) {
        Objects.requireNonNull(requestPath, "requestPath is null");
        return switch (pathMatch) {
        case EXACT -> requestPath.equals(path);
        case PREFIX -> prefixContains(path, requestPath);
        };
    }

    static boolean overlaps(Set<String> firstMethods,
                            ChaosHttpScope.PathMatch firstPathMatch,
                            String firstPath,
                            Set<String> secondMethods,
                            ChaosHttpScope.PathMatch secondPathMatch,
                            String secondPath) {
        if (!methodsOverlap(firstMethods, secondMethods)) {
            return false;
        }
        return switch (firstPathMatch) {
        case EXACT -> switch (secondPathMatch) {
            case EXACT -> firstPath.equals(secondPath);
            case PREFIX -> prefixContains(secondPath, firstPath);
        };
        case PREFIX -> switch (secondPathMatch) {
            case EXACT -> prefixContains(firstPath, secondPath);
            case PREFIX -> prefixContains(firstPath, secondPath) || prefixContains(secondPath, firstPath);
        };
        };
    }

    private static boolean methodsOverlap(Set<String> first, Set<String> second) {
        return first.stream().anyMatch(second::contains);
    }

    private static boolean prefixContains(String prefix, String path) {
        return prefix.equals("/") || path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
