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

import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class ConfigurationTest {
    @Test
    void resolvesProductionDefaults() {
        Config config = config(Map.of("OPENAI_API_KEY", "unused-test-key"));

        assertThat(config.get("assistant.docs-path").asString().get(), is("./helidon-docs/docs"));
        assertThat(config.get("langchain4j.providers.open-ai.base-url").asString().get(), is("https://api.openai.com/v1"));
        assertThat(config.get("langchain4j.models.assistant-model.model-name").asString().get(), is("gpt-4o-mini"));
    }

    @Test
    void resolvesExplicitOverridesThroughTheProductionConfiguration() {
        Config config = config(Map.of("OPENAI_API_KEY", "unused-test-key",
                                      "HELIDON_DOCS_PATH", "/local/docs",
                                      "OPENAI_BASE_URL", "http://localhost:1234/v1",
                                      "OPENAI_MODEL", "local-model"));

        assertThat(config.get("assistant.docs-path").asString().get(), is("/local/docs"));
        assertThat(config.get("langchain4j.providers.open-ai.base-url").asString().get(), is("http://localhost:1234/v1"));
        assertThat(config.get("langchain4j.models.assistant-model.model-name").asString().get(), is("local-model"));
    }

    private static Config config(Map<String, String> overrides) {
        return Config.builder()
                .disableEnvironmentVariablesSource()
                .disableSystemPropertiesSource()
                .sources(ConfigSources.create(overrides), ConfigSources.file("src/main/resources/application.yaml"))
                .build();
    }
}
