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

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.testing.junit5.Testing;

import dev.langchain4j.http.client.HttpClientBuilder;
import org.junit.jupiter.api.Test;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_X_YAML;
import static io.helidon.common.testing.junit5.OptionalMatcher.optionalEmpty;
import static io.helidon.extensions.langchain4j.providers.cohere.CohereConstants.ConfigCategory.MODEL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testing.Test(perMethod = true)
class ScoringModelConfigTest {

    @Test
    void testDefaultRoot(Config c) {
        var config = CohereScoringModelConfig.create(CohereConstants.create(c, MODEL, "test-model"));

        assertThat(config, is(notNullValue()));
        assertThat(config.apiKey().isPresent(), equalTo(true));
        assertThat(config.apiKey().get(), equalTo("api-key"));
        assertThat(config.modelName().isPresent(), equalTo(true));
        assertThat(config.modelName().get(), equalTo("model-name"));
        assertThat(config.baseUrl().isPresent(), equalTo(true));
        assertThat(config.baseUrl().get(), equalTo("base-url"));
        assertThat(config.timeout().isPresent(), is(true));
        assertThat(config.timeout().get(), equalTo(Duration.parse("PT10M")));
        assertThat(config.maxRetries().isPresent(), is(true));
        assertThat(config.maxRetries().get(), is(5));
        assertThat(config.logRequests().isPresent(), is(true));
        assertThat(config.logRequests().get(), is(true));
        assertThat(config.logResponses().isPresent(), is(true));
        assertThat(config.logResponses().get(), is(true));
        assertThat(config.httpClientBuilder().orElseThrow(),
                   instanceOf(MockHttpClientFactory.TrackingHttpClientBuilder.class));
        assertThat(config.configuredBuilder().build(), is(notNullValue()));
    }

    @Test
    void testNamedHttpClientBuilder(ServiceRegistry registry) {
        // language=YAML
        var yaml = """
                langchain4j:
                  models:
                    test-model:
                      provider: cohere

                  providers:
                    cohere:
                      api-key: api-key
                      http-client-builder.service-registry.named: customHttpClient
                """;

        var config = CohereScoringModelConfig.builder()
                .serviceRegistry(registry)
                .config(CohereConstants.create(Config.just(ConfigSources.create(yaml, APPLICATION_X_YAML)),
                                               MODEL,
                                               "test-model"))
                .build();
        var namedBuilder = registry.first(HttpClientBuilder.class, Qualifier.createNamed("customHttpClient"))
                .orElseThrow();

        assertThat(config.httpClientBuilder().orElseThrow(), is(namedBuilder));
        var trackingBuilder = (MockHttpClientFactory.TrackingHttpClientBuilder) namedBuilder;
        var buildCount = trackingBuilder.buildCount();
        config.configuredBuilder().build();
        assertThat(trackingBuilder.buildCount(), is(buildCount + 1));
    }

    @Test
    void testHttpClientBuilderDiscoveryCanBeDisabled(ServiceRegistry registry) {
        // language=YAML
        var yaml = """
                api-key: api-key
                http-client-builder-discover-services: false
                """;

        var config = CohereScoringModelConfig.builder()
                .serviceRegistry(registry)
                .config(Config.just(ConfigSources.create(yaml, APPLICATION_X_YAML)))
                .build();

        assertThat(config.httpClientBuilder(), optionalEmpty());
    }

    @Test
    void testDirectConfiguredHttpClientBuilderPreservesSetterOrder() {
        var configuredBuilder = new MockHttpClientFactory.TrackingHttpClientBuilder();
        var programmaticBuilder = new MockHttpClientFactory.TrackingHttpClientBuilder();
        var config = Config.builder()
                .sources(ConfigSources.create(Map.of("http-client-builder", "configured")))
                .addMapper(HttpClientBuilder.class, _ -> configuredBuilder)
                .build();

        var programmaticWins = CohereScoringModelConfig.builder()
                .config(config)
                .httpClientBuilder(programmaticBuilder)
                .build();
        var configWins = CohereScoringModelConfig.builder()
                .httpClientBuilder(programmaticBuilder)
                .config(config)
                .build();

        assertThat(programmaticWins.httpClientBuilder().orElseThrow(), is(programmaticBuilder));
        assertThat(configWins.httpClientBuilder().orElseThrow(), is(configuredBuilder));
    }

