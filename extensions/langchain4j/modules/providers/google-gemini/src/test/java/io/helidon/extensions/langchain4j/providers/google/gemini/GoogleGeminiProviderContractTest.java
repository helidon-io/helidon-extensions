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

package io.helidon.extensions.langchain4j.providers.google.gemini;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.langchain4j.Ai;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

@ServerTest
class GoogleGeminiProviderContractTest {

    private static final String API_KEY = "contract-api-key";
    private static final String CHAT_MODEL_NAME = "contract-chat-model";
    private static final String CUSTOM_HEADER_VALUE = "gemini-contract";
    private static final String STREAMING_MODEL_NAME = "contract-streaming-model";
    private static final ConcurrentLinkedQueue<CapturedRequest> REQUESTS = new ConcurrentLinkedQueue<>();

    private final URI serverUri;

    GoogleGeminiProviderContractTest(URI serverUri) {
        this.serverUri = serverUri;
    }

    @SetUpRoute
    static void routing(HttpRules rules) {
        rules.post("/v1beta/models/contract-model:generateContent", (req, res) -> {
            REQUESTS.add(capture(req));
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send(response("gemini-sync-ok", true));
        });
        rules.post("/v1beta/models/contract-model:streamGenerateContent", (req, res) -> {
            REQUESTS.add(capture(req));
            res.headers().contentType(MediaTypes.TEXT_EVENT_STREAM);
            res.send("data: " + compactJson(response("gemini-", false))
                             + "\n\ndata: " + compactJson(response("stream-ok", true))
                             + "\n\n");
        });
    }

    @Test
    void exercisesSyncAndStreamingModelsThroughHelidon() {
        ServiceRegistryManager registryManager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                                                        .putContractInstance(Config.class,
                                                                                                             config())
                                                                                        .build());
        try {
            var registry = registryManager.registry();
            assertThat(registry.getNamed(ChatModel.class, CHAT_MODEL_NAME),
                       instanceOf(GoogleAiGeminiChatModel.class));
            assertThat(registry.getNamed(StreamingChatModel.class, STREAMING_MODEL_NAME),
                       instanceOf(GoogleAiGeminiStreamingChatModel.class));

            ContractChatService chatService = registry.get(ContractChatService.class);
            assertThat(chatService.chat("sync-contract-prompt"), is("gemini-sync-ok"));

            ContractStreamingService streamingService = registry.get(ContractStreamingService.class);
            assertThat(streamingService.chat("stream-contract-prompt").collect(Collectors.joining()),
                       is("gemini-stream-ok"));
        } finally {
            registryManager.shutdown();
        }

        CapturedRequest syncRequest = REQUESTS.remove();
        assertThat(syncRequest.path(), is("/v1beta/models/contract-model:generateContent"));
        assertThat(syncRequest.query().isEmpty(), is(true));
        assertCommonRequest(syncRequest, "sync-contract-prompt");

        CapturedRequest streamingRequest = REQUESTS.remove();
        assertThat(streamingRequest.path(), is("/v1beta/models/contract-model:streamGenerateContent"));
        assertThat(streamingRequest.query().get("alt"), is(List.of("sse")));
        assertCommonRequest(streamingRequest, "stream-contract-prompt");
        assertThat(REQUESTS.isEmpty(), is(true));
    }

    private static void assertCommonRequest(CapturedRequest request, String prompt) {
        String body = compactJson(request.body());
        assertThat(request.apiKey(), is(API_KEY));
        assertThat(request.customHeader(), is(CUSTOM_HEADER_VALUE));
        assertThat(request.contentType(), containsString(MediaTypes.APPLICATION_JSON_VALUE));
        assertThat(body, containsString("\"contents\":[{"));
        assertThat(body, containsString("\"parts\":[{\"text\":\"" + prompt + "\"}]"));
        assertThat(body, containsString("\"role\":\"user\""));
        assertThat(body, containsString("\"generationConfig\":{"));
        assertThat(body, containsString("\"temperature\":0.2"));
    }

    private static CapturedRequest capture(ServerRequest request) {
        Map<String, List<String>> headers = request.headers().toMap();
        return new CapturedRequest(request.path().absolute().rawPath(),
                                   request.query().toMap(),
                                   header(headers, "x-goog-api-key"),
                                   header(headers, "x-contract-header"),
                                   header(headers, "content-type"),
                                   request.content().as(String.class));
    }

    private static String compactJson(String json) {
        return json.replaceAll("\\s", "");
    }

    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet()
                .stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse("");
    }

    private static String response(String text, boolean complete) {
        return """
                {
                  "responseId": "gemini-contract",
                  "modelVersion": "contract-model",
                  "candidates": [
                    {
                      "content": {"role": "model", "parts": [{"text": "%s"}]},
                      "finishReason": %s
                    }
                  ],
                  "usageMetadata": {"promptTokenCount": 1, "candidatesTokenCount": 1, "totalTokenCount": 2}
                }
                """.formatted(text, complete ? "\"STOP\"" : "null");
    }

    private Config config() {
        REQUESTS.clear();
        String baseUrl = serverUri.resolve("v1beta").toString();
        // language=YAML
        String yaml = """
                langchain4j:
                  models:
                    %s:
                      provider: google-gemini
                      api-key: %s
                      model-name: contract-model
                      base-url: %s
                      temperature: 0.2
                      max-retries: 0
                      timeout: PT5S
                      custom-headers.X-Contract-Header: %s
                      http-client-builder-discover-services: false
                      listeners-discover-services: false
                    %s:
                      provider: google-gemini
                      api-key: %s
                      model-name: contract-model
                      base-url: %s
                      temperature: 0.2
                      max-retries: 0
                      timeout: PT5S
                      custom-headers.X-Contract-Header: %s
                      http-client-builder-discover-services: false
                      listeners-discover-services: false
                """.formatted(CHAT_MODEL_NAME,
                               API_KEY,
                               baseUrl,
                               CUSTOM_HEADER_VALUE,
                               STREAMING_MODEL_NAME,
                               API_KEY,
                               baseUrl,
                               CUSTOM_HEADER_VALUE);
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    @Ai.Service
    @Ai.ChatModel(CHAT_MODEL_NAME)
    public interface ContractChatService {
        String chat(String prompt);
    }

    @Ai.Service
    @Ai.StreamingChatModel(STREAMING_MODEL_NAME)
    public interface ContractStreamingService {
        Stream<String> chat(String prompt);
    }

    private record CapturedRequest(String path,
                                   Map<String, List<String>> query,
                                   String apiKey,
                                   String customHeader,
                                   String contentType,
                                   String body) {
    }
}
