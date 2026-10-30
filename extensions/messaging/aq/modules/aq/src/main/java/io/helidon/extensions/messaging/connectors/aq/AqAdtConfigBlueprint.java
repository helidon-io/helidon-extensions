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

package io.helidon.extensions.messaging.connectors.aq;

import java.util.List;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Construction options for detached Oracle SQL values.
 */
@Prototype.Blueprint
@Prototype.Sealed
@Prototype.CustomMethods(AqAdtSupport.class)
interface AqAdtConfigBlueprint extends Prototype.Factory<AqAdt> {
    /**
     * Qualified SQL type name.
     *
     * @return type name
     */
    String typeName();

    /**
     * Ordered attributes or array elements. Use {@link AqAdt.NullValue#INSTANCE} for SQL NULL.
     *
     * @return values
     */
    @Option.Singular("attribute")
    List<Object> attributes();

    /**
     * SQL value shape.
     *
     * @return value shape
     */
    @Option.Default("STRUCT")
    AqAdt.Kind kind();
}
