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

import io.helidon.builder.api.Prototype;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Bean-style model enabling only Jackson deserialization support.
 */
@Prototype.Blueprint(beanStyle = true)
@Prototype.Extensions({@Prototype.Extension(JsonDeserialize.class)})
interface DeserializeOnlyBlueprint {
    /**
     * Count discovered without a property annotation.
     *
     * @return count
     */
    int getCount();
}
