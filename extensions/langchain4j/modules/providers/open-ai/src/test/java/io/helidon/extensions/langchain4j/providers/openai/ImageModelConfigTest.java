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

package io.helidon.extensions.langchain4j.providers.openai;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.config.metadata.model.CmModel;

import org.junit.jupiter.api.Test;

import static io.helidon.extensions.langchain4j.providers.openai.OpenAiConstants.ConfigCategory.MODEL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageModelConfigTest {
    public static final String MODEL_NAME = "test-image-model";

    @Test
    void testDefaultRoot() {
        var config = OpenAiImageModelConfig.create(
                OpenAiConstants.create(Config.just(ConfigSources.classpath("application.yaml")), MODEL, MODEL_NAME));

        assertThat(config, is(notNullValue()));
        assertThat(config.apiKey().isPresent(), is(true));
        assertThat(config.apiKey().get(), is("api-key"));
        assertThat(config.modelName().isPresent(), is(true));
        assertThat(config.modelName().get(), is("model-name"));
        assertThat(config.baseUrl().isPresent(), is(true));
        assertThat(config.baseUrl().get(), is("base-url"));
        assertThat(config.organizationId().isPresent(), is(true));
        assertThat(config.organizationId().get(), is("organization-id"));
        assertThat(config.size().isPresent(), is(true));
        assertThat(config.size().get(), is("size"));
        assertThat(config.quality().isPresent(), is(true));
        assertThat(config.quality().get(), is("quality"));
        assertThat(config.background().isPresent(), is(true));
        assertThat(config.background().get(), is("transparent"));
        assertThat(config.outputFormat().isPresent(), is(true));
        assertThat(config.outputFormat().get(), is("png"));
        assertThat(config.outputCompression().isPresent(), is(true));
        assertThat(config.outputCompression().get(), is(80));
        assertThat(config.moderation().isPresent(), is(true));
        assertThat(config.moderation().get(), is("low"));
        assertThat(config.user().isPresent(), is(true));
        assertThat(config.user().get(), is("user"));
        assertThat(config.timeout().isPresent(), is(true));
        assertThat(config.timeout().get(), equalTo(Duration.parse("PT10M")));
        assertThat(config.maxRetries().isPresent(), is(true));
        assertThat(config.maxRetries().get(), is(3));
        assertThat(config.logRequests().isPresent(), is(true));
        assertThat(config.logRequests().get(), is(true));
        assertThat(config.logResponses().isPresent(), is(true));
        assertThat(config.logResponses().get(), is(true));
        assertThat(config.customHeaders().size(), is(2));
        assertThat(config.customHeaders().get("header1"), is(equalTo("value1")));
        assertThat(config.customHeaders().get("header2"), is(equalTo("value2")));
    }

    @Test
    void testLegacyImageApiIsNotGenerated() throws NoSuchMethodException {
        assertNoPublicMethod(OpenAiImageModelConfig.class, "style");
        assertNoPublicMethod(OpenAiImageModelConfig.class, "responseFormat");
        assertNoPublicMethod(OpenAiImageModelConfig.Builder.class, "style");
        assertNoPublicMethod(OpenAiImageModelConfig.Builder.class, "style", String.class);
        assertNoPublicMethod(OpenAiImageModelConfig.Builder.class, "clearStyle");
        assertNoPublicMethod(OpenAiImageModelConfig.Builder.class, "responseFormat");
        assertNoPublicMethod(OpenAiImageModelConfig.Builder.class, "responseFormat", String.class);
        assertNoPublicMethod(OpenAiImageModelConfig.Builder.class, "clearResponseFormat");

        assertThat(OpenAiChatModelConfig.class.getMethod("responseFormat"), is(notNullValue()));
    }

    @Test
    void testLegacyImageOptionsAreAbsentFromMetadata() throws IOException {
        try (var metadataResource = getClass().getResourceAsStream("/META-INF/helidon/config-metadata.json")) {
            assertThat(metadataResource, is(notNullValue()));
            var metadata = CmModel.fromJson(metadataResource);
            var imageOptions = optionKeys(metadata, OpenAiImageModelConfig.class);

            assertThat(imageOptions, not(hasItem("style")));
            assertThat(imageOptions, not(hasItem("response-format")));
            assertThat(optionKeys(metadata, OpenAiChatModelConfig.class), hasItem("response-format"));
            assertThat(optionKeys(metadata, OpenAiStreamingChatModelConfig.class), hasItem("response-format"));
        }
    }

    private static void assertNoPublicMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        assertThrows(NoSuchMethodException.class, () -> type.getMethod(name, parameterTypes));
    }

    private static Set<String> optionKeys(CmModel metadata, Class<?> configType) {
        return metadata.modules()
                .stream()
                .flatMap(module -> module.types().stream())
                .filter(type -> type.typeName().equals(configType.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing metadata for " + configType.getName()))
                .options()
                .stream()
                .flatMap(option -> option.key().stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
