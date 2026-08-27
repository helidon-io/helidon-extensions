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

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.helidon.common.media.type.MediaType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonValue;

import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.EXACT;
import static io.helidon.extensions.chaos.ChaosHttpScope.PathMatch.PREFIX;

/**
 * Strict JSON boundary for the first Chaos run-plan slice.
 */
final class ChaosRunPlanJson {

    private static final SecureRandom SEED_SOURCE = new SecureRandom();
    private static final BigDecimal MINIMUM_PROBABILITY = BigDecimal.valueOf(0x1.0p-53);
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "connection",
            "content-length",
            "content-type",
            "cookie",
            "date",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "server",
            "set-cookie",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "via",
            "www-authenticate",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto");

    private ChaosRunPlanJson() {
    }

    static ChaosRunPlan parse(JsonObject json, ChaosLimitsConfig limits) {
        rejectUnknown(json, "", Set.of("name", "maximumDuration", "seed", "stages"));
        String name = requiredNonBlank(json, "name", "/name");
        Duration maximumDuration = duration(json, "maximumDuration", "/maximumDuration");
        requirePositive(maximumDuration, "/maximumDuration");
        if (maximumDuration.compareTo(limits.maximumRunDuration()) > 0) {
            throw invalid("/maximumDuration", "duration-limit", "maximumDuration exceeds the server limit.");
        }
        long seed = json.containsKey("seed") ? integer(json, "seed", "/seed") : SEED_SOURCE.nextLong();

        JsonArray stages = requiredArray(json, "stages", "/stages");
        if (stages.size() != 1) {
            throw invalid("/stages", "stage-count", "Exactly one stage is supported.");
        }
        ChaosRunPlan.ChaosStage stage = stage(requiredObject(stages.get(0).orElseThrow(), "/stages/0"),
                                               maximumDuration,
                                               limits);
        return new ChaosRunPlan(name, maximumDuration, seed, stage);
    }

    private static ChaosRunPlan.ChaosStage stage(JsonObject json,
                                                  Duration maximumDuration,
                                                  ChaosLimitsConfig limits) {
        String path = "/stages/0";
        rejectUnknown(json, path, Set.of("name", "duration", "disruptions"));
        String name = requiredNonBlank(json, "name", path + "/name");
        Duration duration = duration(json, "duration", path + "/duration");
        requirePositive(duration, path + "/duration");
        if (duration.compareTo(maximumDuration) > 0) {
            throw invalid(path + "/duration", "duration-limit", "Stage duration exceeds maximumDuration.");
        }

        JsonArray disruptions = requiredArray(json, "disruptions", path + "/disruptions");
        if (disruptions.size() != 1) {
            throw invalid(path + "/disruptions", "disruption-count", "Exactly one disruption is supported.");
        }
        return new ChaosRunPlan.ChaosStage(name,
                                           duration,
                                           disruption(requiredObject(disruptions.get(0).orElseThrow(),
                                                                     path + "/disruptions/0"),
                                                      limits));
    }

    private static ChaosRunPlan.ChaosDisruption disruption(JsonObject json, ChaosLimitsConfig limits) {
        String path = "/stages/0/disruptions/0";
        rejectUnknown(json, path, Set.of("name", "scope", "activation", "effect", "budget"));
        String name = requiredNonBlank(json, "name", path + "/name");
        ChaosHttpScope scope = scope(requiredObject(json, "scope", path + "/scope"), path + "/scope");
        ChaosActivation activation = activation(requiredObject(json, "activation", path + "/activation"),
                                                  path + "/activation");
        ChaosEffect effect = effect(requiredObject(json, "effect", path + "/effect"),
                                    path + "/effect",
                                    limits);
        ChaosBudget budget = budget(requiredObject(json, "budget", path + "/budget"), path + "/budget", limits);
        return new ChaosRunPlan.ChaosDisruption(name, scope, activation, effect, budget);
    }

    private static ChaosHttpScope scope(JsonObject json, String path) {
        rejectUnknown(json, path, Set.of("type", "methods", "path"));
        requireType(json, "type", "inbound-http", path + "/type");
        JsonArray methodsJson = requiredArray(json, "methods", path + "/methods");
        if (methodsJson.size() == 0) {
            throw invalid(path + "/methods", "empty-methods", "At least one HTTP method is required.");
        }
        Set<String> methods = new LinkedHashSet<>();
        for (int i = 0; i < methodsJson.size(); i++) {
            String method = requiredString(methodsJson.get(i).orElseThrow(), path + "/methods/" + i)
                    .toUpperCase(Locale.ROOT);
            if (!TOKEN.matcher(method).matches()) {
                throw invalid(path + "/methods/" + i, "invalid-method", "HTTP method is not a valid token.");
            }
            methods.add(method);
        }

        JsonObject pathJson = requiredObject(json, "path", path + "/path");
        rejectUnknown(pathJson, path + "/path", Set.of("match", "value"));
        String match = requiredString(pathJson, "match", path + "/path/match");
        ChaosHttpScope.PathMatch pathMatch = switch (match) {
        case "exact" -> EXACT;
        case "prefix" -> PREFIX;
        default -> throw invalid(path + "/path/match", "unsupported-path-match",
                                 "Path match must be exact or prefix.");
        };
        String value = requiredString(pathJson, "value", path + "/path/value");
        validatePath(value, path + "/path/value");
        return new ChaosHttpScope(methods, pathMatch, value);
    }

