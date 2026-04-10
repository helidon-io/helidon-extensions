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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

class ChessRulesServiceTest {
    private final ChessRulesService rules = new ChessRulesService();

    @Test
    void initialPositionHasTwentyLegalMoves() {
        assertThat(rules.legalMoves(ChessPosition.initial()).size(), is(20));
    }

    @Test
    void supportsCastlingOnBothSides() {
        ChessPosition position = ChessPosition.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");

        assertThat(rules.legalMoves(position).stream().map(ChessMove::uci).toList(),
                   hasItem("e1g1"));
        assertThat(rules.legalMoves(position).stream().map(ChessMove::uci).toList(),
                   hasItem("e1c1"));
    }

    @Test
    void supportsEnPassantCapture() {
        ChessPosition position = ChessPosition.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");

        ChessPosition next = rules.applyMove(position, "e5d6");

        assertThat(next.pieceAt(ChessPosition.squareIndex("d6")), is('P'));
        assertThat(next.pieceAt(ChessPosition.squareIndex("d5")), is('.'));
    }

    @Test
    void promotesToQueen() {
        ChessPosition position = ChessPosition.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");

        ChessPosition next = rules.applyMove(position, "a7a8q");

        assertThat(next.pieceAt(ChessPosition.squareIndex("a8")), is('Q'));
    }

    @Test
    void detectsCheckmateAndStalemate() {
        ChessPosition checkmate = ChessPosition.fromFen("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
        ChessPosition stalemate = ChessPosition.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");

        assertThat(rules.gameStatus(checkmate), is(GameStatus.CHECKMATE));
        assertThat(rules.gameStatus(stalemate), is(GameStatus.STALEMATE));
        assertThat(rules.legalMoves(stalemate).stream().map(ChessMove::uci).toList(), empty());
    }
}
