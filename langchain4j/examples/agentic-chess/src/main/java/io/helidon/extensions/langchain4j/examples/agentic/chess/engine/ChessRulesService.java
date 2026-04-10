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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import io.helidon.service.registry.Service;

@Service.Singleton
public final class ChessRulesService {
    private static final int[][] BISHOP_DIRECTIONS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] KING_DIRECTIONS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };
    private static final int[][] KNIGHT_DIRECTIONS = {
            {1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}
    };
    private static final int[][] ROOK_DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public ChessPosition initialPosition() {
        return ChessPosition.initial();
    }

    public List<ChessMove> legalMoves(ChessPosition position) {
        Objects.requireNonNull(position);
        Side side = position.sideToMove();
        List<ChessMove> legalMoves = new ArrayList<>();
        for (ChessMove move : pseudoLegalMoves(position)) {
            ChessPosition next = applyMoveUnchecked(position, move);
            if (!isInCheck(next, side)) {
                legalMoves.add(move);
            }
        }
        legalMoves.sort(Comparator.comparing(ChessMove::uci));
        return List.copyOf(legalMoves);
    }

    public Optional<ChessMove> findLegalMove(ChessPosition position, String moveUci) {
        if (moveUci == null || moveUci.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeUci(moveUci);
        return legalMoves(position).stream()
                .filter(move -> move.uci().equals(normalized))
                .findFirst();
    }

    public ChessPosition applyMove(ChessPosition position, ChessMove move) {
        return findLegalMove(position, move.uci())
                .map(legalMove -> applyMoveUnchecked(position, legalMove))
                .orElseThrow(() -> new IllegalArgumentException("Illegal move: " + move.uci()));
    }

    public ChessPosition applyMove(ChessPosition position, String moveUci) {
        ChessMove move = findLegalMove(position, moveUci)
                .orElseThrow(() -> new IllegalArgumentException("Illegal move: " + moveUci));
        return applyMoveUnchecked(position, move);
    }

    public ChessPosition applyLine(ChessPosition position, List<String> moves) {
        ChessPosition result = position;
        for (String move : moves) {
            result = applyMove(result, move);
        }
        return result;
    }

    public boolean isInCheck(ChessPosition position, Side side) {
        int kingSquare = findKing(position, side);
        return isSquareAttacked(position, kingSquare, side.opposite());
    }

    public GameStatus gameStatus(ChessPosition position) {
        List<ChessMove> legalMoves = legalMoves(position);
        if (!legalMoves.isEmpty()) {
            return GameStatus.ACTIVE;
        }
        return isInCheck(position, position.sideToMove()) ? GameStatus.CHECKMATE : GameStatus.STALEMATE;
    }

    public String legalMovesSummary(ChessPosition position, List<ChessMove> legalMoves) {
        StringBuilder builder = new StringBuilder();
        for (ChessMove move : legalMoves) {
            char piece = position.pieceAt(move.from());
            builder.append("- ")
                    .append(move.uci())
                    .append(" : ")
                    .append(pieceName(piece))
                    .append(" from ")
                    .append(move.fromSquare())
                    .append(" to ")
                    .append(move.toSquare())
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private List<ChessMove> pseudoLegalMoves(ChessPosition position) {
        List<ChessMove> moves = new ArrayList<>();
        Side side = position.sideToMove();
        for (int square = 0; square < 64; square++) {
            char piece = position.pieceAt(square);
            if (piece == '.' || Side.fromPiece(piece) != side) {
                continue;
            }
            switch (Character.toLowerCase(piece)) {
            case 'p' -> pawnMoves(position, square, piece, moves);
            case 'n' -> jumpMoves(position, square, piece, KNIGHT_DIRECTIONS, moves);
            case 'b' -> slidingMoves(position, square, piece, BISHOP_DIRECTIONS, moves);
            case 'r' -> slidingMoves(position, square, piece, ROOK_DIRECTIONS, moves);
            case 'q' -> {
                slidingMoves(position, square, piece, BISHOP_DIRECTIONS, moves);
                slidingMoves(position, square, piece, ROOK_DIRECTIONS, moves);
            }
            case 'k' -> kingMoves(position, square, piece, moves);
            default -> throw new IllegalStateException("Unexpected piece: " + piece);
            }
        }
        return moves;
    }

    private void pawnMoves(ChessPosition position, int square, char piece, List<ChessMove> moves) {
        Side side = Side.fromPiece(piece);
        int file = ChessPosition.fileOf(square);
        int rank = ChessPosition.rankOf(square);
        int forward = side == Side.WHITE ? 1 : -1;
        int promotionRank = side == Side.WHITE ? 7 : 0;
        int startRank = side == Side.WHITE ? 1 : 6;

        int oneStepRank = rank + forward;
        if (isBoardSquare(file, oneStepRank)) {
            int oneStepSquare = ChessPosition.square(file, oneStepRank);
            if (position.pieceAt(oneStepSquare) == '.') {
                addPawnAdvance(square, oneStepSquare, promotionRank, moves);
                if (rank == startRank) {
                    int twoStepRank = rank + (2 * forward);
                    int twoStepSquare = ChessPosition.square(file, twoStepRank);
                    if (position.pieceAt(twoStepSquare) == '.') {
                        moves.add(new ChessMove(square, twoStepSquare, '\0'));
                    }
                }
            }
        }

        for (int fileDelta : List.of(-1, 1)) {
            int targetFile = file + fileDelta;
            int targetRank = rank + forward;
            if (!isBoardSquare(targetFile, targetRank)) {
                continue;
            }
            int targetSquare = ChessPosition.square(targetFile, targetRank);
            char targetPiece = position.pieceAt(targetSquare);
            boolean normalCapture = targetPiece != '.' && Side.fromPiece(targetPiece) != side;
            boolean enPassantCapture = targetSquare == position.enPassantSquare();
            if (normalCapture || enPassantCapture) {
                addPawnAdvance(square, targetSquare, promotionRank, moves);
            }
        }
    }

    private void addPawnAdvance(int fromSquare, int toSquare, int promotionRank, List<ChessMove> moves) {
        if (ChessPosition.rankOf(toSquare) == promotionRank) {
            moves.add(new ChessMove(fromSquare, toSquare, 'q'));
        } else {
            moves.add(new ChessMove(fromSquare, toSquare, '\0'));
        }
    }

    private void jumpMoves(ChessPosition position,
                           int square,
                           char piece,
                           int[][] directions,
                           List<ChessMove> moves) {
        int file = ChessPosition.fileOf(square);
        int rank = ChessPosition.rankOf(square);
        Side side = Side.fromPiece(piece);
        for (int[] direction : directions) {
            int targetFile = file + direction[0];
            int targetRank = rank + direction[1];
            if (!isBoardSquare(targetFile, targetRank)) {
                continue;
            }
            int targetSquare = ChessPosition.square(targetFile, targetRank);
            char targetPiece = position.pieceAt(targetSquare);
            if (targetPiece == '.' || Side.fromPiece(targetPiece) != side) {
                moves.add(new ChessMove(square, targetSquare, '\0'));
            }
        }
    }

    private void slidingMoves(ChessPosition position,
                              int square,
                              char piece,
                              int[][] directions,
                              List<ChessMove> moves) {
        int file = ChessPosition.fileOf(square);
        int rank = ChessPosition.rankOf(square);
        Side side = Side.fromPiece(piece);
        for (int[] direction : directions) {
            int targetFile = file + direction[0];
            int targetRank = rank + direction[1];
            while (isBoardSquare(targetFile, targetRank)) {
                int targetSquare = ChessPosition.square(targetFile, targetRank);
                char targetPiece = position.pieceAt(targetSquare);
                if (targetPiece == '.') {
                    moves.add(new ChessMove(square, targetSquare, '\0'));
                } else {
                    if (Side.fromPiece(targetPiece) != side) {
                        moves.add(new ChessMove(square, targetSquare, '\0'));
                    }
                    break;
                }
                targetFile += direction[0];
                targetRank += direction[1];
            }
        }
    }

    private void kingMoves(ChessPosition position, int square, char piece, List<ChessMove> moves) {
        jumpMoves(position, square, piece, KING_DIRECTIONS, moves);
        Side side = Side.fromPiece(piece);
        if (isInCheck(position, side)) {
            return;
        }

        char[] board = position.boardCopy();
        if (side == Side.WHITE && square == ChessPosition.squareIndex("e1")) {
            if (position.whiteKingSide()
                    && board[ChessPosition.squareIndex("f1")] == '.'
                    && board[ChessPosition.squareIndex("g1")] == '.'
                    && board[ChessPosition.squareIndex("h1")] == 'R'
                    && !isSquareAttacked(position, ChessPosition.squareIndex("f1"), Side.BLACK)
                    && !isSquareAttacked(position, ChessPosition.squareIndex("g1"), Side.BLACK)) {
                moves.add(new ChessMove(square, ChessPosition.squareIndex("g1"), '\0'));
            }
            if (position.whiteQueenSide()
                    && board[ChessPosition.squareIndex("d1")] == '.'
                    && board[ChessPosition.squareIndex("c1")] == '.'
                    && board[ChessPosition.squareIndex("b1")] == '.'
                    && board[ChessPosition.squareIndex("a1")] == 'R'
                    && !isSquareAttacked(position, ChessPosition.squareIndex("d1"), Side.BLACK)
                    && !isSquareAttacked(position, ChessPosition.squareIndex("c1"), Side.BLACK)) {
                moves.add(new ChessMove(square, ChessPosition.squareIndex("c1"), '\0'));
            }
        }
        if (side == Side.BLACK && square == ChessPosition.squareIndex("e8")) {
            if (position.blackKingSide()
                    && board[ChessPosition.squareIndex("f8")] == '.'
                    && board[ChessPosition.squareIndex("g8")] == '.'
                    && board[ChessPosition.squareIndex("h8")] == 'r'
                    && !isSquareAttacked(position, ChessPosition.squareIndex("f8"), Side.WHITE)
                    && !isSquareAttacked(position, ChessPosition.squareIndex("g8"), Side.WHITE)) {
                moves.add(new ChessMove(square, ChessPosition.squareIndex("g8"), '\0'));
            }
            if (position.blackQueenSide()
                    && board[ChessPosition.squareIndex("d8")] == '.'
                    && board[ChessPosition.squareIndex("c8")] == '.'
                    && board[ChessPosition.squareIndex("b8")] == '.'
                    && board[ChessPosition.squareIndex("a8")] == 'r'
                    && !isSquareAttacked(position, ChessPosition.squareIndex("d8"), Side.WHITE)
                    && !isSquareAttacked(position, ChessPosition.squareIndex("c8"), Side.WHITE)) {
                moves.add(new ChessMove(square, ChessPosition.squareIndex("c8"), '\0'));
            }
        }
    }

    private boolean isSquareAttacked(ChessPosition position, int square, Side attackingSide) {
        int file = ChessPosition.fileOf(square);
        int rank = ChessPosition.rankOf(square);

        int pawnRank = rank + (attackingSide == Side.WHITE ? -1 : 1);
        if (isBoardSquare(file - 1, pawnRank)
                && position.pieceAt(ChessPosition.square(file - 1, pawnRank)) == (attackingSide == Side.WHITE ? 'P' : 'p')) {
            return true;
        }
        if (isBoardSquare(file + 1, pawnRank)
                && position.pieceAt(ChessPosition.square(file + 1, pawnRank)) == (attackingSide == Side.WHITE ? 'P' : 'p')) {
            return true;
        }

        for (int[] direction : KNIGHT_DIRECTIONS) {
            int targetFile = file + direction[0];
            int targetRank = rank + direction[1];
            if (isBoardSquare(targetFile, targetRank)
                    && position.pieceAt(ChessPosition.square(targetFile, targetRank)) == (attackingSide == Side.WHITE ? 'N' : 'n')) {
                return true;
            }
        }

        if (isAttackedBySliding(position, square, attackingSide, BISHOP_DIRECTIONS, 'b', 'q')) {
            return true;
        }
        if (isAttackedBySliding(position, square, attackingSide, ROOK_DIRECTIONS, 'r', 'q')) {
            return true;
        }

        for (int[] direction : KING_DIRECTIONS) {
            int targetFile = file + direction[0];
            int targetRank = rank + direction[1];
            if (isBoardSquare(targetFile, targetRank)
                    && position.pieceAt(ChessPosition.square(targetFile, targetRank)) == (attackingSide == Side.WHITE ? 'K' : 'k')) {
                return true;
            }
        }

        return false;
    }

    private boolean isAttackedBySliding(ChessPosition position,
                                        int square,
                                        Side attackingSide,
                                        int[][] directions,
                                        char primaryPiece,
                                        char secondaryPiece) {
        int file = ChessPosition.fileOf(square);
        int rank = ChessPosition.rankOf(square);
        for (int[] direction : directions) {
            int targetFile = file + direction[0];
            int targetRank = rank + direction[1];
            while (isBoardSquare(targetFile, targetRank)) {
                char piece = position.pieceAt(ChessPosition.square(targetFile, targetRank));
                if (piece != '.') {
                    if (Side.fromPiece(piece) == attackingSide) {
                        char normalized = Character.toLowerCase(piece);
                        return normalized == primaryPiece || normalized == secondaryPiece;
                    }
                    break;
                }
                targetFile += direction[0];
                targetRank += direction[1];
            }
        }
        return false;
    }

    private ChessPosition applyMoveUnchecked(ChessPosition position, ChessMove move) {
        char[] board = position.boardCopy();
        char movingPiece = board[move.from()];
        if (movingPiece == '.') {
            throw new IllegalArgumentException("No piece on " + move.fromSquare());
        }

        Side mover = Side.fromPiece(movingPiece);
        char capturedPiece = board[move.to()];
        board[move.from()] = '.';

        boolean pawnMove = Character.toLowerCase(movingPiece) == 'p';
        boolean enPassantCapture = pawnMove
                && ChessPosition.fileOf(move.from()) != ChessPosition.fileOf(move.to())
                && capturedPiece == '.'
                && move.to() == position.enPassantSquare();
        if (enPassantCapture) {
            int capturedSquare = mover == Side.WHITE ? move.to() - 8 : move.to() + 8;
            capturedPiece = board[capturedSquare];
            board[capturedSquare] = '.';
        }

        if (Character.toLowerCase(movingPiece) == 'k' && Math.abs(move.to() - move.from()) == 2) {
            moveCastlingRook(board, move.to(), mover);
        }

        char pieceToPlace = movingPiece;
        if (pawnMove && (ChessPosition.rankOf(move.to()) == 7 || ChessPosition.rankOf(move.to()) == 0)) {
            char promotion = move.isPromotion() ? move.promotion() : 'q';
            pieceToPlace = mover == Side.WHITE ? Character.toUpperCase(promotion) : Character.toLowerCase(promotion);
        }
        board[move.to()] = pieceToPlace;

        boolean whiteKingSide = position.whiteKingSide();
        boolean whiteQueenSide = position.whiteQueenSide();
        boolean blackKingSide = position.blackKingSide();
        boolean blackQueenSide = position.blackQueenSide();

        if (Character.toLowerCase(movingPiece) == 'k') {
            if (mover == Side.WHITE) {
                whiteKingSide = false;
                whiteQueenSide = false;
            } else {
                blackKingSide = false;
                blackQueenSide = false;
            }
        }

        if (Character.toLowerCase(movingPiece) == 'r') {
            whiteKingSide = whiteKingSide && move.from() != ChessPosition.squareIndex("h1");
            whiteQueenSide = whiteQueenSide && move.from() != ChessPosition.squareIndex("a1");
            blackKingSide = blackKingSide && move.from() != ChessPosition.squareIndex("h8");
            blackQueenSide = blackQueenSide && move.from() != ChessPosition.squareIndex("a8");
        }

        if (capturedPiece == 'R') {
            whiteKingSide = whiteKingSide && move.to() != ChessPosition.squareIndex("h1");
            whiteQueenSide = whiteQueenSide && move.to() != ChessPosition.squareIndex("a1");
        } else if (capturedPiece == 'r') {
            blackKingSide = blackKingSide && move.to() != ChessPosition.squareIndex("h8");
            blackQueenSide = blackQueenSide && move.to() != ChessPosition.squareIndex("a8");
        }

        int enPassantSquare = -1;
        if (pawnMove && Math.abs(move.to() - move.from()) == 16) {
            enPassantSquare = mover == Side.WHITE ? move.from() + 8 : move.from() - 8;
        }

        int halfmoveClock = (pawnMove || capturedPiece != '.') ? 0 : position.halfmoveClock() + 1;
        int fullmoveNumber = position.fullmoveNumber() + (mover == Side.BLACK ? 1 : 0);

        return ChessPosition.create(board,
                                    mover.opposite(),
                                    whiteKingSide,
                                    whiteQueenSide,
                                    blackKingSide,
                                    blackQueenSide,
                                    enPassantSquare,
                                    halfmoveClock,
                                    fullmoveNumber);
    }

    private void moveCastlingRook(char[] board, int kingTargetSquare, Side side) {
        if (side == Side.WHITE) {
            if (kingTargetSquare == ChessPosition.squareIndex("g1")) {
                board[ChessPosition.squareIndex("f1")] = 'R';
                board[ChessPosition.squareIndex("h1")] = '.';
            } else {
                board[ChessPosition.squareIndex("d1")] = 'R';
                board[ChessPosition.squareIndex("a1")] = '.';
            }
            return;
        }

        if (kingTargetSquare == ChessPosition.squareIndex("g8")) {
            board[ChessPosition.squareIndex("f8")] = 'r';
            board[ChessPosition.squareIndex("h8")] = '.';
        } else {
            board[ChessPosition.squareIndex("d8")] = 'r';
            board[ChessPosition.squareIndex("a8")] = '.';
        }
    }

    private int findKing(ChessPosition position, Side side) {
        char king = side == Side.WHITE ? 'K' : 'k';
        for (int square = 0; square < 64; square++) {
            if (position.pieceAt(square) == king) {
                return square;
            }
        }
        throw new IllegalStateException("Missing king for " + side);
    }

    private boolean isBoardSquare(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    private String normalizeUci(String moveUci) {
        String normalized = moveUci.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() != 4 && normalized.length() != 5) {
            throw new IllegalArgumentException("Invalid UCI move: " + moveUci);
        }
        ChessPosition.squareIndex(normalized.substring(0, 2));
        ChessPosition.squareIndex(normalized.substring(2, 4));
        char promotion = normalized.length() == 5 ? normalized.charAt(4) : '\0';
        return new ChessMove(ChessPosition.squareIndex(normalized.substring(0, 2)),
                             ChessPosition.squareIndex(normalized.substring(2, 4)),
                             promotion).uci();
    }

    private String pieceName(char piece) {
        char normalized = Character.toLowerCase(piece);
        return switch (normalized) {
            case 'p' -> "pawn";
            case 'n' -> "knight";
            case 'b' -> "bishop";
            case 'r' -> "rook";
            case 'q' -> "queen";
            case 'k' -> "king";
            default -> "piece";
        };
    }
}