    private static ChaosActivation activation(JsonObject json, String path) {
        rejectUnknown(json, path, Set.of("type", "probability"));
        String type = requiredString(json, "type", path + "/type");
        return switch (type) {
        case "always" -> {
            rejectUnknown(json, path, Set.of("type"));
            yield ChaosActivation.always();
        }
        case "probability" -> {
            rejectUnknown(json, path, Set.of("type", "probability"));
            BigDecimal probability = number(json, "probability", path + "/probability");
            if (probability.compareTo(BigDecimal.ZERO) <= 0 || probability.compareTo(BigDecimal.ONE) > 0) {
                throw invalid(path + "/probability", "invalid-probability",
                              "probability must be greater than zero and at most one.");
            }
            if (probability.compareTo(MINIMUM_PROBABILITY) < 0) {
                throw invalid(path + "/probability", "probability-precision",
                              "probability must be at least 2^-53.");
            }
            double normalized = probability.doubleValue();
            if (normalized == 1 && probability.compareTo(BigDecimal.ONE) < 0) {
                throw invalid(path + "/probability", "probability-precision",
                              "probability is too close to one for the supported numeric precision.");
            }
            yield new ChaosActivation.Probability(normalized);
        }
        default -> throw invalid(path + "/type", "unsupported-type",
                                 "Activation type must be always or probability.");
        };
    }

    private static ChaosEffect effect(JsonObject json, String path, ChaosLimitsConfig limits) {
        rejectUnknown(json, path, Set.of("type", "status", "headers", "mediaType", "body"));
        String type = requiredString(json, "type", path + "/type");
        return switch (type) {
        case "synthetic-http-response" -> syntheticResponse(json, path, limits);
        default -> throw invalid(path + "/type", "unsupported-type",
                                 "Effect type must be synthetic-http-response.");
        };
    }

