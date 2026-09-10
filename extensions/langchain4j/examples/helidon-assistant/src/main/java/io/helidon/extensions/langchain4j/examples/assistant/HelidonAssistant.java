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

import java.util.Objects;

import io.helidon.extensions.langchain4j.Ai;

import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.V;
import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Classifies a question, consults the appropriate expert, and updates the conversation summary.
 */
@Ai.Agent("helidon-assistant")
public interface HelidonAssistant {

    /**
     * Combines the expert answer and updated summary for the HTTP response.
     *
     * @param lastResponse expert answer
     * @param nextSummary updated conversation summary
     * @return response containing the message and summary
     */
    @Output
    static JsonObject response(@V("lastResponse") String lastResponse, @V("nextSummary") String nextSummary) {
        return Json.createObjectBuilder()
                .add("message", Objects.requireNonNull(lastResponse))
                .add("summary", Objects.requireNonNull(nextSummary))
                .build();
    }

    /**
     * Answers a question using only the conversation context supplied with this invocation.
     *
     * @param question current question
     * @param previousSummary previous conversation summary, or an empty string for a new conversation
     * @return answer and updated summary
     */
    @SequenceAgent(subAgents = {FlavorClassifier.class, FlavorRouter.class, ConversationSummarizer.class})
    JsonObject chat(@V("question") String question, @V("previousSummary") String previousSummary);
}