    @Test
    void testProgrammaticHttpClientBuilderIsApplied() {
        var httpClientBuilder = new MockHttpClientFactory.TrackingHttpClientBuilder();
        var config = CohereScoringModelConfig.builder()
                .apiKey("api-key")
                .httpClientBuilderDiscoverServices(false)
                .httpClientBuilder(httpClientBuilder)
                .build();

        assertThat(config.httpClientBuilder().orElseThrow(), is(httpClientBuilder));
        var buildCount = httpClientBuilder.buildCount();
        config.configuredBuilder().build();
        assertThat(httpClientBuilder.buildCount(), is(buildCount + 1));
    }

    @Test
    void testBuilderAndPrototypeCopiesKeepProgrammaticHttpClientBuilder() {
        var httpClientBuilder = new MockHttpClientFactory.TrackingHttpClientBuilder();
        var source = CohereScoringModelConfig.builder()
                .apiKey("api-key")
                .httpClientBuilderDiscoverServices(false)
                .httpClientBuilder(httpClientBuilder);

        var builderCopy = CohereScoringModelConfig.builder()
                .from(source)
                .build();
        var prototypeCopy = CohereScoringModelConfig.builder(source.build())
                .build();

        assertThat(builderCopy.httpClientBuilder().orElseThrow(), is(httpClientBuilder));
        assertThat(prototypeCopy.httpClientBuilder().orElseThrow(), is(httpClientBuilder));
        builderCopy.configuredBuilder().build();
        prototypeCopy.configuredBuilder().build();
        assertThat(httpClientBuilder.buildCount(), is(2));
    }

    @Test
    void testHttpClientMetadataReplacesLegacyProxyOption() throws IOException {
        try (var metadataResource = getClass().getResourceAsStream("/META-INF/helidon/config-metadata.json")) {
            assertThat(metadataResource, is(notNullValue()));
            var metadata = new String(metadataResource.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(metadata, not(containsString("\"key\":\"proxy\"")));
            assertThat(metadata, containsString("\"key\":\"http-client-builder\","));
            assertThat(metadata, containsString("\"type\":\"dev.langchain4j.http.client.HttpClientBuilder\""));
            assertThat(metadata, containsString("\"description\":\"HTTP client builder to use\""));
            assertThat(metadata, containsString("\"key\":\"http-client-builder-discover-services\""));
        }
    }

    @Test
    void testLegacyProxyApiIsNotGenerated() {
        assertThrows(NoSuchMethodException.class,
                     () -> CohereScoringModelConfig.class.getDeclaredMethod("proxy"));
        assertThrows(NoSuchMethodException.class,
                     () -> CohereScoringModelConfig.BuilderBase.class.getDeclaredMethod("proxy"));
        assertThrows(NoSuchMethodException.class,
                     () -> CohereScoringModelConfig.BuilderBase.class.getDeclaredMethod("proxy", Proxy.class));
        assertThrows(NoSuchMethodException.class,
                     () -> CohereScoringModelConfig.BuilderBase.class.getDeclaredMethod("clearProxy"));
        assertThrows(NoSuchMethodException.class,
                     () -> CohereScoringModelConfig.BuilderBase.class.getDeclaredMethod("proxyDiscoverServices"));
    }

    @Test
    void testHttpClientDiscoveryFlagRemainsBuilderOnly() throws NoSuchMethodException {
        assertThrows(NoSuchMethodException.class,
                     () -> CohereScoringModelConfig.class.getDeclaredMethod("httpClientBuilderDiscoverServices"));
        assertThat(CohereScoringModelConfig.BuilderBase.class
                           .getDeclaredMethod("httpClientBuilderDiscoverServices"),
                   is(notNullValue()));
    }
}
