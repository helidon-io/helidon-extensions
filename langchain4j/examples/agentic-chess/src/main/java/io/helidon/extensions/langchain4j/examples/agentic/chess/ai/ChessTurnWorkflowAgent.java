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

import java.util.concurrent.CompletableFuture;

import dev.langchain4j.agentic.declarative.Output;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.helidon.extensions.langchain4j.Ai;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.AiMoveProposal;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessGameCoordinator;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessPosition;

@Ai.Agent("chess-turn-workflow")
public interface ChessTurnWorkflowAgent {
    @SequenceAgent(outputKey = ChessTurnWorkflowState.TURN_RESULT_KEY, subAgents = {
            ChessHumanAgent.class,
            ChessTurnOutcomeRouterAgent.class
    })
    ChessTurnWorkflowResult playTurn(@MemoryId String sessionId,
                                     @V("generation") String generation,
                                     @V("coordinator") ChessGameCoordinator coordinator,
                                     @V("humanMoveSemaphore") HumanMoveSemaphore humanMoveSemaphore,
                                     @V("humanContext") String humanContext,
                                     @V("legalMoves") String legalMoves,
                                     @V("candidateLineCount") int candidateLineCount,
                                     @V("candidateLinePly") int candidateLinePly);

    @SuppressWarnings("unchecked")
    @Output
    static ChessTurnWorkflowResult output(AgenticScope scope) {
        return new ChessTurnWorkflowResult((String) scope.readState(ChessTurnWorkflowState.HUMAN_MOVE_KEY),
                                           (ChessPosition) scope.readState(ChessTurnWorkflowState.POST_HUMAN_POSITION_KEY),
                                           (AiMoveProposal) scope.readState(ChessTurnWorkflowState.OPPONENT_DECISION_KEY),
                                           (CompletableFuture<Void>) scope.readState(
                                                   ChessTurnWorkflowState.WHITE_COMMENTARY_FUTURE_KEY),
                                           scope.readState(ChessTurnWorkflowState.TURN_ACTIVE_KEY, false));
    }
}
