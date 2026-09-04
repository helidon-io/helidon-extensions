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

package io.helidon.extensions.langchain4j.providers.ollama;

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
import dev.langchain4j.model.language.LanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaLanguageModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@ServerTest
class OllamaProviderContractTest {

    private static final String CUSTOM_HEADER_VALUE = "ollama-contract";
    private static final String LOGICAL_MODEL_NAME = "ollama-contract-model";
    private static final ConcurrentLinkedQueue<CapturedRequest> REQUESTS = new ConcurrentLinkedQueue<>();

    private final URI serverUri;

    OllamaProviderContractTest(URI serverUri) {
        this.serverUri = serverUri;
    }

    @SetUpRoute
    static void routing(HttpRules rules) {
        rules.post("/ollama/api/chat", (req, res) -> {
            String body = req.content().as(String.class);
            REQUESTS.add(capture(req, body));
            if (compactJson(body).contains("\"stream\":true")) {
                res.headers().contentType(MediaTypes.APPLICATION_X_NDJSON);
                res.send("{\"model\":\"contract-model\",\"message\":{\"role\":\"assistant\","
                                 + "\"content\":\"ollama-\"},\"done\":false}\n"
                                 + "{\"model\":\"contract-model\",\"message\":{\"role\":\"assistant\","
                                 + "\"content\":\"stream-ok\"},\"done_reason\":\"stop\",\"done\":true,"
                                 + "\"prompt_eval_count\":1,\"eval_count\":1}\n");
            } else {
                res.headers().contentType(MediaTypes.APPLICATION_JSON);
                res.send("""
                                 {
                                   "model": "contract-model",
                                   "message": {"role": "assistant", "content": "ollama-sync-ok"},
                                   "done_reason": "stop",
                                   "done": true,
                                   "prompt_eval_count": 1,
                                   "eval_count": 1
                                 }
                                 """);
            }
        });
        rules.post("/ollama/api/generate", (req, res) -> {
            REQUESTS.add(capture(req));
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send("""
                             {
                               "model": "contract-model",
                               "response": "ollama-language-ok",
                               "done": true,
                               "prompt_eval_count": 1,
                               "eval_count": 1
                             }
                             """);
        });
        rules.post("/ollama/api/embed", (req, res) -> {
            REQUESTS.add(capture(req));
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send("""
                             {
                               "model": "contract-model",
                               "embeddings": [[0.125, -0.5, 0.75]],
                               "prompt_eval_count": 1
                             }
                             """);
        });
    }

    @Test
    void exercisesAllOllamaModelsThroughHelidon() {
        ServiceRegistryManager registryManager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                                                        .putContractInstance(Config.class,
                                                                                                             config())
                                                                                        .build());
        try {
            var registry = registryManager.registry();
            assertThat(registry.getNamed(ChatModel.class, LOGICAL_MODEL_NAME), instanceOf(OllamaChatModel.class));
            assertThat(registry.getNamed(StreamingChatModel.class, LOGICAL_MODEL_NAME),
                       instanceOf(OllamaStreamingChatModel.class));

            LanguageModel languageModel = registry.getNamed(LanguageModel.class, LOGICAL_MODEL_NAME);
            EmbeddingModel embeddingModel = registry.getNamed(EmbeddingModel.class, LOGICAL_MODEL_NAME);
            assertThat(languageModel, instanceOf(OllamaLanguageModel.class));
            assertThat(embeddingModel, instanceOf(OllamaEmbeddingModel.class));

            assertThat(registry.get(ContractChatService.class).chat("sync-contract-prompt"),
                       is("ollama-sync-ok"));
            assertThat(registry.get(ContractStreamingService.class)
                               .chat("stream-contract-prompt")
                               .collect(Collectors.joining()),
                       is("ollama-stream-ok"));
            assertThat(languageModel.generate("language-contract-prompt").content(), is("ollama-language-ok"));
            assertArrayEquals(new float[] {0.125F, -0.5F, 0.75F},
                              embeddingModel.embed("embedding-contract-prompt").content().vector());
        } finally {
            registryManager.shutdown();
        }

