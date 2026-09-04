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

package io.helidon.extensions.langchain4j.providers.openai;

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
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ServerTest
class OpenAiProviderContractTest {

    private static final String API_KEY = "contract-api-key";
    private static final String CHAT_MODEL_NAME = "contract-chat-model";
    private static final String CUSTOM_HEADER_VALUE = "openai-contract";
    private static final String EMBEDDING_MODEL_NAME = "contract-embedding-model";
    private static final String STREAMING_MODEL_NAME = "contract-streaming-model";
    private static final ConcurrentLinkedQueue<CapturedRequest> REQUESTS = new ConcurrentLinkedQueue<>();

    private final URI serverUri;

    OpenAiProviderContractTest(URI serverUri) {
        this.serverUri = serverUri;
    }

    @SetUpRoute
    static void routing(HttpRules rules) {
        rules.post("/v1/chat/completions", (req, res) -> {
            String body = req.content().as(String.class);
            REQUESTS.add(capture(req, body));
            if (compactJson(body).contains("\"stream\":true")) {
                res.headers().contentType(MediaTypes.TEXT_EVENT_STREAM);
                res.send("data: {\"id\":\"chatcmpl-stream\",\"model\":\"contract-model\",\"choices\":[{\"index\":0,"
                                 + "\"delta\":{\"role\":\"assistant\",\"content\":\"openai-stream-ok\"},"
                                 + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
            } else {
                res.headers().contentType(MediaTypes.APPLICATION_JSON);
                res.send("""
                                 {
                                   "id": "chatcmpl-sync",
                                   "created": 1,
                                   "model": "contract-model",
                                   "choices": [
                                     {
                                       "index": 0,
                                       "message": {"role": "assistant", "content": "openai-sync-ok"},
                                       "finish_reason": "stop"
                                     }
                                   ],
                                   "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                                 }
                                 """);
            }
        });
        rules.post("/v1/embeddings", (req, res) -> {
            REQUESTS.add(capture(req, req.content().as(String.class)));
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send("""
                             {
                               "model": "contract-model",
                               "data": [{"index": 0, "embedding": [0.125, -0.5, 0.75]}],
                               "usage": {"prompt_tokens": 2, "total_tokens": 2}
                             }
                             """);
        });
    }

    @Test
    void exercisesModelsThroughHelidon() {
        ServiceRegistryManager registryManager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                                                        .putContractInstance(Config.class,
                                                                                                             config())
                                                                                        .build());
        try {
            var registry = registryManager.registry();
            assertThat(registry.getNamed(ChatModel.class, CHAT_MODEL_NAME), instanceOf(OpenAiChatModel.class));
            assertThat(registry.getNamed(StreamingChatModel.class, STREAMING_MODEL_NAME),
                       instanceOf(OpenAiStreamingChatModel.class));
            EmbeddingModel embeddingModel = registry.getNamed(EmbeddingModel.class, EMBEDDING_MODEL_NAME);
            assertThat(embeddingModel, instanceOf(OpenAiEmbeddingModel.class));

            ContractChatService chatService = registry.get(ContractChatService.class);
            assertThat(chatService.chat("sync contract prompt"), is("openai-sync-ok"));

            ContractStreamingService streamingService = registry.get(ContractStreamingService.class);
            assertThat(streamingService.chat("stream contract prompt").collect(Collectors.joining()),
                       is("openai-stream-ok"));
            assertArrayEquals(new float[] {0.125F, -0.5F, 0.75F},
                              embeddingModel.embed("embedding contract prompt").content().vector());
        } finally {
            registryManager.shutdown();
        }

        CapturedRequest syncRequest = REQUESTS.remove();
        assertThat(syncRequest.path(), is("/v1/chat/completions"));
        assertCommonRequest(syncRequest);
        assertThat(compactJson(syncRequest.body()), containsString("\"model\":\"contract-model\""));
        assertThat(syncRequest.body(), containsString("sync contract prompt"));
        assertThat(compactJson(syncRequest.body()), containsString("\"messages\":[{"));
        assertThat(compactJson(syncRequest.body()), containsString("\"role\":\"user\""));
        assertThat(compactJson(syncRequest.body()), containsString("\"stream\":false"));

        CapturedRequest streamingRequest = REQUESTS.remove();
        assertThat(streamingRequest.path(), is("/v1/chat/completions"));
        assertCommonRequest(streamingRequest);
        assertThat(compactJson(streamingRequest.body()), containsString("\"model\":\"contract-model\""));
        assertThat(streamingRequest.body(), containsString("stream contract prompt"));
        assertThat(compactJson(streamingRequest.body()), containsString("\"messages\":[{"));
        assertThat(compactJson(streamingRequest.body()), containsString("\"role\":\"user\""));
        assertThat(compactJson(streamingRequest.body()), containsString("\"stream\":true"));

        CapturedRequest embeddingRequest = REQUESTS.remove();
        assertThat(embeddingRequest.path(), is("/v1/embeddings"));
        assertCommonRequest(embeddingRequest);
        assertTrue(embeddingRequest.body()
                           .matches("(?s).*\"input\"\\s*:\\s*\\[\\s*\"embedding contract prompt\"\\s*].*"));
        assertThat(compactJson(embeddingRequest.body()), containsString("\"model\":\"contract-model\""));
        assertThat(compactJson(embeddingRequest.body()), containsString("\"dimensions\":3"));
        assertThat(compactJson(embeddingRequest.body()), containsString("\"encoding_format\":\"float\""));
        assertThat(compactJson(embeddingRequest.body()), containsString("\"user\":\"contract-user\""));
        assertThat(REQUESTS.isEmpty(), is(true));
    }

