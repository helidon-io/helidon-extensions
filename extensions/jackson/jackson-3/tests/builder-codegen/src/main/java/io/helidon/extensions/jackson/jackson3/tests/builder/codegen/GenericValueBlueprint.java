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

package io.helidon.extensions.jackson.jackson3.tests.builder.codegen;

import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Generic model used to exercise generated Jackson builder factories.
 *
 * @param <T> numeric value type
 */
@Prototype.Blueprint
@Prototype.Extension(JsonSerialize.class)
@Prototype.Extension(JsonDeserialize.class)
interface GenericValueBlueprint<T extends Number> {
    /**
     * Value carried by this model.
     *
     * @return numeric value
     */
    @JsonProperty("value")
    T value();

    /**
     * Optional display name with a default retained when JSON omits the property.
     *
     * @return display name
     */
    @Option.Default("default-label")
    @JsonProperty
    Optional<String> displayName();

    /**
     * Message whose Jackson mutator name could collide with a regular setter.
     *
     * @return message
     */
    @Option.Default("first")
    @JsonProperty
    String message();

    /**
     * Property whose regular setter could collide with a Jackson mutator.
     *
     * @return Jackson message
     */
    @Option.Default("second")
    @JsonProperty
    String jacksonMessage();
}
