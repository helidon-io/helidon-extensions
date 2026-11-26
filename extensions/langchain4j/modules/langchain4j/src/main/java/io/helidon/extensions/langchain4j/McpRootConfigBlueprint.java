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

package io.helidon.extensions.langchain4j;

import java.net.URI;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;

/**
 * A root advertised to an MCP server.
 */
@Api.Incubating
@Prototype.Blueprint
@Prototype.Configured
interface McpRootConfigBlueprint {
    /**
     * URI identifying the root.
     *
     * @return root URI
     */
    @Option.Configured
    @Option.Required
    URI uri();

    /**
     * Optional display name for the root. When omitted, the root URI is sent as
     * the display name to the MCP server.
     *
     * @return display name, or empty if not configured
     */
    @Option.Configured
    Optional<String> name();
}
