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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.helidon.common.Default;
import io.helidon.config.Configuration;
import io.helidon.extensions.langchain4j.examples.agentic.chess.ai.HumanMoveSemaphore;
import io.helidon.service.registry.Service;

@Service.Singleton
final class ChessSessionStore {
    private final ConcurrentMap<String, ChessSession> sessions = new ConcurrentHashMap<>();
    private final HumanMoveSemaphore humanMoveSemaphore;
    private final Duration idleTimeout;
    private final Duration cleanupInterval;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread cleanupThread;

    ChessSessionStore(HumanMoveSemaphore humanMoveSemaphore,
                      @Configuration.Value("agentic-chess.sessions.idle-timeout") @Default.Value("PT30M")
                      Duration idleTimeout,
                      @Configuration.Value("agentic-chess.sessions.cleanup-interval") @Default.Value("PT1M")
                      Duration cleanupInterval) {
        this.humanMoveSemaphore = humanMoveSemaphore;
        this.idleTimeout = idleTimeout;
        this.cleanupInterval = cleanupInterval;
    }

    @Service.PostConstruct
    void startCleanupLoop() {
        running.set(true);
        cleanupThread = Thread.startVirtualThread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(cleanupInterval);
                    evictExpiredSessions(Instant.now());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    // Keep cleanup best-effort so session expiry does not destabilize the app.
                }
            }
        });
    }

    @Service.PreDestroy
    void stopCleanupLoop() {
        running.set(false);
        Thread cleanupThread = this.cleanupThread;
        if (cleanupThread != null) {
            cleanupThread.interrupt();
        }
    }

    ChessSession create() {
        evictExpiredSessions(Instant.now());
        String sessionId = UUID.randomUUID().toString();
        ChessSession session = new ChessSession(sessionId);
        sessions.put(sessionId, session);
        return session;
    }

    ChessSession reset(String sessionId) {
        evictExpiredSessions(Instant.now());
        ChessSession session = new ChessSession(sessionId);
        sessions.put(sessionId, session);
        return session;
    }

    Optional<ChessSession> find(String sessionId) {
        ChessSession session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (!isExpired(session, Instant.now())) {
            return Optional.of(session);
        }
        if (sessions.remove(sessionId, session)) {
            humanMoveSemaphore.cancel(sessionId);
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    ChessSession require(String sessionId) {
        return find(sessionId).orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
    }

    boolean isCurrentGeneration(String sessionId, String generation) {
        return find(sessionId)
                .map(session -> session.generation().equals(generation))
                .orElse(false);
    }

    private void evictExpiredSessions(Instant now) {
        sessions.forEach((sessionId, session) -> {
            if (!isExpired(session, now)) {
                return;
            }
            if (sessions.remove(sessionId, session)) {
                humanMoveSemaphore.cancel(sessionId);
            }
        });
    }

    private boolean isExpired(ChessSession session, Instant now) {
        return session.lastTouched().plus(idleTimeout).isBefore(now);
    }
}
