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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.CandidateLineView;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.ChessSnapshot;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.CommentaryView;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.LegalMoveView;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.MoveEntryView;

final class ChessSession {
    private final String sessionId;
    private final String generation;
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();

    private ChessPosition position;
    private GameStatus status;
    private WaitingFor waitingFor;
    private boolean aiThinking;
    private volatile Instant lastTouched;
    private long revision;
    private String lastMove;
    private boolean commentaryStreaming;
    private String commentaryPhase;
    private long commentaryRunId;
    private String streamingCommentary;
    private String terminalMessage;
    private final List<MoveRecord> moveHistory = new ArrayList<>();
    private final List<CommentaryEntry> commentary = new ArrayList<>();
    private List<CandidateLine> candidateLines = List.of();

    ChessSession(String sessionId) {
        this(sessionId, UUID.randomUUID().toString(), ChessPosition.initial());
    }

    ChessSession(String sessionId, String generation, ChessPosition position) {
        this.sessionId = sessionId;
        this.generation = generation;
        this.position = position;
        this.status = GameStatus.ACTIVE;
        this.waitingFor = WaitingFor.NONE;
        this.aiThinking = false;
        this.lastTouched = Instant.now();
        this.revision = 1;
        this.lastMove = "";
        this.commentaryStreaming = false;
        this.commentaryPhase = "";
        this.commentaryRunId = 0;
        this.streamingCommentary = "";
        this.terminalMessage = "";
    }

    Lock readLock() {
        return stateLock.readLock();
    }

    Lock writeLock() {
        return stateLock.writeLock();
    }

    String sessionId() {
        return sessionId;
    }

    String generation() {
        return generation;
    }

    ChessPosition position() {
        return position;
    }

    void position(ChessPosition position) {
        this.position = position;
    }

    GameStatus status() {
        return status;
    }

    void status(GameStatus status) {
        this.status = status;
    }

    WaitingFor waitingFor() {
        return waitingFor;
    }

    void waitingFor(WaitingFor waitingFor) {
        this.waitingFor = waitingFor;
    }

    boolean aiThinking() {
        return aiThinking;
    }

    void aiThinking(boolean aiThinking) {
        this.aiThinking = aiThinking;
    }

    long revision() {
        return revision;
    }

    Instant lastTouched() {
        return lastTouched;
    }

    void touch() {
        this.lastTouched = Instant.now();
        this.revision++;
    }

    String lastMove() {
        return lastMove;
    }

    void lastMove(String lastMove) {
        this.lastMove = lastMove == null ? "" : lastMove;
    }

    boolean commentaryStreaming() {
        return commentaryStreaming;
    }

    void commentaryStreaming(boolean commentaryStreaming) {
        this.commentaryStreaming = commentaryStreaming;
    }

    String commentaryPhase() {
        return commentaryPhase;
    }

    void commentaryPhase(String commentaryPhase) {
        this.commentaryPhase = commentaryPhase == null ? "" : commentaryPhase;
    }

    long commentaryRunId() {
        return commentaryRunId;
    }

    void commentaryRunId(long commentaryRunId) {
        this.commentaryRunId = commentaryRunId;
    }

    String streamingCommentary() {
        return streamingCommentary;
    }

    void streamingCommentary(String streamingCommentary) {
        this.streamingCommentary = streamingCommentary == null ? "" : streamingCommentary;
    }

    String terminalMessage() {
        return terminalMessage;
    }

    void terminalMessage(String terminalMessage) {
        this.terminalMessage = terminalMessage == null ? "" : terminalMessage;
    }

    List<MoveRecord> moveHistory() {
        return moveHistory;
    }

    List<CommentaryEntry> commentary() {
        return commentary;
    }

    List<CandidateLine> candidateLines() {
        return candidateLines;
    }

    void candidateLines(List<CandidateLine> candidateLines) {
        this.candidateLines = List.copyOf(candidateLines == null ? List.of() : candidateLines);
    }

    ChessSnapshot snapshot(ChessRulesService rules) {
        Lock readLock = readLock();
        readLock.lock();
        try {
            return snapshotLocked(rules);
        } finally {
            readLock.unlock();
        }
    }

    ChessSnapshot snapshotLocked(ChessRulesService rules) {
        List<LegalMoveView> legalMoves = waitingFor == WaitingFor.HUMAN && status == GameStatus.ACTIVE
                ? rules.legalMoves(position).stream()
                        .map(move -> new LegalMoveView(move.uci(), move.fromSquare(), move.toSquare(), move.isPromotion()))
                        .toList()
                : List.of();

        List<MoveEntryView> moveEntries = moveHistory.stream()
                .map(move -> new MoveEntryView(move.ply(), move.side().name(), move.move()))
                .toList();
        List<CommentaryView> commentaryEntries = commentary.stream()
                .map(entry -> new CommentaryView(entry.phase(), entry.text()))
                .toList();
        List<CandidateLineView> candidateLineViews = candidateLines.stream()
                .map(line -> new CandidateLineView(line.summary(), line.moves(), line.finalFen()))
                .toList();

        return new ChessSnapshot(sessionId,
                                 revision,
                                 position.toFen(),
                                 position.sideToMove().name(),
                                 status.name(),
                                 waitingFor.name(),
                                 waitingFor == WaitingFor.HUMAN && status == GameStatus.ACTIVE,
                                 aiThinking,
                                 commentaryStreaming,
                                 commentaryPhase,
                                 lastMove,
                                 streamingCommentary,
                                 terminalMessage,
                                 legalMoves,
                                 moveEntries,
                                 commentaryEntries,
                                 candidateLineViews);
    }
}
