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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.AiMoveProposal;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessPosition;

public record ChessTurnWorkflowResult(String humanMove,
                                      ChessPosition postHumanPosition,
                                      AiMoveProposal opponentDecision,
                                      CompletableFuture<Void> whiteCommentaryFuture,
                                      boolean aiTurnActive) {
    public ChessTurnWorkflowResult {
        Objects.requireNonNull(humanMove);
        Objects.requireNonNull(postHumanPosition);
        Objects.requireNonNull(whiteCommentaryFuture);
        if (aiTurnActive && opponentDecision == null) {
            throw new IllegalArgumentException("An active AI turn requires an opponent decision");
        }
    }
}
