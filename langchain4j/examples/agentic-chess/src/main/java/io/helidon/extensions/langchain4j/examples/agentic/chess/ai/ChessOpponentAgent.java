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

import io.helidon.extensions.langchain4j.Ai;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.AiMoveProposal;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("chess-opponent")
@Ai.ChatModel("agentic-chess-chat-model")
@Ai.ChatMemoryProvider("chess-chat-memory")
public interface ChessOpponentAgent {
    @SystemMessage("""
            You are a careful chess opponent playing the black side in a Helidon LangChain4j teaching example.
            The deterministic chess rules layer is authoritative.
            Think strategically, but only return legal UCI moves and legal candidate lines.
            """)
    @UserMessage("""
            Current position FEN: {{fen}}
            Side to move: {{sideToMove}}

            Move history:
            {{moveHistory}}

            Legal moves:
            {{legalMoves}}

            Rules:
            - The chosen move must be legal.
            - Candidate lines must contain up to {{candidateLineCount}} lines.
            - Each line must contain between 1 and {{candidateLinePly}} plies from the current position.
            - The first candidate line must begin with the chosen move.
            - Use coordinate notation only.
            - Do not add prose outside the structured response.
            """)
    @Agent(value = "Chess opponent that chooses a legal move", outputKey = "opponentDecision")
    AiMoveProposal chooseMove(@MemoryId String sessionId,
                              @V("fen") String fen,
                              @V("sideToMove") String sideToMove,
                              @V("moveHistory") String moveHistory,
                              @V("legalMoves") String legalMoves,
                              @V("candidateLineCount") int candidateLineCount,
                              @V("candidateLinePly") int candidateLinePly);
}
