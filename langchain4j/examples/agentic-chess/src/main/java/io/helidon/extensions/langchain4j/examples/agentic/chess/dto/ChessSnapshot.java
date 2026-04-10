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

package io.helidon.extensions.langchain4j.examples.agentic.chess.dto;

import java.util.List;

public record ChessSnapshot(String sessionId,
                            long revision,
                            String fen,
                            String turn,
                            String status,
                            String waitingFor,
                            boolean awaitingHumanMove,
                            boolean aiThinking,
                            boolean commentaryStreaming,
                            String commentaryPhase,
                            String lastMove,
                            String streamingCommentary,
                            String terminalMessage,
                            List<LegalMoveView> legalMoves,
                            List<MoveEntryView> moveHistory,
                            List<CommentaryView> commentary,
                            List<CandidateLineView> candidateLines) {
}
