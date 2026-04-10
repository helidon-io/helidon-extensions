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

import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessGameCoordinator;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.websocket.WebSocketServer;
import io.helidon.websocket.WebSocket;
import io.helidon.websocket.WsSession;

@SuppressWarnings("deprecation")
@WebSocketServer.Endpoint
@Http.Path("/chess/events/{sessionId}")
@Service.Singleton
public final class ChessEventSocketEndpoint {
    private final ChessEventBroadcaster broadcaster;
    private final ChessGameCoordinator coordinator;

    public ChessEventSocketEndpoint(ChessEventBroadcaster broadcaster, ChessGameCoordinator coordinator) {
        this.broadcaster = broadcaster;
        this.coordinator = coordinator;
    }

    @WebSocket.OnOpen
    void onOpen(WsSession session, @Http.PathParam("sessionId") String sessionId) {
        broadcaster.register(sessionId, session);
        coordinator.pushSnapshot(sessionId);
    }

    @WebSocket.OnMessage
    void onMessage(@Http.PathParam("sessionId") String sessionId, String message) {
        if ("resync".equalsIgnoreCase(message.trim())) {
            coordinator.pushSnapshot(sessionId);
        }
    }

    @WebSocket.OnClose
    void onClose(WsSession session, @Http.PathParam("sessionId") String sessionId) {
        broadcaster.unregister(sessionId, session);
    }

    @WebSocket.OnError
    void onError(WsSession session, @Http.PathParam("sessionId") String sessionId, Throwable throwable) {
        broadcaster.unregister(sessionId, session);
    }
}
