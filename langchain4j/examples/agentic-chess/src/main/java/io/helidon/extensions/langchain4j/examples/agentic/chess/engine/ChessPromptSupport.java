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

package io.helidon.extensions.langchain4j.examples.agentic.chess.engine;

import java.util.List;

import io.helidon.common.Default;
import io.helidon.config.Configuration;
import io.helidon.service.registry.Service;

@Service.Singleton
final class ChessPromptSupport {
    private final int candidateLineCount;
    private final int candidateLinePly;

    ChessPromptSupport(@Configuration.Value("agentic-chess.candidate-lines.count") @Default.Int(3) int candidateLineCount,
                       @Configuration.Value("agentic-chess.candidate-lines.plies") @Default.Int(3) int candidateLinePly) {
        this.candidateLineCount = candidateLineCount;
        this.candidateLinePly = candidateLinePly;
    }

    int candidateLineCount() {
        return candidateLineCount;
    }

    int candidateLinePly() {
        return candidateLinePly;
    }

    String moveHistory(List<MoveRecord> moveHistory) {
        if (moveHistory.isEmpty()) {
            return "(start position)";
        }

        StringBuilder builder = new StringBuilder();
        for (MoveRecord move : moveHistory) {
            int moveNumber = ((move.ply() - 1) / 2) + 1;
            builder.append(moveNumber)
                    .append(move.side() == Side.WHITE ? ". " : "... ")
                    .append(move.move())
                    .append('\n');
        }
        return builder.toString().trim();
    }

    String humanInputContext(ChessPosition position, List<ChessMove> legalMoves, ChessRulesService rules) {
        return """
                Session is waiting for the human player's move.
                Current side to move: %s

                Board:
                %s

                Legal moves:
                %s
                """.formatted(position.sideToMove().display(),
                               position.asciiBoard(),
                               rules.legalMovesSummary(position, legalMoves));
    }
}
