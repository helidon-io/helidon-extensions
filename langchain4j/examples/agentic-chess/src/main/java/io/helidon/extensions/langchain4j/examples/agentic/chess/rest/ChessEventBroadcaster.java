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

import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.ChessEvent;
import io.helidon.service.registry.Service;
import io.helidon.websocket.WsSession;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

@Service.Singleton
public final class ChessEventBroadcaster {
    private static final System.Logger LOGGER = System.getLogger(ChessEventBroadcaster.class.getName());

    private final Jsonb jsonb = JsonbBuilder.create();
    private final ConcurrentMap<String, Set<WsSession>> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<WsSession, ReentrantLock> sendLocks = new ConcurrentHashMap<>();

    public void register(String sessionId, WsSession session) {
        sendLocks.putIfAbsent(session, new ReentrantLock());
        sessions.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(String sessionId, WsSession session) {
        sessions.computeIfPresent(sessionId, (ignored, activeSessions) -> {
            activeSessions.remove(session);
            return activeSessions.isEmpty() ? null : activeSessions;
        });
        sendLocks.remove(session);
    }

    public void broadcast(String sessionId, ChessEvent event) {
        String payload = jsonb.toJson(event);
        Set<WsSession> activeSessions = sessions.get(sessionId);
        if (activeSessions == null || activeSessions.isEmpty()) {
            return;
        }

        for (WsSession session : new ArrayList<>(activeSessions)) {
            try {
                Lock sendLock = sendLocks.computeIfAbsent(session, ignored -> new ReentrantLock());
                sendLock.lock();
                try {
                    session.send(payload, true);
                } finally {
                    sendLock.unlock();
                }
            } catch (RuntimeException e) {
                unregister(sessionId, session);
                if (isClosedConnection(e)) {
                    LOGGER.log(System.Logger.Level.DEBUG,
                               "Removing closed chess event WebSocket session for game " + sessionId,
                               e);
                } else {
                    LOGGER.log(System.Logger.Level.WARNING,
                               "Removing failed chess event WebSocket session for game " + sessionId,
                               e);
                }
            }
        }
    }

    private boolean isClosedConnection(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ClosedChannelException) {
                return true;
            }
            if (current instanceof UncheckedIOException unchecked
                    && unchecked.getCause() instanceof ClosedChannelException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