    private static void assertCommonRequest(CapturedRequest request) {
        assertThat(request.authorization(), is("Bearer " + API_KEY));
        assertThat(request.organization(), is("contract-organization"));
        assertThat(request.project(), is("contract-project"));
        assertThat(request.customHeader(), is(CUSTOM_HEADER_VALUE));
        assertThat(request.contentType(), containsString(MediaTypes.APPLICATION_JSON_VALUE));
    }

    private static CapturedRequest capture(ServerRequest request, String body) {
        Map<String, List<String>> headers = request.headers().toMap();
        return new CapturedRequest(request.path().absolute().rawPath(),
                                   header(headers, "authorization"),
                                   header(headers, "openai-organization"),
                                   header(headers, "openai-project"),
                                   header(headers, "x-contract-header"),
                                   header(headers, "content-type"),
                                   body);
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

    private Config config() {
        REQUESTS.clear();
        String baseUrl = serverUri.resolve("v1/").toString();
        // language=YAML
        String yaml = """
                langchain4j:
                  models:
                    %s:
                      provider: open-ai
                      api-key: %s
                      model-name: contract-model
                      base-url: %s
                      organization-id: contract-organization
                      project-id: contract-project
                      custom-headers.X-Contract-Header: %s
                      timeout: PT5S
                      max-retries: 0
                      http-client-builder-discover-services: false
                      listeners-discover-services: false
                    %s:
                      provider: open-ai
                      api-key: %s
                      model-name: contract-model
                      base-url: %s
                      organization-id: contract-organization
                      project-id: contract-project
                      custom-headers.X-Contract-Header: %s
                      timeout: PT5S
                      max-retries: 0
                      http-client-builder-discover-services: false
                      listeners-discover-services: false
                    %s:
                      provider: open-ai
                      api-key: %s
                      model-name: contract-model
                      base-url: %s
                      organization-id: contract-organization
                      project-id: contract-project
                      custom-headers.X-Contract-Header: %s
                      dimensions: 3
                      encoding-format: float
                      user: contract-user
                      max-retries: 0
                      timeout: PT5S
                      http-client-builder-discover-services: false
                      listeners-discover-services: false
                """.formatted(CHAT_MODEL_NAME,
                               API_KEY,
                               baseUrl,
                               CUSTOM_HEADER_VALUE,
                               STREAMING_MODEL_NAME,
                               API_KEY,
                               baseUrl,
                               CUSTOM_HEADER_VALUE,
                               EMBEDDING_MODEL_NAME,
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
                                   String authorization,
                                   String organization,
                                   String project,
                                   String customHeader,
                                   String contentType,
                                   String body) {
    }
}