        CapturedRequest syncRequest = REQUESTS.remove();
        assertThat(syncRequest.path(), is("/ollama/api/chat"));
        assertCommonRequest(syncRequest);
        assertThat(compactJson(syncRequest.body()),
                   containsString("\"messages\":[{\"role\":\"user\",\"content\":\"sync-contract-prompt\"}]"));
        assertConfiguredOptions(syncRequest);
        assertThat(compactJson(syncRequest.body()), containsString("\"stream\":false"));

        CapturedRequest streamingRequest = REQUESTS.remove();
        assertThat(streamingRequest.path(), is("/ollama/api/chat"));
        assertCommonRequest(streamingRequest);
        assertThat(compactJson(streamingRequest.body()),
                   containsString("\"messages\":[{\"role\":\"user\",\"content\":\"stream-contract-prompt\"}]"));
        assertConfiguredOptions(streamingRequest);
        assertThat(compactJson(streamingRequest.body()), containsString("\"stream\":true"));

        CapturedRequest languageRequest = REQUESTS.remove();
        assertThat(languageRequest.path(), is("/ollama/api/generate"));
        assertCommonRequest(languageRequest);
        assertThat(compactJson(languageRequest.body()),
                   containsString("\"prompt\":\"language-contract-prompt\""));
        assertConfiguredOptions(languageRequest);
        assertThat(compactJson(languageRequest.body()), containsString("\"stream\":false"));

        CapturedRequest embeddingRequest = REQUESTS.remove();
        assertThat(embeddingRequest.path(), is("/ollama/api/embed"));
        assertCommonRequest(embeddingRequest);
        assertThat(compactJson(embeddingRequest.body()),
                   containsString("\"input\":[\"embedding-contract-prompt\"]"));
        assertThat(compactJson(embeddingRequest.body()), containsString("\"dimensions\":3"));
        assertThat(REQUESTS.isEmpty(), is(true));
    }

    private static void assertCommonRequest(CapturedRequest request) {
        assertThat(request.customHeader(), is(CUSTOM_HEADER_VALUE));
        assertThat(request.contentType(), containsString(MediaTypes.APPLICATION_JSON_VALUE));
        assertThat(compactJson(request.body()), containsString("\"model\":\"contract-model\""));
    }

    private static void assertConfiguredOptions(CapturedRequest request) {
        String body = compactJson(request.body());
        assertThat(body, containsString("\"temperature\":0.2"));
        assertThat(body, containsString("\"top_k\":4"));
        assertThat(body, containsString("\"top_p\":0.8"));
        assertThat(body, containsString("\"repeat_penalty\":1.1"));
        assertThat(body, containsString("\"seed\":42"));
        assertThat(body, containsString("\"num_predict\":16"));
        assertThat(body, containsString("\"stop\":[\"STOP\"]"));
    }

    private static CapturedRequest capture(ServerRequest request) {
        return capture(request, request.content().as(String.class));
    }

    private static CapturedRequest capture(ServerRequest request, String body) {
        Map<String, List<String>> headers = request.headers().toMap();
        return new CapturedRequest(request.path().absolute().rawPath(),
                                   header(headers, "x-contract-header"),
                                   header(headers, "content-type"),
                                   body);
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

    private Config config() {
        REQUESTS.clear();
        String baseUrl = serverUri.resolve("ollama").toString();
        // language=YAML
        String yaml = """
                langchain4j:
                  models:
                    %s:
                      provider: ollama
                      model-name: contract-model
                      base-url: %s
                      temperature: 0.2
                      top-k: 4
                      top-p: 0.8
                      repeat-penalty: 1.1
                      seed: 42
                      num-predict: 16
                      stop: [STOP]
                      dimensions: 3
                      timeout: PT5S
                      max-retries: 0
                      custom-headers.X-Contract-Header: %s
                      http-client-builder-discover-services: false
                      listeners-discover-services: false
                """.formatted(LOGICAL_MODEL_NAME, baseUrl, CUSTOM_HEADER_VALUE);
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    @Ai.Service
    @Ai.ChatModel(LOGICAL_MODEL_NAME)
    public interface ContractChatService {
        String chat(String prompt);
    }

    @Ai.Service
    @Ai.StreamingChatModel(LOGICAL_MODEL_NAME)
    public interface ContractStreamingService {
        Stream<String> chat(String prompt);
    }

    private record CapturedRequest(String path, String customHeader, String contentType, String body) {
    }
}
