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
package io.helidon.extensions.chaos;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import io.helidon.common.media.type.MediaType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.json.JsonObject;
import io.helidon.security.SecurityContext;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.security.SecurityFeature;

import static io.helidon.extensions.chaos.ChaosRunState.STOPPING;

/**
 * Role-protected control resources for bounded local chaos runs.
 */
final class ChaosControlService implements HttpService {
    private static final String RUNS_PATH = "/chaos/v1/runs/";
    private static final MediaType PROBLEM_JSON = MediaTypes.create("application", "problem+json");

    private final ChaosRunEngine engine;
    private final ChaosLimitsConfig limits;
    private final boolean anonymousLoopback;
    private final Handler authorization;
    private final Semaphore capacity;

    ChaosControlService(ChaosRunEngine engine, ChaosConfig config, boolean anonymousLoopback) {
        this(engine,
             config,
             anonymousLoopback,
             new Semaphore(config.limits().maximumConcurrentControlRequests(), true));
    }

    ChaosControlService(ChaosRunEngine engine,
                        ChaosConfig config,
                        boolean anonymousLoopback,
                        Semaphore capacity) {
        this.engine = Objects.requireNonNull(engine);
        this.limits = Objects.requireNonNull(config).limits();
        this.anonymousLoopback = anonymousLoopback;
        this.capacity = Objects.requireNonNull(capacity);
        this.authorization = anonymousLoopback
                ? (request, response) -> response.next()
                : SecurityFeature.rolesAllowed(config.security().requiredRole()).audit();
    }

    @Override
    public void routing(HttpRules rules) {
        rules.post("/runs", authorization, this::createRun)
                .get("/runs", authorization, this::listRuns)
                .get("/runs/{runId}", authorization, this::getRun)
                .delete("/runs/{runId}", authorization, this::deleteRun);
    }

    @Override
    public void afterStop() {
        engine.close();
    }

    private static JsonObject requestBody(ServerRequest request) {
        if (!request.content().hasEntity()) {
            throw ChaosRequestException.badRequest("", "missing-body", "A JSON request body is required.");
        }
        try {
            return request.content().as(JsonObject.class);
        } catch (RuntimeException exception) {
            throw ChaosRequestException.badRequest("",
                                                    "malformed-json",
                                                    "The request body must contain one JSON object.",
                                                    exception);
        }
    }

    private static UUID runId(ServerRequest request) {
        String value = request.path().pathParameters().get("runId");
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("UUID is not in canonical form");
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw ChaosRequestException.badRequest("/runId", "invalid-run-id", "runId must be a UUID.", exception);
        }
    }

    private static ChaosRunEngine.NotFoundException notFound(UUID id) {
        return new ChaosRunEngine.NotFoundException(id);
    }

    private static String instance(ServerRequest request) {
        return request.path().absolute().path();
    }

    private static void sendJson(ServerResponse response, Status status, Object body) {
        response.status(status)
                .header(HeaderNames.CONTENT_TYPE, MediaTypes.APPLICATION_JSON.text())
                .send(body);
    }

    private static void sendProblem(ServerResponse response, ChaosProblemJson.Problem problem) {
        byte[] body = problem.body().toString().getBytes(StandardCharsets.UTF_8);
        response.status(problem.status())
                .header(HeaderNames.CONTENT_TYPE, PROBLEM_JSON.text());
        response.contentLength(body.length);
        response.send(body);
    }

    private void createRun(ServerRequest request, ServerResponse response) {
        bounded(request, response, () -> {
            if (!request.headers().testContentType(MediaTypes.APPLICATION_JSON)) {
                sendProblem(response, ChaosProblemJson.unsupportedMediaType(instance(request)));
                return;
            }
            JsonObject body = requestBody(request);
            ChaosRunPlan plan = ChaosRunPlanJson.parse(body, limits);
            ChaosRunView run = engine.create(plan, actor(request));
            response.header(HeaderNames.LOCATION, RUNS_PATH + run.id());
            sendJson(response, Status.CREATED_201, ChaosRunJson.toJson(run));
        });
    }

    private void listRuns(ServerRequest request, ServerResponse response) {
        bounded(request,
                response,
                () -> sendJson(response, Status.OK_200, ChaosRunJson.toJson(engine.list())));
    }

    private void getRun(ServerRequest request, ServerResponse response) {
        bounded(request, response, () -> {
            UUID id = runId(request);
            ChaosRunView run = engine.get(id).orElseThrow(() -> notFound(id));
            sendJson(response, Status.OK_200, ChaosRunJson.toJson(run));
        });
    }

    private void deleteRun(ServerRequest request, ServerResponse response) {
        bounded(request, response, () -> {
            ChaosRunView run = engine.stop(runId(request));
            Status status = run.state() == STOPPING ? Status.ACCEPTED_202 : Status.OK_200;
            sendJson(response, status, ChaosRunJson.toJson(run));
        });
    }

    private void bounded(ServerRequest request, ServerResponse response, Operation operation) {
        String instance = instance(request);
        if (!capacity.tryAcquire()) {
            sendProblem(response, ChaosProblemJson.controlCapacity(instance));
            return;
        }
        try {
            operation.execute();
        } catch (RuntimeException exception) {
            sendProblem(response, ChaosProblemJson.from(exception, instance));
        } finally {
            capacity.release();
        }
    }

    private String actor(ServerRequest request) {
        if (anonymousLoopback) {
            return "anonymous-local";
        }
        String actor = request.context()
                .get(SecurityContext.class)
                .filter(SecurityContext::isAuthenticated)
                .map(SecurityContext::userName)
                .orElseThrow(() -> new IllegalStateException("Authenticated security context is unavailable"));
        if (actor.isBlank()) {
            throw new IllegalStateException("Authenticated principal name is blank");
        }
        return actor;
    }

    @FunctionalInterface
    private interface Operation {
        void execute();
    }
}
