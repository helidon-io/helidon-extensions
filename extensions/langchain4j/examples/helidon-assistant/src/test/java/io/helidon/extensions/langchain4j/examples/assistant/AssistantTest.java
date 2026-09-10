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

package io.helidon.extensions.langchain4j.examples.assistant;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.langchain4j.providers.mock.MockChatModel;
import io.helidon.extensions.langchain4j.providers.mock.MockChatRule;
import io.helidon.http.Status;
import io.helidon.service.registry.Service;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.testing.junit5.ServerTest;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

@ServerTest
class AssistantTest {
    private final Http1Client client;
    private final MockChatModel model;
    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

    private String flavor = "SE";

    AssistantTest(Http1Client client, @Service.Named("assistant-model") MockChatModel model) {
        this.client = client;
        this.model = model;
    }

    @BeforeEach
    void configureModel() {
        model.activeRules().addFirst(new MockChatRule() {
            @Override
            public boolean matches(ChatRequest request) {
                return true;
            }

            @Override
            public String mock(ChatRequest request) {
                requests.add(request);
                String system = systemText(request);
                String user = userText(request);
                if (system.contains("Classify")) {
                    return flavor;
                }
                if (system.contains("Helidon SE expert")) {
                    assertThat(user, containsString("SE_REFERENCE_MARKER"));
                    assertThat(user, not(containsString("MP_REFERENCE_MARKER")));
                    return "SE answer from local docs";
                }
                if (system.contains("Helidon MP expert")) {
                    assertThat(user, containsString("MP_REFERENCE_MARKER"));
                    assertThat(user, not(containsString("SE_REFERENCE_MARKER")));
                    return "MP answer from local docs";
                }
                assertThat(user, containsString(flavor + " answer from local docs"));
                return flavor + " conversation summary";
            }
        });
    }

    @AfterEach
    void resetModel() {
        model.resetRules();
    }

    @Test
    void routesSeQuestionThroughRealRetrieverAndSummarizer() {
        JsonObject answer = chat("How do I write a Helidon SE HTTP endpoint?", "");

        assertThat(answer.getString("message"), is("SE answer from local docs"));
        assertThat(answer.getString("summary"), is("SE conversation summary"));
        assertThat(requests, hasSize(3));
    }

    @Test
    void routesMpQuestionAndUsesOnlyMpDocumentation() {
        flavor = "MP";

        JsonObject answer = chat("How do I write a MicroProfile Jakarta REST endpoint?", "");

        assertThat(answer.getString("message"), is("MP answer from local docs"));
        assertThat(answer.getString("summary"), is("MP conversation summary"));
        assertThat(requests, hasSize(3));
    }

    @Test
    void carriesSummaryIntoFollowUpWithoutSharingItWithNewConversations() {
        JsonObject first = chat("How do I configure Helidon SE?", "FIRST_USER_CONTEXT");
        requests.clear();

        chat("Show an HTTP example", first.getString("summary"));
        assertThat(requests, hasSize(3));
        requests.forEach(request -> assertThat(userText(request), containsString("SE conversation summary")));
        requests.clear();

        chat("Start a new Helidon question", "");
        assertThat(requests, hasSize(3));
        requests.forEach(request -> {
            assertThat(userText(request), not(containsString("FIRST_USER_CONTEXT")));
            assertThat(userText(request), not(containsString("SE conversation summary")));
        });
    }

    @Test
    void rejectsInvalidInputsBeforeCallingTheModel() {
        List<JsonObject> invalid = List.of(
                Json.createObjectBuilder().build(),
                Json.createObjectBuilder().add("message", " ").build(),
                Json.createObjectBuilder().add("message", 42).build(),
                Json.createObjectBuilder().add("message", "x".repeat(8001)).build(),
                Json.createObjectBuilder().add("message", "hello").add("summary", JsonValue.NULL).build(),
                Json.createObjectBuilder().add("message", "hello").add("summary", "x".repeat(16001)).build());
        for (JsonObject request : invalid) {
            try (var response = client.post("/chat").contentType(MediaTypes.APPLICATION_JSON).submit(request)) {
                assertThat(response.status(), is(Status.BAD_REQUEST_400));
            }
        }
        assertThat(requests, hasSize(0));
    }

    @Test
    void acceptsFirstMessageWithoutSummary() {
        try (var response = client.post("/chat").contentType(MediaTypes.APPLICATION_JSON)
                .submit(Json.createObjectBuilder().add("message", "Helidon SE question").build())) {
            assertThat(response.status(), is(Status.OK_200));
            assertThat(response.as(JsonObject.class).getString("summary"), is("SE conversation summary"));
        }
    }

    @Test
    void rejectsMalformedJsonAndNonObjectBodies() {
        for (String body : List.of("{", "[]", "null", "42")) {
            try (var response = client.post("/chat").contentType(MediaTypes.APPLICATION_JSON).submit(body)) {
                assertThat(response.status(), is(Status.BAD_REQUEST_400));
            }
        }
        assertThat(requests, hasSize(0));
    }

    @Test
    void rejectsOversizedRequestBeforeCallingTheModel() {
        try (var response = client.post("/chat").contentType(MediaTypes.APPLICATION_JSON).submit("x".repeat(262145))) {
            assertThat(response.status(), is(Status.REQUEST_ENTITY_TOO_LARGE_413));
        }
        assertThat(requests, hasSize(0));
    }

    @Test
    void servesBrowserChatWithoutExternalScripts() {
        String page = client.get("/").requestEntity(String.class);
        assertThat(page, containsString("Helidon Assistant"));
        assertThat(page, containsString("chat.js"));
        String script = client.get("/chat.js").requestEntity(String.class);
        assertThat(script, containsString("content.textContent = text"));
        assertThat(script, not(containsString("innerHTML")));
    }

    private static String systemText(ChatRequest request) {
        return request.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::text)
                .collect(Collectors.joining("\n"));
    }

    private static String userText(ChatRequest request) {
        return request.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .collect(Collectors.joining("\n"));
    }

    private JsonObject chat(String message, String summary) {
        JsonObject request = Json.createObjectBuilder().add("message", message).add("summary", summary).build();
        try (var response = client.post("/chat").contentType(MediaTypes.APPLICATION_JSON).submit(request)) {
            assertThat(response.status(), is(Status.OK_200));
            return response.as(JsonObject.class);
        }
    }
}
