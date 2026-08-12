/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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

import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.common.Weighted;
import io.helidon.extensions.langchain4j.AiProvider;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.cohere.CohereScoringModel;

@AiProvider.ModelConfig(value = CohereScoringModel.class,
                        weight = Weighted.DEFAULT_WEIGHT - 20,
                        providerKey = "cohere",
                        skip = {"proxy\\(java\\.net\\.Proxy\\)",
                                "httpClientBuilder\\(dev\\.langchain4j\\.http\\.client\\.HttpClientBuilder\\)"})
interface CohereScoringLc4jProvider {

    /**
     * HTTP client builder to use.
     *
     * @return an {@link Optional} containing HTTP client builder to use
     */
    @Option.Configured
    @Option.RegistryService
    @AiProvider.CustomBuilderMapping
    Optional<HttpClientBuilder> httpClientBuilder();

    /**
     * Customizes the model builder with the configured LangChain4j HTTP client.
     *
     * @return partially configured LangChain4j model builder
     */
    default CohereScoringModel.CohereScoringModelBuilder configuredBuilder() {
        var modelBuilder = CohereScoringModel.builder();
        httpClientBuilder().ifPresent(modelBuilder::httpClientBuilder);
        return modelBuilder;
    }
}
