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

package io.helidon.extensions.langchain4j.examples.agentic.chess.rest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.ChessEvent;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.ChessSnapshot;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.MoveEntryView;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.MoveSubmissionResult;
import io.helidon.extensions.langchain4j.providers.mock.MockChatModel;
import io.helidon.extensions.langchain4j.providers.mock.MockChatRule;
import io.helidon.extensions.langchain4j.providers.mock.MockStreamingChatModel;
import io.helidon.http.Status;
import io.helidon.service.registry.Service;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.testing.junit5.ServerTest;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@ServerTest
class ChessExampleServerTest {
    private static final Jsonb JSONB = JsonbBuilder.create();

    private final HttpClient client;
    private final URI baseUri;
    private final MockChatModel chatModel;
    private final MockStreamingChatModel streamingModel;

    ChessExampleServerTest(WebServer server,
                           @Service.Named("agentic-chess-mock-chat-model") MockChatModel chatModel,
                           @Service.Named("agentic-chess-mock-streaming-model") MockStreamingChatModel streamingModel) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.baseUri = URI.create("http://localhost:" + server.port());
        this.chatModel = chatModel;
        this.streamingModel = streamingModel;
        configureOpponent(chatModel);
        configureCommentary(streamingModel);
    }

    @Test
    void servesChessUiAssets() throws Exception {
        HttpResponse<String> chessUiRedirect = client.send(HttpRequest.newBuilder(baseUri.resolve("/chess"))
                                                                   .GET()
                                                                   .build(),
                                                           HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> chessUi = client.send(HttpRequest.newBuilder(baseUri.resolve("/chess/"))
                                                           .GET()
                                                           .build(),
                                                   HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> chessCss = client.send(HttpRequest.newBuilder(baseUri.resolve("/chess/app.css"))
                                                            .GET()
                                                            .build(),
                                                    HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> chessJs = client.send(HttpRequest.newBuilder(baseUri.resolve("/chess/app.js"))
                                                           .GET()
                                                           .build(),
                                                   HttpResponse.BodyHandlers.ofString());

        assertThat(chessUiRedirect.statusCode(), is(Status.MOVED_PERMANENTLY_301.code()));
        assertThat(chessUiRedirect.headers().firstValue("location").orElse(""), is("/chess/"));
        assertThat(chessUi.statusCode(), is(Status.OK_200.code()));
        assertThat(chessUi.body(), containsString("Agentic Chess"));
        assertThat(chessUi.body(), not(containsString("Move List")));
        assertThat(chessUi.body(), containsString("id=\"board-card\""));
        assertThat(chessUi.body(), containsString("class=\"status-pill busy\""));
        assertThat(chessCss.statusCode(), is(Status.OK_200.code()));
        assertThat(chessCss.body(), containsString(".board-animation-layer"));
        assertThat(chessCss.body(), containsString("pointer-events: none"));
        assertThat(chessCss.body(), containsString("--board-square-size"));
        assertThat(chessCss.body(), containsString("max-height: 34rem"));
        assertThat(chessCss.body(), containsString("grid-template-columns: repeat(8, var(--board-square-size))"));
        assertThat(chessCss.body(), containsString(".board > .square"));
        assertThat(chessCss.body(), containsString("border-radius: 0"));
        assertThat(chessCss.body(), containsString(".status-pill.busy::before"));
        assertThat(chessCss.body(), containsString(".candidate-panel"));
        assertThat(chessJs.statusCode(), is(Status.OK_200.code()));
        assertThat(chessJs.body(), containsString("void bootstrapGame();"));
        assertThat(chessJs.body(), containsString("elements.board.addEventListener(\"mouseleave\""));
        assertThat(chessJs.body(), containsString("new ResizeObserver(syncSidebarHeight)"));
        assertThat(chessJs.body(), containsString("square !== activeSourceSquare && activeTargets.has(square)"));
        assertThat(chessJs.body(), containsString("snapshot.aiThinking"));
        assertThat(chessJs.body(), containsString("snapshot.commentaryStreaming"));
        assertThat(chessJs.body(), containsString("appendHighlightedMoveText"));
        assertThat(chessJs.body(), containsString("candidateHistory.unshift"));
        assertThat(chessJs.body(), containsString("elements.sidebar.style.height = `${boardCardHeight}px`;"));
    }

    @Test
    void playsOneRoundTripAndStreamsEvents() throws Exception {
        ChessSnapshot created = postJson("/chess/api/game", "", ChessSnapshot.class);
        assertThat(created.awaitingHumanMove(), is(true));

        EventCollector collector = new EventCollector();
        WebSocket webSocket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri("/chess/events/" + created.sessionId()), collector)
                .get(5, TimeUnit.SECONDS);
        webSocket.sendText("resync", true).get(5, TimeUnit.SECONDS);

        MoveSubmissionResult submission = postJson("/chess/api/game/" + created.sessionId() + "/move",
                                                   "{\"move\":\"e2e4\"}",
                                                   MoveSubmissionResult.class);
        assertThat(submission.accepted(), is(true));

        ChessSnapshot afterAiTurn = awaitSnapshot(created.sessionId(),
                                                  snapshot -> snapshot.moveHistory().size() == 2
                                                          && snapshot.commentary().size() >= 2
                                                          && snapshot.awaitingHumanMove(),
                                                  Duration.ofSeconds(10));

        assertThat(afterAiTurn.moveHistory().stream().map(MoveEntryView::move).toList(),
                   is(List.of("e2e4", "e7e5")));
        assertThat(afterAiTurn.candidateLines().size(), is(3));

        List<String> eventTypes = collector.awaitEventTypes(types ->
                                                                    types.contains("awaiting-move")
                                                                            && types.contains("human-move-accepted")
                                                                            && types.contains("ai-thinking")
                                                                            && types.contains("ai-move-accepted")
                                                                            && types.contains("commentary-chunk")
                                                                            && types.contains("commentary-finished"),
                                                            Duration.ofSeconds(10));

        assertThat(eventTypes, hasItem("awaiting-move"));
        assertThat(eventTypes, hasItem("human-move-accepted"));
        assertThat(eventTypes, hasItem("ai-thinking"));
        assertThat(eventTypes, hasItem("ai-move-accepted"));
        assertThat(eventTypes, hasItem("commentary-chunk"));
        assertThat(eventTypes, hasItem("commentary-finished"));

        webSocket.abort();
    }

    @Test
    void exposesConcurrentAiThinkingAndCommentaryStreamingAfterHumanMove() throws Exception {
        configureOpponent(chatModel, 1_500);
        configureCommentary(streamingModel, 120);

        ChessSnapshot created = postJson("/chess/api/game", "", ChessSnapshot.class);
        EventCollector collector = new EventCollector();
        WebSocket webSocket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri("/chess/events/" + created.sessionId()), collector)
                .get(5, TimeUnit.SECONDS);
        webSocket.sendText("resync", true).get(5, TimeUnit.SECONDS);

        MoveSubmissionResult submission = postJson("/chess/api/game/" + created.sessionId() + "/move",
                                                   "{\"move\":\"e2e4\"}",
                                                   MoveSubmissionResult.class);
        assertThat(submission.accepted(), is(true));

        collector.awaitEventTypes(types -> types.contains("ai-thinking")
                        && types.contains("commentary-start"),
                Duration.ofSeconds(10));

        ChessSnapshot inFlight = awaitSnapshot(created.sessionId(),
                                              snapshot -> snapshot.aiThinking()
                                                      && snapshot.commentaryStreaming()
                                                      && "AI".equals(snapshot.waitingFor())
                                                      && "After White's move".equals(snapshot.commentaryPhase()),
                                              Duration.ofSeconds(5));

        assertThat(inFlight.awaitingHumanMove(), is(false));
        assertThat(inFlight.status(), is("ACTIVE"));
        assertThat(inFlight.commentaryPhase(), is("After White's move"));

        webSocket.abort();
    }

    @Test
    void ignoresAbortedWebSocketClientsDuringBroadcast() throws Exception {
        ChessSnapshot created = postJson("/chess/api/game", "", ChessSnapshot.class);
        assertThat(created.awaitingHumanMove(), is(true));

        WebSocket webSocket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri("/chess/events/" + created.sessionId()), new EventCollector())
                .get(5, TimeUnit.SECONDS);

        webSocket.abort();

        MoveSubmissionResult submission = postJson("/chess/api/game/" + created.sessionId() + "/move",
                                                   "{\"move\":\"e2e4\"}",
                                                   MoveSubmissionResult.class);
        assertThat(submission.accepted(), is(true));

        ChessSnapshot afterAiTurn = awaitSnapshot(created.sessionId(),
                                                  snapshot -> snapshot.moveHistory().size() == 2
                                                          && snapshot.awaitingHumanMove()
                                                          && "e7e5".equals(snapshot.lastMove()),
                                                  Duration.ofSeconds(10));

        assertThat(afterAiTurn.moveHistory().stream().map(MoveEntryView::move).toList(),
                   is(List.of("e2e4", "e7e5")));
    }

    private void configureOpponent(MockChatModel chatModel) {
        configureOpponent(chatModel, 0);
    }

    private void configureOpponent(MockChatModel chatModel, long responseDelayMillis) {
        chatModel.resetRules();
        chatModel.activeRules().addFirst(new MockChatRule() {
            @Override
            public boolean matches(dev.langchain4j.model.chat.request.ChatRequest req) {
                return concatMessages(req.messages())
                        .contains("Think strategically, but only return legal UCI moves and legal candidate lines.");
            }

            @Override
            public String mock(dev.langchain4j.model.chat.request.ChatRequest req) {
                sleep(responseDelayMillis);
                assertThat(concatMessages(req.messages()), not(containsString("Return JSON only with this schema")));
                return """
                        {
                          "move": "e7e5",
                          "summary": "Black mirrors White's central claim.",
                          "candidateLines": [
                            {"summary": "Classical symmetry", "moves": ["e7e5", "g1f3", "b8c6"]},
                            {"summary": "Sicilian flavor", "moves": ["c7c5", "g1f3", "d7d6"]},
                            {"summary": "French structure", "moves": ["e7e6", "d2d4", "d7d5"]}
                          ]
                        }
                        """;
            }
        });
    }

    private void configureCommentary(MockStreamingChatModel streamingModel) {
        configureCommentary(streamingModel, 0);
    }

    private void configureCommentary(MockStreamingChatModel streamingModel, long chunkDelayMillis) {
        streamingModel.resetRules();
        streamingModel.activeRules().addFirst(new MockChatRule() {
            @Override
            public boolean matches(dev.langchain4j.model.chat.request.ChatRequest req) {
                return concatMessages(req.messages()).contains("Produce a short running commentary update.");
            }

            @Override
            public String mock(String concatenatedReq) {
                if (concatenatedReq.contains("After White's move")) {
                    return "White claims the center with e2e4 and opens lines for rapid development.";
                }
                return "Black answers with e7e5, keeping the position balanced and principled.";
            }

            @Override
            public void doMock(dev.langchain4j.model.chat.request.ChatRequest chatRequest,
                               dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                var response = MockChatRule.super.doMock(chatRequest);
                for (String chunk : response.aiMessage().text().splitWithDelimiters("\\s", 0)) {
                    handler.onPartialResponse(chunk);
                    sleep(chunkDelayMillis);
                }
                handler.onCompleteResponse(response);
            }
        });
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while delaying mock response", e);
        }
    }

    private ChessSnapshot awaitSnapshot(String sessionId,
                                        Predicate<ChessSnapshot> condition,
                                        Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        ChessSnapshot lastSnapshot = null;
        while (System.nanoTime() < deadline) {
            ChessSnapshot snapshot = getJson("/chess/api/game/" + sessionId, ChessSnapshot.class);
            lastSnapshot = snapshot;
            if (condition.test(snapshot)) {
                return snapshot;
            }
            Thread.sleep(150);
        }
        throw new TimeoutException("Timed out waiting for snapshot update. Last snapshot: "
                                           + (lastSnapshot == null ? "<none>" : JSONB.toJson(lastSnapshot)));
    }

    private URI wsUri(String path) {
        return URI.create("ws://localhost:" + baseUri.getPort() + path);
    }

    private <T> T getJson(String path, Class<T> type) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(baseUri.resolve(path))
                                                            .GET()
                                                            .header("Accept", "application/json")
                                                            .build(),
                                                    HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != Status.OK_200.code()) {
            throw new IllegalStateException("Unexpected status " + response.statusCode() + " for " + path);
        }
        return JSONB.fromJson(response.body(), type);
    }

    private <T> T postJson(String path, String body, Class<T> type) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(baseUri.resolve(path))
                                                            .POST(HttpRequest.BodyPublishers.ofString(body))
                                                            .header("Accept", "application/json")
                                                            .header("Content-Type", "application/json")
                                                            .build(),
                                                    HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != Status.OK_200.code()) {
            throw new IllegalStateException("Unexpected status " + response.statusCode() + ": " + response.body());
        }
        return JSONB.fromJson(response.body(), type);
    }

    private static final class EventCollector implements WebSocket.Listener {
        private final LinkedBlockingQueue<ChessEvent> events = new LinkedBlockingQueue<>();
        private final CopyOnWriteArrayList<String> bufferedEventTypes = new CopyOnWriteArrayList<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private final CompletableFuture<Void> opened = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            opened.complete(null);
            webSocket.request(64);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                ChessEvent event = JSONB.fromJson(textBuffer.toString(), ChessEvent.class);
                events.add(event);
                bufferedEventTypes.add(event.type());
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        List<String> awaitEventTypes(Predicate<List<String>> condition, Duration timeout)
                throws InterruptedException, ExecutionException, TimeoutException {
            opened.get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                List<String> snapshot = List.copyOf(bufferedEventTypes);
                if (condition.test(snapshot)) {
                    return snapshot;
                }
                events.poll(150, TimeUnit.MILLISECONDS);
            }
            return List.copyOf(bufferedEventTypes);
        }
    }
}
