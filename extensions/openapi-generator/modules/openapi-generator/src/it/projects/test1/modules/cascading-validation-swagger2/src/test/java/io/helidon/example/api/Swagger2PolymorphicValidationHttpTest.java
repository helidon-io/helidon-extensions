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

package io.helidon.example.api;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.example.model.ConstrainedBase;
import io.helidon.example.model.ConstrainedChild;
import io.helidon.example.model.ConstrainedHolder;
import io.helidon.http.Http;
import io.helidon.http.Status;
import io.helidon.service.registry.Service;
import io.helidon.validation.Validation;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.http.RestServer;
import io.helidon.webserver.testing.junit5.ServerTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/** Exercises Swagger 2 subtype validation through native JSON binding and real HTTP admission. */
@ServerTest
class Swagger2PolymorphicValidationHttpTest {
    private static final AtomicInteger INVOCATIONS = new AtomicInteger();
    private static final AtomicReference<ConstrainedBase> RECEIVED = new AtomicReference<>();
    private final Http1Client client;

    /** Uses the real native server test client. */
    Swagger2PolymorphicValidationHttpTest(Http1Client client) {
        this.client = client;
    }

    /** Separates endpoint effects between accepted and rejected requests. */
    @BeforeEach
    void resetObservations() {
        INVOCATIONS.set(0);
        RECEIVED.set(null);
    }

    /** A base-typed request must decode and validate a concrete constrained child. */
    @Test
    void acceptsValidBaseTypedRequest() {
        submit("/swagger2-base", payload("valid"), Status.NO_CONTENT_204);
        assertReceivedChild();
    }

    /** A child-only constraint must reject a base-typed request before endpoint logic. */
    @Test
    void rejectsInvalidBaseTypedRequestBeforeEndpointLogic() {
        submit("/swagger2-base", payload("longer-than-ten"), Status.BAD_REQUEST_400);
        assertNoEndpointEffects();
    }

    /** A nested base-typed property must decode and validate the same concrete child. */
    @Test
    void acceptsValidNestedProperty() {
        submit("/swagger2-holder", "{\"value\":" + payload("valid") + "}", Status.NO_CONTENT_204);
        assertReceivedChild();
    }

    /** Nested runtime subtype constraints must reject the enclosing request before endpoint logic. */
    @Test
    void rejectsInvalidNestedPropertyBeforeEndpointLogic() {
        submit("/swagger2-holder", "{\"value\":" + payload("longer-than-ten") + "}", Status.BAD_REQUEST_400);
        assertNoEndpointEffects();
    }

    private void submit(String path, String body, Status expected) {
        try (var response = client.post(path).contentType(MediaTypes.APPLICATION_JSON).submit(body)) {
            assertThat(response.status(), is(expected));
        }
    }

    private static String payload(String code) {
        return "{\"kind\":\"ConstrainedChild\",\"code\":\"" + code + "\"}";
    }

    private static void assertReceivedChild() {
        assertThat(INVOCATIONS.get(), is(1));
        assertThat(RECEIVED.get(), instanceOf(ConstrainedChild.class));
        assertThat(((ConstrainedChild) RECEIVED.get()).code(), is("valid"));
    }

    private static void assertNoEndpointEffects() {
        assertThat(INVOCATIONS.get(), is(0));
        assertThat(RECEIVED.get(), is((ConstrainedBase) null));
    }

    /** Records observable effects only after generated native subtype validation succeeds. */
    @Service.Singleton
    @RestServer.Endpoint
    public static class RecordingEndpoint {
        /** Accepts a generated Swagger 2 base type. */
        @Http.POST
        @Http.Path("/swagger2-base")
        @RestServer.Status(204)
        public void accept(@Validation.Valid @Http.Entity ConstrainedBase value) {
            RECEIVED.set(value);
            INVOCATIONS.incrementAndGet();
        }

        /** Accepts a generated holder containing a base-typed property. */
        @Http.POST
        @Http.Path("/swagger2-holder")
        @RestServer.Status(204)
        public void acceptHolder(@Validation.Valid @Http.Entity ConstrainedHolder holder) {
            RECEIVED.set(holder.value());
            INVOCATIONS.incrementAndGet();
        }
    }
}
