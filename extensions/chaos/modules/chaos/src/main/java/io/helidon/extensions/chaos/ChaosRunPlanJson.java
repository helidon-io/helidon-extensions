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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.helidon.common.media.type.MediaType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.extensions.chaos.ChaosActivation.PeriodicBurstActivation;
import io.helidon.extensions.chaos.ChaosActivation.ProbabilityActivation;
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
    private static final Pattern URI_SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*");
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
        if (stages.size() == 0) {
            throw invalid("/stages", "stage-count", "At least one stage is required.");
        }
        if (stages.size() > limits.maximumStagesPerRun()) {
            throw invalid("/stages", "stage-limit", "Stage count exceeds the server limit.");
        }
        Set<String> stageNames = new LinkedHashSet<>();
        var parsedStages = new ArrayList<ChaosRunPlan.ChaosStage>(stages.size());
        Duration totalDuration = Duration.ZERO;
        for (int index = 0; index < stages.size(); index++) {
            String path = "/stages/" + index;
            ChaosRunPlan.ChaosStage stage = stage(requiredObject(stages.get(index).orElseThrow(), path),
                                                   path,
                                                   limits);
            if (!stageNames.add(stage.name())) {
                throw invalid(path + "/name", "duplicate-name", "Stage names must be unique within a run.");
            }
            try {
                totalDuration = totalDuration.plus(stage.duration());
            } catch (ArithmeticException exception) {
                throw invalid(path + "/duration", "duration-limit",
                              "Cumulative stage duration is too large.", exception);
            }
            if (totalDuration.compareTo(maximumDuration) > 0) {
                throw invalid(path + "/duration", "duration-limit",
                              "Cumulative stage duration exceeds maximumDuration.");
            }
            parsedStages.add(stage);
        }
        return new ChaosRunPlan(name, maximumDuration, seed, parsedStages);
    }

    private static ChaosRunPlan.ChaosStage stage(JsonObject json,
                                                  String path,
                                                  ChaosLimitsConfig limits) {
        rejectUnknown(json, path, Set.of("name", "duration", "disruptions"));
        String name = requiredNonBlank(json, "name", path + "/name");
        Duration duration = duration(json, "duration", path + "/duration");
        requirePositive(duration, path + "/duration");

        JsonArray disruptions = requiredArray(json, "disruptions", path + "/disruptions");
        if (disruptions.size() > 1) {
            throw invalid(path + "/disruptions", "disruption-count", "At most one disruption is supported per stage.");
        }
        Optional<ChaosRunPlan.ChaosDisruption> disruption = disruptions.size() == 0
                ? Optional.empty()
                : Optional.of(disruption(requiredObject(disruptions.get(0).orElseThrow(), path + "/disruptions/0"),
                                         path + "/disruptions/0",
                                         limits));
        return new ChaosRunPlan.ChaosStage(name,
                                           duration,
                                           disruption);
    }

    private static ChaosRunPlan.ChaosDisruption disruption(JsonObject json,
                                                           String path,
                                                           ChaosLimitsConfig limits) {
        rejectUnknown(json, path, Set.of("name", "scope", "activation", "effect", "budget"));
        String name = requiredNonBlank(json, "name", path + "/name");
        ChaosScope scope = scope(requiredObject(json, "scope", path + "/scope"), path + "/scope");
        ChaosActivation activation = activation(requiredObject(json, "activation", path + "/activation"),
                                                  path + "/activation");
        ChaosEffect effect = effect(requiredObject(json, "effect", path + "/effect"),
                                    path + "/effect",
                                    limits);
        validateEffectForScope(scope, effect, path + "/effect");
        ChaosBudget budget = budget(requiredObject(json, "budget", path + "/budget"), path + "/budget", limits);
        return new ChaosRunPlan.ChaosDisruption(name, scope, activation, effect, budget);
    }

    private static ChaosScope scope(JsonObject json, String path) {
        rejectUnknown(json, path, Set.of("type", "methods", "scheme", "host", "port", "path"));
        String type = requiredString(json, "type", path + "/type");
        return switch (type) {
        case "inbound-http" -> inboundHttpScope(json, path);
        case "outbound-http" -> outboundHttpScope(json, path);
        default -> throw invalid(path + "/type", "unsupported-type",
                                 "Scope type must be inbound-http or outbound-http.");
        };
    }

    private static ChaosHttpScope inboundHttpScope(JsonObject json, String path) {
        rejectUnknown(json, path, Set.of("type", "methods", "path"));
        return new ChaosHttpScope(methods(json, path), pathMatch(json, path), pathValue(json, path));
    }

    private static ChaosOutboundHttpScope outboundHttpScope(JsonObject json, String path) {
        rejectUnknown(json, path, Set.of("type", "methods", "scheme", "host", "port", "path"));
        String scheme = scheme(json, path);
        String host = host(json, path);
        long port = integer(json, "port", path + "/port");
        if (port < 1 || port > 65535) {
            throw invalid(path + "/port", "invalid-port", "port must be between 1 and 65535.");
        }
        return new ChaosOutboundHttpScope(methods(json, path),
                                          scheme,
                                          host,
                                          (int) port,
                                          pathMatch(json, path),
                                          pathValue(json, path));
    }

    private static Set<String> methods(JsonObject json, String path) {
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
        return methods;
    }

    private static ChaosHttpScope.PathMatch pathMatch(JsonObject json, String path) {
        JsonObject pathJson = requiredObject(json, "path", path + "/path");
        rejectUnknown(pathJson, path + "/path", Set.of("match", "value"));
        String match = requiredString(pathJson, "match", path + "/path/match");
        return switch (match) {
        case "exact" -> EXACT;
        case "prefix" -> PREFIX;
        default -> throw invalid(path + "/path/match", "unsupported-path-match",
                                 "Path match must be exact or prefix.");
        };
    }

    private static String pathValue(JsonObject json, String path) {
        JsonObject pathJson = requiredObject(json, "path", path + "/path");
        rejectUnknown(pathJson, path + "/path", Set.of("match", "value"));
        String value = requiredString(pathJson, "value", path + "/path/value");
        validatePath(value, path + "/path/value");
        return value;
    }

    private static String scheme(JsonObject json, String path) {
        String value = requiredString(json, "scheme", path + "/scheme");
        if (!URI_SCHEME.matcher(value).matches()) {
            throw invalid(path + "/scheme", "invalid-scheme", "scheme must be a valid URI scheme.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String host(JsonObject json, String path) {
        String value = requiredString(json, "host", path + "/host");
        if (value.isBlank()) {
            throw invalid(path + "/host", "invalid-host", "host must be a valid URI host.");
        }
        try {
            URI uri = URI.create("//" + value);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || !value.equals(uri.getRawAuthority())
                    || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw invalid(path + "/host", "invalid-host", "host must be a valid URI host.");
            }
            return uri.getHost().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw invalid(path + "/host", "invalid-host", "host must be a valid URI host.", exception);
        }
    }

    private static ChaosActivation activation(JsonObject json, String path) {
        rejectUnknown(json, path, Set.of("type", "probability", "initialSkip", "cycleSize", "burstSize"));
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
            yield new ProbabilityActivation(normalized);
        }
        case "periodic-burst" -> {
            rejectUnknown(json, path, Set.of("type", "initialSkip", "cycleSize", "burstSize"));
            long initialSkip = json.containsKey("initialSkip")
                    ? integer(json, "initialSkip", path + "/initialSkip")
                    : 0;
            long cycleSize = integer(json, "cycleSize", path + "/cycleSize");
            long burstSize = integer(json, "burstSize", path + "/burstSize");
            if (initialSkip < 0) {
                throw invalid(path + "/initialSkip", "invalid-initial-skip",
                              "initialSkip must not be negative.");
            }
            if (cycleSize <= 0) {
                throw invalid(path + "/cycleSize", "invalid-cycle-size", "cycleSize must be positive.");
            }
            if (burstSize <= 0 || burstSize > cycleSize) {
                throw invalid(path + "/burstSize", "invalid-burst-size",
                              "burstSize must be positive and at most cycleSize.");
            }
            yield new PeriodicBurstActivation(initialSkip, cycleSize, burstSize);
        }
        default -> throw invalid(path + "/type", "unsupported-type",
                                 "Activation type must be always, probability, or periodic-burst.");
        };
    }

    private static ChaosEffect effect(JsonObject json,
                                      String path,
                                      ChaosLimitsConfig limits) {
        return effect(json, path, limits, true);
    }

    private static ChaosEffect effect(JsonObject json,
                                      String path,
                                      ChaosLimitsConfig limits,
                                      boolean weightedChoiceAllowed) {
        rejectUnknown(json, path,
                      Set.of("type", "status", "headers", "mediaType", "body", "delay", "jitter", "outcomes"));
        String type = requiredString(json, "type", path + "/type");
        return switch (type) {
        case "connect-failure" -> connectFailure(json, path);
        case "latency" -> latency(json, path, limits);
        case "synthetic-http-response" -> syntheticResponse(json, path, limits);
        case "weighted-choice" -> {
            if (!weightedChoiceAllowed) {
                throw invalid(path + "/type", "nested-weighted-choice",
                              "weighted-choice outcomes must be latency, synthetic-http-response, or connect-failure.");
            }
            yield weightedChoice(json, path, limits);
        }
        default -> throw invalid(path + "/type", "unsupported-type",
                                 "Effect type must be connect-failure, latency, synthetic-http-response, or weighted-choice.");
        };
    }

    private static ChaosConnectFailure connectFailure(JsonObject json, String path) {
        rejectUnknown(json, path, Set.of("type"));
        return ChaosConnectFailure.instance();
    }

    private static ChaosWeightedChoice weightedChoice(JsonObject json,
                                                       String path,
                                                       ChaosLimitsConfig limits) {
        rejectUnknown(json, path, Set.of("type", "outcomes"));
        JsonArray values = requiredArray(json, "outcomes", path + "/outcomes");
        if (values.size() == 0) {
            throw invalid(path + "/outcomes", "empty-outcomes", "At least one weighted outcome is required.");
        }
        long totalWeight = 0;
        var outcomes = new ArrayList<ChaosWeightedChoice.Outcome>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String outcomePath = path + "/outcomes/" + index;
            JsonObject value = requiredObject(values.get(index).orElseThrow(), outcomePath);
            rejectUnknown(value, outcomePath, Set.of("weight", "effect"));
            long weight = integer(value, "weight", outcomePath + "/weight");
            if (weight <= 0) {
                throw invalid(outcomePath + "/weight", "invalid-weight", "weight must be positive.");
            }
            try {
                totalWeight = Math.addExact(totalWeight, weight);
            } catch (ArithmeticException exception) {
                throw invalid(path + "/outcomes", "weight-total", "The outcome weight total is too large.", exception);
            }
            ChaosEffect outcomeEffect = effect(requiredObject(value, "effect", outcomePath + "/effect"),
                                                 outcomePath + "/effect",
                                                 limits,
                                                 false);
            outcomes.add(new ChaosWeightedChoice.Outcome(weight, outcomeEffect));
        }
        return new ChaosWeightedChoice(outcomes);
    }

    private static void validateEffectForScope(ChaosScope scope, ChaosEffect effect, String path) {
        if (scope instanceof ChaosHttpScope && effect instanceof ChaosConnectFailure) {
            throw invalid(path + "/type", "unsupported-inbound-effect",
                          "connect-failure is supported only for outbound-http scopes.");
        }
        if (effect instanceof ChaosWeightedChoice choice) {
            for (int index = 0; index < choice.outcomes().size(); index++) {
                validateEffectForScope(scope,
                                       choice.outcomes().get(index).effect(),
                                       path + "/outcomes/" + index + "/effect");
            }
        }
    }

    private static ChaosLatency latency(JsonObject json, String path, ChaosLimitsConfig limits) {
        rejectUnknown(json, path, Set.of("type", "delay", "jitter"));
        Duration delay = duration(json, "delay", path + "/delay");
        requirePositive(delay, path + "/delay");
        if (delay.compareTo(limits.maximumLatency()) > 0) {
            throw invalid(path + "/delay", "latency-limit", "delay exceeds the server latency limit.");
        }
        Duration jitter = json.containsKey("jitter") ? duration(json, "jitter", path + "/jitter") : Duration.ZERO;
        if (jitter.isNegative()) {
            throw invalid(path + "/jitter", "invalid-jitter", "jitter must not be negative.");
        }
        if (jitter.compareTo(delay) > 0) {
            throw invalid(path + "/jitter", "invalid-jitter", "jitter must not exceed delay.");
        }
        Duration maximumDelay;
        try {
            maximumDelay = delay.plus(jitter);
        } catch (ArithmeticException exception) {
            throw invalid(path + "/jitter", "latency-limit", "delay plus jitter is too large.", exception);
        }
        if (maximumDelay.compareTo(limits.maximumLatency()) > 0) {
            throw invalid(path + "/jitter", "latency-limit",
                          "delay plus jitter exceeds the server latency limit.");
        }
        return new ChaosLatency(delay, jitter);
    }

    private static ChaosSyntheticResponse syntheticResponse(JsonObject json, String path, ChaosLimitsConfig limits) {
        rejectUnknown(json, path, Set.of("type", "status", "headers", "mediaType", "body"));
        long statusValue = integer(json, "status", path + "/status");
        if (statusValue < 400 || statusValue > 599) {
            throw invalid(path + "/status", "invalid-status", "Synthetic status must be between 400 and 599.");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        Set<String> headerNames = new LinkedHashSet<>();
        if (json.containsKey("headers")) {
            JsonObject headerJson = requiredObject(json, "headers", path + "/headers");
            for (String name : headerJson.keysAsStrings()) {
                String headerPath = path + "/headers/" + pointerToken(name);
                validateHeaderName(name, headerPath);
                if (!headerNames.add(name.toLowerCase(Locale.ROOT))) {
                    throw invalid(headerPath, "duplicate-header", "Header name must be unique ignoring case.");
                }
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
            } catch (RuntimeException exception) {
                throw invalid(path + "/mediaType", "invalid-media-type", "mediaType is not valid.", exception);
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

    private static void validatePath(String value, String path) {
        if (!value.startsWith("/") || value.contains("?") || value.contains("#") || value.contains("%")) {
            throw invalid(path, "invalid-path", "Path must be an absolute decoded path without query or fragment.");
        }
        try {
            URI uri = URI.create(value);
            if (!value.equals(uri.normalize().getPath()) || value.contains("//")) {
                throw invalid(path, "non-normalized-path", "Path must be normalized.");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(path, "invalid-path", "Path must be a valid absolute path.", exception);
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
        } catch (DateTimeParseException exception) {
            throw invalid(path, "invalid-duration", "Value must be an ISO-8601 duration.", exception);
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
        } catch (RuntimeException exception) {
            throw bad(path, "invalid-type", "Value must be an integer.", exception);
        }
    }

    private static BigDecimal number(JsonObject json, String name, String path) {
        JsonValue value = requiredValue(json, name, path);
        try {
            return value.asNumber().bigDecimalValue();
        } catch (RuntimeException exception) {
            throw bad(path, "invalid-type", "Value must be a number.", exception);
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
        } catch (RuntimeException exception) {
            throw bad(path, "invalid-type", "Value must be a string.", exception);
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
        } catch (RuntimeException exception) {
            throw bad(path, "invalid-type", "Value must be an array.", exception);
        }
    }

    private static JsonObject requiredObject(JsonObject json, String name, String path) {
        return requiredObject(requiredValue(json, name, path), path);
    }

    private static JsonObject requiredObject(JsonValue value, String path) {
        try {
            return value.asObject();
        } catch (RuntimeException exception) {
            throw bad(path, "invalid-type", "Value must be an object.", exception);
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

    private static ChaosRequestException bad(String path, String code, String message, Throwable cause) {
        return ChaosRequestException.badRequest(path, code, message, cause);
    }

    private static ChaosRequestException invalid(String path, String code, String message) {
        return ChaosRequestException.invalidPlan(path, code, message);
    }

    private static ChaosRequestException invalid(String path, String code, String message, Throwable cause) {
        return ChaosRequestException.invalidPlan(path, code, message, cause);
    }
}
