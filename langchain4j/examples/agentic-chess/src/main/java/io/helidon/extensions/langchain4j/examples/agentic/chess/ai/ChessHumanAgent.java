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

package io.helidon.extensions.langchain4j.examples.agentic.chess.ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.HumanInTheLoop;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.helidon.extensions.langchain4j.Ai;

@Ai.Agent("chess-human-move-collector")
public interface ChessHumanAgent {
    @HumanInTheLoop(description = "Collect the human player's chess move from the /chess browser UI",
                    outputKey = ChessTurnWorkflowState.HUMAN_MOVE_KEY)
    @Agent(value = "Human chess player that chooses a legal move", outputKey = "humanDecision")
    static String await(@MemoryId String sessionId,
                        @V("humanMoveSemaphore") HumanMoveSemaphore humanMoveSemaphore,
                        @V("humanContext") String humanContext,
                        @V("legalMoves") String legalMoves) {
        return humanMoveSemaphore.awaitMove(sessionId);
    }
}
