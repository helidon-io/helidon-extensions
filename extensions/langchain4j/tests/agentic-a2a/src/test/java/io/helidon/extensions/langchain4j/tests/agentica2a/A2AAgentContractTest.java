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

package io.helidon.extensions.langchain4j.tests.agentica2a;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.service.registry.Services;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import dev.langchain4j.agentic.a2a.A2AClientInstance;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@ServerTest
class A2AAgentContractTest {

    private static final ConcurrentLinkedQueue<String> CARD_REQUESTS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<CapturedRequest> MESSAGE_REQUESTS = new ConcurrentLinkedQueue<>();

    private static URI serverUri;

    A2AAgentContractTest(URI serverUri) {
        A2AAgentContractTest.serverUri = serverUri;
        System.setProperty(UnannotatedRemoteWriterAgent.SERVER_URL_PROPERTY,
                           serverUri.resolve("configured").toString());
        CARD_REQUESTS.clear();
        MESSAGE_REQUESTS.clear();
    }

    @AfterAll
    static void clearServerUrlProperty() {
        System.clearProperty(UnannotatedRemoteWriterAgent.SERVER_URL_PROPERTY);
    }

    @SetUpRoute
    static void routing(HttpRules rules) {
        rules.get("/configured/.well-known/agent-card.json", (req, res) -> {
            CARD_REQUESTS.add(req.path().absolute().rawPath());
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send(agentCard());
        });
        rules.post("/configured/a2a", (req, res) -> {
            MESSAGE_REQUESTS.add(capture(req));
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send("""
                             {
                               "jsonrpc": "2.0",
                               "id": 1,
                               "result": {
                                 "message": {
                                   "messageId": "loopback-response",
                                   "role": "ROLE_AGENT",
                                   "parts": [{"text": "a2a-loopback-ok"}],
                                   "metadata": {}
                                 }
                               }
                             }
                             """);
        });
    }

    @Test
    void configuredA2AAgentWorksTopLevelAndNestedWithoutChatModel() {
        assertThat(Services.first(ChatModel.class).isEmpty(), is(true));

        RemoteWriterAgent remoteWriter = Services.get(RemoteWriterAgent.class);
        A2AClientInstance clientInstance = (A2AClientInstance) remoteWriter;
        assertThat(clientInstance.outputKey(), is("configured-output"));
        assertThat(clientInstance.async(), is(false));

        assertThat(remoteWriter.write("top-level-topic"), is("a2a-loopback-ok"));

        var workflowResult = Services.get(RemoteWriterWorkflow.class).write("nested-topic");
        assertThat(workflowResult.result(), is("a2a-loopback-ok"));
        assertThat(workflowResult.agenticScope().readState("configured-output"), is("a2a-loopback-ok"));
        assertThat(workflowResult.agenticScope().readState("annotation-output"), is(nullValue()));

        var fallbackResult = Services.get(UnannotatedRemoteWriterWorkflow.class).write("fallback-topic");
        assertThat(fallbackResult.result(), is("a2a-loopback-ok"));
        assertThat(fallbackResult.agenticScope().readState("fallback-output"), is("a2a-loopback-ok"));

        assertThat(CARD_REQUESTS, everyItem(is("/configured/.well-known/agent-card.json")));
        assertThat(CARD_REQUESTS.size(), is(3));
        assertThat(MESSAGE_REQUESTS.size(), is(3));

        CapturedRequest directRequest = MESSAGE_REQUESTS.remove();
        assertRequest(directRequest, "top-level-topic");
        CapturedRequest nestedRequest = MESSAGE_REQUESTS.remove();
        assertRequest(nestedRequest, "nested-topic");
        CapturedRequest fallbackRequest = MESSAGE_REQUESTS.remove();
        assertRequest(fallbackRequest, "fallback-topic");
        assertThat(MESSAGE_REQUESTS.isEmpty(), is(true));
    }

    private static void assertRequest(CapturedRequest request, String topic) {
        String body = compactJson(request.body());
        assertThat(request.path(), is("/configured/a2a"));
        assertThat(request.a2aVersion(), is("1.0"));
        assertThat(request.contentType(), containsString(MediaTypes.APPLICATION_JSON_VALUE));
        assertThat(body, containsString("\"method\":\"SendMessage\""));
        assertThat(body, containsString("\"role\":\"ROLE_USER\""));
        assertThat(body, containsString("\"parts\":[{\"text\":\"" + topic + "\""));
    }

    private static CapturedRequest capture(ServerRequest request) {
        Map<String, List<String>> headers = request.headers().toMap();
        return new CapturedRequest(request.path().absolute().rawPath(),
                                   header(headers, "a2a-version"),
                                   header(headers, "content-type"),
                                   request.content().as(String.class));
    }

    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet()
                .stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse("");
    }

    private static String compactJson(String json) {
        return json.replaceAll("\\s", "");
    }

    private static String agentCard() {
        return """
                {
                  "name": "Loopback Writer",
                  "description": "Credential-free A2A test agent",
                  "version": "1.0.0",
                  "capabilities": {
                    "streaming": false,
                    "pushNotifications": false,
                    "extendedAgentCard": false
                  },
                  "defaultInputModes": ["text/plain"],
                  "defaultOutputModes": ["text/plain"],
                  "skills": [],
                  "supportedInterfaces": [
                    {
                      "url": "%s",
                      "protocolBinding": "JSONRPC",
                      "protocolVersion": "1.0"
                    }
                  ]
                }
                """.formatted(serverUri.resolve("configured/a2a"));
    }

    private record CapturedRequest(String path, String a2aVersion, String contentType, String body) {
    }
}
