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

import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;

/**
 * Selection of an existing Oracle AQ enqueue transformation.
 */
@Api.Incubating
@Prototype.Blueprint(decorator = AqEnqueueTransformationSupport.BuilderDecorator.class, createEmptyPublic = false)
@Prototype.Sealed
@Prototype.Configured(root = false)
interface AqEnqueueTransformationConfigBlueprint {
    /**
     * Name of the existing database transformation, required when enabled.
     * An explicitly configured name must not be blank, including when disabled.
     *
     * @return database transformation name
     */
    @Option.Configured
    Optional<String> name();

    /**
     * Apply the configured enqueue transformation. Defaults to true.
     * Disabling a channel policy suppresses an inherited connector transformation.
     *
     * @return whether the transformation is enabled
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean enabled();
}
