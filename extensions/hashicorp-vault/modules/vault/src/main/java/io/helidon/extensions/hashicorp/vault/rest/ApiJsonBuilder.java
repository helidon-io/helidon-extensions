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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.common.Base64Value;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonBoolean;
import io.helidon.json.JsonNumber;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonString;
import io.helidon.json.JsonValue;

/**
 * Common base class for builders that construct a JSON object.
 *
 * @param <T> type of the subclass
 */
public abstract class ApiJsonBuilder<T extends ApiJsonBuilder<T>> {
    private final Map<String, JsonValue> values = new LinkedHashMap<>();
    private final Map<String, ApiJsonBuilder<?>> objects = new LinkedHashMap<>();
    private final Map<String, List<JsonValue>> arrays = new LinkedHashMap<>();
    private final Map<String, List<ApiJsonBuilder<?>>> objectArrays = new LinkedHashMap<>();
    private final Map<String, Map<String, JsonValue>> objectsAsMaps = new LinkedHashMap<>();

    /**
     * Default constructor.
     */
    protected ApiJsonBuilder() {
        super();
    }

    /**
     * Create a JSON object from this builder.
     *
     * @return JSON object or empty
     */
    public Optional<JsonObject> toJson() {
        JsonObject.Builder payload = JsonObject.builder();
        preBuild(payload);
        values.forEach(payload::set);
        objects.forEach((key, value) -> value.toJson().ifPresent(it -> payload.set(key, it)));
        arrays.forEach((key, value) -> payload.set(key, JsonArray.create(List.copyOf(value))));
        objectArrays.forEach((key, value) -> addObjectArray(payload, key, value));
        objectsAsMaps.forEach((key, value) -> {
            JsonObject.Builder childObject = JsonObject.builder();
            value.forEach(childObject::set);
            payload.set(key, childObject.build());
        });
        postBuild(payload);
        return Optional.of(payload.build());
    }

    /**
     * Can be returned by subclasses that can be subclassed again.
     *
     * @return this instance as a subclass type
     */
    @SuppressWarnings("unchecked")
    protected T me() {
        return (T) this;
    }

    /**
     * Called before adding properties defined in this request.
     *
     * @param payload payload builder
     */
    protected void preBuild(JsonObject.Builder payload) {
    }

    /**
     * Called after adding properties defined in this request.
     *
     * @param payload payload builder
     */
    protected void postBuild(JsonObject.Builder payload) {
    }

    /**
     * Add an element to an array.
     *
     * @param name    key in the json payload
     * @param element element of the array
     * @return updated request
     */
    protected T addToArray(String name, String element) {
        arrays.computeIfAbsent(name, it -> new LinkedList<>())
                .add(JsonString.create(element));

        return me();
    }

    /**
     * Add an element to an array.
     *
     * @param name    key in the json payload
     * @param element element of the array
     * @return updated request
     */
    protected T addToArray(String name, int element) {
        arrays.computeIfAbsent(name, it -> new LinkedList<>())
                .add(JsonNumber.create(element));

        return me();
    }

    /**
     * Add an element to an array.
     *
     * @param name    key in the json payload
     * @param element element of the array
     * @return updated request
     */
    protected T addToArray(String name, long element) {
        arrays.computeIfAbsent(name, it -> new LinkedList<>())
                .add(JsonNumber.create(element));

        return me();
    }

    /**
     * Add an element to an array.
     *
     * @param name    key in the json payload
     * @param element element of the array
     * @return updated request
     */
    protected T addToArray(String name, double element) {
        arrays.computeIfAbsent(name, it -> new LinkedList<>())
                .add(JsonNumber.create(element));

        return me();
    }

    /**
     * Add an element to an array.
     *
     * @param name    key in the json payload
     * @param element element of the array
     * @return updated request
     */
    protected T addToArray(String name, boolean element) {
        arrays.computeIfAbsent(name, it -> new LinkedList<>())
                .add(JsonBoolean.create(element));

        return me();
    }

    /**
     * Add custom string to payload.
     * If such a name is already added, it will be replaced.
     *
     * @param name  json key
     * @param value json String value
     * @return updated request
     */
    protected T add(String name, String value) {
        values.put(name, JsonString.create(value));
        return me();
    }

    /**
     * Add custom int to payload.
     * If such a name is already added, it will be replaced.
     *
     * @param name  json key
     * @param value json value
     * @return updated request
     */
    protected T add(String name, int value) {
        values.put(name, JsonNumber.create(value));
        return me();
    }

