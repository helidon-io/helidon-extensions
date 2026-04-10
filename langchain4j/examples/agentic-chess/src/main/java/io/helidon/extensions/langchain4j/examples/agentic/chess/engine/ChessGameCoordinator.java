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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;

import io.helidon.extensions.langchain4j.examples.agentic.chess.ai.ChessCommentaryService;
import io.helidon.extensions.langchain4j.examples.agentic.chess.ai.ChessTurnWorkflowAgent;
import io.helidon.extensions.langchain4j.examples.agentic.chess.ai.ChessTurnWorkflowResult;
import io.helidon.extensions.langchain4j.examples.agentic.chess.ai.HumanMoveSemaphore;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.AiCandidateLine;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.AiMoveProposal;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.ChessEvent;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.ChessSnapshot;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.MoveSubmissionResult;
import io.helidon.extensions.langchain4j.examples.agentic.chess.rest.ChessEventBroadcaster;
import io.helidon.service.registry.Service;

@Service.Singleton
public final class ChessGameCoordinator {
    private static final System.Logger LOGGER = System.getLogger(ChessGameCoordinator.class.getName());

    private final ChessCommentaryService commentaryAgent;
    private final ChessEventBroadcaster broadcaster;
    private final HumanMoveSemaphore humanMoveSemaphore;
    private final ChessTurnWorkflowAgent turnWorkflowAgent;
    private final ChessPromptSupport promptSupport;
    private final ChessRulesService rules;
    private final ChessSessionStore sessions;

    ChessGameCoordinator(ChessCommentaryService commentaryAgent,
                         ChessEventBroadcaster broadcaster,
                         HumanMoveSemaphore humanMoveSemaphore,
                         ChessTurnWorkflowAgent turnWorkflowAgent,
                         ChessPromptSupport promptSupport,
                         ChessRulesService rules,
                         ChessSessionStore sessions) {
        this.commentaryAgent = commentaryAgent;
        this.broadcaster = broadcaster;
        this.humanMoveSemaphore = humanMoveSemaphore;
        this.turnWorkflowAgent = turnWorkflowAgent;
        this.promptSupport = promptSupport;
        this.rules = rules;
        this.sessions = sessions;
    }

    public ChessSnapshot createGame() {
        ChessSession session = sessions.create();
        Lock writeLock = session.writeLock();
        writeLock.lock();
        try {
            session.waitingFor(WaitingFor.HUMAN);
            session.aiThinking(false);
            session.commentaryStreaming(false);
            session.commentaryPhase("");
            session.terminalMessage("");
            session.streamingCommentary("");
            session.touch();
        } finally {
            writeLock.unlock();
        }
        startGameLoop(session.sessionId(), session.generation());
        return session.snapshot(rules);
    }

    public ChessSnapshot resetGame(String sessionId) {
        humanMoveSemaphore.cancel(sessionId);
        ChessSession session = sessions.reset(sessionId);
        Lock writeLock = session.writeLock();
        writeLock.lock();
        try {
            session.waitingFor(WaitingFor.HUMAN);
            session.aiThinking(false);
            session.commentaryStreaming(false);
            session.commentaryPhase("");
            session.terminalMessage("");
            session.streamingCommentary("");
            session.touch();
        } finally {
            writeLock.unlock();
        }
        startGameLoop(session.sessionId(), session.generation());
        return session.snapshot(rules);
    }

    public Optional<ChessSnapshot> snapshot(String sessionId) {
        return sessions.find(sessionId).map(session -> session.snapshot(rules));
    }

    public void pushSnapshot(String sessionId) {
        snapshot(sessionId).ifPresent(snapshot -> broadcaster.broadcast(sessionId, snapshotEvent("state-snapshot", snapshot)));
    }

