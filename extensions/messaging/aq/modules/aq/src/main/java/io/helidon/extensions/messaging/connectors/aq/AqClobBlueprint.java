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

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Detached SQL CLOB or NCLOB attribute in an {@link AqAdt} object or array.
 * Unlike a plain string, this value preserves its SQL LOB type when forwarded.
 * The content is immutable and holds no JDBC resources.
 */
@Prototype.Blueprint
@Prototype.Sealed
interface AqClobBlueprint {
    /**
     * Character content.
     *
     * @return text
     */
    String text();

    /**
     * Whether this value represents an NCLOB, using the database national character set.
     * Defaults to {@code false}, representing a CLOB.
     *
     * @return whether the SQL type is NCLOB
     */
    @Option.DefaultBoolean(false)
    boolean national();
}
