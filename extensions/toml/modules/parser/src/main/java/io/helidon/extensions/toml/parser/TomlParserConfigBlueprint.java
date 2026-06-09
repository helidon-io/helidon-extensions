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

package io.helidon.extensions.toml.parser;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;

/**
 * Configuration of the TOML parser.
 */
@Prototype.Blueprint
@Api.Incubating
interface TomlParserConfigBlueprint extends Prototype.Factory<TomlParser> {
    /**
     * Maximum nesting depth for arrays and tables.
     *
     * @return maximum nesting depth
     */
    @Option.DefaultInt(TomlParser.DEFAULT_MAX_NESTING_DEPTH)
    int maxNestingDepth();

    /**
     * Version-specific parser behavior.
     * <p>
     * TOML documents do not identify the specification version they use, so this option controls how strictly
     * version-specific syntax is handled.
     *
     * @return version behavior
     */
    @Option.Default("BEST_EFFORT")
    TomlVersionBehavior versionBehavior();
}
