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

import java.util.Map;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;

/**
 * JNDI lookup of an existing AQ destination using a connector-owned naming context.
 */
@Api.Incubating
@Prototype.Blueprint(decorator = AqJndiSupport.BuilderDecorator.class, createEmptyPublic = false)
@Prototype.Sealed
@Prototype.Configured(root = false)
interface AqJndiConfigBlueprint {
    /**
     * Required nonblank JNDI name of a {@link javax.jms.Destination}.
     *
     * @return JNDI destination name
     */
    @Option.Configured
    String destination();

    /**
     * Initial-context environment properties, initially empty.
     * Keys must be nonblank and values must be non-null. Replacing the map replaces all entries.
     * The naming provider can also use its normal environment and resource-file defaults.
     *
     * @return naming context environment
     */
    @Option.Configured
    @Option.Confidential
    @Option.Singular("envProperty")
    Map<String, String> envProperties();
}
