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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ChessPosition {
    private final char[] board;
    private final Side sideToMove;
    private final boolean whiteKingSide;
    private final boolean whiteQueenSide;
    private final boolean blackKingSide;
    private final boolean blackQueenSide;
    private final int enPassantSquare;
    private final int halfmoveClock;
    private final int fullmoveNumber;

    private ChessPosition(char[] board,
                          Side sideToMove,
                          boolean whiteKingSide,
                          boolean whiteQueenSide,
                          boolean blackKingSide,
                          boolean blackQueenSide,
                          int enPassantSquare,
                          int halfmoveClock,
                          int fullmoveNumber) {
        this.board = board.clone();
        this.sideToMove = Objects.requireNonNull(sideToMove);
        this.whiteKingSide = whiteKingSide;
        this.whiteQueenSide = whiteQueenSide;
        this.blackKingSide = blackKingSide;
        this.blackQueenSide = blackQueenSide;
        this.enPassantSquare = enPassantSquare;
        this.halfmoveClock = halfmoveClock;
        this.fullmoveNumber = fullmoveNumber;
    }

    static ChessPosition initial() {
        return fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    static ChessPosition create(char[] board,
                                Side sideToMove,
                                boolean whiteKingSide,
                                boolean whiteQueenSide,
                                boolean blackKingSide,
                                boolean blackQueenSide,
                                int enPassantSquare,
                                int halfmoveClock,
                                int fullmoveNumber) {
        return new ChessPosition(board,
                                 sideToMove,
                                 whiteKingSide,
                                 whiteQueenSide,
                                 blackKingSide,
                                 blackQueenSide,
                                 enPassantSquare,
                                 halfmoveClock,
                                 fullmoveNumber);
    }

    public static ChessPosition fromFen(String fen) {
        Objects.requireNonNull(fen);
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid FEN: " + fen);
        }

        char[] board = new char[64];
        Arrays.fill(board, '.');
        String[] ranks = parts[0].split("/");
        if (ranks.length != 8) {
            throw new IllegalArgumentException("Invalid FEN board: " + fen);
        }

        for (int fenRank = 0; fenRank < 8; fenRank++) {
            int rank = 7 - fenRank;
            int file = 0;
            for (char token : ranks[fenRank].toCharArray()) {
                if (Character.isDigit(token)) {
                    file += token - '0';
                } else {
                    board[square(file, rank)] = token;
                    file++;
                }
            }
            if (file != 8) {
                throw new IllegalArgumentException("Invalid FEN rank: " + ranks[fenRank]);
            }
        }

        Side sideToMove = switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "w" -> Side.WHITE;
            case "b" -> Side.BLACK;
            default -> throw new IllegalArgumentException("Invalid FEN side to move: " + parts[1]);
        };

        String castling = parts[2];
        boolean whiteKingSide = castling.contains("K");
        boolean whiteQueenSide = castling.contains("Q");
        boolean blackKingSide = castling.contains("k");
        boolean blackQueenSide = castling.contains("q");

        int enPassantSquare = "-".equals(parts[3]) ? -1 : squareIndex(parts[3]);
        int halfmoveClock = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        int fullmoveNumber = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;

        return new ChessPosition(board,
                                 sideToMove,
                                 whiteKingSide,
                                 whiteQueenSide,
                                 blackKingSide,
                                 blackQueenSide,
                                 enPassantSquare,
                                 halfmoveClock,
                                 fullmoveNumber);
    }

    char pieceAt(int square) {
        return board[square];
    }

    char[] boardCopy() {
        return board.clone();
    }

    Side sideToMove() {
        return sideToMove;
    }

    boolean whiteKingSide() {
        return whiteKingSide;
    }

    boolean whiteQueenSide() {
        return whiteQueenSide;
    }

    boolean blackKingSide() {
        return blackKingSide;
    }

    boolean blackQueenSide() {
        return blackQueenSide;
    }

    int enPassantSquare() {
        return enPassantSquare;
    }

    int halfmoveClock() {
        return halfmoveClock;
    }

    int fullmoveNumber() {
        return fullmoveNumber;
    }

    String toFen() {
        StringBuilder builder = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                char piece = board[square(file, rank)];
                if (piece == '.') {
                    empty++;
                } else {
                    if (empty > 0) {
                        builder.append(empty);
                        empty = 0;
                    }
                    builder.append(piece);
                }
            }
            if (empty > 0) {
                builder.append(empty);
            }
            if (rank > 0) {
                builder.append('/');
            }
        }

        builder.append(' ')
                .append(sideToMove == Side.WHITE ? 'w' : 'b')
                .append(' ');

        String castling = castlingRights();
        builder.append(castling.isBlank() ? "-" : castling)
                .append(' ')
                .append(enPassantSquare < 0 ? "-" : squareName(enPassantSquare))
                .append(' ')
                .append(halfmoveClock)
                .append(' ')
                .append(fullmoveNumber);
        return builder.toString();
    }

    String asciiBoard() {
        StringBuilder builder = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            builder.append(rank + 1).append(" | ");
            for (int file = 0; file < 8; file++) {
                builder.append(pieceAt(square(file, rank))).append(' ');
            }
            builder.append('\n');
        }
        builder.append("    a b c d e f g h");
        return builder.toString();
    }

    private String castlingRights() {
        StringBuilder builder = new StringBuilder();
        if (whiteKingSide) {
            builder.append('K');
        }
        if (whiteQueenSide) {
            builder.append('Q');
        }
        if (blackKingSide) {
            builder.append('k');
        }
        if (blackQueenSide) {
            builder.append('q');
        }
        return builder.toString();
    }

    static int square(int file, int rank) {
        return rank * 8 + file;
    }

    static int fileOf(int square) {
        return square % 8;
    }

    static int rankOf(int square) {
        return square / 8;
    }

    static String squareName(int square) {
        return String.valueOf((char) ('a' + fileOf(square))) + (rankOf(square) + 1);
    }

    static int squareIndex(String squareName) {
        if (squareName == null || squareName.length() != 2) {
            throw new IllegalArgumentException("Invalid square name: " + squareName);
        }
        char fileChar = Character.toLowerCase(squareName.charAt(0));
        char rankChar = squareName.charAt(1);
        if (fileChar < 'a' || fileChar > 'h' || rankChar < '1' || rankChar > '8') {
            throw new IllegalArgumentException("Invalid square name: " + squareName);
        }
        return square(fileChar - 'a', rankChar - '1');
    }

    List<String> occupiedSquares() {
        List<String> result = new ArrayList<>();
        for (int square = 0; square < 64; square++) {
            if (board[square] != '.') {
                result.add(squareName(square));
            }
        }
        return List.copyOf(result);
    }
}
