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

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.example.model.CappedRecord;
import io.helidon.example.model.PolymorphicRecord;
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

/** Proves subtype validation before a real native endpoint with an observable successful result. */
@ServerTest
class PolymorphicValidationHttpTest {
    private static final AtomicInteger INVOCATIONS = new AtomicInteger();
    private static final AtomicReference<PolymorphicRecord> RECEIVED = new AtomicReference<>();
    private final Http1Client client;

    /** Uses the actual server test client rather than invoking endpoint methods directly. */
    PolymorphicValidationHttpTest(Http1Client client) {
        this.client = client;
    }

    /** Keeps each test's side-effect observation independent. */
    @BeforeEach
    void resetObservations() {
        INVOCATIONS.set(0);
        RECEIVED.set(null);
    }

    /** A valid boundary request must decode to the correct subtype and run endpoint logic exactly once. */
    @Test
    void accepts255ItemsAndPreservesDecodedValues() {
        try (var response = client.post("/polymorphic-native-proof")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(payload(255, "ocid1.test"))) {
            assertThat(response.status(), is(Status.NO_CONTENT_204));
        }
        assertThat(INVOCATIONS.get(), is(1));
        assertThat(RECEIVED.get(), instanceOf(CappedRecord.class));
        CappedRecord record = (CappedRecord) RECEIVED.get();
        assertThat(record.compartmentId(), is("ocid1.test"));
        assertThat(record.values(), is(Collections.nCopies(255, "item")));
    }

    /** The subtype-only limit must reject the next value without invoking endpoint logic. */
    @Test
    void rejects256ItemsBeforeEndpointLogic() {
        try (var response = client.post("/polymorphic-native-proof")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(payload(256, "ocid1.test"))) {
            assertThat(response.status(), is(Status.BAD_REQUEST_400));
        }
        assertThat(INVOCATIONS.get(), is(0));
        assertThat(RECEIVED.get(), is((PolymorphicRecord) null));
    }

    /** Dispatching to the subtype must also preserve inherited validation. */
    @Test
    void rejectsInvalidInheritedFieldBeforeEndpointLogic() {
        try (var response = client.post("/polymorphic-native-proof")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(payload(1, "x"))) {
            assertThat(response.status(), is(Status.BAD_REQUEST_400));
        }
        assertThat(INVOCATIONS.get(), is(0));
    }

    /** Keeps the discriminator first to isolate validation from the separate JSON ordering limitation. */
    private static String payload(int size, String compartmentId) {
        return "{\"kind\":\"CAPPED\",\"compartmentId\":\"" + compartmentId
                + "\",\"label\":\"valid\",\"values\":["
                + String.join(",", Collections.nCopies(size, "\"item\"")) + "]}";
    }

    /** A real native endpoint using the generated base model and its generated validation services. */
    @Service.Singleton
    @RestServer.Endpoint
    @Http.Path("/polymorphic-native-proof")
    public static class RecordingEndpoint {
        /** Records successful native request admission; invalid bodies must never reach this method. */
        @Http.POST
        @RestServer.Status(204)
        public void accept(@Validation.Valid @Http.Entity PolymorphicRecord record) {
            RECEIVED.set(record);
            INVOCATIONS.incrementAndGet();
        }
    }
}
