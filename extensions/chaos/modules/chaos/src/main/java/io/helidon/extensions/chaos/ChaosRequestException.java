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

/**
 * Safe request error that can be converted to an RFC 9457 response.
 */
final class ChaosRequestException extends RuntimeException {

    private final int status;
    private final String problemType;
    private final String title;
    private final List<ChaosViolation> violations;

    private ChaosRequestException(int status,
                                  String problemType,
                                  String title,
                                  String detail,
                                  List<ChaosViolation> violations,
                                  Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.problemType = problemType;
        this.title = title;
        this.violations = List.copyOf(violations);
    }

    static ChaosRequestException badRequest(String path, String code, String message) {
        return badRequest(path, code, message, null);
    }

    static ChaosRequestException badRequest(String path, String code, String message, Throwable cause) {
        return new ChaosRequestException(400,
                                         "invalid-request",
                                         "Invalid chaos request",
                                         "The request body is not a valid chaos run request.",
                                         List.of(new ChaosViolation(path, code, message)),
                                         cause);
    }

    static ChaosRequestException invalidPlan(String path, String code, String message) {
        return invalidPlan(path, code, message, null);
    }

    static ChaosRequestException invalidPlan(String path, String code, String message, Throwable cause) {
        return new ChaosRequestException(422,
                                         "invalid-plan",
                                         "Invalid chaos run plan",
                                         "The run plan violates one or more constraints.",
                                         List.of(new ChaosViolation(path, code, message)),
                                         cause);
    }

    int status() {
        return status;
    }

    String problemType() {
        return problemType;
    }

    String title() {
        return title;
    }

    List<ChaosViolation> violations() {
        return violations;
    }
}