    public MoveSubmissionResult submitHumanMove(String sessionId, String moveUci) {
        ChessSession session = sessions.require(sessionId);
        ChessSnapshot snapshot;
        String normalizedMove;
        Lock writeLock = session.writeLock();
        writeLock.lock();
        try {
            snapshot = session.snapshotLocked(rules);
            if (session.status() != GameStatus.ACTIVE) {
                return new MoveSubmissionResult(false, "Game is already finished.", snapshot);
            }
            if (session.waitingFor() != WaitingFor.HUMAN) {
                return new MoveSubmissionResult(false, "The game is not waiting for a human move.", snapshot);
            }
            Optional<ChessMove> legalMove;
            try {
                legalMove = rules.findLegalMove(session.position(), moveUci);
            } catch (Exception e) {
                legalMove = Optional.empty();
            }
            if (legalMove.isEmpty()) {
                ChessEvent rejection = new ChessEvent("human-move-rejected",
                                                      sessionId,
                                                      snapshot.revision(),
                                                      "Illegal move: " + moveUci,
                                                      moveUci,
                                                      null,
                                                      snapshot);
                broadcaster.broadcast(sessionId, rejection);
                return new MoveSubmissionResult(false, "Illegal move.", snapshot);
            }
            normalizedMove = legalMove.get().uci();
        } finally {
            writeLock.unlock();
        }

        boolean accepted = humanMoveSemaphore.submitMove(sessionId, normalizedMove);
        if (!accepted) {
            snapshot = sessions.require(sessionId).snapshot(rules);
            ChessEvent rejection = new ChessEvent("human-move-rejected",
                                                  sessionId,
                                                  snapshot.revision(),
                                                  "Move collector is not ready.",
                                                  normalizedMove,
                                                  null,
                                                  snapshot);
            broadcaster.broadcast(sessionId, rejection);
            return new MoveSubmissionResult(false, "Move collector is not ready.", snapshot);
        }

        return new MoveSubmissionResult(true, "Move accepted for processing.", sessions.require(sessionId).snapshot(rules));
    }

    private void startGameLoop(String sessionId, String generation) {
        Thread.startVirtualThread(() -> runGameLoop(sessionId, generation));
    }

    private void runGameLoop(String sessionId, String generation) {
        try {
            while (true) {
                ChessSession session = currentSession(sessionId, generation);
                if (session == null) {
                    return;
                }

                ChessHumanTurnContext humanTurn = prepareHumanTurn(session);
                broadcaster.broadcast(sessionId, snapshotEvent("state-snapshot", humanTurn.snapshot()));
                broadcaster.broadcast(sessionId, snapshotEvent("awaiting-move", humanTurn.snapshot()));

                ChessTurnWorkflowResult turnResult = turnWorkflowAgent.playTurn(sessionId,
                                                                                generation,
                                                                                this,
                                                                                humanMoveSemaphore,
                                                                                humanTurn.humanContext(),
                                                                                humanTurn.legalMovesPrompt(),
                                                                                promptSupport.candidateLineCount(),
                                                                                promptSupport.candidateLinePly());

                turnResult.whiteCommentaryFuture().join();
                if (!turnResult.aiTurnActive()) {
                    if (finishIfTerminal(sessionId, generation)) {
                        return;
                    }
                    if (currentSession(sessionId, generation) == null) {
                        return;
                    }
                    continue;
                }

                session = currentSession(sessionId, generation);
                if (session == null) {
                    return;
                }

                applyAiMove(sessionId, session, turnResult.postHumanPosition(), turnResult.opponentDecision());

                startCommentaryAsync(sessionId, generation, "After Black's move").join();
                if (finishIfTerminal(sessionId, generation)) {
                    return;
                }
            }
        } catch (CancellationException ignored) {
            // reset/new game replaced this loop
        } catch (Exception e) {
            ChessSession session = currentSession(sessionId, generation);
            if (session == null) {
                return;
            }
            Throwable failure = rootCause(e);
            String failureMessage = failureDescription(failure);
            LOGGER.log(System.Logger.Level.ERROR, "Agentic chess game loop failed for session " + sessionId, e);
            ChessSnapshot snapshot;
            Lock writeLock = session.writeLock();
            writeLock.lock();
            try {
                session.waitingFor(WaitingFor.NONE);
                session.aiThinking(false);
                session.commentaryStreaming(false);
                session.commentaryPhase("");
                session.terminalMessage("Game loop failed: " + failureMessage);
                session.streamingCommentary("");
                session.touch();
                snapshot = session.snapshotLocked(rules);
            } finally {
                writeLock.unlock();
            }
            broadcaster.broadcast(sessionId, new ChessEvent("error",
                                                            sessionId,
                                                            snapshot.revision(),
                                                            failureMessage,
                                                            null,
                                                            null,
                                                            snapshot));
        }
    }

