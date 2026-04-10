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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import io.helidon.service.registry.Service;

@Service.Singleton
public final class HumanMoveSemaphore {
    private final ConcurrentMap<String, CompletableFuture<String>> pendingMoves = new ConcurrentHashMap<>();

    public String awaitMove(String sessionId) {
        // Allow the browser to submit a move slightly before the HITL workflow starts waiting.
        CompletableFuture<String> nextMove = pendingMoves.computeIfAbsent(sessionId, ignored -> new CompletableFuture<>());
        try {
            return nextMove.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for move", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed while waiting for move", cause);
        } finally {
            pendingMoves.remove(sessionId, nextMove);
        }
    }

    public boolean submitMove(String sessionId, String moveUci) {
        CompletableFuture<String> pending = pendingMoves.computeIfAbsent(sessionId, ignored -> new CompletableFuture<>());
        return pending.complete(moveUci);
    }

    public void cancel(String sessionId) {
        CompletableFuture<String> pending = pendingMoves.remove(sessionId);
        if (pending != null) {
            pending.completeExceptionally(new CancellationException("Session was reset"));
        }
    }
}
