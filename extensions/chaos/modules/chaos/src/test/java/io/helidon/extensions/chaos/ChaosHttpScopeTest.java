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

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.EXACT;
import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.PREFIX;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosHttpScopeTest {

    @Test
    void exactMatchRequiresSameMethodAndPath() {
        ChaosHttpScope scope = new ChaosHttpScope(Set.of("GET"), EXACT, "/orders");

        assertThat(scope.matches("GET", "/orders"), is(true));
        assertThat(scope.matches("POST", "/orders"), is(false));
        assertThat(scope.matches("GET", "/orders/42"), is(false));
    }

    @Test
    void prefixMatchIsPathSegmentAware() {
        ChaosHttpScope scope = new ChaosHttpScope(Set.of("GET"), PREFIX, "/orders");

        assertThat(scope.matches("GET", "/orders"), is(true));
        assertThat(scope.matches("GET", "/orders/42"), is(true));
        assertThat(scope.matches("GET", "/orders-old"), is(false));
    }

    @Test
    void rootPrefixMatchesEveryAbsolutePath() {
        ChaosHttpScope scope = new ChaosHttpScope(Set.of("GET"), PREFIX, "/");

        assertThat(scope.matches("GET", "/"), is(true));
        assertThat(scope.matches("GET", "/orders"), is(true));
    }

    @Test
    void normalizesAndDefensivelyCopiesMethods() {
        Set<String> methods = new LinkedHashSet<>();
        methods.add("get");
        ChaosHttpScope scope = new ChaosHttpScope(methods, EXACT, "/orders");

        methods.add("POST");

        assertThat(scope.methods(), is(Set.of("GET")));
        assertThat(scope.matches("gEt", "/orders"), is(true));
        assertThat(scope.matches("POST", "/orders"), is(false));
    }

    @Test
    void rejectsNullInputsImmediately() {
        Set<String> methodsWithNull = new LinkedHashSet<>();
        methodsWithNull.add(null);

        assertThrows(NullPointerException.class, () -> new ChaosHttpScope(null, EXACT, "/orders"));
        assertThrows(NullPointerException.class, () -> new ChaosHttpScope(methodsWithNull, EXACT, "/orders"));
        assertThrows(NullPointerException.class, () -> new ChaosHttpScope(Set.of("GET"), null, "/orders"));
        assertThrows(NullPointerException.class, () -> new ChaosHttpScope(Set.of("GET"), EXACT, null));

        ChaosHttpScope scope = new ChaosHttpScope(Set.of("GET"), EXACT, "/orders");
        assertThrows(NullPointerException.class, () -> scope.matches(null, "/orders"));
        assertThrows(NullPointerException.class, () -> scope.matches("GET", null));
    }

    @Test
    void overlapsOnlyCompatibleInboundScopes() {
        ChaosHttpScope prefix = new ChaosHttpScope(Set.of("get", "post"), PREFIX, "/orders");
        ChaosHttpScope exact = new ChaosHttpScope(Set.of("GET"), EXACT, "/orders/42");
        ChaosHttpScope differentMethod = new ChaosHttpScope(Set.of("DELETE"), EXACT, "/orders/42");
        ChaosHttpScope differentSegment = new ChaosHttpScope(Set.of("GET"), PREFIX, "/orders-old");

        assertThat(prefix.overlaps(exact), is(true));
        assertThat(exact.overlaps(prefix), is(true));
        assertThat(prefix.overlaps(differentMethod), is(false));
        assertThat(prefix.overlaps(differentSegment), is(false));
    }

    @Test
    void doesNotOverlapOutboundScopesInEitherDirection() {
        ChaosHttpScope inbound = new ChaosHttpScope(Set.of("GET"), PREFIX, "/orders");
        ChaosOutboundHttpScope outbound = new ChaosOutboundHttpScope(Set.of("GET"),
                                                                     "https",
                                                                     "orders.example",
                                                                     443,
                                                                     PREFIX,
                                                                     "/orders");

        assertThat(inbound.overlaps(outbound), is(false));
        assertThat(outbound.overlaps(inbound), is(false));
    }
}
