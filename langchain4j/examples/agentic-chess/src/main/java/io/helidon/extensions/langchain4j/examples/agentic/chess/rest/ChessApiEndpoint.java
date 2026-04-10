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

import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.ChessSnapshot;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.ErrorResponse;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.MoveRequest;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.MoveSubmissionResult;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessGameCoordinator;
import io.helidon.http.Http;
import io.helidon.http.Status;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;
import io.helidon.webserver.http.ServerResponse;

@RestServer.Endpoint
@Http.Path("/chess/api")
@Service.Singleton
public final class ChessApiEndpoint {
    private final ChessGameCoordinator coordinator;

    public ChessApiEndpoint(ChessGameCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Http.POST
    @Http.Path("/game")
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
    ChessSnapshot createGame() {
        return coordinator.createGame();
    }

    @Http.GET
    @Http.Path("/game/{sessionId}")
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
    Object snapshot(@Http.PathParam("sessionId") String sessionId, ServerResponse response) {
        return coordinator.snapshot(sessionId)
                .<Object>map(snapshot -> snapshot)
                .orElseGet(() -> {
                    response.status(Status.NOT_FOUND_404);
                    return new ErrorResponse("Unknown session: " + sessionId);
                });
    }

    @Http.POST
    @Http.Path("/game/{sessionId}/reset")
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
    ChessSnapshot reset(@Http.PathParam("sessionId") String sessionId) {
        return coordinator.resetGame(sessionId);
    }

    @Http.POST
    @Http.Path("/game/{sessionId}/move")
    @Http.Consumes(MediaTypes.APPLICATION_JSON_VALUE)
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
    Object move(@Http.PathParam("sessionId") String sessionId,
                @Http.Entity MoveRequest moveRequest,
                ServerResponse response) {
        if (moveRequest == null || moveRequest.getMove() == null || moveRequest.getMove().isBlank()) {
            response.status(Status.BAD_REQUEST_400);
            return new ErrorResponse("Request must contain a non-empty 'move' field.");
        }

        MoveSubmissionResult result = coordinator.submitHumanMove(sessionId, moveRequest.getMove());
        if (!result.accepted()) {
            response.status(Status.BAD_REQUEST_400);
        }
        return result;
    }
}