    private ChessHumanTurnContext prepareHumanTurn(ChessSession session) {
        Lock writeLock = session.writeLock();
        writeLock.lock();
        try {
            List<ChessMove> legalMoves = rules.legalMoves(session.position());
            session.waitingFor(WaitingFor.HUMAN);
            session.aiThinking(false);
            session.commentaryStreaming(false);
            session.commentaryPhase("");
            session.streamingCommentary("");
            session.touch();
            ChessSnapshot snapshot = session.snapshotLocked(rules);
            String humanContext = promptSupport.humanInputContext(session.position(), legalMoves, rules);
            return new ChessHumanTurnContext(humanContext,
                                             rules.legalMovesSummary(session.position(), legalMoves),
                                             snapshot);
        } finally {
            writeLock.unlock();
        }
    }

    public ChessTurnContinuation processHumanMoveForWorkflow(String sessionId, String generation, String humanMove) {
        ChessSession session = currentSession(sessionId, generation);
        if (session == null) {
            throw new CancellationException("Session was replaced before the human move could be applied");
        }

        ChessHumanMoveApplication application = applyHumanMove(sessionId, session, generation, humanMove);
        if (!application.applied()) {
            throw new CancellationException("Session changed while applying the human move");
        }

        CompletableFuture<Void> whiteCommentary = startCommentaryAsync(sessionId, generation, "After White's move");
        if (application.status() != GameStatus.ACTIVE) {
            return new ChessTurnContinuation(application.position(), null, whiteCommentary);
        }

        ChessAiTurnContext aiTurn = prepareAiTurn(sessionId, generation);
        if (aiTurn == null) {
            if (currentSession(sessionId, generation) == null) {
                throw new CancellationException("Session was replaced before the AI turn could start");
            }
            return new ChessTurnContinuation(application.position(), null, whiteCommentary);
        }

        broadcaster.broadcast(sessionId, snapshotEvent("ai-thinking", aiTurn.snapshot()));
        return new ChessTurnContinuation(application.position(), aiTurn, whiteCommentary);
    }

    private ChessHumanMoveApplication applyHumanMove(String sessionId,
                                                     ChessSession session,
                                                     String generation,
                                                     String moveUci) {
        ChessSnapshot snapshot;
        ChessPosition nextPosition;
        GameStatus nextStatus;
        Lock writeLock = session.writeLock();
        writeLock.lock();
        try {
            if (!session.generation().equals(generation) || session.waitingFor() != WaitingFor.HUMAN) {
                return ChessHumanMoveApplication.notApplied();
            }
            Optional<ChessMove> move = rules.findLegalMove(session.position(), moveUci);
            if (move.isEmpty()) {
                snapshot = session.snapshotLocked(rules);
                broadcaster.broadcast(sessionId, new ChessEvent("human-move-rejected",
                                                                sessionId,
                                                                snapshot.revision(),
                                                                "Illegal move: " + moveUci,
                                                                moveUci,
                                                                null,
                                                                snapshot));
                return ChessHumanMoveApplication.notApplied();
            }

            nextPosition = rules.applyMove(session.position(), move.get());
            session.position(nextPosition);
            session.moveHistory().add(new MoveRecord(session.moveHistory().size() + 1, Side.WHITE, move.get().uci()));
            session.lastMove(move.get().uci());
            session.candidateLines(List.of());
            session.aiThinking(false);
            session.commentaryStreaming(false);
            session.commentaryPhase("");
            session.streamingCommentary("");
            nextStatus = rules.gameStatus(nextPosition);
            session.status(nextStatus);
            session.waitingFor(nextStatus == GameStatus.ACTIVE ? WaitingFor.AI : WaitingFor.COMMENTARY);
            session.terminalMessage(terminalMessage(nextPosition, nextStatus));
            session.touch();
            snapshot = session.snapshotLocked(rules);
        } finally {
            writeLock.unlock();
        }
        broadcaster.broadcast(sessionId, new ChessEvent("human-move-accepted",
                                                        sessionId,
                                                        snapshot.revision(),
                                                        null,
                                                        moveUci,
                                                        null,
                                                        snapshot));
        return ChessHumanMoveApplication.applied(nextPosition, nextStatus);
    }

    private ChessAiTurnContext prepareAiTurn(String sessionId, String generation) {
        ChessSession session = currentSession(sessionId, generation);
        if (session == null) {
            return null;
        }
        Lock writeLock = session.writeLock();
        writeLock.lock();
        try {
            if (session.status() != GameStatus.ACTIVE) {
                return null;
            }
            ChessPosition rootPosition = session.position();
            List<ChessMove> legalMoves = rules.legalMoves(rootPosition);
            session.waitingFor(WaitingFor.AI);
            session.aiThinking(true);
            session.touch();
            ChessSnapshot snapshot = session.snapshotLocked(rules);
            return new ChessAiTurnContext(rootPosition.toFen(),
                                          rootPosition.sideToMove().display(),
                                          promptSupport.moveHistory(session.moveHistory()),
                                          rules.legalMovesSummary(rootPosition, legalMoves),
                                          snapshot);
        } finally {
            writeLock.unlock();
        }
    }

