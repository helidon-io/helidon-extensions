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

import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.declarative.ConditionalAgent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.helidon.extensions.langchain4j.Ai;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.AiMoveProposal;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessAiTurnContext;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessGameCoordinator;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessTurnContinuation;

@Ai.Agent("chess-turn-outcome-router")
public interface ChessTurnOutcomeRouterAgent {
    @ConditionalAgent(subAgents = ChessOpponentAgent.class)
    AiMoveProposal route();

    @ActivationCondition(ChessOpponentAgent.class)
    static boolean activateOpponent(AgenticScope scope,
                                    @MemoryId String sessionId,
                                    @V("generation") String generation,
                                    @V("coordinator") ChessGameCoordinator coordinator,
                                    @V(ChessTurnWorkflowState.HUMAN_MOVE_KEY) String humanMove) {
        clearTurnState(scope);

        ChessTurnContinuation continuation = coordinator.processHumanMoveForWorkflow(sessionId, generation, humanMove);
        scope.writeState(ChessTurnWorkflowState.POST_HUMAN_POSITION_KEY, continuation.postHumanPosition());
        scope.writeState(ChessTurnWorkflowState.WHITE_COMMENTARY_FUTURE_KEY, continuation.whiteCommentaryFuture());

        ChessAiTurnContext aiTurn = continuation.aiTurn();
        boolean turnActive = aiTurn != null;
        scope.writeState(ChessTurnWorkflowState.TURN_ACTIVE_KEY, turnActive);
        if (!turnActive) {
            return false;
        }

        scope.writeState(ChessTurnWorkflowState.FEN_KEY, aiTurn.fen());
        scope.writeState(ChessTurnWorkflowState.SIDE_TO_MOVE_KEY, aiTurn.sideToMove());
        scope.writeState(ChessTurnWorkflowState.MOVE_HISTORY_KEY, aiTurn.moveHistory());
        scope.writeState(ChessTurnWorkflowState.LEGAL_MOVES_KEY, aiTurn.legalMovesPrompt());
        return true;
    }

    private static void clearTurnState(AgenticScope scope) {
        scope.writeState(ChessTurnWorkflowState.OPPONENT_DECISION_KEY, null);
        scope.writeState(ChessTurnWorkflowState.POST_HUMAN_POSITION_KEY, null);
        scope.writeState(ChessTurnWorkflowState.WHITE_COMMENTARY_FUTURE_KEY, null);
        scope.writeState(ChessTurnWorkflowState.FEN_KEY, null);
        scope.writeState(ChessTurnWorkflowState.SIDE_TO_MOVE_KEY, null);
        scope.writeState(ChessTurnWorkflowState.MOVE_HISTORY_KEY, null);
        scope.writeState(ChessTurnWorkflowState.LEGAL_MOVES_KEY, null);
        scope.writeState(ChessTurnWorkflowState.TURN_ACTIVE_KEY, false);
    }
}
