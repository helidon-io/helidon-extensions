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
 * Bean-style model whose ordinary builder setters are private.
 */
@Prototype.Blueprint(beanStyle = true)
@Prototype.Extension(JsonSerialize.class)
@Prototype.Extension(JsonDeserialize.class)
interface PrivateSettersBlueprint {
    /**
     * Count assigned through a private ordinary setter.
     *
     * @return count
     */
    @Option.Access("PRIVATE")
    @JsonProperty("count")
    int getCount();

    /**
     * Optional label assigned through a private ordinary setter.
     *
     * @return label
     */
    @Option.Access("PRIVATE")
    @Option.Default("default-private-label")
    @JsonProperty("label")
    Optional<String> getLabel();
}