    private void applyAiMove(String sessionId, ChessSession session, ChessPosition rootPosition, AiMoveProposal proposal) {
        ChessSnapshot snapshot;
        Lock writeLock = session.writeLock();
        writeLock.lock();
        try {
            ChessMove chosenMove = rules.findLegalMove(rootPosition, proposal.getMove())
                    .orElseThrow(() -> new IllegalStateException("AI chose an illegal move: " + proposal.getMove()));
            List<CandidateLine> candidateLines = toCandidateLines(rootPosition, proposal);
            ChessPosition nextPosition = rules.applyMove(rootPosition, chosenMove);
            session.position(nextPosition);
            session.moveHistory().add(new MoveRecord(session.moveHistory().size() + 1, Side.BLACK, chosenMove.uci()));
            session.lastMove(chosenMove.uci());
            session.candidateLines(candidateLines);
            session.aiThinking(false);
            session.commentaryStreaming(false);
            session.commentaryPhase("");
            session.streamingCommentary("");
            session.status(rules.gameStatus(nextPosition));
            session.waitingFor(WaitingFor.COMMENTARY);
            session.terminalMessage(terminalMessage(nextPosition, session.status()));
            session.touch();
            snapshot = session.snapshotLocked(rules);
        } finally {
            writeLock.unlock();
        }
        broadcaster.broadcast(sessionId, new ChessEvent("ai-move-accepted",
                                                        sessionId,
                                                        snapshot.revision(),
                                                        proposal.getSummary(),
                                                        proposal.getMove(),
                                                        null,
                                                        snapshot));
    }

    private List<CandidateLine> toCandidateLines(ChessPosition rootPosition, AiMoveProposal proposal) {
        List<CandidateLine> result = new ArrayList<>();
        for (AiCandidateLine line : proposal.getCandidateLines()) {
            if (line.getMoves().isEmpty()) {
                continue;
            }
            try {
                ChessPosition finalPosition = rules.applyLine(rootPosition, line.getMoves());
                result.add(new CandidateLine(line.getSummary(), line.getMoves(), finalPosition.toFen()));
            } catch (Exception ignored) {
                // guardrail should catch these first; keep coordinator resilient if it does not
            }
        }

        if (!result.isEmpty()) {
            return List.copyOf(result);
        }

        ChessPosition finalPosition = rules.applyMove(rootPosition, proposal.getMove());
        return List.of(new CandidateLine(proposal.getSummary(), List.of(proposal.getMove()), finalPosition.toFen()));
    }

