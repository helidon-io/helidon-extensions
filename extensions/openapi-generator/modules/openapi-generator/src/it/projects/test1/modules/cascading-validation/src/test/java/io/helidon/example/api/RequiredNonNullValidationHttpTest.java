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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.example.model.RequiredEnvelope;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.http.RestServer;
import io.helidon.webserver.testing.junit5.ServerTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises required-only models through the generated contract and actual native HTTP validation. */
@ServerTest
class RequiredNonNullValidationHttpTest {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final String VALID = "{\"moduleName\":\"module\",\"count\":0,\"enabled\":false,"
            + "\"labels\":[],\"attributes\":{},\"nullableLabel\":null}";
    private final Http1Client client;

    /** Receives the server's real HTTP client. */
    RequiredNonNullValidationHttpTest(Http1Client client) {
        this.client = client;
    }

    /** Required non-null scalar, collection, and map values must not reach endpoint logic as null. */
    @Test
    void rejectsExplicitNullRequiredValues() {
        assertAll(List.of("\"moduleName\":\"module\"", "\"count\":0", "\"enabled\":false",
                          "\"labels\":[]", "\"attributes\":{}")
                          .stream().<Executable>map(property -> () -> {
                              String name = property.substring(0, property.indexOf(':'));
                              verify("{\"details\":" + VALID.replace(property, name + ":null") + "}", 400, 0);
                          }));
    }

    /** Presence checks and nested non-null checks are separate from nullable values. */
    @Test
    void rejectsNullAndMissingNestedValues() {
        assertAll(() -> verify("{\"details\":null}", 400, 0),
                  () -> verify("{}", 400, 0),
                  () -> verify("{\"details\":" + VALID.replace("\"moduleName\":\"module\",", "") + "}", 400, 0),
                  () -> verify("{\"details\":" + VALID.replace(",\"nullableLabel\":null", "") + "}", 400, 0));
    }

    /** Nullable, optional, zero, false, empty containers, and empty strings remain valid. */
    @Test
    void acceptsNullableAndEmptyControls() {
        assertAll(() -> verify("{\"details\":" + VALID + "}", 204, 1),
                  () -> verify("{\"details\":" + VALID.replace("\"module\"", "\"\"") + "}", 204, 1),
                  () -> verify("{\"details\":" + VALID.replace("\"nullableLabel\":null",
                                                               "\"nullableLabel\":\"label\",\"optionalLabel\":null") + "}", 204, 1),
                  () -> verify("{\"details\":" + VALID.replace("\"nullableLabel\":null",
                                                               "\"nullableLabel\":null,\"optionalLabel\":\"optional\"")
                          + "}", 204, 1));
    }

    /** Each request independently proves its status and business invocation count. */
    private void verify(String payload, int status, int effects) {
        CALLS.set(0);
        try (var response = client.post("/required-native-proof").contentType(MediaTypes.APPLICATION_JSON).submit(payload)) {
            assertAll(payload, () -> assertEquals(status, response.status().code()),
                      () -> assertEquals(effects, CALLS.get()));
        }
    }

    /** The generated API supplies route and validation annotations; this implementation adds none. */
    @Service.Singleton
    @RestServer.Endpoint
    @Http.Path("/required-native-proof")
    public static class RecordingEndpoint implements RequiredFieldsApi {
        /** Records only requests admitted by generated JSON and validation services. */
        @Override
        public void acceptRequiredEnvelope(RequiredEnvelope envelope) {
            CALLS.incrementAndGet();
        }
    }
}
