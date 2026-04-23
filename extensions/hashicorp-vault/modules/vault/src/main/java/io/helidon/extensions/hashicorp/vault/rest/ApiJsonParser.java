/*
 * Copyright (c) 2021, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.hashicorp.vault.rest;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.json.JsonObject;
import io.helidon.json.JsonValue;
import io.helidon.json.JsonValueType;

/**
 * Helper methods to process a returned JSON.
 */
public abstract class ApiJsonParser {
    /**
     * Get a string value from a json value.
     *
     * @param value Json value
     * @return string representation of the value.
     * @throws io.helidon.extensions.hashicorp.vault.rest.ApiException in case the value is array or object
     */
    protected static String stringValue(JsonValue value) {
        return switch (value.type()) {
            case ARRAY -> throw new ApiException("Cannot create a simple String from an array: " + value);
            case OBJECT -> throw new ApiException("Cannot create a simple String from an object: " + value);
            case STRING -> value.asString().value();
            case BOOLEAN, NUMBER -> value.toString();
            case NULL -> "null";
            default -> throw new ApiException("Cannot create a simple String from an unknown value: " + value);
        };
    }

    /**
     * Convert a JSON array in the JSON object to a list of strings.
     *
     * @param json JSON object
     * @param name name of the array in the object
     * @return list from the array, or empty if the array does not exist or is null
     */
    protected static List<String> toList(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(JsonValue::asArray)
                .map(jsonArray -> {
                    List<String> result = new LinkedList<>();
                    for (JsonValue jsonValue : jsonArray.values()) {
                        result.add(stringValue(jsonValue));
                    }
                    return List.copyOf(result);
                }).orElseGet(List::of);
    }

    /**
     * Get bytes from a base64 string value.
     *
     * @param json JSON object
     * @param name name of the property
     * @return bytes or empty if the property does not exist or is null
     */
    protected static Optional<byte[]> toBytesBase64(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(value -> Base64.getDecoder().decode(value.asString().value()));
    }

    /**
     * Get a child JSON object.
     *
     * @param json JSON object
     * @param name name of the property
     * @return JSON object or empty if the property does not exist or is null
     */
    protected static Optional<JsonObject> toObject(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(JsonValue::asObject);
    }

    /**
     * Get a string value.
     *
     * @param json JSON object
     * @param name name of the property
     * @return string or empty if the property does not exist or is null
     */
    protected static Optional<String> toString(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(value -> value.asString().value());
    }

    /**
     * Get an int value.
     *
     * @param json JSON object
     * @param name name of the property
     * @return int or empty if the property does not exist or is null
     */
    protected static Optional<Integer> toInt(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(value -> value.asNumber().intValue());
    }

    /**
     * Get a long value.
     *
     * @param json JSON object
     * @param name name of the property
     * @return long or empty if the property does not exist or is null
     */
    protected static Optional<Long> toLong(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(value -> value.asNumber().longValue());
    }

    /**
     * Get a double value.
     *
     * @param json JSON object
     * @param name name of the property
     * @return double or empty if the property does not exist or is null
     */
    protected static Optional<Double> toDouble(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(value -> value.asNumber().doubleValue());
    }

    /**
     * Get a boolean value.
     *
     * @param json JSON object
     * @param name name of the property
     * @return boolean or empty if the property does not exist or is null
     */
    protected static Optional<Boolean> toBoolean(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(value -> value.asBoolean().value());
    }

    /**
     * Get an {@link java.time.Instant} value.
     *
     * @param json      JSON object
     * @param name      name of the property
     * @param formatter to use when parsing the string value
     * @return instant or empty if the property does not exist or is null
     */
    protected static Optional<Instant> toInstant(JsonObject json, String name, DateTimeFormatter formatter) {
        return optionalValue(json, name)
                .map(value -> value.asString().value())
                .flatMap(timeString -> {
                    if (timeString.isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(Instant.from(formatter.parse(timeString)));
                });
    }

    /**
     * Get a map value.
     *
     * @param json JSON object
     * @param name name of the property
     * @return map with property key/value pairs, or empty if the property does not exist or is null
     */
    protected static Map<String, String> toMap(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(JsonValue::asObject)
                .map(nested -> {
                    Map<String, String> map = new HashMap<>();
                    nested.keysAsStrings()
                            .forEach(key -> map.put(key, stringValue(nested.value(key).orElseThrow())));

                    return Map.copyOf(map);
                }).orElseGet(Map::of);

    }

    /**
     * If the property is present on the JSON object, returns a non-empty optional, otherwise returns an
     * empty.
     *
     * @param json JSON object
     * @param name name of the property
     * @return non-empty optional if the property exists and is not null
     */
    protected static Optional<Boolean> isPresent(JsonObject json, String name) {
        return optionalValue(json, name)
                .map(ignored -> true);
    }

    private static Optional<JsonValue> optionalValue(JsonObject json, String name) {
        return json.value(name)
                .filter(value -> value.type() != JsonValueType.NULL);
    }
}