    private static ChaosSyntheticResponse syntheticResponse(JsonObject json, String path, ChaosLimitsConfig limits) {
        rejectUnknown(json, path, Set.of("type", "status", "headers", "mediaType", "body"));
        long statusValue = integer(json, "status", path + "/status");
        if (statusValue < 400 || statusValue > 599) {
            throw invalid(path + "/status", "invalid-status", "Synthetic status must be between 400 and 599.");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (json.containsKey("headers")) {
            JsonObject headerJson = requiredObject(json, "headers", path + "/headers");
            for (String name : headerJson.keysAsStrings()) {
                String headerPath = path + "/headers/" + pointerToken(name);
                validateHeaderName(name, headerPath);
                String value = requiredString(headerJson, name, headerPath);
                validateHeaderValue(value, headerPath);
                headers.put(name, value);
            }
        }

        Optional<MediaType> mediaType = Optional.empty();
        if (json.containsKey("mediaType")) {
            String value = requiredString(json, "mediaType", path + "/mediaType");
            try {
                mediaType = Optional.of(MediaTypes.create(value));
            } catch (RuntimeException e) {
                throw invalid(path + "/mediaType", "invalid-media-type", "mediaType is not valid.");
            }
        }

        String body = json.containsKey("body") ? requiredString(json, "body", path + "/body") : "";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        if (bodyBytes.length > limits.maximumSyntheticBodyBytes()) {
            throw invalid(path + "/body", "body-limit", "Synthetic body exceeds the server byte limit.");
        }
        return new ChaosSyntheticResponse((int) statusValue, headers, mediaType, bodyBytes);
    }

    private static ChaosBudget budget(JsonObject json, String path, ChaosLimitsConfig limits) {
        rejectUnknown(json, path, Set.of("maximumActivations", "maximumConcurrent"));
        long maximumActivations = integer(json, "maximumActivations", path + "/maximumActivations");
        long maximumConcurrent = integer(json, "maximumConcurrent", path + "/maximumConcurrent");
        if (maximumActivations <= 0) {
            throw invalid(path + "/maximumActivations", "invalid-budget", "maximumActivations must be positive.");
        }
        if (maximumActivations > limits.maximumActivationsPerDisruption()) {
            throw invalid(path + "/maximumActivations", "budget-limit",
                          "maximumActivations exceeds the server limit.");
        }
        if (maximumConcurrent <= 0) {
            throw invalid(path + "/maximumConcurrent", "invalid-budget", "maximumConcurrent must be positive.");
        }
        if (maximumConcurrent > limits.maximumConcurrentActivationsPerDisruption()) {
            throw invalid(path + "/maximumConcurrent", "budget-limit",
                          "maximumConcurrent exceeds the server limit.");
        }
        if (maximumConcurrent > Integer.MAX_VALUE) {
            throw invalid(path + "/maximumConcurrent", "integer-range", "maximumConcurrent is too large.");
        }
        return new ChaosBudget(maximumActivations, (int) maximumConcurrent);
    }

    private static void requireType(JsonObject json, String name, String expected, String path) {
        String actual = requiredString(json, name, path);
        if (!expected.equals(actual)) {
            throw invalid(path, "unsupported-type", "Only " + expected + " is supported in this release.");
        }
    }

    private static void validatePath(String value, String path) {
        if (!value.startsWith("/") || value.contains("?") || value.contains("#") || value.contains("%")) {
            throw invalid(path, "invalid-path", "Path must be an absolute decoded path without query or fragment.");
        }
        try {
            URI uri = URI.create(value);
            if (!value.equals(uri.normalize().getPath()) || value.contains("//")) {
                throw invalid(path, "non-normalized-path", "Path must be normalized.");
            }
        } catch (IllegalArgumentException e) {
            throw invalid(path, "invalid-path", "Path must be a valid absolute path.");
        }
        if (value.equals("/chaos") || value.startsWith("/chaos/")) {
            throw invalid(path, "control-path", "Chaos control paths cannot be disrupted.");
        }
    }

    private static void validateHeaderName(String name, String path) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!TOKEN.matcher(name).matches()) {
            throw invalid(path, "invalid-header-name", "Header name is not a valid HTTP token.");
        }
        if (FORBIDDEN_HEADERS.contains(normalized)) {
            throw invalid(path, "forbidden-header", "Header is controlled by the server and cannot be supplied.");
        }
    }

    private static void validateHeaderValue(String value, String path) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character <= 0x1f && character != '\t') || character == 0x7f) {
                throw invalid(path, "invalid-header-value", "Header value contains a forbidden control character.");
            }
        }
    }

    private static Duration duration(JsonObject json, String name, String path) {
        String value = requiredString(json, name, path);
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(path, "invalid-duration", "Value must be an ISO-8601 duration.");
        }
    }

    private static void requirePositive(Duration value, String path) {
        if (value.isZero() || value.isNegative()) {
            throw invalid(path, "invalid-duration", "Duration must be positive.");
        }
    }

    private static long integer(JsonObject json, String name, String path) {
        JsonValue value = requiredValue(json, name, path);
        try {
            BigDecimal number = value.asNumber().bigDecimalValue();
            return number.longValueExact();
        } catch (RuntimeException e) {
            throw bad(path, "invalid-type", "Value must be an integer.");
        }
    }

    private static BigDecimal number(JsonObject json, String name, String path) {
        JsonValue value = requiredValue(json, name, path);
        try {
            return value.asNumber().bigDecimalValue();
        } catch (RuntimeException e) {
            throw bad(path, "invalid-type", "Value must be a number.");
        }
    }

    private static String requiredNonBlank(JsonObject json, String name, String path) {
        String value = requiredString(json, name, path);
        if (value.isBlank()) {
            throw invalid(path, "blank-value", "Value must not be blank.");
        }
        return value;
    }

    private static String requiredString(JsonObject json, String name, String path) {
        return requiredString(requiredValue(json, name, path), path);
    }

    private static String requiredString(JsonValue value, String path) {
        String result;
        try {
            result = value.asString().value();
        } catch (RuntimeException e) {
            throw bad(path, "invalid-type", "Value must be a string.");
        }
        validateUnicode(result, path);
        return result;
    }

    private static void validateUnicode(String value, String path) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw invalid(path, "invalid-unicode", "String contains an unpaired Unicode surrogate.");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw invalid(path, "invalid-unicode", "String contains an unpaired Unicode surrogate.");
            }
        }
    }

    private static JsonArray requiredArray(JsonObject json, String name, String path) {
        JsonValue value = requiredValue(json, name, path);
        try {
            return value.asArray();
        } catch (RuntimeException e) {
            throw bad(path, "invalid-type", "Value must be an array.");
        }
    }

    private static JsonObject requiredObject(JsonObject json, String name, String path) {
        return requiredObject(requiredValue(json, name, path), path);
    }

    private static JsonObject requiredObject(JsonValue value, String path) {
        try {
            return value.asObject();
        } catch (RuntimeException e) {
            throw bad(path, "invalid-type", "Value must be an object.");
        }
    }

    private static JsonValue requiredValue(JsonObject json, String name, String path) {
        return json.value(name).orElseThrow(() -> bad(path, "required-property", "Required property is missing."));
    }

    private static void rejectUnknown(JsonObject json, String path, Set<String> allowed) {
        for (String key : json.keysAsStrings()) {
            if (!allowed.contains(key)) {
                throw bad(path + "/" + pointerToken(key), "unknown-property", "Unknown property is not allowed.");
            }
        }
    }

    private static String pointerToken(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static ChaosRequestException bad(String path, String code, String message) {
        return ChaosRequestException.badRequest(path, code, message);
    }

    private static ChaosRequestException invalid(String path, String code, String message) {
        return ChaosRequestException.invalidPlan(path, code, message);
    }
}
