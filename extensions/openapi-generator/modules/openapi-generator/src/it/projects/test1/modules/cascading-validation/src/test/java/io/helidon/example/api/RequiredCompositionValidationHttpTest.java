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
import io.helidon.example.model.RequiredCompositionEnvelope;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.http.RestServer;
import io.helidon.webserver.testing.junit5.ServerTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks allOf requirements using generated binding and native HTTP validation. */
@ServerTest
class RequiredCompositionValidationHttpTest {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final String VALID = """
            {"strict":{"kind":"NULLABLE_CHILD","code":"strict"},
             "inherited":{"code":"inherited"},"grandchild":{"code":"grandchild"},
             "nullableInherited":{"code":null},"nullable":{"code":null},"object":{"details":{"value":"object"}}}
            """;
    private final Http1Client client;

    RequiredCompositionValidationHttpTest(Http1Client client) {
        this.client = client;
    }

    @Test
    void rejectsNullRequiredValuesAcrossAllOf() {
        assertAll(List.of("strict", "inherited", "grandchild").stream().<Executable>map(value ->
                () -> verify(VALID.replace("\"code\":\"" + value + "\"", "\"code\":null"), 400, 0)));
    }

    @Test
    void rejectsNullRequiredObjectDespiteNullableRedeclaration() {
        verify(VALID.replace("{\"value\":\"object\"}", "null"), 400, 0);
    }

    @Test
    void rejectsMissingRequiredInheritedValues() {
        assertAll(() -> verify(VALID.replace("\"code\":\"inherited\"", ""), 400, 0),
                  () -> verify(VALID.replace("\"code\":\"grandchild\"", ""), 400, 0));
    }

    @Test
    void retainsPresenceOfNullableInheritedProperty() {
        verify(VALID.replace("\"nullableInherited\":{\"code\":null}", "\"nullableInherited\":{}"), 400, 0);
        verify(VALID, 204, 1);
    }

    @Test
    void acceptsNullableAndEmptyStringControls() {
        verify(VALID, 204, 1);
        verify(VALID.replace("\"code\":\"strict\"", "\"code\":\"\""), 204, 1);
    }

    private void verify(String payload, int status, int effects) {
        CALLS.set(0);
        try (var response = client.post("/required-composition-proof").contentType(MediaTypes.APPLICATION_JSON).submit(payload)) {
            String detail = response.status().code() == status ? "" : response.entity().as(String.class);
            assertAll(payload + " " + detail, () -> assertEquals(status, response.status().code()),
                      () -> assertEquals(effects, CALLS.get()));
        }
    }

    @Service.Singleton
    @RestServer.Endpoint
    @Http.Path("/required-composition-proof")
    public static class RecordingEndpoint implements RequiredCompositionApi {
        @Override
        public void acceptRequiredComposition(RequiredCompositionEnvelope envelope) {
            CALLS.incrementAndGet();
        }
    }
}
