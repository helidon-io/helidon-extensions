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

import io.helidon.common.features.api.HelidonFlavor;
import io.helidon.extensions.langchain4j.Ai;

import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.service.V;

/**
 * Routes each question to one expert using the classifier's result.
 */
@Ai.Agent("flavor-router")
public interface FlavorRouter {

    /**
     * Determines whether the SE expert should answer.
     *
     * @param flavor classified Helidon flavor
     * @return whether to invoke the SE expert
     */
    @ActivationCondition(HelidonSeExpert.class)
    static boolean activateSeExpert(@V("flavor") HelidonFlavor flavor) {
        return Objects.requireNonNull(flavor) == HelidonFlavor.SE;
    }

    /**
     * Determines whether the MP expert should answer.
     *
     * @param flavor classified Helidon flavor
     * @return whether to invoke the MP expert
     */
    @ActivationCondition(HelidonMpExpert.class)
    static boolean activateMpExpert(@V("flavor") HelidonFlavor flavor) {
        return Objects.requireNonNull(flavor) == HelidonFlavor.MP;
    }

    /**
     * Invokes the selected expert with the conversation context.
     *
     * @param question current question
     * @param previousSummary previous conversation summary
     * @return selected expert's answer
     */
    @ConditionalAgent(outputKey = "lastResponse", subAgents = {HelidonSeExpert.class, HelidonMpExpert.class})
    String answer(@V("question") String question, @V("previousSummary") String previousSummary);
}
