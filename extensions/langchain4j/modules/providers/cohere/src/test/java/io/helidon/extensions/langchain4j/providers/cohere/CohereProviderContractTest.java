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

package io.helidon.extensions.langchain4j.providers.cohere;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.cohere.CohereEmbeddingModel;
import dev.langchain4j.model.cohere.CohereScoringModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

@ServerTest
class CohereProviderContractTest {

    private static final String API_KEY = "contract-api-key";
    private static final String LOGICAL_MODEL_NAME = "cohere-contract-model";
    private static final ConcurrentLinkedQueue<CapturedRequest> REQUESTS = new ConcurrentLinkedQueue<>();

    private final URI serverUri;

    CohereProviderContractTest(URI serverUri) {
        this.serverUri = serverUri;
    }

    @SetUpRoute
    static void routing(HttpRules rules) {
        rules.post("/v2/embed", (req, res) -> {
            REQUESTS.add(capture(req));
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send("""
                             {
                               "id": "embed-v2",
                               "embeddings": {"float": [[0.125, -0.5, 0.75]]},
                               "meta": {"billed_units": {"input_tokens": 2}}
                             }
                             """);
        });
        rules.post("/v1/embed", (req, res) -> {
            REQUESTS.add(capture(req));
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send("""
                             {
                               "id": "embed-v1",
                               "texts": ["first", "second"],
                               "embeddings": [[1.0, 0.0], [0.0, 1.0]],
                               "meta": {"billed_units": {"input_tokens": 2}}
                             }
                             """);
        });
        rules.post("/v1/rerank", (req, res) -> {
            REQUESTS.add(capture(req));
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send("""
                             {
                               "id": "rerank",
                               "results": [
                                 {"index": 1, "relevance_score": 0.25},
                                 {"index": 0, "relevance_score": 0.9}
                               ],
                               "meta": {"billed_units": {"search_units": 1}}
                             }
                             """);
        });
    }

    @Test
    void exercisesEmbeddingAndScoringModelsThroughHelidon() {
        ServiceRegistryManager registryManager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                                                                                        .putContractInstance(Config.class,
                                                                                                             config())
                                                                                        .build());
        try {
            var registry = registryManager.registry();
            EmbeddingModel embeddingModel = registry.getNamed(EmbeddingModel.class, LOGICAL_MODEL_NAME);
            ScoringModel scoringModel = registry.getNamed(ScoringModel.class, LOGICAL_MODEL_NAME);
            assertThat(embeddingModel, instanceOf(CohereEmbeddingModel.class));
            assertThat(scoringModel, instanceOf(CohereScoringModel.class));

            assertThat(embeddingModel.embed("cohere-v2-prompt").content().vectorAsList(),
                       contains(0.125F, -0.5F, 0.75F));
            assertThat(embeddingModel.embedAll(List.of(TextSegment.from("first"), TextSegment.from("second")))
                                       .content()
                                       .stream()
                                       .map(embedding -> embedding.vectorAsList())
                                       .toList(),
                       is(List.of(List.of(1.0F, 0.0F), List.of(0.0F, 1.0F))));
            assertThat(scoringModel.scoreAll(List.of(TextSegment.from("first"), TextSegment.from("second")),
                                             "contract-query")
                                   .content(),
                       contains(0.9, 0.25));
        } finally {
            registryManager.shutdown();
        }

        CapturedRequest v2EmbeddingRequest = REQUESTS.remove();
        assertThat(v2EmbeddingRequest.path(), is("/v2/embed"));
        assertCommonRequest(v2EmbeddingRequest);
        assertThat(compactJson(v2EmbeddingRequest.body()), containsString("\"model\":\"contract-model\""));
        assertThat(compactJson(v2EmbeddingRequest.body()), containsString("\"input_type\":\"search_document\""));
        assertThat(compactJson(v2EmbeddingRequest.body()), containsString("\"embedding_types\":[\"float\"]"));
        assertThat(compactJson(v2EmbeddingRequest.body()),
                   containsString("\"inputs\":[{\"content\":[{\"type\":\"text\",\"text\":\"cohere-v2-prompt\"}]"));

        CapturedRequest v1EmbeddingRequest = REQUESTS.remove();
        assertThat(v1EmbeddingRequest.path(), is("/v1/embed"));
        assertCommonRequest(v1EmbeddingRequest);
        assertThat(compactJson(v1EmbeddingRequest.body()), containsString("\"model\":\"contract-model\""));
        assertThat(compactJson(v1EmbeddingRequest.body()), containsString("\"input_type\":\"search_document\""));
        assertThat(compactJson(v1EmbeddingRequest.body()), containsString("\"texts\":[\"first\",\"second\"]"));

        CapturedRequest scoringRequest = REQUESTS.remove();
        assertThat(scoringRequest.path(), is("/v1/rerank"));
        assertCommonRequest(scoringRequest);
        assertThat(compactJson(scoringRequest.body()), containsString("\"model\":\"contract-model\""));
        assertThat(compactJson(scoringRequest.body()), containsString("\"query\":\"contract-query\""));
        assertThat(compactJson(scoringRequest.body()), containsString("\"documents\":[\"first\",\"second\"]"));
        assertThat(REQUESTS.isEmpty(), is(true));
    }

    private static void assertCommonRequest(CapturedRequest request) {
        assertThat(request.authorization(), is("Bearer " + API_KEY));
        assertThat(request.contentType(), containsString(MediaTypes.APPLICATION_JSON_VALUE));
        assertThat(request.accept(), containsString(MediaTypes.APPLICATION_JSON_VALUE));
    }

    private static CapturedRequest capture(ServerRequest request) {
        Map<String, List<String>> headers = request.headers().toMap();
        return new CapturedRequest(request.path().absolute().rawPath(),
                                   header(headers, "authorization"),
                                   header(headers, "content-type"),
                                   header(headers, "accept"),
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

    private Config config() {
        REQUESTS.clear();
        String baseUrl = serverUri.resolve("v1/").toString();
        // language=YAML
        String yaml = """
                langchain4j:
                  models:
                    %s:
                      provider: cohere
                      api-key: %s
                      model-name: contract-model
                      base-url: %s
                      input-type: search_document
                      timeout: PT5S
                      max-retries: 0
                      http-client-builder-discover-services: false
                      listeners-discover-services: false
                """.formatted(LOGICAL_MODEL_NAME, API_KEY, baseUrl);
        return Config.just(ConfigSources.create(yaml, MediaTypes.APPLICATION_X_YAML));
    }

    private record CapturedRequest(String path, String authorization, String contentType, String accept, String body) {
    }
}