    /**
     * Add custom double to payload.
     * If such a name is already added, it will be replaced.
     *
     * @param name  json key
     * @param value json value
     * @return updated request
     */
    protected T add(String name, double value) {
        values.put(name, JsonNumber.create(value));
        return me();
    }

    /**
     * Add custom long to payload.
     * If such a name is already added, it will be replaced.
     *
     * @param name  json key
     * @param value json value
     * @return updated request
     */
    protected T add(String name, long value) {
        values.put(name, JsonNumber.create(value));
        return me();
    }

    /**
     * Add custom boolean to payload.
     * If such a name is already added, it will be replaced.
     *
     * @param name  json key
     * @param value json value
     * @return updated request
     */
    protected T add(String name, boolean value) {
        values.put(name, JsonBoolean.create(value));
        return me();
    }

    /**
     * Add a custom object to payload.
     *
     * @param name   json key
     * @param object json value
     * @return updated request
     */
    protected T add(String name, ApiJsonBuilder<?> object) {
        objects.put(name, object);
        return me();
    }

    /**
     * Add a string encoded with base64.
     *
     * @param name        json key
     * @param base64Value base64 data
     * @return updated request
     */
    protected T addBase64(String name, Base64Value base64Value) {
        return add(name, base64Value.toBase64());
    }

    /**
     * Configure an empty array.
     *
     * @param name name of the property
     * @return updated builder
     */
    protected T emptyArray(String name) {
        arrays.put(name, new LinkedList<>());
        return me();
    }

    /**
     * Add an object to an array.
     *
     * @param name    name of the nested property
     * @param element a {@link ApiJsonBuilder} of the element of the array
     * @return updated builder
     */
    protected T addToArray(String name, ApiJsonBuilder<?> element) {
        objectArrays.computeIfAbsent(name, it -> new LinkedList<>())
                .add(element);

        return me();
    }

    /**
     * Add a key/value pair to a named object.
     *
     * @param name  name of the object to create under the root
     * @param key   key of the nested property
     * @param value value of the nested property
     * @return updated builder
     */
    protected T addToObject(String name, String key, String value) {
        objectsAsMaps.computeIfAbsent(name, it -> new LinkedHashMap<>())
                .put(key, JsonString.create(value));

        return me();
    }

    /**
     * Add a key/value pair to a named object.
     *
     * @param name  name of the object to create under the root
     * @param key   key of the nested property
     * @param value value of the nested property
     * @return updated builder
     */
    protected T addToObject(String name, String key, int value) {
        objectsAsMaps.computeIfAbsent(name, it -> new LinkedHashMap<>())
                .put(key, JsonNumber.create(value));

        return me();
    }

    /**
     * Add a key/value pair to a named object.
     *
     * @param name  name of the object to create under the root
     * @param key   key of the nested property
     * @param value value of the nested property
     * @return updated builder
     */
    protected T addToObject(String name, String key, long value) {
        objectsAsMaps.computeIfAbsent(name, it -> new LinkedHashMap<>())
                .put(key, JsonNumber.create(value));

        return me();
    }

    /**
     * Add a key/value pair to a named object.
     *
     * @param name  name of the object to create under the root
     * @param key   key of the nested property
     * @param value value of the nested property
     * @return updated builder
     */
    protected T addToObject(String name, String key, double value) {
        objectsAsMaps.computeIfAbsent(name, it -> new LinkedHashMap<>())
                .put(key, JsonNumber.create(value));

        return me();
    }

    /**
     * Add a key/value pair to a named object.
     *
     * @param name  name of the object to create under the root
     * @param key   key of the nested property
     * @param value value of the nested property
     * @return updated builder
     */
    protected T addToObject(String name, String key, boolean value) {
        objectsAsMaps.computeIfAbsent(name, _ -> new LinkedHashMap<>())
                .put(key, JsonBoolean.create(value));

        return me();
    }

    private void addObjectArray(JsonObject.Builder payloadBuilder,
                                String name,
                                List<ApiJsonBuilder<?>> values) {

        if (values == null) {
            return;
        }

        List<JsonValue> arrayValues = new ArrayList<>();
        for (ApiJsonBuilder<?> element : values) {
            element.toJson().ifPresent(arrayValues::add);
        }

        payloadBuilder.set(name, JsonArray.create(arrayValues));
    }
}
