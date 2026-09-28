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

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.EXACT;
import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.PREFIX;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosOutboundHttpScopeTest {

    @Test
    void normalizesLogicalComponentsAndDefensivelyCopiesMethods() {
        Set<String> methods = new LinkedHashSet<>();
        methods.add("get");
        methods.add("PoSt");
        ChaosOutboundHttpScope scope = new ChaosOutboundHttpScope(methods,
                                                                  "HTTPS",
                                                                  "API.Example.COM",
                                                                  443,
                                                                  EXACT,
                                                                  "/v1/items");

        methods.add("DELETE");

        assertThat(scope.methods(), is(Set.of("GET", "POST")));
        assertThat(scope.scheme(), is("https"));
        assertThat(scope.host(), is("api.example.com"));
        assertThat(scope.port(), is(443));
        assertThat(scope.pathMatch(), is(EXACT));
        assertThat(scope.path(), is("/v1/items"));
        assertThat(scope.matches("gEt", "hTtPs", "Api.Example.Com", 443, "/v1/items"), is(true));
        assertThat(scope.matches("DELETE", "https", "api.example.com", 443, "/v1/items"), is(false));
    }

    @Test
    void requiresSchemeHostAndEffectivePortToMatch() {
        ChaosOutboundHttpScope scope = scope(EXACT, "/v1/items");

        assertThat(scope.matches("GET", "http", "api.example.com", 443, "/v1/items"), is(false));
        assertThat(scope.matches("GET", "https", "other.example.com", 443, "/v1/items"), is(false));
        assertThat(scope.matches("GET", "https", "api.example.com", 8443, "/v1/items"), is(false));
    }

    @Test
    void exactAndPrefixMatchesArePathSegmentAware() {
        ChaosOutboundHttpScope exact = scope(EXACT, "/v1/items");
        ChaosOutboundHttpScope prefix = scope(PREFIX, "/v1/items");

        assertThat(exact.matches("GET", "https", "api.example.com", 443, "/v1/items"), is(true));
        assertThat(exact.matches("GET", "https", "api.example.com", 443, "/v1/items/42"), is(false));
        assertThat(prefix.matches("GET", "https", "api.example.com", 443, "/v1/items"), is(true));
        assertThat(prefix.matches("GET", "https", "api.example.com", 443, "/v1/items/42"), is(true));
        assertThat(prefix.matches("GET", "https", "api.example.com", 443, "/v1/items-old"), is(false));
    }

    @Test
    void matchesOnlyTheDecodedPathComponent() {
        ChaosOutboundHttpScope scope = scope(EXACT, "/v1/items");
        URI requestUri = URI.create("https://api.example.com/v1/item%73?expand=true#details");

        assertThat(scope.matches("GET",
                                 requestUri.getScheme(),
                                 requestUri.getHost(),
                                 443,
                                 requestUri.getPath()), is(true));
        assertThat(scope.matches("GET",
                                 requestUri.getScheme(),
                                 requestUri.getHost(),
                                 443,
                                 requestUri.getRawPath()), is(false));
        assertThat(scope.matches("GET",
                                 requestUri.getScheme(),
                                 requestUri.getHost(),
                                 443,
                                 requestUri.getPath() + "?expand=true#details"), is(false));
    }

    @Test
    void overlapsOnlyWhenEveryLogicalComponentAndPathOverlaps() {
        ChaosOutboundHttpScope prefix = new ChaosOutboundHttpScope(Set.of("GET", "POST"),
                                                                   "HTTPS",
                                                                   "API.Example.COM",
                                                                   443,
                                                                   PREFIX,
                                                                   "/v1/items");
        ChaosOutboundHttpScope exact = new ChaosOutboundHttpScope(Set.of("get"),
                                                                  "https",
                                                                  "api.example.com",
                                                                  443,
                                                                  EXACT,
                                                                  "/v1/items/42");

        assertThat(prefix.overlaps(exact), is(true));
        assertThat(exact.overlaps(prefix), is(true));
        assertThat(prefix.overlaps(outbound(Set.of("DELETE"), "https", "api.example.com", 443,
                                                    EXACT, "/v1/items/42")), is(false));
        assertThat(prefix.overlaps(outbound(Set.of("GET"), "http", "api.example.com", 443,
                                                    EXACT, "/v1/items/42")), is(false));
        assertThat(prefix.overlaps(outbound(Set.of("GET"), "https", "other.example.com", 443,
                                                    EXACT, "/v1/items/42")), is(false));
        assertThat(prefix.overlaps(outbound(Set.of("GET"), "https", "api.example.com", 8443,
                                                    EXACT, "/v1/items/42")), is(false));
        assertThat(prefix.overlaps(outbound(Set.of("GET"), "https", "api.example.com", 443,
                                                    EXACT, "/v1/items-old")), is(false));
    }

    @Test
    void rejectsInvalidConstructorArgumentsImmediately() {
        Set<String> methodsWithNull = new LinkedHashSet<>();
        methodsWithNull.add(null);

        assertThrows(NullPointerException.class,
                     () -> new ChaosOutboundHttpScope(null, "https", "api.example.com", 443, EXACT, "/"));
        assertThrows(NullPointerException.class,
                     () -> new ChaosOutboundHttpScope(methodsWithNull,
                                                      "https",
                                                      "api.example.com",
                                                      443,
                                                      EXACT,
                                                      "/"));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosOutboundHttpScope(Set.of(), "https", "api.example.com", 443, EXACT, "/"));
        assertThrows(NullPointerException.class,
                     () -> new ChaosOutboundHttpScope(Set.of("GET"), null, "api.example.com", 443, EXACT, "/"));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosOutboundHttpScope(Set.of("GET"), " ", "api.example.com", 443, EXACT, "/"));
        assertThrows(NullPointerException.class,
                     () -> new ChaosOutboundHttpScope(Set.of("GET"), "https", null, 443, EXACT, "/"));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosOutboundHttpScope(Set.of("GET"), "https", " ", 443, EXACT, "/"));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosOutboundHttpScope(Set.of("GET"), "https", "api.example.com", 0, EXACT, "/"));
        assertThrows(IllegalArgumentException.class,
                     () -> new ChaosOutboundHttpScope(Set.of("GET"), "https", "api.example.com", 65536, EXACT, "/"));
        assertThrows(NullPointerException.class,
                     () -> new ChaosOutboundHttpScope(Set.of("GET"), "https", "api.example.com", 443, null, "/"));
        assertThrows(NullPointerException.class,
                     () -> new ChaosOutboundHttpScope(Set.of("GET"), "https", "api.example.com", 443, EXACT, null));
    }

    @Test
    void rejectsNullMatchingArgumentsImmediately() {
        ChaosOutboundHttpScope scope = scope(EXACT, "/v1/items");

        assertThrows(NullPointerException.class,
                     () -> scope.matches(null, "https", "api.example.com", 443, "/v1/items"));
        assertThrows(NullPointerException.class,
                     () -> scope.matches("GET", null, "api.example.com", 443, "/v1/items"));
        assertThrows(NullPointerException.class,
                     () -> scope.matches("GET", "https", null, 443, "/v1/items"));
        assertThrows(NullPointerException.class,
                     () -> scope.matches("GET", "https", "api.example.com", 443, null));
    }

    private static ChaosOutboundHttpScope scope(ChaosHttpScope.PathMatch pathMatch, String path) {
        return outbound(Set.of("GET"), "https", "api.example.com", 443, pathMatch, path);
    }

    private static ChaosOutboundHttpScope outbound(Set<String> methods,
                                                   String scheme,
                                                   String host,
                                                   int port,
                                                   ChaosHttpScope.PathMatch pathMatch,
                                                   String path) {
        return new ChaosOutboundHttpScope(methods, scheme, host, port, pathMatch, path);
    }
}
