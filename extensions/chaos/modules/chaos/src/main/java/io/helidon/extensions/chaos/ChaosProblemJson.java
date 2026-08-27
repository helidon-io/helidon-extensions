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

import java.util.List;
import java.util.Objects;

import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;

/**
 * Safe RFC 9457 problem representations for the control API.
 */
final class ChaosProblemJson {
    private static final String PROBLEM_PATH = "/chaos/v1/problems/";

    private ChaosProblemJson() {
    }

    static Problem from(Throwable error, String instance) {
        Objects.requireNonNull(error);
        if (error instanceof ChaosRequestException request) {
            return problem(request.status(),
                           request.problemType(),
                           request.title(),
                           request.getMessage(),
                           instance,
                           request.violations());
        }
        if (error instanceof ChaosRunEngine.ConflictException) {
            return problem(409,
                           "run-conflict",
                           "Chaos run conflict",
                           "The run conflicts with the current local chaos engine state.",
                           instance,
                           List.of());
        }
        if (error instanceof ChaosRunEngine.NotFoundException) {
            return problem(404,
                           "run-not-found",
                           "Chaos run not found",
                           "The requested chaos run does not exist or is no longer retained.",
                           instance,
                           List.of());
        }
        return problem(500,
                       "internal-error",
                       "Internal chaos error",
                       "The chaos request could not be completed.",
                       instance,
                       List.of());
    }

    static Problem unsupportedMediaType(String instance) {
        return problem(415,
                       "unsupported-media-type",
                       "Unsupported media type",
                       "Chaos run creation requires an application/json request body.",
                       instance,
                       List.of());
    }

    static Problem controlCapacity(String instance) {
        return problem(429,
                       "control-capacity",
                       "Chaos control capacity exhausted",
                       "The bounded chaos control request capacity is currently exhausted.",
                       instance,
                       List.of());
    }

    private static Problem problem(int status,
                                   String type,
                                   String title,
                                   String detail,
                                   String instance,
                                   List<ChaosViolation> violations) {
        JsonObject.Builder body = JsonObject.builder()
                .set("type", PROBLEM_PATH + type)
                .set("title", title)
                .set("status", status)
                .set("detail", detail)
                .set("instance", instance);
        if (!violations.isEmpty()) {
            body.set("violations", JsonArray.create(violations.stream()
                                                      .map(ChaosProblemJson::violation)
                                                      .toList()));
        }
        return new Problem(status, body.build());
    }

    private static JsonObject violation(ChaosViolation violation) {
        return JsonObject.builder()
                .set("path", violation.path())
                .set("code", violation.code())
                .set("message", violation.message())
                .build();
    }

    /**
     * HTTP status and body for one problem response.
     *
     * @param status HTTP status
     * @param body RFC 9457 body
     */
    record Problem(int status, JsonObject body) {
    }
}