    private void streamCommentary(String sessionId, String generation, String phase) {
        ChessSession session = currentSession(sessionId, generation);
        if (session == null) {
            return;
        }

        String fen;
        String status;
        String lastMove;
        String moveHistory;
        long commentaryRunId;
        ChessSnapshot startSnapshot;
        Lock writeLock = session.writeLock();
        writeLock.lock();
        try {
            session.commentaryRunId(session.commentaryRunId() + 1);
            commentaryRunId = session.commentaryRunId();
            session.commentaryStreaming(true);
            session.commentaryPhase(phase);
            session.streamingCommentary("");
            session.touch();
            fen = session.position().toFen();
            status = session.status().name();
            lastMove = session.lastMove();
            moveHistory = promptSupport.moveHistory(session.moveHistory());
            startSnapshot = session.snapshotLocked(rules);
        } finally {
            writeLock.unlock();
        }
        broadcaster.broadcast(sessionId, new ChessEvent("commentary-start",
                                                        sessionId,
                                                        startSnapshot.revision(),
                                                        phase,
                                                        null,
                                                        null,
                                                        startSnapshot));

        try (Stream<String> tokens = commentaryAgent.comment(sessionId, phase, fen, status, lastMove, moveHistory)) {
            var iterator = tokens.iterator();
            while (iterator.hasNext()) {
                ChessSession current = currentSession(sessionId, generation);
                if (current == null) {
                    return;
                }
                String token = iterator.next();
                long revision;
                Lock currentWriteLock = current.writeLock();
                currentWriteLock.lock();
                try {
                    if (current.commentaryRunId() != commentaryRunId || !current.commentaryStreaming()) {
                        return;
                    }
                    current.streamingCommentary(current.streamingCommentary() + token);
                    current.touch();
                    revision = current.revision();
                } finally {
                    currentWriteLock.unlock();
                }
                broadcaster.broadcast(sessionId, new ChessEvent("commentary-chunk",
                                                                sessionId,
                                                                revision,
                                                                null,
                                                                null,
                                                                token,
                                                                null));
            }
        } catch (Exception e) {
            ChessSession current = currentSession(sessionId, generation);
            if (current != null) {
                ChessSnapshot errorSnapshot;
                Lock currentWriteLock = current.writeLock();
                currentWriteLock.lock();
                try {
                    if (current.commentaryRunId() != commentaryRunId) {
                        return;
                    }
                    current.commentaryStreaming(false);
                    current.commentaryPhase("");
                    current.streamingCommentary("");
                    current.touch();
                    errorSnapshot = current.snapshotLocked(rules);
                } finally {
                    currentWriteLock.unlock();
                }
                broadcaster.broadcast(sessionId, new ChessEvent("error",
                                                                sessionId,
                                                                errorSnapshot.revision(),
                                                                "Commentary failed: " + e.getMessage(),
                                                                null,
                                                                null,
                                                                errorSnapshot));
            }
            return;
        }

        session = currentSession(sessionId, generation);
        if (session == null) {
            return;
        }

        ChessSnapshot endSnapshot;
        writeLock = session.writeLock();
        writeLock.lock();
        try {
            if (session.commentaryRunId() != commentaryRunId || !session.commentaryStreaming()) {
                return;
            }
            String commentary = session.streamingCommentary().trim();
            if (!commentary.isBlank()) {
                session.commentary().add(new CommentaryEntry(phase, commentary));
            }
            session.commentaryStreaming(false);
            session.commentaryPhase("");
            session.streamingCommentary("");
            session.touch();
            endSnapshot = session.snapshotLocked(rules);
        } finally {
            writeLock.unlock();
        }
        broadcaster.broadcast(sessionId, new ChessEvent("commentary-finished",
                                                        sessionId,
                                                        endSnapshot.revision(),
                                                        phase,
                                                        null,
                                                        null,
                                                        endSnapshot));
    }

    private boolean finishIfTerminal(String sessionId, String generation) {
        ChessSession session = currentSession(sessionId, generation);
        if (session == null) {
            return true;
        }
        ChessSnapshot snapshot;
        Lock writeLock = session.writeLock();
        writeLock.lock();
        try {
            if (session.status() == GameStatus.ACTIVE) {
                return false;
            }
            session.waitingFor(WaitingFor.NONE);
            session.aiThinking(false);
            session.commentaryStreaming(false);
            session.commentaryPhase("");
            session.streamingCommentary("");
            session.touch();
            snapshot = session.snapshotLocked(rules);
        } finally {
            writeLock.unlock();
        }
        broadcaster.broadcast(sessionId, new ChessEvent("game-over",
                                                        sessionId,
                                                        snapshot.revision(),
                                                        snapshot.terminalMessage(),
                                                        snapshot.lastMove(),
                                                        null,
                                                        snapshot));
        return true;
    }

    private ChessEvent snapshotEvent(String type, ChessSnapshot snapshot) {
        return new ChessEvent(type,
                              snapshot.sessionId(),
                              snapshot.revision(),
                              null,
                              snapshot.lastMove(),
                              null,
                              snapshot);
    }

    private String terminalMessage(ChessPosition position, GameStatus status) {
        return switch (status) {
            case ACTIVE -> "";
            case CHECKMATE -> "Checkmate. " + position.sideToMove().opposite().display() + " wins.";
            case STALEMATE -> "Stalemate.";
        };
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String failureDescription(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return failure.getClass().getSimpleName() + ": " + message;
    }

    private ChessSession currentSession(String sessionId, String generation) {
        return sessions.find(sessionId)
                .filter(session -> session.generation().equals(generation))
                .orElse(null);
    }

    private CompletableFuture<Void> startCommentaryAsync(String sessionId, String generation, String phase) {
        return startAsync(() -> {
            streamCommentary(sessionId, generation, phase);
            return null;
        });
    }

    private <T> CompletableFuture<T> startAsync(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }
}
