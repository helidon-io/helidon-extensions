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

final class ChessTurnWorkflowState {
    static final String FEN_KEY = "fen";
    static final String HUMAN_MOVE_KEY = "humanMove";
    static final String LEGAL_MOVES_KEY = "legalMoves";
    static final String MOVE_HISTORY_KEY = "moveHistory";
    static final String OPPONENT_DECISION_KEY = "opponentDecision";
    static final String POST_HUMAN_POSITION_KEY = "postHumanPosition";
    static final String SIDE_TO_MOVE_KEY = "sideToMove";
    static final String TURN_ACTIVE_KEY = "turnActive";
    static final String TURN_RESULT_KEY = "turnResult";
    static final String WHITE_COMMENTARY_FUTURE_KEY = "whiteCommentaryFuture";

    private ChessTurnWorkflowState() {
    }
}
