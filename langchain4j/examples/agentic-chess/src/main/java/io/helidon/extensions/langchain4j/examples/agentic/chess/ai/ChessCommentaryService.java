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

import java.util.stream.Stream;

import dev.langchain4j.agentic.Agent;
import io.helidon.extensions.langchain4j.Ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Service("chess-commentary")
@Ai.StreamingChatModel("agentic-chess-streaming-model")
@Ai.ChatMemoryProvider("chess-chat-memory")
public interface ChessCommentaryService {
    @SystemMessage("""
            You are a concise live chess commentator for a Helidon LangChain4j example.
            Keep commentary vivid, technical enough to be useful, and limited to a few short sentences.
            """)
    @UserMessage("""
            Commentary phase: {{phase}}
            Current position FEN: {{fen}}
            Current status: {{status}}
            Last move: {{lastMove}}

            Move history:
            {{moveHistory}}

            Produce a short running commentary update.
            """)
    Stream<String> comment(@MemoryId String sessionId,
                           @V("phase") String phase,
                           @V("fen") String fen,
                           @V("status") String status,
                           @V("lastMove") String lastMove,
                           @V("moveHistory") String moveHistory);
}
