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

package io.helidon.extensions.langchain4j.providers.mock;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.langchain4j.Ai;
import io.helidon.service.registry.Services;
import io.helidon.testing.junit5.Testing;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

@Testing.Test(perMethod = true)
public class MockStreamingTest {

    @Test
    void customMockResponse() {
        // language=YAML
        var overrideConfig = """
                langchain4j:
                  services:
                    support-streaming-service:
                      streaming-chat-model: streaming-mock-model
                """;
        Services.set(Config.class,
                     Config.builder()
                             .addSource(ConfigSources.create(overrideConfig, MediaTypes.APPLICATION_X_YAML))
                             .addSource(ConfigSources.create(Config.create()))
                             .build());
        var aiService = Services.get(MockStreamingTest.HelidonSupportService.class);
        var model = Services.getNamed(MockStreamingChatModel.class, "streaming-mock-model");
        try {
            model.activeRules().clear();
            model.activeRules().add(new MockChatRule() {
                @Override
                public boolean matches(ChatRequest req) {
                    return true;
                }

                @Override
                public String mock(String concatenatedReq) {
                    return "Custom manually added response!";
                }
            });
            assertThat(join(aiService.chat("Custom rule match")),
                       is("Custom manually added response!"));
            assertThat(tokens(aiService.chat("Custom rule match")),
                       contains("Custom manually added response!".splitWithDelimiters("\\s", 0)));
        } finally {
            model.resetRules();
        }
    }

    @Ai.Service("support-streaming-service")
    @Ai.StreamingChatModel("first-mock-model")
    public interface HelidonSupportService {

        @SystemMessage("You are a Helidon expert!")
        TokenStream chat(String prompt);
    }

    private static String join(TokenStream tokenStream) {
        return String.join("", tokens(tokenStream));
    }

    private static List<String> tokens(TokenStream tokenStream) {
        CompletableFuture<List<String>> response = new CompletableFuture<>();
        List<String> tokens = new java.util.concurrent.CopyOnWriteArrayList<>();
        tokenStream.onPartialResponse(tokens::add)
                .onCompleteResponse(ignored -> response.complete(List.copyOf(tokens)))
                .onError(response::completeExceptionally)
                .start();
        return response.join();
    }
}
